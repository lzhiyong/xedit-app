/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef ART_RUNTIME_NATIVE_STACK_DUMP_H_
#define ART_RUNTIME_NATIVE_STACK_DUMP_H_

#include <unistd.h>

#include <iosfwd>

#include "base/macros.h"

namespace unwindstack {
class AndroidLocalUnwinder;
}  // namespace unwindstack

namespace art {

class ArtMethod;

// Remove method parameters by finding matching top-level parenthesis and removing them.
// Since functions can be defined inside functions, this can remove multiple substrings.
std::string StripParameters(std::string name);

// Dumps the native stack for thread 'tid' to 'os'.
void DumpNativeStack(std::ostream& os,
                     pid_t tid,
                     const char* prefix = "",
                     ArtMethod* current_method = nullptr,
                     void* ucontext = nullptr,
                     bool skip_frames = true)
    NO_THREAD_SAFETY_ANALYSIS;

void DumpNativeStack(std::ostream& os,
                     unwindstack::AndroidLocalUnwinder& unwinder,
                     pid_t tid,
                     const char* prefix = "",
                     ArtMethod* current_method = nullptr,
                     void* ucontext = nullptr,
                     bool skip_frames = true)
    NO_THREAD_SAFETY_ANALYSIS;

}  // namespace art

#endif  // ART_RUNTIME_NATIVE_STACK_DUMP_H_
