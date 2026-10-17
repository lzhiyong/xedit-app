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
 * ANR capture via SIGQUIT (Matrix-style)
 * ======================================
 *
 * When system_server declares an ANR it sends SIGQUIT to the process and waits
 * for ART's "Signal Catcher" thread to write the Java traces. Intercepting that
 * signal tells us about the ANR with zero polling cost: nothing runs at all
 * until the system itself decides there is an ANR.
 *
 * Normally SIGQUIT is blocked in every thread and only Signal Catcher receives
 * it, via sigwait(). We unblock it on the MAIN thread only. For a
 * process-directed signal the kernel prefers the thread-group leader (the
 * main thread) when it does not block the signal, so SIGQUIT now lands in
 * sigquit_callback() instead.
 *
 * Rules - each one prevents a concrete failure:
 *   1. Always forward. Every SIGQUIT is re-sent to Signal Catcher with
 *      tgkill(), whether or not it is reported. A thread-directed SIGQUIT to a
 *      thread that is sigwait()ing for it is consumed by sigwait(), so it never
 *      re-enters our handler. Skipping this would leave the system's ANR trace
 *      empty and make system_server wait for a dump that never comes.
 *   2. Never chain to SIG_DFL: SIGQUIT's default action kills the process.
 *   3. Never uninstall the handler once installed. Threads created by the main
 *      thread while SIGQUIT was unblocked inherit that mask; if SIG_DFL came
 *      back, a later SIGQUIT could land on one of them and kill the process.
 *      Disabling therefore only re-blocks the main thread and switches the
 *      handler to pure pass-through mode.
 *   4. The handler only rings a doorbell (JNI is not async-signal-safe). The
 *      "anr_report" thread does the rest, in this order:
 *        a) onAnrSignal()  - snapshot the main thread's stack first, because
 *                            the system may kill a background process right
 *                            after its dump completes
 *        b) forward to Signal Catcher
 *        c) onAnrConfirm() - confirm on the Java side. A SIGQUIT alone does
 *                            not prove that THIS process is the one that
 *                            ANR'd: system_server may also dump other
 *                            processes when another app ANRs, and
 *                            `adb shell kill -3` sends one too.
 *   5. Refuse to enable if Signal Catcher cannot be found, since there would
 *      be nowhere to forward to.
 */

#include "anr_monitor.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>

#include <atomic>

#include "log.h"

namespace crash {
namespace {

// Set by anr_monitor_init(). handler_class is BORROWED from crash_handler.cpp,
// which owns the global ref; anr_monitor_shutdown() drops it before release.
JavaVM *jvm = nullptr;
jclass handler_class = nullptr;  // x.github.module.crash.CrashHandler

// Java hooks, resolved in anr_monitor_init(). Optional: if either one is
// missing, ANR monitoring refuses to enable and crash capture is unaffected.
jmethodID javaAnrSignalMethod = nullptr;   // CrashHandler.onAnrSignal()
jmethodID javaAnrConfirmMethod = nullptr;  // CrashHandler.onAnrConfirm()

// Doorbell from sigquit_callback() to the anr_report thread. Same eventfd
// pattern as signal_notifier in crash_handler.cpp, but a separate pipeline: an ANR must never wait
// behind a crash report, and a crash report must never wait behind an ANR
// confirmation (which can take seconds).
int anr_notifier = -1;

// true  -> SIGQUIT is reported to Java, then forwarded.
// false -> sigquit_callback() is a pure pass-through to Signal Catcher.
std::atomic<bool> anr_enabled{ false };

// Set once anr_report is attached to the JVM and parked in read(). Until then
// the handler forwards directly instead of ringing a doorbell nobody answers.
std::atomic<bool> anr_reporter_ready{ false };

// Signal Catcher's tid, cached so the handler can forward without touching
// /proc (opendir/readdir are not async-signal-safe).
std::atomic<pid_t> signal_catcher_tid{ 0 };

// Main-thread-only bookkeeping: enable/disable both run on the main thread.
bool anr_reporter_started = false;
bool sigquit_installed = false;
struct sigaction old_sigquit_action;

// Read a small /proc file into buf, NUL-terminated. Never call from a handler.
static ssize_t read_small_file(const char *path, char *buf, size_t cap) {
    int fd = TEMP_FAILURE_RETRY(open(path, O_RDONLY | O_CLOEXEC));
    if (fd < 0) return -1;
    ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf, cap - 1));
    close(fd);
    if (n < 0) return -1;
    buf[n] = '\0';
    return n;
}

/*
 * Find ART's Signal Catcher by thread name.
 *
 * Do NOT also require SIGQUIT in the thread's /proc SigBlk mask. It looks like
 * a sensible sanity check (Signal Catcher blocks SIGQUIT so it can sigwait()
 * for it), but while a thread is sleeping inside sigwait() the kernel
 * temporarily REMOVES the awaited signals from its blocked mask
 * (do_sigtimedwait), and /proc reports that live mask. Signal Catcher spends
 * nearly all of its life in sigwait(), so such a check fails almost every
 * time and silently disables the whole ANR monitor.
 */
static pid_t find_signal_catcher_tid() {
    DIR *dir = opendir("/proc/self/task");
    if (dir == nullptr) return 0;

    pid_t found = 0;
    while (dirent *entry = readdir(dir)) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        const pid_t tid = static_cast<pid_t>(atoi(entry->d_name));

        char path[64];
        snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
        char comm[32];
        if (read_small_file(path, comm, sizeof(comm)) <= 0) continue;
        comm[strcspn(comm, "\n")] = '\0';

        if (strcmp(comm, "Signal Catcher") == 0) {
            found = tid;
            break;
        }
    }
    closedir(dir);
    return found;
}

// Async-signal-safe: a single syscall on the cached tid.
static bool tgkill_signal_catcher() {
    const pid_t tid = signal_catcher_tid.load(std::memory_order_relaxed);
    return tid > 0 && syscall(SYS_tgkill, getpid(), tid, SIGQUIT) == 0;
}

// Reporter-side forward: if the cached tid has gone stale, re-resolve once.
static void forward_to_signal_catcher() {
    if (tgkill_signal_catcher()) return;
    signal_catcher_tid.store(find_signal_catcher_tid(), std::memory_order_relaxed);
    if (!tgkill_signal_catcher()) {
        LOGE("%s\n", "cannot forward SIGQUIT to Signal Catcher; system ANR trace may be empty");
    }
}

// Runs on whichever thread the kernel picked - normally the main thread,
// possibly while it is stuck. Must stay tiny and async-signal-safe, and must
// RETURN: unlike a fault, SIGQUIT is not fatal and the thread carries on.
static void sigquit_callback(int, siginfo_t *, void *) {
    const int saved_errno = errno;  // the interrupted code may be reading errno

    bool handed_off = false;
    if (anr_enabled.load(std::memory_order_acquire) &&
        anr_reporter_ready.load(std::memory_order_acquire)) {
        uint64_t token = 1;
        handed_off = TEMP_FAILURE_RETRY(
            write(anr_notifier, &token, sizeof(token))) ==
            static_cast<ssize_t>(sizeof(token));
    }

    // Disabled, reporter not up, or doorbell failed: forward right here so
    // the system still gets its trace.
    if (!handed_off) {
        tgkill_signal_catcher();
    }

    errno = saved_errno;
}

static void call_java_hook(JNIEnv *env, jmethodID method) {
    if (handler_class == nullptr || method == nullptr) return;
    env->CallStaticVoidMethod(handler_class, method);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// ANR reporter thread: owns every JNI call of the ANR pipeline. Started lazily
// on the first enable, then lives forever, parked in read() at zero cost.
static void *anr_report_thread(void *) {
    pthread_setname_np(pthread_self(), "anr_report");

    // This thread answers the doorbell, so SIGQUIT must never be delivered
    // here. It is created before the main thread unblocks SIGQUIT and would
    // inherit the blocked mask anyway; this makes that independent of timing.
    sigset_t block_set {};
    sigemptyset(&block_set);
    sigaddset(&block_set, SIGQUIT);
    pthread_sigmask(SIG_BLOCK, &block_set, nullptr);

    JNIEnv *env = nullptr;
    JavaVMAttachArgs args { JNI_VERSION_1_6, "anr_report", nullptr };
    if (jvm->AttachCurrentThread(&env, &args) != JNI_OK) {
        LOGE("%s\n", "The jvm failed to attach the anr reporter thread");
        return nullptr;  // anr_reporter_ready stays false: handler forwards directly
    }

    anr_reporter_ready.store(true, std::memory_order_release);

    for (;;) {
        uint64_t token = 0;
        ssize_t rc = TEMP_FAILURE_RETRY(read(anr_notifier, &token, sizeof(token)));
        if (rc != static_cast<ssize_t>(sizeof(token))) {
            LOGE("anr eventfd read failed: %s\n", strerror(errno));
            anr_reporter_ready.store(false, std::memory_order_release);
            break;
        }

        // Several SIGQUITs arriving before this read are summed by the eventfd
        // into one wake-up. That is fine: pending standard signals do not
        // queue either, so Signal Catcher would only have seen one.
        const bool report = anr_enabled.load(std::memory_order_acquire);
        LOGI("SIGQUIT received (report=%d)\n", report ? 1 : 0);

        if (report) call_java_hook(env, javaAnrSignalMethod);   // a) snapshot
        forward_to_signal_catcher();                            // b) forward
        if (report) call_java_hook(env, javaAnrConfirmMethod);  // c) confirm
    }

    jvm->DetachCurrentThread();
    return nullptr;
}

}  // namespace

// CrashHandler.nativeEnableAnrMonitor(). MUST run on the main thread: the
// signal mask is per-thread, and the main thread is the one we unblock.
jboolean JNICALL native_enable_anr_monitor(JNIEnv *, jclass) {
    if (gettid() != getpid()) {
        LOGE("%s\n", "nativeEnableAnrMonitor must be called on the main thread");
        return JNI_FALSE;
    }
    if (anr_enabled.load(std::memory_order_acquire)) return JNI_TRUE;
    if (javaAnrSignalMethod == nullptr || javaAnrConfirmMethod == nullptr) {
        LOGE("%s\n", "ANR java hooks missing; ANR monitor disabled");
        return JNI_FALSE;
    }

    // Rule 5: no Signal Catcher means nowhere to forward to.
    const pid_t catcher = find_signal_catcher_tid();
    if (catcher <= 0) {
        LOGE("%s\n", "Signal Catcher thread not found; ANR monitor disabled");
        return JNI_FALSE;
    }
    signal_catcher_tid.store(catcher, std::memory_order_relaxed);

    // 1) Reporter first, created while SIGQUIT is still blocked on this
    //    thread so the new thread inherits the blocked mask.
    if (!anr_reporter_started) {
        anr_notifier = eventfd(0, EFD_CLOEXEC);
        if (anr_notifier < 0) {
            LOGE("anr eventfd failed: %s\n", strerror(errno));
            return JNI_FALSE;
        }
        pthread_t thread;
        if (pthread_create(&thread, nullptr, anr_report_thread, nullptr) != 0) {
            LOGE("%s\n", "pthread_create for anr reporter failed");
            close(anr_notifier);
            anr_notifier = -1;
            return JNI_FALSE;
        }
        pthread_detach(thread);
        anr_reporter_started = true;
    }

    // 2) Handler, installed once and never removed (rule 3).
    if (!sigquit_installed) {
        struct sigaction sa {};
        sa.sa_sigaction = sigquit_callback;
        sigemptyset(&sa.sa_mask);
        sigaddset(&sa.sa_mask, SIGQUIT);
        // SA_RESTART here (unlike the fault handlers): this handler returns,
        // and the interrupted main thread should resume its syscall.
        sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
        if (sigaction(SIGQUIT, &sa, &old_sigquit_action) != 0) {
            LOGE("sigaction(SIGQUIT) failed: %s\n", strerror(errno));
            return JNI_FALSE;
        }
        sigquit_installed = true;
    }

    // 3) Start reporting, and only then 4) unblock. By the time the kernel can
    //    deliver SIGQUIT to this thread, a forwarding handler is in place.
    anr_enabled.store(true, std::memory_order_release);

    sigset_t set {};
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    if (pthread_sigmask(SIG_UNBLOCK, &set, nullptr) != 0) {
        LOGE("%s\n", "failed to unblock SIGQUIT on the main thread");
        anr_enabled.store(false, std::memory_order_release);
        return JNI_FALSE;
    }

    LOGI("ANR monitor enabled (Signal Catcher tid=%d)\n", static_cast<int>(catcher));
    return JNI_TRUE;
}

// CrashHandler.nativeDisableAnrMonitor(). MUST run on the main thread.
void JNICALL native_disable_anr_monitor(JNIEnv *, jclass) {
    if (gettid() != getpid()) {
        LOGE("%s\n", "nativeDisableAnrMonitor must be called on the main thread");
        return;
    }

    // Re-block first so new SIGQUITs go back to Signal Catcher directly.
    sigset_t set {};
    sigemptyset(&set);
    sigaddset(&set, SIGQUIT);
    pthread_sigmask(SIG_BLOCK, &set, nullptr);

    // The handler stays installed in pass-through mode (rule 3): threads that
    // inherited the unblocked mask may still receive SIGQUIT.
    anr_enabled.store(false, std::memory_order_release);
}

void anr_monitor_init(JavaVM *vm, JNIEnv *env, jclass clazz) {
    jvm = vm;
    handler_class = clazz;

    javaAnrSignalMethod = env->GetStaticMethodID(clazz, "onAnrSignal", "()V");
    if (javaAnrSignalMethod == nullptr) env->ExceptionClear();
    javaAnrConfirmMethod = env->GetStaticMethodID(clazz, "onAnrConfirm", "()V");
    if (javaAnrConfirmMethod == nullptr) env->ExceptionClear();
}

void anr_monitor_shutdown() {
    // This is the one place rule 3 is broken on purpose: once the .so is
    // unmapped, a handler pointing into it is certain death, whereas a stray
    // SIGQUIT on a thread that inherited the unblocked mask is merely possible.
    anr_enabled.store(false, std::memory_order_release);
    if (sigquit_installed) {
        sigaction(SIGQUIT, &old_sigquit_action, nullptr);
        sigquit_installed = false;
    }
    // The owner is about to delete the global ref; stop using it.
    handler_class = nullptr;
    javaAnrSignalMethod = nullptr;
    javaAnrConfirmMethod = nullptr;
}

}  // namespace crash
