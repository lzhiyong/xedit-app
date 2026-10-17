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

#ifndef __STACKTRACE_H__
#define __STACKTRACE_H__

#include <signal.h>
#include <sys/types.h>
#include <sys/ucontext.h>

#include <string>

/*
 * Crash context handed from the signal handler to the reporter thread.
 *
 * IMPORTANT: siginfo_t and ucontext_t are stored BY VALUE, not by pointer.
 * The kernel hands the handler pointers into the crashing thread's signal
 * frame (on the alternate signal stack when SA_ONSTACK is set). That frame is
 * only alive while signal_callback() has not returned, and the very same alt
 * stack region is reused by any subsequent signal on that thread. The reporter
 * thread runs asynchronously and must not depend on either, so the handler
 * copies both structs into this object before waking it up.
 *
 * signo is carried here as well, rather than being squeezed through the
 * eventfd payload: an eventfd write ADDS to a counter, so two notifications
 * arriving before one read would be summed into a meaningless signal number.
 */
typedef struct abort_context {
    siginfo_t  si;                 // copy of the kernel-supplied siginfo
    ucontext_t sc;                 // copy of the kernel-supplied ucontext
    int        signo;              // the signal that fired
    pid_t      tid;                // tid of the crashing thread
    pid_t      pid;                // process id, so tid == pid means "main thread"
    char       thread_name[16];    // from prctl(PR_GET_NAME) - kernel caps at 16
} err_context_t;

/*
 * Format the captured crash as a human-readable report.
 *
 * app_marker
 *   nullptr / "" (default) - emit every frame the unwinder produced. This is
 *                 what diagnosing a native crash normally needs: most of them
 *                 land in libc (abort/malloc), libart or a third-party .so,
 *                 with the app's own frames further down the stack or absent
 *                 entirely, so filtering them out often leaves nothing.
 *   otherwise   - emit only frames whose mapped file path contains this
 *                 substring, i.e. the app's own code. Useful when the report
 *                 is shown to an end user, or when the Kotlin side only wants
 *                 the app-owned portion. If no frame matches, the report says
 *                 so rather than silently returning an empty backtrace.
 *
 * The marker is a parameter rather than a hard-coded package name so the
 * caller can derive it at runtime (see resolve_app_marker() in
 * crash_handler.cpp) instead of baking a build-time string into this file.
 * Pass a stable buffer: nothing here takes ownership.
 */
std::string dump_stacktrace(const err_context_t *, const char *app_marker = nullptr);

#endif // __STACKTRACE_H__
