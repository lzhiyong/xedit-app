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
 * Native crash capture - design overview
 * ======================================
 *
 * Goal
 * ----
 * Catch fatal native fault signals (see kHandledSignals) and deliver a
 * formatted stack trace to Kotlin CrashHandler.callback() BEFORE the process
 * dies - while keeping logcat/debuggerd and any other crash SDK that
 * registered earlier fully working. We chain to previously-installed handlers
 * instead of replacing them.
 *
 * Scope
 * -----
 * The crash pipeline described below handles SYNCHRONOUS FAULTS ONLY: signals
 * the kernel raises because the thread executed something impossible. ANR is
 * NOT a crash and never goes through it. ANRs are captured by a separate,
 * opt-in SIGQUIT pipeline (Matrix-style) in anr_monitor.cpp, which documents
 * the rules it follows so that it does not break the system's own ANR traces.
 * ApplicationExitInfo (API 30+) remains the fallback for ANRs that kill the
 * process before Java can react.
 *
 * Files
 * -----
 *   crash_handler.cpp    fault signals -> reporter thread -> Kotlin callback;
 *                        JNI_OnLoad/OnUnload for the whole library
 *   abort_message.cpp    reads android_set_abort_message() text, the
 *                        "Abort message" line of a crash
 *   fallback_report.cpp  native-written copy of the report (write-ahead);
 *                        Kotlin deletes it once its own log is on disk
 *   anr_monitor.cpp      opt-in SIGQUIT-based ANR detection
 *
 * Why a hand-rolled pipeline
 * --------------------------
 * JNI calls are not async-signal-safe, so the signal handler itself can never
 * report into Java. All Java-facing work is delegated to a dedicated
 * "crash_report" thread created once at install time and kept alive for the
 * rest of the process. The two ends are connected by an eventfd doorbell, and
 * a semaphore acts as a rendezvous so the process does not die mid-report.
 *
 * Flow
 * ----
 *   crashing thread                          crash_report thread (started once)
 *   ----------------                         ----------------------------------
 *   fault -> signal_callback()
 *     (runs on the alt signal stack, SA_ONSTACK)
 *     0. bail out if this IS the reporter thread, or if another thread is
 *        already reporting (single-report latch)
 *     1. arm the 15s watchdog (alarm) - the process still dies even if the
 *        reporting path below hangs
 *     2. COPY siginfo/ucontext/tid/name into err_context (by value)
 *     3. write a doorbell token -> signal_notifier --->  wakes from read()
 *     4. sem_timedwait(10s), on EVERY thread,            read_abort_message()
 *        main thread included                            fallback file: header
 *                                                        dump_stacktrace()
 *                                                        fallback file: trace
 *                                                        CallStaticVoidMethod(
 *                                                          signo, report,
 *                                                          fallback file name)
 *                                                          Kotlin: persist, then
 *                                                          delete the fallback
 *                                                          file, then listener
 *                                                        sem_post()
 *     5. invoke_previous_handler() ----> default action, process dies
 *
 *     The main thread waits like any other. That is only correct because
 *     CrashActivity lives in its own process (android:process=":crash"): a
 *     same-process activity would need this thread's Looper, which never runs
 *     again once we are inside a signal handler. Do not move it back.
 *
 * The fallback file steps are implemented in fallback_report.cpp. The native
 * side never deletes the file itself: only Kotlin knows the exact moment its
 * own log reached the disk (after CrashLogStore.write(), BEFORE the listener
 * runs), so it is Kotlin that deletes it. A file still present at the next
 * launch therefore means "Kotlin never saved this crash", and
 * CrashLogStore.adoptNativeFallbacks() turns it into a regular crash log.
 *
 * Ordering invariants (established in JNI_OnLoad - do NOT reorder)
 * ----------------------------------------------------------------
 *   1. Resolve javaCrashHandlerClass / javaCrashCallbackMethod FIRST. A crash
 *      landing in the reporter before the refs exist used to mean a null
 *      jclass and a second, unhandled crash. Refs are cached as global refs so
 *      no FindClass ever happens on the reporter thread (its class-loader
 *      context would be wrong anyway).
 *   2. Create the eventfd/semaphore and START THE REPORTER THREAD, and only
 *      then arm the handlers. Arming first leaves a window in which a crashing
 *      thread can pass the "is the reporter up?" check and then block with
 *      nobody to wake it.
 *   3. This library must be the FIRST native library loaded in the process
 *      (top of Application.attachBaseContext): a fault in any library loaded
 *      before this point runs before any handler exists and cannot be caught.
 *      The same caveat applies to THIS library's own static initializers,
 *      which run before JNI_OnLoad - keep them trivial, and split a minimal
 *      hook library out if heavy globals are needed.
 *
 * Deliberate design choices
 * -------------------------
 *   - sigaltstack + SA_ONSTACK: the handler must run somewhere safe even if
 *     the crash was a stack overflow that destroyed the thread's own stack.
 *     The stack is mmap'd with a guard page rather than calloc'd: a SIGABRT
 *     raised from inside the allocator must not have its handler stack sitting
 *     in the very heap that is suspect. Note sigaltstack is PER-THREAD; bionic
 *     already installs one on every thread it creates, so this call mainly
 *     upgrades the installing thread's.
 *   - sa_mask blocks only the signals we handle (not sigfillset): the handler
 *     can legitimately run for a while (waiting for the report), and blocking
 *     every signal would starve ART's signal-based thread machinery (e.g.
 *     cross-thread Thread.getStackTrace() issued from the Kotlin callback) for
 *     that whole window.
 *   - invoke_previous_handler: preserves whatever was registered before us
 *     (another crash SDK, ART, debuggerd via libsigchain) - this is what keeps
 *     debuggerd working alongside this handler. It always ends with SIG_DFL +
 *     re-raise so the process is guaranteed to die even if a chained handler
 *     returns.
 *   - err_context is a single shared instance, protected by the single-report
 *     latch rather than by luck: exactly one thread ever writes it.
 *   - waiter_pending: the reporter must know whether a crashing thread is
 *     actually blocked on crash_done_sem (the doorbell may have failed, or the
 *     reporter may be dying) before deciding to sem_post - otherwise a stale
 *     semaphore count would let a future wait pass through without waiting.
 *   - The reporter thread does NOT block the fault signals. If a synchronous
 *     fault hits a thread that blocks it, the kernel resets that signal to
 *     SIG_DFL for the WHOLE process, which bypasses debuggerd: no report and
 *     no crash. Unblocked, a fault on the reporter goes through
 *     signal_callback()'s reporter check and chains to debuggerd normally.
 *
 * Known limits
 * ------------
 *   - Faults during static initializers of libraries loaded before this one
 *     (or before our JNI_OnLoad ran) are invisible here; Android debuggerd
 *     remains the fallback for those.
 *   - A crash caused by heap corruption may deadlock the reporter inside
 *     malloc. That is what the watchdog is for.
 *   - Everything added to signal_callback must be async-signal-safe:
 *     write/read/alarm/signal/sem_post/clock_gettime qualify, usleep and
 *     sem_timedwait are widely-tolerated gray areas, JNI/malloc/
 *     anything-locked are strictly off-limits.
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <semaphore.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/types.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <string>

#include "abort_message.h"
#include "anr_monitor.h"
#include "fallback_report.h"
#include "log.h"
#include "stacktrace.h"

// The app module package name
#define PACKAGE "x/github/module/crash/"

namespace {

/*
 * Fatal, SYNCHRONOUS fault signals only - signals the kernel raises because
 * the thread itself executed something impossible. Each one means the process
 * is already unrecoverable, so terminating after reporting is correct.
 *
 * Deliberately NOT handled:
 *   SIGQUIT - ART's SignalCatcher thread sigwait()s on this to dump ANR
 *             traces, and ART keeps it blocked in every other thread so the
 *             kernel is forced to deliver it there. Installing a handler (and
 *             unblocking it, as this file used to) lets an ANR trace request
 *             land on a normal thread instead: we would steal the dump from
 *             SignalCatcher (producing empty ANR traces in Play Console) and
 *             then chain to SIG_DFL, killing the app on every ANR dump and on
 *             every `adb shell kill -3`. That is why it is NOT in this list.
 *             anr_monitor.cpp does intercept it,
 *             but under rules that avoid exactly these failures: it forwards
 *             every SIGQUIT back to SignalCatcher with tgkill and never
 *             chains to SIG_DFL.
 *   SIGHUP / SIGINT / SIGTERM - asynchronous, externally sent shutdown
 *             requests, not crashes. Handling them yields false-positive
 *             crash reports and steals them from whoever wanted them.
 *
 * SIGFPE and SIGSYS are added: integer division by zero is common C++ UB, and
 * seccomp-bpf (Android O+) raises SIGSYS for a blocked syscall, which
 * third-party .so files hit more often than one would like.
 */
constexpr std::array<int, 7> kHandledSignals = {
    SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSEGV, SIGSYS, SIGTRAP
};

// old_actions[] is indexed by signal number, so it must span the whole signal
// number space. NSIG is bionic's count of signal numbers (real-time included).
constexpr int kMaxSignalNum = NSIG;

// The process must die even if the reporting path wedges (e.g. the reporter
// blocks in malloc because the crashing thread holds the allocator lock).
constexpr unsigned kWatchdogSeconds = 15;

// How long a crashing thread (main thread included) waits for the report.
// Bounded so a dead/stuck reporter costs us this, not the full watchdog.
// Keep the Kotlin callback well under it: a main thread stuck here for more
// than ~5s with pending input can additionally be reported as an ANR.
constexpr time_t kReportTimeoutSeconds = 10;

// A second thread crashing while the first is reporting waits this long for
// the first to finish, so it does not tear the process down mid-report.
constexpr long kSecondaryWaitUs = 5 * 1000 * 1000;
constexpr useconds_t kSecondaryPollUs = 10 * 1000;

// global JVM / java refs - only ever written once, in JNI_OnLoad, BEFORE any
// signal handler is armed. Nothing after init_native_capture() touches these.
JavaVM *jvm = nullptr;
jclass javaCrashHandlerClass = nullptr;       // x.github.module.crash.CrashHandler
jmethodID javaCrashCallbackMethod = nullptr;  // CrashHandler.callback(int, String, String)

// eventfd used purely as a DOORBELL: the handler writes a token to wake the
// reporter. The signal number travels in err_context, not in this payload -
// an eventfd write adds to a counter, so two tokens arriving before one read
// would be summed into a nonsense signal number.
int signal_notifier = -1;

// True once the reporter thread is attached to the JVM and parked in read().
// The handler checks this instead of `signal_notifier >= 0` so it never blocks
// on a reporter that failed to start or has given up.
std::atomic<bool> reporter_ready{ false };

// Released by the reporter after the Kotlin callback completes, so a crashing
// thread can block until reporting is done instead of dying at once.
sem_t crash_done_sem;

// Set by the crashing thread right before it blocks on crash_done_sem. The
// reporter checks/clears it to decide whether a sem_post is owed.
std::atomic<bool> waiter_pending{ false };

// Single-report latch. Exactly one thread ever gets to write err_context and
// drive the report; any other thread that faults concurrently waits briefly
// and then chains straight to the previous handler.
std::atomic<bool> crash_in_progress{ false };

// Set once the primary crashing thread is done with the reporting rendezvous,
// so secondary crashing threads can stop waiting early.
std::atomic<bool> report_finished{ false };

// tid of the reporter thread, so the handler can recognise a crash that
// happened on the reporter itself (which must never try to report into
// itself - the read end would be the very thread that is blocked).
std::atomic<pid_t> reporter_tid{ 0 };

// Shared crash context, written by the latch winner only, read by the
// reporter. Holds copies, never pointers into the signal frame.
err_context_t err_context;

// Previously-installed handlers, so we chain instead of clobbering whatever
// another library (or ART / debuggerd via libsigchain) already registered.
struct sigaction old_actions[kMaxSignalNum];

// SIGALRM is used by the watchdog but is not one of kHandledSignals, so its
// previous disposition is saved separately and restored if we cancel.
struct sigaction old_alarm_action;

// Alternate signal stack bookkeeping, kept so a failed install can roll back.
void *alt_stack_base = nullptr;
size_t alt_stack_len = 0;


static void invoke_previous_handler(int signo, siginfo_t *si, void *sc) {
    if (signo <= 0 || signo >= kMaxSignalNum) return;
    struct sigaction &old = old_actions[signo];

    if ((old.sa_flags & SA_SIGINFO) && old.sa_sigaction != nullptr) {
        old.sa_sigaction(signo, si, sc);
    } else if (!(old.sa_flags & SA_SIGINFO) &&
               old.sa_handler != SIG_DFL &&
               old.sa_handler != SIG_IGN &&
               old.sa_handler != nullptr) {
        old.sa_handler(signo);
    }

    // Always finish with the default action, even if a chained handler
    // returned. These signals are fatal by definition: returning from the
    // handler re-executes the faulting instruction, so a chained handler that
    // simply returns (or an SDK that only logs) would spin forever re-faulting
    // instead of letting the process die. Restoring SIG_DFL and re-raising
    // also covers asynchronously delivered ones (`kill -SEGV`), where there is
    // no faulting instruction to retry.
    signal(signo, SIG_DFL);
    raise(signo);
}

// Arm the watchdog. signal()/alarm() are both async-signal-safe.
static void arm_watchdog() {
    signal(SIGALRM, SIG_DFL);
    alarm(kWatchdogSeconds);
}

// Cancel it. Called from the reporter thread (not a handler), so plain
// sigaction() is fine here.
static void cancel_watchdog() {
    alarm(0);
    sigaction(SIGALRM, &old_alarm_action, nullptr);
}

// Copy everything the reporter will need out of the signal frame, which stops
// being valid the moment this handler returns.
static void capture_context(int signo, siginfo_t *si, void *sc) {
    memset(&err_context, 0, sizeof(err_context));

    if (si != nullptr) {
        err_context.si = *si;
    } else {
        err_context.si.si_signo = signo;
    }
    if (sc != nullptr) {
        memcpy(&err_context.sc, sc, sizeof(ucontext_t));
#if defined(__x86_64__) || defined(__i386__)
        // bionic's x86 ucontext_t keeps the FP state in a trailing
        // __fpregs_mem member and has uc_mcontext.fpregs point into it. After
        // a byte-wise copy that pointer would still aim at the original
        // (soon-to-be-dead) frame, so null it instead of leaving a dangling
        // pointer around - unwindstack only reads general-purpose registers.
        err_context.sc.uc_mcontext.fpregs = nullptr;
#endif
    }

    err_context.signo = signo;
    err_context.tid = gettid();
    err_context.pid = getpid();
    // prctl is a single syscall; safe to call from a handler.
    prctl(PR_GET_NAME, err_context.thread_name, 0, 0, 0);
}

static void signal_callback(int signo, siginfo_t *si, void *sc) {
    const pid_t self = gettid();

    // (a) A crash ON the reporter thread must never try to report: the thread
    //     that would have to read the doorbell is the one that just faulted.
    //     Chain straight through so debuggerd still produces a crash.
    if (self == reporter_tid.load(std::memory_order_relaxed)) {
        arm_watchdog();
        invoke_previous_handler(signo, si, sc);
        return;
    }

    // (b) Only the first crashing thread reports. Without this latch two
    //     threads faulting at once both write err_context (last writer wins,
    //     so the trace can describe one crash with the other's registers) and
    //     both ring the doorbell, which an eventfd would sum into one token.
    bool expected = false;
    if (!crash_in_progress.compare_exchange_strong(
            expected, true,
            std::memory_order_acq_rel, std::memory_order_acquire)) {
        for (long waited = 0;
             waited < kSecondaryWaitUs &&
             !report_finished.load(std::memory_order_acquire);
             waited += kSecondaryPollUs) {
            usleep(kSecondaryPollUs);
        }
        invoke_previous_handler(signo, si, sc);
        return;
    }

    arm_watchdog();
    capture_context(signo, si, sc);

    if (reporter_ready.load(std::memory_order_acquire)) {
        // Release-store pairs with the reporter's acquire-load after read(),
        // publishing every byte of err_context written above. The write()
        // syscall happens to be a barrier on every Android ABI, but relying on
        // that was never actually guaranteed by the memory model.
        waiter_pending.store(true, std::memory_order_release);

        uint64_t token = 1;
        ssize_t n = TEMP_FAILURE_RETRY(
            write(signal_notifier, &token, sizeof(token)));

        if (n == static_cast<ssize_t>(sizeof(token))) {
            // Block until the report is done - on the main thread too, which
            // is safe only because CrashActivity runs in the :crash process
            // (see the header comment). Chaining to debuggerd any earlier
            // would let crash_dump freeze every thread, the reporter
            // included, in the middle of the report. The timeout means a
            // wedged or exited reporter costs 10s rather than the full
            // watchdog, and the process still dies through the chain below.
            struct timespec ts {};
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += kReportTimeoutSeconds;
            while (sem_timedwait(&crash_done_sem, &ts) != 0 &&
                   errno == EINTR) {
                // retry on interruption only; ETIMEDOUT falls through
            }
        } else {
            // Doorbell never rang - nobody will ever post the semaphore, so
            // make sure we do not advertise a waiter that is not there.
            LOGE("crash doorbell write failed: %s\n", strerror(errno));
            waiter_pending.store(false, std::memory_order_release);
        }
    }

    report_finished.store(true, std::memory_order_release);

    // Give whatever else was registered for this signal (another crash SDK,
    // ART, debuggerd via libsigchain) a chance to see it too, instead of
    // silently swallowing it - this is what makes logcat/debuggerd keep
    // working alongside this handler.
    invoke_previous_handler(signo, si, sc);
}

static void release_waiter() {
    if (waiter_pending.exchange(false, std::memory_order_acq_rel)) {
        sem_post(&crash_done_sem);
    }
}

// Hand the report to CrashHandler.callback(), together with the name of the
// fallback file ("" if none was written). Kotlin deletes that file as soon as
// its own log is on disk; native code never deletes it.
static void deliver_to_java(JNIEnv *env, const std::string &report,
                            const char *fallback_name) {
    // Resolved in JNI_OnLoad before any handler is armed, so never null in
    // practice; kept so a stray early signal cannot double-fault here.
    if (javaCrashHandlerClass == nullptr || javaCrashCallbackMethod == nullptr) {
        LOGE("%s\n", "javaCrashHandlerClass not ready yet, dropping native crash callback");
        return;
    }

    jstring message = env->NewStringUTF(report.c_str());
    if (message == nullptr) {
        env->ExceptionClear();
        return;
    }
    // The name is plain ASCII (native_<ms>_<pid>.log), so this cannot hit the
    // CheckJNI UTF-8 abort. A null here only means Kotlin cannot clean up, and
    // the file is then adopted on the next launch - still no loss.
    jstring name = env->NewStringUTF(fallback_name != nullptr ? fallback_name : "");
    if (name == nullptr) env->ExceptionClear();

    env->CallStaticVoidMethod(javaCrashHandlerClass, javaCrashCallbackMethod,
                              static_cast<jint>(err_context.signo), message, name);
    env->DeleteLocalRef(message);
    if (name != nullptr) env->DeleteLocalRef(name);

    // A Kotlin-side exception would otherwise stay pending and blow up on the
    // next JNI call on this thread. Whether the fallback file survives is
    // already decided on the Kotlin side, so nothing else to do here.
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// Reporter thread: owns every JNI call. Created once, lives forever.
static void *crash_report_thread(void *) {
    pthread_setname_np(pthread_self(), "crash_report");
    reporter_tid.store(gettid(), std::memory_order_relaxed);

    // Deliberately NOT blocking the fault signals here: a synchronous fault on
    // a thread that blocks it makes the kernel reset that signal to SIG_DFL
    // process-wide, bypassing debuggerd - no report AND no crash. Left
    // unblocked, any fault on this thread (an unwinder bug, or a
    // process-directed `kill -SEGV` that happens to land here) hits
    // signal_callback()'s reporter check and chains straight to debuggerd,
    // without ever ringing a doorbell only this thread could answer.

    JNIEnv *env = nullptr;
    JavaVMAttachArgs args { JNI_VERSION_1_6, "crash_report", nullptr };
    if (jvm->AttachCurrentThread(&env, &args) != JNI_OK) {
        LOGE("%s\n", "The jvm failed to attach the crash reporter thread");
        reporter_ready.store(false, std::memory_order_release);
        release_waiter();
        return nullptr;
    }

    // Only now may a crashing thread decide to block waiting for us.
    reporter_ready.store(true, std::memory_order_release);

    for (;;) {
        uint64_t token = 0;
        ssize_t rc = TEMP_FAILURE_RETRY(
            read(signal_notifier, &token, sizeof(token)));

        if (rc != static_cast<ssize_t>(sizeof(token))) {
            // A broken eventfd is unrecoverable, but exiting silently used to
            // leave every future crashing thread blocked on a semaphore that
            // nobody would ever post. Tell the handler we are gone first.
            LOGE("eventfd read failed: %s\n", strerror(errno));
            reporter_ready.store(false, std::memory_order_release);
            release_waiter();
            break;
        }

        // Pairs with the handler's release-store on waiter_pending; from here
        // on err_context is guaranteed to be fully visible.
        std::atomic_thread_fence(std::memory_order_acquire);

        // Everything up to fallback.begin() is heap-free, so the header and
        // abort message reach the disk even if dump_stacktrace() then wedges
        // in a corrupted heap.
        const char *abort_message = crash::read_abort_message();
        crash::FallbackReport fallback;
        fallback.begin(err_context, abort_message);

        // Always the full backtrace: most native crashes land in libc
        // (abort/malloc), libart or a third-party .so, and those frames are
        // the ones that explain the crash. Highlight app frames server-side.
        const std::string trace = dump_stacktrace(&err_context, nullptr);
        fallback.finish(trace);

        std::string report;
        if (abort_message[0] != '\0') {
            // Same wording as a crash, so existing tooling/grep works.
            report.append("Abort message: '").append(abort_message).append("'\n");
        }
        report += trace;

        // No fallback.discard() here: Kotlin deletes the file right after its
        // own log is written, which is the only moment that is both safe (our
        // copy is no longer needed) and early enough (a listener that throws
        // or hangs past the 10s rendezvous can no longer cause a duplicate).
        deliver_to_java(env, report, fallback.name());

        // Unblock the crashing thread before anything else - it is parked on a
        // 10s timeout and every millisecond here comes out of that budget.
        release_waiter();

        // Cancel the watchdog: reaching this point means the crash was fully
        // reported and the process is about to die through
        // invoke_previous_handler anyway. Cancel regardless - if a chained
        // handler from another SDK somehow lets the process survive, a
        // leftover SIGALRM would kill it out of the blue 15 seconds later.
        cancel_watchdog();
    }

    jvm->DetachCurrentThread();
    return nullptr;
}

// mmap'd rather than calloc'd: if the crash came out of the allocator
// (SIGABRT from malloc's own checks, a corrupted arena), the handler must not
// be running on a stack carved out of that same suspect heap. A PROT_NONE
// guard page below the stack turns an overflow of the handler stack itself
// into a clean fault instead of silent corruption.
static bool install_alt_stack() {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    size_t size = std::max<size_t>(static_cast<size_t>(SIGSTKSZ), 64 * 1024);
    size = (size + page - 1) & ~(page - 1);
    const size_t total = size + page;

    void *base = mmap(nullptr, total, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) {
        LOGE("alt stack mmap failed: %s\n", strerror(errno));
        return false;
    }
    if (mprotect(base, page, PROT_NONE) != 0) {
        LOGE("alt stack guard page failed: %s\n", strerror(errno));
    }

    stack_t ss {};
    ss.ss_sp = static_cast<char *>(base) + page;
    ss.ss_size = size;
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) {
        LOGE("sigaltstack failed: %s\n", strerror(errno));
        munmap(base, total);
        return false;
    }

    alt_stack_base = base;
    alt_stack_len = total;
    return true;
}

static bool register_signals(void (*handler)(int, siginfo_t *, void *)) {
    // Remember SIGALRM's disposition so the watchdog can be cancelled without
    // leaving the app's own SIGALRM handling clobbered.
    sigaction(SIGALRM, nullptr, &old_alarm_action);

    if (!install_alt_stack()) {
        return false;
    }

    struct sigaction siga {};
    siga.sa_sigaction = handler;
    // Only block the signals we ourselves handle (prevents nested re-entry
    // into this same handler). Deliberately NOT sigfillset(): this handler can
    // run for a while (it waits for the report to finish), and blocking every
    // signal for that whole window can starve things like ART's own
    // signal-based thread-introspection (e.g. a cross-thread
    // Thread.getStackTrace() from the Kotlin callback needing to suspend this
    // very thread).
    sigemptyset(&siga.sa_mask);
    for (int signum : kHandledSignals) {
        sigaddset(&siga.sa_mask, signum);
    }
    // No SA_RESTART: it only governs how interrupted slow syscalls resume,
    // which is meaningless for signals that always end the process, and
    // leaving it on suggests these handlers are expected to return normally.
    siga.sa_flags = SA_SIGINFO | SA_ONSTACK;

    size_t installed = 0;
    for (int signum : kHandledSignals) {
        if (signum <= 0 || signum >= kMaxSignalNum ||
            sigaction(signum, &siga, &old_actions[signum]) != 0) {
            LOGE("sigaction(%d) failed: %s\n", signum, strerror(errno));
            // Roll back: a half-installed handler set is worse than none. The
            // old code left the successfully-installed handlers live while
            // reporting failure, after which init tore the eventfd down - so
            // those handlers kept firing against a dead pipeline.
            for (size_t j = 0; j < installed; ++j) {
                int done = kHandledSignals[j];
                sigaction(done, &old_actions[done], nullptr);
            }
            if (alt_stack_base != nullptr) {
                stack_t disable {};
                disable.ss_flags = SS_DISABLE;
                sigaltstack(&disable, nullptr);
                munmap(alt_stack_base, alt_stack_len);
                alt_stack_base = nullptr;
                alt_stack_len = 0;
            }
            return false;
        }
        ++installed;
    }
    return true;
}

static bool init_native_capture() {
    if (sem_init(&crash_done_sem, 0, 0) != 0) {
        LOGE("sem_init failed: %s\n", strerror(errno));
        return false;
    }

    signal_notifier = eventfd(0, EFD_CLOEXEC);
    if (signal_notifier < 0) {
        LOGE("eventfd failed: %s\n", strerror(errno));
        sem_destroy(&crash_done_sem);
        return false;
    }

    // Start the reporter BEFORE arming the handlers. The old order armed
    // first, leaving a window where a crashing thread could see the pipeline
    // as live and block on a semaphore that no thread existed to post.
    pthread_t thread;
    if (pthread_create(&thread, nullptr, crash_report_thread, nullptr) != 0) {
        LOGE("pthread_create failed: %s\n", strerror(errno));
        close(signal_notifier);
        signal_notifier = -1;
        sem_destroy(&crash_done_sem);
        return false;
    }
    pthread_detach(thread);

    if (!register_signals(signal_callback)) {
        // Leave the reporter thread parked in read(); it is harmless and
        // cannot be joined safely from here. Just make sure no handler is
        // live and no crashing thread will ever wait on it.
        reporter_ready.store(false, std::memory_order_release);
        return false;
    }
    return true;
}

// Test hook for SIGSEGV - a genuine invalid memory access, not raise(), so it
// exercises the same "faulting instruction gets retried on SIG_DFL" path a
// real crash does. raise(SIGSEGV) does NOT retry on return, so it cannot
// validate invoke_previous_handler(): it would falsely look fine even if that
// call were broken or removed.
//
// Signature is (JNIEnv*, jclass) as the JNI calling convention requires. The
// previous no-argument version only worked by accident, because the arguments
// happened to be ignored on the ABIs it was tested on.
void JNICALL native_test_crash(JNIEnv *, jclass) {
    *((volatile int *)nullptr) = 1;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    jvm = vm;
    if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to init the JVM environment\n");
        return JNI_ERR;
    }

    // 1) Resolve everything Java-side FIRST. Signal handlers must never go
    //    live while javaCrashHandlerClass/javaCrashCallbackMethod could still
    //    be null - otherwise a crash here in JNI_OnLoad, or in any other
    //    library loaded right after this one, leads the reporter to touch a
    //    null jclass and double-fault with nothing left to catch it.
    jclass local = env->FindClass(PACKAGE "CrashHandler");
    if (local == nullptr) {
        LOGE("Can not find the class CrashHandler\n");
        env->ExceptionClear();
        return JNI_ERR;
    }

    javaCrashHandlerClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    javaCrashCallbackMethod = env->GetStaticMethodID(
        javaCrashHandlerClass, "callback",
        "(ILjava/lang/String;Ljava/lang/String;)V");
    if (javaCrashCallbackMethod == nullptr) {
        LOGE("Can not find CrashHandler.callback(int, String, String)\n");
        env->ExceptionClear();
        env->DeleteGlobalRef(javaCrashHandlerClass);
        javaCrashHandlerClass = nullptr;
        return JNI_ERR;
    }

    // Optional ANR hooks. A missing one only disables ANR monitoring
    // (nativeEnableAnrMonitor returns false); crash capture is unaffected.
    crash::anr_monitor_init(jvm, env, javaCrashHandlerClass);

    const JNINativeMethod methods[] = {
        {"nativeTestCrash", "()V", reinterpret_cast<void *>(&native_test_crash)},
        {"nativeSetFallbackDir", "(Ljava/lang/String;)Z",
         reinterpret_cast<void *>(&crash::native_set_fallback_dir)},
        {"nativeEnableAnrMonitor", "()Z",
         reinterpret_cast<void *>(&crash::native_enable_anr_monitor)},
        {"nativeDisableAnrMonitor", "()V",
         reinterpret_cast<void *>(&crash::native_disable_anr_monitor)}
    };

    if (env->RegisterNatives(javaCrashHandlerClass, methods,
                             sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE("Fail to register native methods\n");
        return JNI_ERR;
    }

    // 2) Only now is it safe to start the reporter + arm signal handlers.
    //    Also: make sure this library is the first native library loaded in
    //    the process (e.g. loaded at the very top of
    //    Application.attachBaseContext()) - signals from a library loaded
    //    before this point cannot be caught, no matter what the handler does.
    if (!init_native_capture()) {
        LOGE("Failed to install native crash capture; continuing without it\n");
        // Not fatal to loading the library - better to run without native
        // crash reporting than to fail loadLibrary() entirely.
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    // Unloading is not really supported (the reporter thread cannot be joined
    // safely from here), but the one thing that MUST happen is un-arming the
    // handlers: once this .so is unmapped, every sigaction entry still points
    // at code that no longer exists, so the next signal of any handled kind
    // would jump into unmapped memory - an unrecoverable crash caused purely
    // by the crash handler itself.
    reporter_ready.store(false, std::memory_order_release);
    for (int signum : kHandledSignals) {
        if (signum > 0 && signum < kMaxSignalNum) {
            sigaction(signum, &old_actions[signum], nullptr);
        }
    }

    // Same for SIGQUIT - and before the class ref below goes away, since the
    // ANR module borrows it.
    crash::anr_monitor_shutdown();

    JNIEnv *env = nullptr;
    if (jvm != nullptr &&
        jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (javaCrashHandlerClass != nullptr) {
            env->DeleteGlobalRef(javaCrashHandlerClass);
            javaCrashHandlerClass = nullptr;
            javaCrashCallbackMethod = nullptr;
        }
    }
}
