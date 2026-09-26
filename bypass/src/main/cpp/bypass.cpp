/*
 * Copyright © 2022 - 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://apache.org
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
#include <vector>
#include <android/log.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TAG "JNI_NATIVE_LOG"
#define PACKAGE "x/github/module/bypass/"
#define JNI_VERSION JNI_VERSION_1_6

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *jvm = nullptr;

/**
 * ====================================================================================
 * UNDERLYING BYPASS EXPLOIT PRINCIPLE
 * ====================================================================================
 *
 * 1. THE RESTRICTION MECHANISM (ART STACK INSPECTION)
 *    Since Android 9, the Android Runtime (ART) blocks regular apps from calling restricted
 *    hidden APIs via Java reflection. When you call Class.getDeclaredMethod(), ART looks up
 *    the execution stack to check who the caller is. If it finds code belonging to a standard
 *    untrusted app, it throws a NoSuchMethodException.
 *
 * 2. THE WEAKNESS (DETACHED NATIVE THREADS)
 *    ART identifies the caller by inspecting the current thread's Java stack frames. 
 *    However, if a thread is spawned directly from native C++ code (via std::async or 
 *    pthread_create), it starts with a completely empty, pure native execution stack.
 *    
 *    When this native thread attaches to the Java Virtual Machine (AttachCurrentThread),
 *    and then invokes Class.getDeclaredMethod(), ART's validation routine cannot trace 
 *    any untrusted Java application frames on the thread stack. Consequently, ART's 
 *    internal policy engine defaults to an "unrestricted/trusted" state and lets the
 *    reflection bypass pass through safely.
 *
 * 3. ASYNC PASSING & CACHING PIPELINE
 *    - The main application invokes the native JNI method.
 *    - The JNI layer creates a detached C++ thread using std::async(std::launch::async).
 *    - The background native thread executes the reflection securely and creates a 
 *      Global Reference to the captured Method/Field object so it survives thread death.
 *    - future.get() halts the main thread synchronously until the target reflection finishes.
 *
 * 4. GLOBAL REFERENCE LIFECYCLE MANAGEMENT
 *    Local JNI references (`jobject`) cannot be passed across threads. They must be promoted
 *    to Global References (`NewGlobalRef`). Because Android imposes a strict limit on the 
 *    Global Reference Table (~51,200), this optimized implementation strictly manages 
 *    and cleans up every single allocated global tag after extraction to prevent system crashes.
 * ====================================================================================
 */

// Struct to safely bundle and ship reflection requests to the isolated native thread
struct MethodArgs {
    jobject global_object;                        // Target Java Class instance
    jstring global_name;                          // Target Method name string
    jobjectArray global_params;                   // Parameter Type Class array
    std::vector<jobject> global_param_elements;   // Tracks individual array items to prevent leaks
};

/**
 * BACKGROUND EXECUTION MODULE: Reflection for Methods
 * Spawns on an isolated, context-free native thread to mask app identity from ART checks.
 */
static jobject get_global_object(const MethodArgs& args) {
    JNIEnv *env = nullptr;
    // Bind the clean native thread into the JVM instance
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("AttachCurrentThread failed in get_global_object");
        return nullptr;
    }
        
    jclass clazz = env->GetObjectClass(args.global_object);
    jmethodID method = env->GetMethodID(
        clazz, 
        "getDeclaredMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"
    );
        
    // Execute Java Reflection. Because there is no Java stack history, restriction layers are bypassed.
    jobject local_object = env->CallObjectMethod(args.global_object, method, args.global_name, args.global_params);
    
    // Elevate local handle to global scope so it can be safely sent back to the parent thread
    jobject global_object = local_object ? env->NewGlobalRef(local_object) : nullptr;
    
    if (local_object) {
        env->DeleteLocalRef(local_object);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    env->DeleteLocalRef(clazz);
    
    // Sever thread ties with JVM to clean up native processing artifacts
    jvm->DetachCurrentThread();   
    return global_object;
}

/**
 * BACKGROUND EXECUTION MODULE: Reflection for Fields
 * Spawns on an isolated, context-free native thread to mask app identity from ART checks.
 */
static jobject get_global_field(jobject global_object, jstring global_name) {
    JNIEnv *env = nullptr;
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("AttachCurrentThread failed in get_global_field");
        return nullptr;
    }

    jclass clazz = env->GetObjectClass(global_object);
    jmethodID method = env->GetMethodID(
        clazz, 
        "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;"
    );
        
    jobject local_field = env->CallObjectMethod(global_object, method, global_name);   
    jobject global_field = local_field ? env->NewGlobalRef(local_field) : nullptr;
    
    if (local_field) {
        env->DeleteLocalRef(local_field);
    }
    
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    
    env->DeleteLocalRef(clazz);
    jvm->DetachCurrentThread();
    return global_field;
}

/**
 * JNI BOUND ENDPOINT: Intercepts Java's JNI.getDeclaredMethod() call
 */
static jobject get_declared_method(
    JNIEnv *env,
    jclass clazz,
    jobject object,
    jstring name,
    jobjectArray params
) {
    MethodArgs args{};
    // Elevate input handles to global variables to allow cross-thread memory reads
    args.global_object = env->NewGlobalRef(object);
    args.global_name = (jstring) env->NewGlobalRef(name);
    args.global_params = nullptr;

    if (params != nullptr) {
        jint length = env->GetArrayLength(params);
        
        // Recreate the array locally to avoid mutating or locking the caller's parameter objects
        jclass class_clazz = env->FindClass("java/lang/Class");
        jobjectArray local_params_copy = env->NewObjectArray(length, class_clazz, nullptr);
        
        for (int i = 0; i < length; i++) {
            jobject element = env->GetObjectArrayElement(params, i);
            if (element) {
                jobject global_element = env->NewGlobalRef(element);
                args.global_param_elements.push_back(global_element);
                env->SetObjectArrayElement(local_params_copy, i, global_element);
                env->DeleteLocalRef(element);
            }
        }
        
        args.global_params = (jobjectArray) env->NewGlobalRef(local_params_copy);
        env->DeleteLocalRef(local_params_copy);
        env->DeleteLocalRef(class_clazz);
    }
    
    // Delegate to a fresh background C++ thread, effectively dropping the restricted Java call stack
    auto future = std::async(std::launch::async, &get_global_object, args);
    jobject result = future.get(); // Main thread pauses here until background reflection resolves

    // PURGE STAGE: Free all temporary global allocations to prevent Global Reference Table Overflow crashes
    env->DeleteGlobalRef(args.global_object);
    env->DeleteGlobalRef(args.global_name);
    if (args.global_params) {
        env->DeleteGlobalRef(args.global_params);
    }
    for (jobject global_elem : args.global_param_elements) {
        env->DeleteGlobalRef(global_elem);
    }
    
    return result;
}

/**
 * JNI BOUND ENDPOINT: Intercepts Java's JNI.getDeclaredField() call
 */
static jobject get_declared_field(
    JNIEnv *env, 
    jclass clazz, 
    jobject object, 
    jstring name
) {
    jobject global_object = env->NewGlobalRef(object);
    jstring global_name = (jstring) env->NewGlobalRef(name);
    
    // Pass execution off to an isolated thread context to break the ART stack check trace
    auto future = std::async(std::launch::async, &get_global_field, global_object, global_name);
    jobject result = future.get();

    // Clean up temporary cross-thread globals
    env->DeleteGlobalRef(global_object);
    env->DeleteGlobalRef(global_name);
    return result;
}

static bool register_native_methods(JNIEnv *env) {
    const char *classpath = PACKAGE "JNI";
    jclass clazz = env->FindClass(classpath);
    
    if (clazz == nullptr) {
        LOGE("Cannot find the class '%s'\n", classpath);
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
        LOGE("Failed to register native methods\n");
        env->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }
    
    env->DeleteLocalRef(clazz);
    return JNI_TRUE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
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
