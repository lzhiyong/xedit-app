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

project(crash_handler)

add_library(${PROJECT_NAME} SHARED
    crash_handler.cpp
    stacktrace.cpp
    stringprintf.cpp
    utils.cpp
    )

# add the unwindstack
add_subdirectory(unwindstack)

target_link_libraries(${PROJECT_NAME} PRIVATE 
    unwindstack 
    log)
target_compile_features(crash_handler PRIVATE cxx_std_20)

