/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <string>

#include <gtest/gtest.h>

#include <unwindstack/Demangle.h>

namespace unwindstack {

TEST(DemangleTest, none) {
  EXPECT_EQ("", DemangleNameIfNeeded(""));
  EXPECT_EQ("a", DemangleNameIfNeeded("a"));
  EXPECT_EQ("_", DemangleNameIfNeeded("_"));
  EXPECT_EQ("ab", DemangleNameIfNeeded("ab"));
  EXPECT_EQ("abc", DemangleNameIfNeeded("abc"));
  EXPECT_EQ("_R", DemangleNameIfNeeded("_R"));
  EXPECT_EQ("_Z", DemangleNameIfNeeded("_Z"));
}

TEST(DemangleTest, cxx_names) {
  EXPECT_EQ("fake(bool)", DemangleNameIfNeeded("_Z4fakeb"));
  EXPECT_EQ("demangle(int)", DemangleNameIfNeeded("_Z8demanglei"));
}

TEST(DemangleTest, rust_names) {
  EXPECT_EQ("std::rt::lang_start_internal",
            DemangleNameIfNeeded("_RNvNtCs2WRBrrl1bb1_3std2rt19lang_start_internal"));
  EXPECT_EQ("profcollectd::main", DemangleNameIfNeeded("_RNvCs4VPobU5SDH_12profcollectd4main"));
}

}  // namespace unwindstack
