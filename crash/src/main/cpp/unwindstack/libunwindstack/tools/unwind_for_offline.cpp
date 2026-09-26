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

#include <cstdio>
#define _GNU_SOURCE 1
#include <inttypes.h>
#include <stdio.h>
#include <sys/mman.h>

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <unwindstack/Elf.h>
#include <unwindstack/JitDebug.h>
#include <unwindstack/MapInfo.h>
#include <unwindstack/Maps.h>
#include <unwindstack/Memory.h>
#include <unwindstack/Regs.h>
#include <unwindstack/Unwinder.h>
#include "utils/ProcessTracer.h"

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>

namespace {
constexpr pid_t kMinPid = 1;
constexpr int kAllCmdOptionsParsed = -1;

int usage(int exit_code) {
  fprintf(stderr, "USAGE: unwind_for_offline [-t] [-e FILE] [-f[FILE]] <PID>\n\n");
  fprintf(stderr, "OPTIONS:\n");
  fprintf(stderr, "-t\n");
  fprintf(stderr, "       Dump offline snapshot for all threads of <PID>.\n");
  fprintf(stderr, "-e FILE\n");
  fprintf(stderr, "       If FILE is a valid ELF file included in /proc/<PID>/maps,\n");
  fprintf(stderr, "       unwind_for_offline will wait until the current frame (PC)\n");
  fprintf(stderr, "       lies within the .so file given by FILE. FILE should be\n");
  fprintf(stderr, "       base name of the path (the component following the final\n");
  fprintf(stderr, "       '/') rather than the fully qualified path.\n");
  fprintf(stderr, "-f [FILE]\n");
  fprintf(stderr, "       Write info (e.g. frames and stack range) logs to a file\n");
  fprintf(stderr, "       rather than to the stdout/stderr. If FILE is not\n");
  fprintf(stderr, "       specified, the output file will be named 'output.txt'.\n");
  return exit_code;
}

bool EnsureProcInDesiredElf(const std::string& elf_name, unwindstack::ProcessTracer& proc) {
  if (proc.UsesSharedLibrary(proc.pid(), elf_name)) {
    printf("Confirmed pid %d does use %s. Waiting for PC to lie within %s...\n", proc.pid(),
           elf_name.c_str(), elf_name.c_str());
    if (!proc.StopInDesiredElf(elf_name)) return false;
  } else {
    fprintf(stderr, "Process %d does not use library %s.\n", proc.pid(), elf_name.c_str());
    return false;
  }
  return true;
}

bool CreateAndChangeDumpDir(std::filesystem::path thread_dir, pid_t tid, bool is_main_thread) {
  std::string dir_name = std::to_string(tid);
  if (is_main_thread) dir_name += "_main-thread";
  thread_dir /= dir_name;
  if (!std::filesystem::create_directory(thread_dir)) {
    fprintf(stderr, "Failed to create directory for tid %d\n", tid);
    return false;
  }
  std::filesystem::current_path(thread_dir);
  return true;
}

bool SaveRegs(unwindstack::Regs* regs) {
  std::unique_ptr<FILE, decltype(&fclose)> fp(fopen("regs.txt", "we+"), &fclose);
  if (fp == nullptr) {
    perror("Failed to create file regs.txt");
    return false;
  }
  regs->IterateRegisters([&fp](const char* name, uint64_t value) {
    fprintf(fp.get(), "%s: %" PRIx64 "\n", name, value);
  });

  return true;
}

bool SaveStack(pid_t tid, const std::vector<std::pair<uint64_t, uint64_t>>& stacks,
               FILE* output_fp) {
  for (size_t i = 0; i < stacks.size(); i++) {
    std::string file_name;
    if (stacks.size() != 1) {
      file_name = "stack" + std::to_string(i) + ".data";
    } else {
      file_name = "stack.data";
    }

    // Do this first, so if it fails, we don't create the file.
    uint64_t sp_start = stacks[i].first;
    uint64_t sp_end = stacks[i].second;
    std::vector<uint8_t> buffer(sp_end - sp_start);
    auto process_memory = unwindstack::Memory::CreateProcessMemory(tid);
    if (!process_memory->Read(sp_start, buffer.data(), buffer.size())) {
      fprintf(stderr, "Unable to read stack data.\n");
      return false;
    }

    fprintf(output_fp, "\nSaving the stack 0x%" PRIx64 "-0x%" PRIx64 "\n", sp_start, sp_end);

    std::unique_ptr<FILE, decltype(&fclose)> fp(fopen(file_name.c_str(), "we+"), &fclose);
    if (fp == nullptr) {
      perror("Failed to create stack.data");
      return false;
    }

    size_t bytes = fwrite(&sp_start, 1, sizeof(sp_start), fp.get());
    if (bytes != sizeof(sp_start)) {
      fprintf(stderr, "Failed to write sp_start data: sizeof(sp_start) %zu, written %zu\n",
              sizeof(sp_start), bytes);
      return false;
    }

    bytes = fwrite(buffer.data(), 1, buffer.size(), fp.get());
    if (bytes != buffer.size()) {
      fprintf(stderr, "Failed to write all stack data: stack size %zu, written %zu\n",
              buffer.size(), bytes);
      return false;
    }
  }

  return true;
}

bool CopyElf(unwindstack::MapInfo* map_info, std::string* name) {
  std::string cur_name = android::base::Basename(map_info->name());

  std::unique_ptr<FILE, decltype(&fclose)> fp(fopen(map_info->name().c_str(), "re"), &fclose);
  if (fp == nullptr) {
    perror((std::string("Cannot open ") + map_info->name().c_str()).c_str());
    return false;
  }

  std::unique_ptr<FILE, decltype(&fclose)> output(fopen(cur_name.c_str(), "we+"), &fclose);
  if (output == nullptr) {
    perror((std::string("Cannot create file " + cur_name)).c_str());
    return false;
  }
  std::vector<uint8_t> buffer(10000);
  size_t bytes;
  while ((bytes = fread(buffer.data(), 1, buffer.size(), fp.get())) > 0) {
    size_t bytes_written = fwrite(buffer.data(), 1, bytes, output.get());
    if (bytes_written != bytes) {
      fprintf(stderr, "Bytes written doesn't match bytes read: read %zu, written %zu\n", bytes,
              bytes_written);
      return false;
    }
  }

  *name = std::move(cur_name);
  return true;
}

bool CreateElfFromMemory(pid_t tid, unwindstack::MapInfo* map_info, std::string* name) {
  std::string cur_name;
  if (map_info->name().empty()) {
    cur_name = android::base::StringPrintf("anonymous_%" PRIx64, map_info->start());
  } else {
    cur_name = android::base::StringPrintf(
        "%s_%" PRIx64, android::base::Basename(map_info->name()).c_str(), map_info->start());
  }

  // If this is a mapped in file, it might not be possible to read the entire
  // map, so read all that is readable.
  std::vector<uint8_t> buffer(map_info->end() - map_info->start());
  auto memory = unwindstack::Memory::CreateProcessMemory(tid);
  size_t bytes = memory->Read(map_info->start(), buffer.data(), buffer.size());
  if (bytes == 0) {
    fprintf(stderr, "Cannot read data from address %" PRIx64 " length %zu\n", map_info->start(),
            buffer.size());
    return false;
  }

  std::unique_ptr<FILE, decltype(&fclose)> output(fopen(cur_name.c_str(), "we+"), &fclose);
  if (output == nullptr) {
    perror((std::string("Cannot create ") + cur_name).c_str());
    return false;
  }

  size_t bytes_written = fwrite(buffer.data(), 1, bytes, output.get());
  if (bytes_written != bytes) {
    fprintf(stderr, "Failed to write all data to file: bytes read %zu, written %zu\n", bytes,
            bytes_written);
    return false;
  }

  *name = std::move(cur_name);

  return true;
}

bool CopyMapInfo(pid_t tid, unwindstack::MapInfo* map_info,
                 std::unordered_map<std::string, std::string>& copied_files, std::string* name) {
  auto entry = copied_files.find(map_info->name());
  if (entry != copied_files.end()) {
    // Already copied the file, do nothing.
    *name = entry->second;
    return true;
  }

  if (CopyElf(map_info, name)) {
    copied_files[map_info->name()] = *name;
    return true;
  }

  if (CreateElfFromMemory(tid, map_info, name)) {
    return true;
  }

  fprintf(stderr, "Cannot save memory or file for map ");
  if (!map_info->name().empty()) {
    fprintf(stderr, "%s\n", map_info->name().c_str());
  } else {
    fprintf(stderr, "anonymous:%" PRIx64 "\n", map_info->start());
  }
  return false;
}

void WriteMapEntry(FILE* fp, unwindstack::MapInfo* map_info, const std::string& name) {
  char perms[5] = {"---p"};
  if (map_info->flags() & PROT_READ) {
    perms[0] = 'r';
  }
  if (map_info->flags() & PROT_WRITE) {
    perms[1] = 'w';
  }
  if (map_info->flags() & PROT_EXEC) {
    perms[2] = 'x';
  }
  fprintf(fp, "%" PRIx64 "-%" PRIx64 " %s %" PRIx64 " 00:00 0", map_info->start(), map_info->end(),
          perms, map_info->offset());
  if (!name.empty()) {
    fprintf(fp, "   %s", name.c_str());
  }
  fprintf(fp, "\n");
}

void SaveMapInfo(FILE* maps_fp, pid_t tid, unwindstack::MapInfo* map_info,
                 std::unordered_map<std::string, std::string>& copied_files) {
  auto prev_info = map_info->GetPrevRealMap();
  if (prev_info != nullptr) {
    SaveMapInfo(maps_fp, tid, prev_info.get(), copied_files);
  }
  std::string map_name;
  if (CopyMapInfo(tid, map_info, copied_files, &map_name)) {
    WriteMapEntry(maps_fp, map_info, map_name);
  }
}

bool SaveData(pid_t tid, const std::filesystem::path& cwd, bool is_main_thread, FILE* output_fp) {
  fprintf(output_fp, "-------------------- tid = %d %s--------------------\n", tid,
          is_main_thread ? "(main thread) " : "--------------");
  unwindstack::Regs* regs = unwindstack::Regs::RemoteGet(tid);
  if (regs == nullptr) {
    fprintf(stderr, "Unable to get remote reg data.\n");
    return false;
  }

  if (!CreateAndChangeDumpDir(cwd, tid, is_main_thread)) {
    return false;
  }

  // Save the current state of the registers.
  if (!SaveRegs(regs)) {
    return false;
  }

  // Do an unwind so we know how much of the stack to save, and what
  // elf files are involved.
  unwindstack::UnwinderFromPid unwinder(1024, tid);
  unwinder.SetRegs(regs);
  uint64_t sp = regs->sp();
  unwinder.Unwind();

  std::vector<std::pair<uint64_t, uint64_t>> stacks;
  unwindstack::Maps* maps = unwinder.GetMaps();
  uint64_t sp_map_start = 0;
  auto map_info = maps->Find(sp);
  if (map_info != nullptr) {
    stacks.emplace_back(std::make_pair(sp, map_info->end()));
    sp_map_start = map_info->start();
  }

  std::unordered_map<uintptr_t, unwindstack::MapInfo*> map_infos;
  for (const auto& frame : unwinder.frames()) {
    auto map_info = maps->Find(frame.sp);
    if (map_info != nullptr && sp_map_start != map_info->start()) {
      stacks.emplace_back(std::make_pair(frame.sp, map_info->end()));
      sp_map_start = map_info->start();
    }
    map_infos[reinterpret_cast<uintptr_t>(frame.map_info.get())] = frame.map_info.get();
  }

  for (size_t i = 0; i < unwinder.NumFrames(); i++) {
    fprintf(output_fp, "%s\n", unwinder.FormatFrame(i).c_str());
  }

  if (!SaveStack(tid, stacks, output_fp)) {
    return false;
  }

  std::unique_ptr<FILE, decltype(&fclose)> maps_fp(fopen("maps.txt", "we+"), &fclose);
  if (maps_fp == nullptr) {
    perror("Failed to create maps.txt");
    return false;
  }

  std::vector<unwindstack::MapInfo*> sorted_map_infos(map_infos.size());
  std::transform(map_infos.begin(), map_infos.end(), sorted_map_infos.begin(),
                 [](auto entry) { return entry.second; });
  std::sort(sorted_map_infos.begin(), sorted_map_infos.end(),
            [](auto a, auto b) { return b->start() > a->start(); });
  std::unordered_map<std::string, std::string> copied_files;
  for (auto& map_info : sorted_map_infos) {
    SaveMapInfo(maps_fp.get(), tid, map_info, copied_files);
  }

  fprintf(output_fp, "------------------------------------------------------------------\n");
  return true;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) return usage(EXIT_FAILURE);

  bool dump_threads = false;
  std::string elf_name;
  std::unique_ptr<FILE, decltype(&fclose)> output_fp(nullptr, &fclose);
  int opt;
  while ((opt = getopt(argc, argv, ":te:f::")) != kAllCmdOptionsParsed) {
    switch (opt) {
      case 't': {
        dump_threads = true;
        break;
      }
      case 'e': {
        elf_name = optarg;
        if (elf_name == "-f") {
          fprintf(stderr, "Missing argument for option e.\n");
          return usage(EXIT_FAILURE);
        }
        break;
      }
      case 'f': {
        const std::string& output_filename = optarg != nullptr ? optarg : "output.txt";
        if (optind == argc - 2) {
          fprintf(stderr, "Ensure there is no space between '-f' and the filename provided.\n");
          return usage(EXIT_FAILURE);
        }
        output_fp.reset(fopen(output_filename.c_str(), "ae"));
        break;
      }
      case '?': {
        if (isprint(optopt))
          fprintf(stderr, "Unknown option `-%c'.\n", optopt);
        else
          fprintf(stderr, "Unknown option character `\\x%x'.\n", optopt);
        return usage(EXIT_FAILURE);
      }
      case ':': {
        fprintf(stderr, "Missing arg for option %c.\n", optopt);
        return usage(EXIT_FAILURE);
      }
      default: {
        return usage(EXIT_FAILURE);
      }
    }
  }
  if (optind != argc - 1) return usage(EXIT_FAILURE);

  pid_t pid;
  if (!android::base::ParseInt(argv[optind], &pid, kMinPid, std::numeric_limits<pid_t>::max()))
    return usage(EXIT_FAILURE);

  unwindstack::ProcessTracer proc(pid, dump_threads);
  if (!proc.Stop()) return EXIT_FAILURE;
  if (!elf_name.empty()) {
    if (!EnsureProcInDesiredElf(elf_name, proc)) return EXIT_FAILURE;
  }
  if (!output_fp) output_fp.reset(stdout);
  std::filesystem::path cwd = std::filesystem::current_path();

  if (!proc.Attach(proc.pid())) return EXIT_FAILURE;
  if (!SaveData(proc.pid(), cwd, /*is_main_thread=*/proc.IsTracingThreads(), output_fp.get()))
    return EXIT_FAILURE;
  if (!proc.Detach(proc.pid())) return EXIT_FAILURE;
  for (const pid_t& tid : proc.tids()) {
    if (!proc.Attach(tid)) return EXIT_FAILURE;
    if (!SaveData(tid, cwd, /*is_main_thread=*/false, output_fp.get())) return EXIT_FAILURE;
    if (!proc.Detach(tid)) return EXIT_FAILURE;
  }

  printf("\nSuccess!\n");
  return EXIT_SUCCESS;
}
