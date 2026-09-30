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

/*
 * Native crash capture — design overview
 * ======================================
 *
 * Goal
 * ----
 * Catch fatal native signals (see kHandledSignals) and deliver a formatted
 * stack trace to Kotlin CrashHandler.callback() BEFORE the process dies —
 * while keeping logcat/debuggerd and any other crash SDK that
 * registered earlier fully working. We chain to previously-installed
 * handlers instead of replacing them.
 *
 * Why a hand-rolled pipeline
 * --------------------------
 * JNI calls are not async-signal-safe, so the signal handler itself can
 * never report into Java. All Java-facing work is delegated to a dedicated
 * "crash_callback" thread created once at install time and kept alive for
 * the rest of the process. The two ends are connected by an eventfd, and a
 * semaphore acts as a rendezvous so the process does not die mid-report.
 *
 * Flow
 * ----
 *   crashing thread                          crash_callback thread (started once)
 *   ----------------                         ------------------------------------
 *   fault -> signal_callback()
 *     (runs on the alt signal stack, SA_ONSTACK)
 *     1. record siginfo/ucontext into err_context
 *     2. arm the 15s watchdog (alarm) — the process still dies even if the
 *        reporting path below hangs
 *     3. write signo -> signal_notifier (eventfd) ---->  wakes from read()
 *     4. rendezvous:                                     AttachCurrentThread
 *        - non-main thread: sem_wait(crash_done_sem)     dump_stacktrace()
 *        - main thread:     usleep(300ms) instead *      CallStaticVoidMethod()
 *                                                         sem_post(crash_done_sem)
 *     5. invoke_previous_handler() -------> default action, process dies
 *
 *     * Main-thread crashes cannot wait for real completion: a same-process
 *       CrashActivity's onCreate() needs this thread's Looper to resume,
 *       which never happens once we are inside a signal handler. The fixed
 *       sleep only gives startActivity()'s Binder call time to get out;
 *       put CrashActivity in its own process for a real fix.
 *
 * Ordering invariants (established in JNI_OnLoad — do NOT reorder)
 * ----------------------------------------------------------------
 *   1. Resolve javaCrashHandlerClass / javaCrashCallbackMethod FIRST. A
 *      crash landing in crash_callback before the refs exist used to mean
 *      a null jclass and a second, unhandled crash. Refs are cached as
 *      global refs so no FindClass ever happens on the callback thread
 *      (its class-loader context would be wrong anyway).
 *   2. Only then arm the handlers (register_signals) and start the thread.
 *   3. This library must be the FIRST native library loaded in the process
 *      (top of Application.attachBaseContext): a fault in any library loaded
 *      before this point runs before any handler exists and cannot be
 *      caught. The same caveat applies to THIS library's own static
 *      initializers, which run before JNI_OnLoad — keep them trivial, and
 *      split a minimal hook library out if heavy globals are needed.
 *
 * Deliberate design choices
 * -------------------------
 *   - sigaltstack + SA_ONSTACK: the handler must run somewhere safe even if
 *     the crash was a stack overflow that destroyed the thread's own stack.
 *   - sa_mask blocks only the signals we handle (not sigfillset): the
 *     handler can now legitimately run for a while (main-thread grace
 *     sleep), and blocking every signal would starve ART's signal-based
 *     thread machinery (e.g. cross-thread Thread.getStackTrace() issued
 *     from the Kotlin callback) for that whole window.
 *   - invoke_previous_handler: preserves whatever was registered before us
 *     (another crash SDK, ART, debuggerd via libsigchain) — this is what
 *     keeps debuggerd working alongside this handler. With nothing else
 *     registered it restores SIG_DFL and re-raises, because a real faulting
 *     instruction is retried on return and must still end the process.
 *   - err_context is a single shared instance: sa_mask prevents same-thread
 *     re-entry, but two different threads faulting at once would race on it
 *     (last writer wins). Accepted: consecutive multi-thread crashes are
 *     rare and the watchdog bounds the damage.
 *   - waiter_pending: crash_callback must know whether the current crash
 *     has a thread blocked on crash_done_sem (non-main) or not (main thread
 *     only sleeps) before deciding to sem_post — otherwise a stale semaphore
 *     count would let a future crash's sem_wait pass through without
 *     waiting at all.
 *
 * Known limits
 * ------------
 *   - Faults during static initializers of libraries loaded before this one
 *     (or before our JNI_OnLoad ran) are invisible here; 
 *     Android debuggerd remain the fallback for those.
 *   - Everything added to signal_callback must be async-signal-safe:
 *     sem_wait/write/alarm qualify, usleep is a widely-tolerated gray area,
 *     JNI/malloc/anything-locked are strictly off-limits.
 */

#include <jni.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <signal.h>
#include <semaphore.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/types.h>

#include <array>
#include <atomic>

#include "log.h"
#include "stacktrace.h"

#define PACKAGE "x/github/module/crash/"

namespace {

constexpr std::array<int, 8> kHandledSignals = {
    SIGHUP, SIGINT, SIGQUIT, SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGSEGV
};
constexpr int kMaxSignalNum = NSIG;  // generous upper bound, avoids relying on NSIG

// global JVM / java refs — only ever written once, in JNI_OnLoad, BEFORE any
// signal handler is armed. Nothing after init_native_capture() should touch these.
JavaVM *jvm = nullptr;                        // cached in JNI_OnLoad so crash_callback can AttachCurrentThread
jclass javaCrashHandlerClass = nullptr;       // global ref to x.github.module.crash.CrashHandler
jmethodID javaCrashCallbackMethod = nullptr;  // CrashHandler.callback(int, String)

// eventfd used to hand the signal number from signal_callback (async-signal-safe
// write) over to the dedicated crash_callback thread, which owns the JNI call.
// -1 means native capture was never installed or has already been torn down.
int signal_notifier = -1;

// semaphore released by crash_callback after the Kotlin callback completes, so a
// crashing non-main thread can block until reporting is done instead of dying
// immediately. See waiter_pending for who is expected to wait on it.
sem_t crash_done_sem;

// set by the crashing thread right before it blocks on crash_done_sem — but only
// when that thread is NOT the main thread (see signal_callback). crash_callback
// checks/clears it to decide whether a sem_post is owed. atomic because it is
// touched from two threads with no other synchronization.
std::atomic<bool> waiter_pending{ false };

// shared crash context written by signal_callback (siginfo + ucontext) and read
// by crash_callback when building the stacktrace string. Single global instance:
// only one crash is reported at a time — sa_mask stops same-thread re-entry, but
// two different threads faulting at once would race here (last writer wins).
err_context_t err_context;

// previously-installed handlers, so we chain instead of clobbering whatever
// another library (or ART / debuggerd via libsigchain) already registered.
struct sigaction old_actions[kMaxSignalNum];


static void invoke_previous_handler(int signo, siginfo_t *si, void *sc) {
    if (signo < 0 || signo >= kMaxSignalNum) return;
    struct sigaction &old = old_actions[signo];

    if ((old.sa_flags & SA_SIGINFO) && old.sa_sigaction != nullptr) {
        old.sa_sigaction(signo, si, sc);
        return;
    }
    if (!(old.sa_flags & SA_SIGINFO) &&
        old.sa_handler != SIG_DFL && old.sa_handler != SIG_IGN && old.sa_handler != nullptr) {
        old.sa_handler(signo);
        return;
    }
    // nothing else was registered (or it was SIG_DFL/SIG_IGN) — fall back to
    // the OS default so the process still terminates normally
    // still gets generated.
    signal(signo, SIG_DFL);
    raise(signo);
}

static void signal_callback(int signo, siginfo_t *si, void *sc) {
    err_context.si = si;
    err_context.sc = reinterpret_cast<ucontext_t*>(sc);

    // watchdog: guarantees the process still dies even if crash_callback
    // hangs (e.g. AttachCurrentThread never returns), instead of sitting in
    // a broken half-crashed state forever.
    signal(SIGALRM, SIG_DFL);
    alarm(15);

    // The main thread's tid equals the process pid on Linux/Android.
    const bool is_main_thread = (gettid() == getpid());

    if (signal_notifier >= 0) {
        // Only block below if this thread isn't the main thread — see why
        // just below.
        waiter_pending.store(!is_main_thread, std::memory_order_relaxed);

        uint64_t data = static_cast<uint64_t>(signo);
        write(signal_notifier, &data, sizeof data);

        if (!is_main_thread) {
            // Safe to block: this thread isn't needed to pump any Looper,
            // so waiting here just gives crash_callback time to finish the
            // Kotlin callback before we let the process die. sem_wait/
            // sem_post are async-signal-safe, so this is fine to call here,
            // unlike a JNI call would be.
            sem_wait(&crash_done_sem);
        } else {
            // Can't wait for actual completion here (see comment below), but
            // don't die with zero delay either — that guarantees losing the
            // race against crash_callback every time. A short, fixed sleep
            // gives it a real chance to at least issue the startActivity()
            // Binder call, which does NOT require our main thread's Looper
            // to be pumping (system_server handles it independently). This
            // is a mitigation, not a fix: if CrashActivity lives in the same
            // process, its onCreate() still can't run until this process's
            // main thread returns to Looper.loop(), which never happens once
            // we're in here. Put CrashActivity in its own process
            // (android:process=":crash" in the manifest) for a real fix.
            usleep(300 * 1000);
        }
        // If this IS the main thread, we only sleep briefly above, never
        // wait on crash_callback's actual completion — see why in the
        // comment attached to that sleep. This still means a main-thread
        // crash races startActivity() against process death to some degree;
        // the only real fix is moving CrashActivity into its own process.
    }

    // Give whatever else was registered for this signal (another crash SDK,
    // ART, debuggerd via libsigchain) a chance to see it too, instead of
    // silently swallowing it — this is what makes logcat/debuggerd keep
    // working alongside this handler.
    invoke_previous_handler(signo, si, sc);
}

// callback java CrashHandler
static void* crash_callback(void *) {
    JNIEnv *env = nullptr;
    if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("%s\n", "The jvm failed to attach current thread");
        if (waiter_pending.exchange(false, std::memory_order_relaxed)) {
            sem_post(&crash_done_sem);
        }
        return nullptr;
    }

    for (;;) {
        uint64_t data;
        ssize_t rc = read(signal_notifier, &data, sizeof data);
        if (rc == static_cast<ssize_t>(sizeof data)) {
            // TODO
        } else if (rc < 0 && errno == EINTR) {
             continue;
        } else {
             LOGE("eventfd read failed: %s", strerror(errno));
             break;
        }

        // javaCrashHandlerClass/javaCrashCallbackMethod are resolved in
        // JNI_OnLoad *before* signal handlers are ever armed, so this should
        // never be null in practice. Kept as a last line of defense so a
        // stray early signal can't turn into a second, unhandled crash here.
        if (javaCrashHandlerClass != nullptr && javaCrashCallbackMethod != nullptr) {
            jstring message = env->NewStringUTF(dump_stacktrace(&err_context).c_str());
            env->CallStaticVoidMethod(javaCrashHandlerClass, javaCrashCallbackMethod,
                                       static_cast<jint>(data), message);
            env->DeleteLocalRef(message);
        } else {
            LOGE("%s\n", "javaCrashHandlerClass not ready yet, dropping native crash callback");
        }

        if (waiter_pending.exchange(false, std::memory_order_relaxed)) {
            sem_post(&crash_done_sem);
        }
        
        // cancel the watchdog: reaching this point means the crash was fully reported
        // and the process is about to die through invoke_previous_handler anyway.
        // Cancel the pending alarm regardless — if a chained handler from another SDK
        // swallows the signal and the process somehow survives, a leftover SIGALRM
        // would otherwise kill it out of the blue 15 seconds later, long after the
        // crash was already handled. alarm(0) is the documented way to cancel a
        // previously scheduled alarm.
        alarm(0);
    }
    return nullptr;
}

static bool register_signals(void (*handler)(int, siginfo_t *, void *)) {
    // create a stack to handle the overflow for the SIGSEGV signal — padded
    // above SIGSTKSZ since unwinding + JNI calls need more headroom than the
    // bare minimum.
    stack_t stack{};
    stack.ss_size = SIGSTKSZ > 32 * 1024 ? SIGSTKSZ : 32 * 1024;
    stack.ss_sp = calloc(1, stack.ss_size);
    if (stack.ss_sp == nullptr || sigaltstack(&stack, nullptr) != 0) {
        LOGE("%s\n", strerror(errno));
        return false;
    }

    // set the mask for signal SIGQUIT
    sigset_t mask{}, old_mask{};
    sigemptyset(&mask);
    sigaddset(&mask, SIGQUIT);
    if (pthread_sigmask(SIG_UNBLOCK, &mask, &old_mask) != 0) {
        LOGE("%s\n", strerror(errno));
    }

    struct sigaction siga{};
    siga.sa_sigaction = handler;
    // Only block the signals we ourselves handle (prevents nested re-entry
    // into this same handler). Deliberately NOT sigfillset(): this handler
    // can now run for a while (see the main-thread grace-period sleep in
    // signal_callback), and blocking every signal for that whole window can
    // starve things like ART's own signal-based thread-introspection
    // mechanisms (e.g. a cross-thread Thread.getStackTrace() call from the
    // Kotlin callback needing to suspend this very thread).
    sigemptyset(&siga.sa_mask);
    for (int signal : kHandledSignals) {
        sigaddset(&siga.sa_mask, signal);
    }
    siga.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;

    bool ok = true;
    for (int signum : kHandledSignals) {
        // save the previously-installed handler instead of discarding it
        if (
            signum >= kMaxSignalNum ||
            sigaction(signum, &siga, &old_actions[signum]) != 0
        ) {
            pthread_sigmask(SIG_SETMASK, &old_mask, nullptr);
            LOGE("%s\n", strerror(errno));
            ok = false;
        }
    }
    return ok;
}

static bool init_native_capture() {
    if (sem_init(&crash_done_sem, 0, 0) != 0) {
        LOGE("%s\n", strerror(errno));
        return false;
    }

    signal_notifier = eventfd(0, EFD_CLOEXEC);
    if (signal_notifier < 0) {
        LOGE("%s\n", strerror(errno));
        return false;
    }
    

    if (!register_signals(signal_callback)) {
        close(signal_notifier);
        signal_notifier = -1;
        return false;
    }

    // create a thread to handle the crash and callbacks to the java layer
    pthread_t thread;
    if (pthread_create(&thread, nullptr, crash_callback, nullptr) != 0) {
        LOGE("%s\n", strerror(errno));
        close(signal_notifier);
        signal_notifier = -1;
        return false;
    }
    pthread_detach(thread);
    return true;
}

// test the SIGSEGV signal — a genuine invalid memory access, not raise(),
// so this actually exercises the same "faulting instruction gets retried on
// SIG_DFL" path a real crash does. raise(SIGSEGV) does NOT retry on return,
// so it can't be used to validate that invoke_previous_handler() is working
// correctly — it'll falsely look fine even if that call is broken/removed.
void JNICALL test_native_crash() {    
    *((volatile int *)nullptr) = 1;
    // raise(SIGSEGV);
    // __builtin_trap();
    // abort();
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    jvm = vm;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to init the JVM environment\n");
        return JNI_ERR;
    }

    // 1) Resolve everything Java-side FIRST. Signal handlers must never go
    //    live while javaCrashHandlerClass/javaCrashCallbackMethod could still
    //    be null — otherwise a crash here in JNI_OnLoad, or in any other
    //    library loaded right after this one, leads crash_callback to touch
    //    a null jclass and double-fault with nothing left to catch it.
    jclass local = env->FindClass(PACKAGE "CrashHandler");
    if (local == nullptr) {
        LOGE("Can not find the class CrashHandler\n");
        env->ExceptionClear();
        return JNI_ERR;
    }
    
    javaCrashHandlerClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    javaCrashCallbackMethod = env->GetStaticMethodID(
        javaCrashHandlerClass, "callback", "(ILjava/lang/String;)V");
    if (javaCrashCallbackMethod == nullptr) {
        LOGE("Can not find CrashHandler.callback(int, String)\n");
        env->ExceptionClear();
        return JNI_ERR;
    }

    const JNINativeMethod methods[] = {
        {"testNativeCrash", "()V", reinterpret_cast<void *>(&test_native_crash)}
    };
    
    if (env->RegisterNatives(javaCrashHandlerClass, methods, 1) != JNI_OK) {
        LOGE("Fail to register native methods\n");
        return JNI_ERR;
    }

    // 2) Only now is it safe to arm signal handlers + start the callback
    //    thread. Also: make sure this library is the first native library
    //    loaded in the process (e.g. loaded at the very top of
    //    Application.attachBaseContext()) — signals from a library loaded
    //    before this point can't be caught, no matter what the handler does.
    if (!init_native_capture()) {
        LOGE("Failed to install native crash capture; continuing without it\n");
        // not fatal to loading the library — better to run without native
        // crash reporting than to fail loadLibrary() entirely
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (javaCrashHandlerClass) {
            env->DeleteGlobalRef(javaCrashHandlerClass);
        }
    }
}

