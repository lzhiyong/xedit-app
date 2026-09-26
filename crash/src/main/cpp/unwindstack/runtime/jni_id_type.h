/*
 * Copyright 2017 The Android Open Source Project
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

#ifndef ART_RUNTIME_JNI_ID_TYPE_H_
#define ART_RUNTIME_JNI_ID_TYPE_H_

#include <iosfwd>

namespace art {

enum class JniIdType {
  // All Jni method/field IDs are pointers to the corresponding Art{Field,Method} type
  kPointer,

  // All Jni method/field IDs are indices into a table.
  kIndices,

  // All Jni method/field IDs are pointers to the corresponding Art{Field,Method} type but we
  // keep around extra information support changing modes to either kPointer or kIndices later.
  kSwapablePointer,

  kDefault = kPointer,
};

std::ostream& operator<<(std::ostream& os, JniIdType rhs);

}  // namespace art
#endif  // ART_RUNTIME_JNI_ID_TYPE_H_
