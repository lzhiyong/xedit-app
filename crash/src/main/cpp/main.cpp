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

#include <jni.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/types.h>

#include "log.h"
#include "stacktrace.h"

#include <vector>

#ifdef __cplusplus
extern "C" {
#endif

// global JVM
static JavaVM *jvm = nullptr;
static jclass javaCrashReportClass = nullptr;

static int notifier = -1;
static err_context_t err_context;

static void signal_callback(int signo, siginfo_t *si, void *sc) {
    signal(signo, SIG_DFL);
    signal(SIGALRM, SIG_DFL);
    (void) alarm(8);
    
    err_context.si = si;
    err_context.sc = (ucontext_t*)sc;
    
    // get the native stacktrace
    _Unwind_Backtrace(unwind_callback, (void*)&err_context);
    
    // notify crash callback
    uint64_t data = signo;
    if (notifier >= 0) {
        write(notifier, &data, sizeof data);
    }
    
    //LOGI("capture signal: %d\n", signo);
}

// callback java CrashReport
static void* crash_callback(void *args) {
    JNIEnv *env = nullptr;
    if(jvm->AttachCurrentThread(&env, nullptr) != JNI_OK){
        LOGE("%s\n", "The jvm failed to attach current thread");
        exit(EXIT_FAILURE);
    }
    
    // read the signal value
    uint64_t data;
    read(notifier, &data, sizeof data);
    
    jmethodID callback = env->GetStaticMethodID(
        javaCrashReportClass, 
        "callback", 
        "(ILjava/lang/String;)V"
    );
    
    jstring message = env->NewStringUTF(dump_stacktrace(&err_context).c_str());
    env->CallStaticVoidMethod(javaCrashReportClass, callback, static_cast<jint>(data), message);
    
    env->DeleteLocalRef(message);
    jvm->DetachCurrentThread();
    
    // send SIGQUIT to android os SignalCatcher thread
    // the adb logcat can continue to print the ANR exception
    // tid is the SignalCatcher thread id 
    // find the tid at /proc/[tid]
    //tgkill(getpid(), tid, SIGQUIT)
    return nullptr;
}

void register_signals(
    std::vector<uint32_t> &signals,
    void (*handler)(int, struct siginfo *, void *)
) {   
    // create a stack to handle the overflow for the SIGSEGV signal
    stack_t stack = {
        .ss_flags = 0,
        .ss_size = SIGSTKSZ       
    };
    stack.ss_sp = calloc(1, SIGSTKSZ);
    
    if (sigaltstack(&stack, nullptr) != 0) {
       LOGE("%s\n", strerror(errno));
    }

    // set the mask for signal SIGQUIT
    sigset_t mask;
    sigset_t old;
    sigemptyset(&mask);
    sigaddset(&mask, SIGQUIT);
    if (pthread_sigmask(SIG_UNBLOCK, &mask, &old) != 0) {
        LOGE("%s\n", strerror(errno));
    }

    struct sigaction siga;
    siga.sa_sigaction = handler;
    // block other signals
    sigfillset(&siga.sa_mask);
    siga.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;

    // register all signals
    for (auto &signum : signals) {
        if (sigaction(signum, &siga, NULL) != 0) {
            // recovery is required for SIGQUIT
            pthread_sigmask(SIG_SETMASK, &old, NULL);
            LOGE("%s\n", strerror(errno));
        }
    }
}

void JNICALL init_native_crash() {
    std::vector<uint32_t> signals{ 
        SIGHUP, SIGINT, SIGQUIT, SIGILL, 
        SIGTRAP, SIGABRT, SIGBUS, SIGSEGV
    };
    
    register_signals(signals, signal_callback);
    
    notifier = eventfd(0, EFD_CLOEXEC);
    
    // create a thread to handle the crash and callbacks to the java side
    pthread_t thread;
    if(pthread_create(&thread, nullptr, crash_callback, nullptr) != 0){
        LOGE("%s\n", strerror(errno));
        close(notifier);
        notifier = -1;
    }
}

// test the SIGSEGV signal
void JNICALL test_native_crash() {
    // test SIGSEGV signal
    raise(SIGSEGV);
    // test SIGQUIT signal
    //raise(SIGQUIT);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    // initial the jvm
    LOGE("Failed to init the jvm environment\n");
    ::jvm = vm;
    if(jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to init the jvm environment\n");
        return JNI_ERR;
    }
    
    jclass local = env->FindClass("io/github/module/crash/CrashReport");
    if(local == nullptr) {
        LOGE("Can not found the class CrashReport\n");
        return JNI_ERR;
    }
    
    javaCrashReportClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    
    const JNINativeMethod methods[] = {
        {"initNativeCrash", "()V", (void *)&init_native_crash},
        {"testNativeCrash", "()V", (void *)&test_native_crash}
    };
    
    size_t size = sizeof methods / sizeof(JNINativeMethod);
    if (env->RegisterNatives(javaCrashReportClass, methods, size) != JNI_OK) {
        LOGE("Fail to register native methods\n");
        return JNI_ERR;
    }
    
    return JNI_VERSION_1_6; 
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if(jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        env->DeleteGlobalRef(javaCrashReportClass);
    }  
}

#ifdef __cplusplus
}
#endif

