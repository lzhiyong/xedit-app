/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <sys/types.h>

#include <memory>
#include <string>
#include <string_view>

#include <android-base/file.h>
#include <dex/dex_file.h>
#include <gtest/gtest.h>

#include "art_api/dex_file_support.h"
#include "dex_file_test_data.h"

namespace art_api {
namespace dex {

TEST(DexFileTest, create) {
  size_t size = sizeof(kDexData);
  std::unique_ptr<DexFile> dex_file;
  EXPECT_TRUE(DexFile::Create(kDexData, size, &size, "", &dex_file).Ok());
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_NE(dex_file, nullptr);
}

TEST(DexFileTest, create_header_too_small) {
  size_t size = sizeof(art::DexFile::Header) - 1;
  std::unique_ptr<DexFile> dex_file;
  DexFile::Error error = DexFile::Create(kDexData, size, &size, "", &dex_file);
  EXPECT_FALSE(error.Ok());
  EXPECT_EQ(error.Code(), ADEXFILE_ERROR_NOT_ENOUGH_DATA);
  EXPECT_STREQ(error.ToString(), "Not enough data. Incomplete dex file.");
  EXPECT_EQ(size, sizeof(art::DexFile::Header));
  EXPECT_EQ(dex_file, nullptr);
}

TEST(DexFileTest, create_file_too_small) {
  size_t size = sizeof(art::DexFile::Header);
  std::unique_ptr<DexFile> dex_file;
  DexFile::Error error = DexFile::Create(kDexData, size, &size, "", &dex_file);
  EXPECT_FALSE(error.Ok());
  EXPECT_EQ(error.Code(), ADEXFILE_ERROR_NOT_ENOUGH_DATA);
  EXPECT_STREQ(error.ToString(), "Not enough data. Incomplete dex file.");
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_EQ(dex_file, nullptr);
}

static std::unique_ptr<DexFile> GetTestDexData() {
  size_t size = sizeof(kDexData);
  std::unique_ptr<DexFile> dex_file;
  EXPECT_TRUE(DexFile::Create(kDexData, size, &size, "", &dex_file).Ok());
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_NE(dex_file, nullptr);
  return dex_file;
}

TEST(DexFileTest, findMethodAtOffset) {
  std::unique_ptr<DexFile> dex_file = GetTestDexData();
  ASSERT_NE(dex_file, nullptr);

  bool found_init = false;
  auto check_init = [&](const DexFile::Method& method) {
    size_t size;
    size_t offset = method.GetCodeOffset(&size);
    EXPECT_EQ(offset, 0x100u);
    EXPECT_EQ(size, 8u);
    EXPECT_STREQ(method.GetName(), "<init>");
    EXPECT_STREQ(method.GetQualifiedName(), "Main.<init>");
    EXPECT_STREQ(method.GetQualifiedName(true), "void Main.<init>()");
    EXPECT_STREQ(method.GetClassDescriptor(), "LMain;");
    found_init = true;
  };
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x102, check_init), 1u);
  EXPECT_TRUE(found_init);

  bool found_main = false;
  auto check_main = [&](const DexFile::Method& method) {
    size_t size;
    size_t offset = method.GetCodeOffset(&size);
    EXPECT_EQ(offset, 0x118u);
    EXPECT_EQ(size, 2u);
    EXPECT_STREQ(method.GetName(), "main");
    EXPECT_STREQ(method.GetQualifiedName(), "Main.main");
    EXPECT_STREQ(method.GetQualifiedName(true), "void Main.main(java.lang.String[])");
    EXPECT_STREQ(method.GetClassDescriptor(), "LMain;");
    found_main = true;
  };
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x118, check_main), 1u);
  EXPECT_TRUE(found_main);
}

TEST(DexFileTest, get_method_info_for_offset_boundaries) {
  std::unique_ptr<DexFile> dex_file = GetTestDexData();
  ASSERT_NE(dex_file, nullptr);

  EXPECT_EQ(dex_file->FindMethodAtOffset(0x99, [](auto){}), 0);
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x100, [](auto){}), 1);
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x107, [](auto){}), 1);
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x108, [](auto){}), 0);
  EXPECT_EQ(dex_file->FindMethodAtOffset(0x100000, [](auto){}), 0);
}

TEST(DexFileTest, get_all_method_infos_without_signature) {
  std::unique_ptr<DexFile> dex_file = GetTestDexData();
  ASSERT_NE(dex_file, nullptr);

  std::vector<std::string> names;
  auto add = [&](const DexFile::Method& method) { names.push_back(method.GetQualifiedName()); };
  EXPECT_EQ(dex_file->ForEachMethod(add), 2u);
  EXPECT_EQ(names, std::vector<std::string>({"Main.<init>", "Main.main"}));
}

}  // namespace dex
}  // namespace art_api
