/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "art_api/dex_file_external.h"
#include "dex_file_test_data.h"

namespace art_api {
namespace dex {

class ADexFileTest : public ::testing::Test {
 protected:
  void TearDown() override {
    ADexFile_destroy(dex_);
  }

  ADexFile* dex_ = nullptr;
};

TEST_F(ADexFileTest, create) {
  size_t size = sizeof(kDexData);
  EXPECT_EQ(ADexFile_create(kDexData, size, &size, "", &dex_), ADEXFILE_ERROR_OK);
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_NE(dex_, nullptr);
}

TEST_F(ADexFileTest, create_null_new_size) {
  const size_t size = sizeof(kDexData);
  EXPECT_EQ(ADexFile_create(kDexData, size, nullptr, "", &dex_), ADEXFILE_ERROR_OK);
  EXPECT_NE(dex_, nullptr);
}

TEST_F(ADexFileTest, create_header_too_small) {
  size_t size = sizeof(art::DexFile::Header) - 1;
  EXPECT_EQ(ADexFile_create(kDexData, size, &size, "", &dex_), ADEXFILE_ERROR_NOT_ENOUGH_DATA);
  EXPECT_EQ(size, sizeof(art::DexFile::Header));
  EXPECT_EQ(dex_, nullptr);
}

TEST_F(ADexFileTest, create_file_too_small) {
  size_t size = sizeof(art::DexFile::Header);
  EXPECT_EQ(ADexFile_create(kDexData, size, &size, "", &dex_), ADEXFILE_ERROR_NOT_ENOUGH_DATA);
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_EQ(dex_, nullptr);
}

static ADexFile* GetTestDexData() {
  size_t size = sizeof(kDexData);
  ADexFile* dex;
  EXPECT_EQ(ADexFile_create(kDexData, size, &size, "", &dex), ADEXFILE_ERROR_OK);
  EXPECT_EQ(size, sizeof(kDexData));
  EXPECT_NE(dex, nullptr);
  return dex;
}

TEST_F(ADexFileTest, findMethodAtOffset) {
  dex_ = GetTestDexData();
  ASSERT_NE(dex_, nullptr);

  bool found_init = false;
  auto check_init = [](void* ctx, const ADexFile_Method* method) {
    size_t size;
    size_t offset = ADexFile_Method_getCodeOffset(method, &size);
    EXPECT_EQ(offset, 0x100u);
    EXPECT_EQ(size, 8u);
    EXPECT_STREQ(ADexFile_Method_getName(method, &size), "<init>");
    EXPECT_STREQ(ADexFile_Method_getQualifiedName(method, false, &size), "Main.<init>");
    EXPECT_STREQ(ADexFile_Method_getQualifiedName(method, true, &size), "void Main.<init>()");
    EXPECT_STREQ(ADexFile_Method_getClassDescriptor(method, &size), "LMain;");
    *reinterpret_cast<bool*>(ctx) = true;
  };
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x102, check_init, &found_init), 1u);
  EXPECT_TRUE(found_init);

  bool found_main = false;
  auto check_main = [](void* ctx, const ADexFile_Method* method) {
    size_t size;
    size_t offset = ADexFile_Method_getCodeOffset(method, &size);
    EXPECT_EQ(offset, 0x118u);
    EXPECT_EQ(size, 2u);
    EXPECT_STREQ(ADexFile_Method_getName(method, &size), "main");
    EXPECT_STREQ(ADexFile_Method_getQualifiedName(method, false, &size), "Main.main");
    EXPECT_STREQ(ADexFile_Method_getQualifiedName(method, true, &size), "void Main.main(java.lang.String[])");
    EXPECT_STREQ(ADexFile_Method_getClassDescriptor(method, &size), "LMain;");
    *reinterpret_cast<bool*>(ctx) = true;
  };
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x118, check_main, &found_main), 1u);
  EXPECT_TRUE(found_main);
}

TEST_F(ADexFileTest, findMethodAtOffset_for_offset_boundaries) {
  dex_ = GetTestDexData();
  ASSERT_NE(dex_, nullptr);

  auto no_cb = [](void*, const ADexFile_Method*) {};
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x99, no_cb, nullptr), 0);
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x100, no_cb, nullptr), 1);
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x107, no_cb, nullptr), 1);
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x108, no_cb, nullptr), 0);
  EXPECT_EQ(ADexFile_findMethodAtOffset(dex_, 0x100000, no_cb, nullptr), 0);
}

TEST_F(ADexFileTest, forEachMethod) {
  dex_ = GetTestDexData();
  ASSERT_NE(dex_, nullptr);

  std::vector<std::string> names;
  auto add = [](void* ctx, const ADexFile_Method* method) {
    reinterpret_cast<decltype(names)*>(ctx)->
        push_back(ADexFile_Method_getQualifiedName(method, false, nullptr));
  };
  EXPECT_EQ(ADexFile_forEachMethod(dex_, add, &names), 2u);
  EXPECT_EQ(names, std::vector<std::string>({"Main.<init>", "Main.main"}));
}

TEST_F(ADexFileTest, Error_toString) {
  constexpr size_t kNumErrors = 4;
  for (size_t i = 0; i < kNumErrors; i++) {
    const char* err = ADexFile_Error_toString(static_cast<ADexFile_Error>(i));
    ASSERT_NE(err, nullptr);
    ASSERT_FALSE(std::string_view(err).empty());
  }
  const char* err = ADexFile_Error_toString(static_cast<ADexFile_Error>(kNumErrors));
  ASSERT_EQ(err, nullptr);
}

}  // namespace dex
}  // namespace art_api
