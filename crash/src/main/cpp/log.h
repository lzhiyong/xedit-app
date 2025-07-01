/*
 * Copyright © 2023 Github Lzhiyong
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

#pragma once

#include <stdio.h>
#include <android/log.h>

#define TAG "JNI_CRASH_STACK_TRACE"

#ifdef __ANDROID__
// android log print
// log.i
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); 
// log.e
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__);
#elif
// c-style log print
#define LOGI(...) fprintf(stdout, __VA_ARGS__); 
#define LOGE(...) fprintf(stderr, __VA_ARGS__);

#endif // end __ANDROID__

