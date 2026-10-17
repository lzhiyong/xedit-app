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
 * Where this code runs
 * --------------------
 * NOT in the signal handler. dump_stacktrace() is called from the dedicated
 * reporter thread while the crashing thread is parked (blocked on a semaphore,
 * or inside its main-thread grace sleep). That is what makes it legal to use
 * std::string, iostreams, unwindstack and malloc here.
 *
 * It does mean one failure mode has to be accepted: if the crash was itself a
 * heap problem (SIGABRT out of malloc's own abort path, a corrupted arena),
 * the crashing thread may still hold the allocator lock, and the first malloc
 * on this thread will block forever. The watchdog alarm armed by the signal
 * handler is what bounds that case - the process dies at the deadline instead
 * of hanging in a half-crashed state.
 */

#include <inttypes.h>

#include <iomanip>
#include <memory>
#include <sstream>

#include <unwindstack/AndroidUnwinder.h>
#include <unwindstack/Regs.h>

#include "utils.h"
#include "stacktrace.h"

namespace {

// A frame counts as app code when its mapped file path contains the marker -
// typically the package name ("x.editor.app", which appears in every
// /data/app/~~.../x.editor.app-.../... path) or the app's install directory.
// The marker is never derived here; the caller supplies it.
bool is_app_frame(const unwindstack::FrameData &frame, const char *marker) {
    if (!frame.map_info) return false;
    // MapInfo::name() returns unwindstack::SharedString, a shared_ptr<string>
    // wrapper with no string members of its own. Binding to a const
    // std::string& is what triggers its conversion operator - calling
    // .find() directly on the SharedString does not compile.
    const std::string &name = frame.map_info->name();
    return name.find(marker) != std::string::npos;
}

std::string format_registers(const err_context_t *ctx) {
    // CreateFromUcontext() returns a raw OWNING pointer. Letting it escape
    // leaks the Regs object (and its per-arch register buffer) on every crash
    // report; harmless for a single fatal crash, but it is a real leak and it
    // trips leak checkers during testing. Take ownership immediately.
    std::unique_ptr<unwindstack::Regs> regs(
        unwindstack::Regs::CreateFromUcontext(
            unwindstack::Regs::CurrentArch(),
            const_cast<ucontext_t *>(&ctx->sc)));

    if (regs == nullptr) {
        return "    <register state unavailable>\n";
    }

    std::ostringstream oss;
    int column = 0;
    regs->IterateRegisters([&oss, &column](const char *name, uint64_t value) {
        oss << "    " << std::left << std::setfill(' ') << std::setw(6) << name
            << " 0x" << std::right << std::hex << std::setfill('0')
            << std::setw(16) << value << std::dec;
        oss << (((++column) % 2 == 0) ? "\n" : "  ");
    });
    if (column % 2 != 0) oss << "\n";
    return oss.str();
}

}  // namespace

std::string dump_stacktrace(const err_context_t *err_context, const char *app_marker) {
    // An empty marker means "do not filter". Checked explicitly rather than
    // relying on name().find("") - that returns 0 for every path, so an empty
    // marker would accidentally mark every frame as app code. It happens to
    // produce the right output here, but for the wrong reason.
    const bool filtering = (app_marker != nullptr && app_marker[0] != '\0');

    std::ostringstream oss;

    const uint64_t fault_addr =
        reinterpret_cast<uint64_t>(err_context->si.si_addr);

    oss << get_signal_string(err_context->si.si_signo,
                             err_context->si.si_code,
                             fault_addr)
        << std::endl;

    // Who actually crashed. Without this a report from a worker thread is
    // indistinguishable from a main-thread one, which changes both the triage
    // and whether the Kotlin side can expect a UI to come up at all.
    oss << "pid: " << err_context->pid
        << ", tid: " << err_context->tid
        << ", name: " << err_context->thread_name
        << (err_context->tid == err_context->pid ? "  >>> main thread <<<" : "")
        << std::endl;

    oss << std::endl << "registers:" << std::endl;
    oss << format_registers(err_context);

    oss << std::endl << "backtrace:" << std::endl;

    unwindstack::AndroidLocalUnwinder unwinder;
    unwindstack::AndroidUnwinderData data;

    if (unwinder.Unwind(const_cast<ucontext_t *>(&err_context->sc), data)) {
        size_t emitted = 0;
        for (const auto &frame : data.frames) {
            if (filtering && !is_app_frame(frame, app_marker)) continue;
            oss << "    " << unwinder.FormatFrame(frame) << std::endl;
            ++emitted;
        }

        if (emitted == 0) {
            // Never return a silently empty backtrace: the old code filtered
            // to app frames unconditionally, so a crash in libc or a
            // third-party .so - which is the common case - produced a report
            // with a signal, registers, and nothing under "backtrace:", with
            // no way to tell that from a failed unwind.
            if (filtering && !data.frames.empty()) {
                oss << "    <no frame matching \"" << app_marker << "\" among "
                    << data.frames.size()
                    << " unwound frames; pass a null marker for the full stack>"
                    << std::endl;
            } else {
                oss << "    <unwind produced no frames>" << std::endl;
            }
        }
    } else {
        // Silently returning an empty backtrace made a failed unwind look
        // identical to a crash with no app frames. Say which it was.
        oss << "    <unwind failed: " << data.GetErrorString() << ">"
            << std::endl;
    }

    return oss.str();
}
