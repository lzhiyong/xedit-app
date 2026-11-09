#
# Copyright © 2023 Github Lzhiyong
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.14.2)

project(android_tree_sitter)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
# tree_sitter is a static library linked into a shared one
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Optimized in every build type, not only in Release: the APK that CI publishes is the
# debug one, and CMake's Debug configuration is -O0 with tree-sitter's assertions on.
# To step through the native code, build without it: -DTS_OPTIMIZE=OFF in the
# cmake arguments of build.gradle.kts.
option(TS_OPTIMIZE "Build with -O2 and ThinLTO, whatever the build type" ON)

# Reproducible builds: do not embed absolute build paths in the binaries
get_filename_component(PROJECT_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options(
    -ffile-prefix-map=${PROJECT_ROOT_DIR}=.
    -ffile-prefix-map=${ANDROID_NDK}=/ndk
)

include_directories(
    ${PROJECT_SOURCE_DIR}/tree-sitter/lib/include
    )
    
add_compile_options(
    -fcolor-diagnostics
    -Wall 
    -Wextra
    -Wno-backslash-newline-escape
    -Wno-unused-parameter
    -Wno-cast-function-type
    -Werror=incompatible-pointer-types
    -Werror=implicit-function-declaration)

# (after the flags of the build type on the command line, so the -O2 is the one that counts)
if(TS_OPTIMIZE)
    add_compile_options(-O2 -flto=thin)
    add_compile_definitions(NDEBUG)
endif()

# Static: one library, so that LTO can inline tree-sitter into the JNI functions (a
# @CriticalNative getter is a call into libtree_sitter.so otherwise, through the PLT, for
# one field). The grammars do not link against libtree_sitter.so, so nothing needs it
# as a library of its own, nor its symbols exported.
add_library(tree_sitter STATIC
    ${PROJECT_SOURCE_DIR}/tree-sitter/lib/src/lib.c
    )

add_library(${PROJECT_NAME} SHARED
    jni_helper.cpp
    ts_node.cpp
    ts_parser.cpp
    ts_tree.cpp
    ts_tree_cursor.cpp
    ts_query.cpp
    ts_query_cursor.cpp
    ts_query_filter.cpp
    ts_language.cpp
    ts_lookahead_iterator.cpp
    )

target_link_libraries(${PROJECT_NAME}
    tree_sitter 
    log
    )

# Only the JNI entry points are exported (the natives are registered in JNI_OnLoad). The
# tree-sitter API is declared with default visibility by its own header, so the visibility
# presets above do not hide it; this does, and LTO can then drop or inline all of it.
set(TS_EXPORTS ${PROJECT_SOURCE_DIR}/exports.map)
target_link_options(${PROJECT_NAME} PRIVATE -Wl,--version-script=${TS_EXPORTS})
set_target_properties(${PROJECT_NAME} PROPERTIES LINK_DEPENDS ${TS_EXPORTS})

if(TS_OPTIMIZE)
    target_link_options(${PROJECT_NAME} PRIVATE -O2 -flto=thin)
endif()

