/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "art_dex_file_loader.h"

#include <sys/stat.h>

#include <memory>

#include "android-base/stringprintf.h"
#include "base/file_magic.h"
#include "base/file_utils.h"
#include "base/logging.h"
#include "base/mem_map.h"
#include "base/mman.h"  // For the PROT_* and MAP_* constants.
#include "base/stl_util.h"
#include "base/systrace.h"
#include "base/unix_file/fd_file.h"
#include "base/zip_archive.h"
#include "dex/compact_dex_file.h"
#include "dex/dex_file.h"
#include "dex/dex_file_verifier.h"
#include "dex/standard_dex_file.h"

namespace art {

using android::base::StringPrintf;

bool ArtDexFileLoader::GetMultiDexChecksums(const char* filename,
                                            std::vector<uint32_t>* checksums,
                                            std::vector<std::string>* dex_locations,
                                            std::string* error_msg,
                                            int zip_fd,
                                            bool* zip_file_only_contains_uncompressed_dex) {
  CHECK(checksums != nullptr);
  uint32_t magic;

  File fd;
  if (zip_fd != -1) {
     if (ReadMagicAndReset(zip_fd, &magic, error_msg)) {
       fd = File(DupCloexec(zip_fd), /* check_usage= */ false);
     }
  } else {
    fd = OpenAndReadMagic(filename, &magic, error_msg);
  }
  if (fd.Fd() == -1) {
    DCHECK(!error_msg->empty());
    return false;
  }
  if (IsZipMagic(magic)) {
    std::unique_ptr<ZipArchive> zip_archive(
        ZipArchive::OpenFromFd(fd.Release(), filename, error_msg));
    if (zip_archive.get() == nullptr) {
      *error_msg = StringPrintf("Failed to open zip archive '%s' (error msg: %s)", filename,
                                error_msg->c_str());
      return false;
    }

    if (zip_file_only_contains_uncompressed_dex != nullptr) {
      // Start by assuming everything is uncompressed.
      *zip_file_only_contains_uncompressed_dex = true;
    }

    uint32_t idx = 0;
    std::string zip_entry_name = GetMultiDexClassesDexName(idx);
    std::unique_ptr<ZipEntry> zip_entry(zip_archive->Find(zip_entry_name.c_str(), error_msg));
    if (zip_entry.get() == nullptr) {
      // A zip file with no dex code should be accepted. It's likely a config split APK, which we
      // are currently passing from higher levels.
      VLOG(dex) << StringPrintf("Zip archive '%s' doesn't contain %s (error msg: %s)",
                                filename,
                                zip_entry_name.c_str(),
                                error_msg->c_str());
      return true;
    }

    do {
      if (zip_file_only_contains_uncompressed_dex != nullptr) {
        if (!(zip_entry->IsUncompressed() && zip_entry->IsAlignedTo(alignof(DexFile::Header)))) {
          *zip_file_only_contains_uncompressed_dex = false;
        }
      }
      checksums->push_back(zip_entry->GetCrc32());
      dex_locations->push_back(GetMultiDexLocation(idx, filename));
      zip_entry_name = GetMultiDexClassesDexName(++idx);
      zip_entry.reset(zip_archive->Find(zip_entry_name.c_str(), error_msg));
    } while (zip_entry.get() != nullptr);
    return true;
  }
  if (IsMagicValid(magic)) {
    ArtDexFileLoader loader(fd.Release(), filename);
    std::vector<std::unique_ptr<const DexFile>> dex_files;
    if (!loader.Open(/* verify= */ false,
                     /* verify_checksum= */ false,
                     error_msg,
                     &dex_files)) {
      return false;
    }
    for (auto& dex_file : dex_files) {
      checksums->push_back(dex_file->GetHeader().checksum_);
    }
    return true;
  }
  *error_msg = StringPrintf("Expected valid zip or dex file: '%s'", filename);
  return false;
}

std::unique_ptr<const DexFile> ArtDexFileLoader::Open(
    const uint8_t* base,
    size_t size,
    const std::string& location,
    uint32_t location_checksum,
    const OatDexFile* oat_dex_file,
    bool verify,
    bool verify_checksum,
    std::string* error_msg,
    std::unique_ptr<DexFileContainer> container) const {
  return OpenCommon(base,
                    size,
                    /*data_base=*/nullptr,
                    /*data_size=*/0,
                    location,
                    location_checksum,
                    oat_dex_file,
                    verify,
                    verify_checksum,
                    error_msg,
                    std::move(container),
                    /*verify_result=*/nullptr);
}

std::unique_ptr<const DexFile> ArtDexFileLoader::Open(const std::string& location,
                                                      uint32_t location_checksum,
                                                      MemMap&& mem_map,
                                                      bool verify,
                                                      bool verify_checksum,
                                                      std::string* error_msg) const {
  ArtDexFileLoader loader(std::move(mem_map), location);
  return loader.Open(location_checksum, verify, verify_checksum, error_msg);
}

bool ArtDexFileLoader::Open(const char* filename,
                            const std::string& location,
                            bool verify,
                            bool verify_checksum,
                            std::string* error_msg,
                            std::vector<std::unique_ptr<const DexFile>>* dex_files) const {
  ArtDexFileLoader loader(filename, location);
  return loader.Open(verify, verify_checksum, error_msg, dex_files);
}

}  // namespace art
