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
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

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
    
add_library(tree_sitter SHARED
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
    ts_language.cpp
    ts_lookahead_iterator.cpp
    )

target_link_libraries(${PROJECT_NAME}
    tree_sitter 
    log
    )

