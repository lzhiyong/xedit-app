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

#ifndef __JNI_HELPER_H__
#define __JNI_HELPER_H__

#include <jni.h>
#include <string.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define PACKAGE "io/github/module/treesitter/"

#define TAG "JNI_TREE_SITTER"

#define JNI_VERSION JNI_VERSION_1_6

// get the base name of pathname
#define FILE_NAME(x) (strrchr(x, '/') ? strrchr(x, '/') + 1 : x)

#ifdef __ANDROID__
// android log print
// log.i
#define LOGI(fmt, ...) \ 
    __android_log_print(ANDROID_LOG_INFO, \
    TAG, "[%s:%s:%u] " fmt, \
    FILE_NAME(__FILE__), __PRETTY_FUNCTION__, __LINE__, ##__VA_ARGS__)

// log.e
#define LOGE(fmt, ...) \ 
    __android_log_print(ANDROID_LOG_ERROR, \
    TAG, "[%s:%s:%u] " fmt, \
    FILE_NAME(__FILE__), __PRETTY_FUNCTION__, __LINE__, ##__VA_ARGS__)
#elif
// C-Style log print
#define LOGI(...) fprintf(stdout, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif // end __ANDROID__

// the global JNIEnv
extern JNIEnv* getEnv();
// the global JavaVM
extern JavaVM* getJavaVM();

#ifdef __cplusplus
}
#endif // __cplusplus

#endif // __JNI_HELPER_H__

