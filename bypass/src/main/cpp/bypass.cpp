/*
 * Copyright © 2022 - 2024 Github Lzhiyong
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

#include <jni.h>
#include <thread>
#include <future>
#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TAG "JNI_SDK_BYPASS"

#define JNI_VERSION JNI_VERSION_1_6

// log.i
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
// log.e
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// global JVM
static JavaVM *jvm = nullptr;

// note this should be running on background thread
static jobject get_global_object(
    jobject object,
    jstring name,
    jobjectArray params
) {
    // attach current thread to get the env
    JNIEnv *env = nullptr;
    jvm->AttachCurrentThread(&env, nullptr);
        
    jclass clazz = env->GetObjectClass(object);
        
    jmethodID method = env->GetMethodID(
        clazz, 
        "getDeclaredMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
    );
        
    // local object
    jobject local_object = env->CallObjectMethod(object, method, name, params);
    // global object
    jobject global_object = local_object ? env->NewGlobalRef(local_object) : nullptr;
    
    if (local_object) {
        env->DeleteLocalRef(local_object);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    // detach current thread
    jvm->DetachCurrentThread();   
    return global_object;
}

// note this should be running on background thread
static jobject get_global_field(jobject object, jstring name) {
    // attach current thread to get the env
    JNIEnv *env = nullptr;
    jvm->AttachCurrentThread(&env, nullptr);
    jclass clazz = env->GetObjectClass(object);
    jmethodID method = env->GetMethodID(
        clazz, 
        "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;"
    );
        
    // local object
    jobject local_field = env->CallObjectMethod(object, method, name);   
    // global object
    jobject global_field = local_field ? env->NewGlobalRef(local_field) : nullptr;
    
    if (local_field) {
        env->DeleteLocalRef(local_field);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    // detach current thread
    jvm->DetachCurrentThread();
    return global_field;
}


static jobject get_declared_method(
    JNIEnv *env,
    jclass clazz,
    jobject object,
    jstring name,
    jobjectArray params
) {
    // convert local object to global object
    jobject global_object = env->NewGlobalRef(object);
    
    // convert local method name to global jstring
    jstring global_name = (jstring) env->NewGlobalRef(name);
    
    // convert local method params to global jobjectArray
    jint length = env->GetArrayLength(params);
    for (int i = 0; i < length; i++) {
        jobject element = (jobject) env->GetObjectArrayElement(params, i);
        jobject global_element = env->NewGlobalRef(element);
        env->SetObjectArrayElement(params, i, global_element);
        env->DeleteLocalRef(element);
    }
    
    jobjectArray global_params = nullptr;
    if (params != nullptr) {
        global_params = (jobjectArray) env->NewGlobalRef(params);
    }
    
    // running on an new thread
    auto future = std::async(
        &get_global_object,
        global_object,
        global_name,
        global_params
    );
    return future.get();
}

static jobject get_declared_field(
    JNIEnv *env, 
    jclass clazz, 
    jobject object, 
    jstring name
) {
    // convert local object to global object
    jobject global_object = env->NewGlobalRef(object);
    // convert local method name to global jstring
    jstring global_name = (jstring) env->NewGlobalRef(name);
    
    // running on an new thread
    auto future = std::async(
        &get_global_field, 
        global_object, 
        global_name
    );
    return future.get();
}

static bool register_native_methods(JNIEnv *env) {
    const char *classpath = "io/github/module/bypass/JNI";
    jclass clazz = env->FindClass(classpath);
    
    if (clazz == nullptr) {
        LOGE("Can not to find the class '%s\n'", classpath);
        return JNI_FALSE;
    }
    
    const JNINativeMethod methods[] = {
        {
            "getDeclaredMethod", 
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", 
            (void *)&get_declared_method
        },
        {
            "getDeclaredField",  
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/reflect/Field;",                    
            (void *)&get_declared_field
        }
    };
    
    size_t size = sizeof methods / sizeof(JNINativeMethod);
    if (env->RegisterNatives(clazz, methods, size) != JNI_OK) {
        LOGE("Fail to register native methods\n");
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    // initial the jvm
    ::jvm = vm;
    if (vm->GetEnv((void **) (&env), JNI_VERSION) != JNI_OK) {
        LOGE("Failed to init the jvm environment\n");
        return JNI_ERR;
    }

    if(!register_native_methods(env)) {
        return JNI_ERR;
    }

    return JNI_VERSION;
}

#ifdef __cplusplus
}
#endif

