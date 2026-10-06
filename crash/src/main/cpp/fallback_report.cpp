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
 * Fallback crash report - see fallback_report.h for the contract.
 *
 * Write-ahead copy of a native crash report. The reporter thread writes it
 * BEFORE calling into Java, so the crash survives even if the JNI callback
 * never completes (corrupted heap, wedged ART, failed attach...).
 *
 * Ownership of the file:
 *   - native code creates and fills it (begin / finish)
 *   - Kotlin deletes it, right after CrashLogStore has written its own log
 *     (the file name is passed to CrashHandler.callback for that purpose)
 *   - a file still present at the next launch was never saved by Kotlin, and
 *     CrashLogStore.adoptNativeFallbacks() converts it into a regular log
 *
 * File layout (adoptNativeFallbacks() parses it, keep the two in sync):
 *   signal <n> (<NAME>), code <c>, fault addr <p>\n
 *   pid: <pid>, tid: <tid>, name: <thread>\n
 *   [Abort message: '<text>'\n]
 *   \n
 *   <stack trace>
 */

#include "fallback_report.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>

#include "log.h"

namespace crash {
namespace {

// Directory for the fallback report, opened once by nativeSetFallbackDir().
// -1 means the fallback is off. Kept as an fd so the crash path only needs
// openat(), never a path lookup from scratch.
std::atomic<int> fallback_dir_fd{ -1 };

bool write_all(int fd, const char *data, size_t len) {
    while (len > 0) {
        ssize_t n = TEMP_FAILURE_RETRY(write(fd, data, len));
        if (n <= 0) return false;
        data += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

const char *signal_name(int signo) {
    switch (signo) {
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSEGV: return "SIGSEGV";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default: return "Unregistered signal";
    }
}

}  // namespace

FallbackReport::~FallbackReport() {
    // Normally finish() has closed it already; this only covers a caller
    // that skipped finish(). The file itself is kept either way.
    if (fd_ >= 0) close(fd_);
}

// fsync'd right away so the header survives even if dump_stacktrace() never
// returns.
void FallbackReport::begin(const err_context_t &ctx, const char *abort_message) {
    const int dir = fallback_dir_fd.load(std::memory_order_acquire);
    if (dir < 0 || fd_ >= 0) return;

    struct timespec now {};
    clock_gettime(CLOCK_REALTIME, &now);
    const long long epoch_ms =
        static_cast<long long>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
    snprintf(name_, sizeof(name_), "native_%lld_%d.log",
             epoch_ms, static_cast<int>(ctx.pid));

    fd_ = TEMP_FAILURE_RETRY(openat(
        dir, name_, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600));
    if (fd_ < 0) {
        name_[0] = '\0';
        return;
    }

    char header[256];
    int len = snprintf(header, sizeof(header),
                       "signal %d (%s), code %d, fault addr %p\n"
                       "pid: %d, tid: %d, name: %s\n",
                       ctx.signo, signal_name(ctx.signo),
                       ctx.si.si_code, ctx.si.si_addr,
                       static_cast<int>(ctx.pid),
                       static_cast<int>(ctx.tid),
                       ctx.thread_name);
    if (len > 0) {
        write_all(fd_, header,
                  std::min(static_cast<size_t>(len), sizeof(header) - 1));
    }
    if (abort_message != nullptr && abort_message[0] != '\0') {
        // Same wording as a crash, so existing tooling/grep works.
        write_all(fd_, "Abort message: '", 16);
        write_all(fd_, abort_message, strlen(abort_message));
        write_all(fd_, "'\n", 2);
    }
    fsync(fd_);
}

void FallbackReport::finish(const std::string &trace) {
    if (fd_ < 0) return;
    write_all(fd_, "\n", 1);
    write_all(fd_, trace.data(), trace.size());
    fsync(fd_);
    close(fd_);
    fd_ = -1;
}

// File name inside the fallback directory, or "" if no file was created
// (fallback disabled, or openat() failed). Passed to Kotlin, which deletes the
// file once its own log is on disk.
const char *FallbackReport::name() const {
    return name_;
}

// No longer used by crash_handler.cpp (Kotlin deletes the file instead); kept
// for callers that want to drop a report they decided not to keep.
void FallbackReport::discard() {
    const int dir = fallback_dir_fd.load(std::memory_order_acquire);
    if (dir < 0 || name_[0] == '\0') return;
    unlinkat(dir, name_, 0);
    name_[0] = '\0';
}

jboolean JNICALL native_set_fallback_dir(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return JNI_FALSE;
    const char *dir = env->GetStringUTFChars(path, nullptr);
    if (dir == nullptr) return JNI_FALSE;

    if (mkdir(dir, 0700) != 0 && errno != EEXIST) {
        LOGE("fallback dir mkdir(%s) failed: %s\n", dir, strerror(errno));
    }
    const int fd = TEMP_FAILURE_RETRY(
        open(dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (fd < 0) {
        LOGE("fallback dir open(%s) failed: %s\n", dir, strerror(errno));
    }
    env->ReleaseStringUTFChars(path, dir);
    if (fd < 0) return JNI_FALSE;

    // The previous fd, if any, is leaked on purpose: the reporter may be
    // using it right now, and one fd is cheaper than a use-after-close.
    fallback_dir_fd.store(fd, std::memory_order_release);
    return JNI_TRUE;
}

}  // namespace crash
