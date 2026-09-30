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

#include <iostream>
#include <iomanip>
#include <sstream>

#include <unwindstack/AndroidUnwinder.h>

#include "utils.h"
#include "stacktrace.h"


static uint64_t get_fault_address(const err_context_t *err_context) {
    return reinterpret_cast<uint64_t>(err_context->si->si_addr);
}

static std::string get_register_snapshot(const err_context_t *err_context) {
    auto regs = unwindstack::Regs::CreateFromUcontext(
        unwindstack::Regs::CurrentArch(), err_context->sc
    );

    std::ostringstream oss;
    regs->IterateRegisters([&oss](const char *name, uint64_t value) {
        oss << "\t" << name << "\t" << std::hex << "\t" << value << std::endl;
    });    
    return oss.str();
}

static bool is_app_frame(const unwindstack::FrameData &frame) {
    if (!frame.map_info) return false;
    const std::string &name = frame.map_info->name();
    return name.find("x.editor.app") != std::string::npos;
}

std::string dump_stacktrace(const err_context_t *err_context) {
    std::ostringstream oss;
    uint64_t addr = get_fault_address(err_context);
    const char *emsg = get_signal_string(
        err_context->si->si_signo, err_context->si->si_code, addr
    );
    
    oss << emsg << std::endl;
    oss << get_register_snapshot(err_context) << std::endl;  
    oss << "backtrace: " << std::endl;
    
    // get the symbol info
    unwindstack::AndroidLocalUnwinder unwinder;
    unwindstack::AndroidUnwinderData data;    
    if (unwinder.Unwind(err_context->sc, data)) {
        for (const auto &frame : data.frames) {
            if (is_app_frame(frame)) {
                oss << unwinder.FormatFrame(frame) << std::endl;
            }
        }
    }
    return oss.str();
}

