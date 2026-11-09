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

#ifndef ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_METHOD_LIST_H_
#define ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_METHOD_LIST_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "jni.h"

#define PALETTE_METHOD_LIST(M)                                                                \
  /* Methods in version 1 API, corresponding to SDK level 31. */                              \
  M(PaletteSchedSetPriority, int32_t tid, int32_t java_priority)                              \
  M(PaletteSchedGetPriority, int32_t tid, /*out*/ int32_t* java_priority)                     \
  M(PaletteWriteCrashThreadStacks, const char* stacks, size_t stacks_len)                     \
  M(PaletteTraceEnabled, /*out*/ bool* enabled)                                               \
  M(PaletteTraceBegin, const char* name)                                                      \
  M(PaletteTraceEnd)                                                                          \
  M(PaletteTraceIntegerValue, const char* name, int32_t value)                                \
  M(PaletteAshmemCreateRegion, const char* name, size_t size, int* fd)                        \
  M(PaletteAshmemSetProtRegion, int, int)                                                     \
  /* Create the staging directory for on-device signing.           */                         \
  /* `staging_dir` is updated to point to a constant string in the */                         \
  /* palette implementation.                                       */                         \
  /* This method is not thread-safe.                               */                         \
  M(PaletteCreateOdrefreshStagingDirectory, /*out*/ const char** staging_dir)                 \
  M(PaletteShouldReportDex2oatCompilation, bool*)                                             \
  M(PaletteNotifyStartDex2oatCompilation, int source_fd, int art_fd, int oat_fd, int vdex_fd) \
  M(PaletteNotifyEndDex2oatCompilation, int source_fd, int art_fd, int oat_fd, int vdex_fd)   \
  M(PaletteNotifyDexFileLoaded, const char* path)                                             \
  M(PaletteNotifyOatFileLoaded, const char* path)                                             \
  M(PaletteShouldReportJniInvocations, bool*)                                                 \
  M(PaletteNotifyBeginJniInvocation, JNIEnv* env)                                             \
  M(PaletteNotifyEndJniInvocation, JNIEnv* env)                                               \
                                                                                              \
  /* Methods in version 2 API, corresponding to SDK level 33. */                              \
  M(PaletteReportLockContention,                                                              \
    JNIEnv* env,                                                                              \
    int32_t wait_ms,                                                                          \
    const char* filename,                                                                     \
    int32_t line_number,                                                                      \
    const char* method_name,                                                                  \
    const char* owner_filename,                                                               \
    int32_t owner_line_number,                                                                \
    const char* owner_method_name,                                                            \
    const char* proc_name,                                                                    \
    const char* thread_name)                                                                  \
                                                                                              \
  /* Methods in version 3 API, corresponding to SDK level 34. */                              \
                                                                                              \
  /* Calls through to SetTaskProfiles in libprocessgroup to set the */                        \
  /* [task profile](https:/-/source.android.com/docs/core/perf/cgroups#task-profiles-file) */ \
  /* for the given thread id. */                                                              \
  /* */                                                                                       \
  /* @param tid The thread id. */                                                             \
  /* @param profiles An array of pointers to C strings that list the task profiles to set. */ \
  /* @param profiles_len The number of elements in profiles. */                               \
  /* @return PALETTE_STATUS_OK if the call succeeded. */                                      \
  /*         PALETTE_STATUS_FAILED_CHECK_LOG if it failed. */                                 \
  /*         PALETTE_STATUS_NOT_SUPPORTED if the implementation no longer supports this */    \
  /*         call. This can happen at any future SDK level since this function wraps an */    \
  /*         internal unstable API. */                                                        \
  M(PaletteSetTaskProfiles, int32_t tid, const char* const profiles[], size_t profiles_len)

#endif  // ART_LIBARTPALETTE_INCLUDE_PALETTE_PALETTE_METHOD_LIST_H_
