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

#include <cxxabi.h>
#include <cinttypes>
#include <dlfcn.h>

#include "utils.h"
#include "stringprintf.h"
#include "stacktrace.h"


uintptr_t pc_from_ucontext(const ucontext_t *uc) {
#if (defined(__arm__))
    return uc->uc_mcontext.arm_pc;
#elif defined(__aarch64__)
    return uc->uc_mcontext.pc;
#elif (defined(__x86_64__))
    return uc->uc_mcontext.gregs[REG_RIP];
#elif (defined(__i386))
  return uc->uc_mcontext.gregs[REG_EIP];
#elif (defined (__ppc__)) || (defined (__powerpc__))
  return uc->uc_mcontext.regs->nip;
#elif (defined(__hppa__))
  return uc->uc_mcontext.sc_iaoq[0] & ~0x3UL;
#elif (defined(__sparc__) && defined (__arch64__))
  return uc->uc_mcontext.mc_gregs[MC_PC];
#elif (defined(__sparc__) && !defined (__arch64__))
  return uc->uc_mcontext.gregs[REG_PC];
#else
#error "Error: architecture is unknown"
#endif
}

uint64_t get_fault_address(const ucontext_t *uc) {
#if (defined(__arm__) || defined(__aarch64__))
    return uc->uc_mcontext.fault_address;
#else
    return 0L;
#endif
}

static const char * get_register_snapshot(const ucontext_t *uc) {
    char *regs_image = nullptr;
#if (defined(__arm__))
    asprintf(&regs_image, "%s r0 %08lx  r1 %08lx  r2 %08lx  r3 %08lx\n"
                 "%s r4 %08lx  r5 %08lx  r6 %08lx  r7 %08lx\n"
                 "%s r8 %08lx  r9 %08lx  r10 %08lx  fp %08lx\n"
                 "%s ip %08lx  sp %08lx  lr %08lx  pc %08lx\n"
                 "%s cpsr %08lx\n",
         indent(4).c_str(), uc->uc_mcontext.arm_r0, uc->uc_mcontext.arm_r1, uc->uc_mcontext.arm_r2, uc->uc_mcontext.arm_r3, 
         indent(4).c_str(), uc->uc_mcontext.arm_r4, uc->uc_mcontext.arm_r5, uc->uc_mcontext.arm_r6, uc->uc_mcontext.arm_r7, 
         indent(4).c_str(), uc->uc_mcontext.arm_r8, uc->uc_mcontext.arm_r9, uc->uc_mcontext.arm_r10, uc->uc_mcontext.arm_fp,
         indent(4).c_str(), uc->uc_mcontext.arm_ip, uc->uc_mcontext.arm_sp, uc->uc_mcontext.arm_lr, uc->uc_mcontext.arm_pc,
         indent(4).c_str(), uc->uc_mcontext.arm_cpsr);

#elif (defined(__aarch64__))
    asprintf(&regs_image, "%s x0 %016llx  x1 %016llx  x2 %016llx  x3 %016llx\n"
                 "%s x4 %016llx  x5 %016llx  x6 %016llx  x7 %016llx\n"
                 "%s x8 %016llx  x9 %016llx  x10 %016llx  x11 %016llx\n"
                 "%s x12 %016llx  x13 %016llx  x14 %016llx  x15 %016llx\n"
                 "%s x16 %016llx  x17 %016llx  x18 %016llx  x19 %016llx\n"
                 "%s x20 %016llx  x21 %016llx  x22 %016llx  x23 %016llx\n"
                 "%s x24 %016llx  x25 %016llx  x26 %016llx  x27 %016llx\n"
                 "%s x28 %016llx  x29 %016llx  x30 %016llx\n"
                 "%s sp %016llx  pc %016llx  pstate %016llx\n",
         indent(4).c_str(), uc->uc_mcontext.regs[0], uc->uc_mcontext.regs[1], uc->uc_mcontext.regs[2], uc->uc_mcontext.regs[3], 
         indent(4).c_str(), uc->uc_mcontext.regs[4], uc->uc_mcontext.regs[5], uc->uc_mcontext.regs[6], uc->uc_mcontext.regs[7], 
         indent(4).c_str(), uc->uc_mcontext.regs[8], uc->uc_mcontext.regs[9], uc->uc_mcontext.regs[10], uc->uc_mcontext.regs[11],
         indent(4).c_str(), uc->uc_mcontext.regs[12], uc->uc_mcontext.regs[13], uc->uc_mcontext.regs[14], uc->uc_mcontext.regs[15],
         indent(4).c_str(), uc->uc_mcontext.regs[16], uc->uc_mcontext.regs[17], uc->uc_mcontext.regs[18], uc->uc_mcontext.regs[19],
         indent(4).c_str(), uc->uc_mcontext.regs[20], uc->uc_mcontext.regs[21], uc->uc_mcontext.regs[22], uc->uc_mcontext.regs[23],
         indent(4).c_str(), uc->uc_mcontext.regs[24], uc->uc_mcontext.regs[25], uc->uc_mcontext.regs[26], uc->uc_mcontext.regs[27],
         indent(4).c_str(), uc->uc_mcontext.regs[28], uc->uc_mcontext.regs[29], uc->uc_mcontext.regs[30],
         indent(4).c_str(), uc->uc_mcontext.sp, uc->uc_mcontext.pc, uc->uc_mcontext.pstate);

#elif (defined(__i386__))
    asprintf(&regs_image, "%s cw %08lx  sw %08lx  tag %08lx  ipoff %08lx\n"
                 "%s cssel %08lx  dataoff %08lx  datasel %08lx  status %08lx\n",
         indent(4).c_str(), uc->uc_mcontext.fpregs->cw, uc->uc_mcontext.fpregs->sw, uc->uc_mcontext.fpregs->tag, uc->uc_mcontext.fpregs->ipoff, 
         indent(4).c_str(), uc->uc_mcontext.fpregs->cssel, uc->uc_mcontext.fpregs->dataoff, uc->uc_mcontext.fpregs->datasel, uc->uc_mcontext.fpregs->status);

#elif (defined(__x86_64__))
    asprintf(&regs_image, "%s cwd %02hdx  swd %02hdx  ftw %02hdx  fop %02hdx\n"
                 "%s rip %08lx  rdp %08lx  mxcsr %04dx  mxcr_mask %04dx\n",
         indent(4).c_str(), uc->uc_mcontext.fpregs->cwd, uc->uc_mcontext.fpregs->swd, uc->uc_mcontext.fpregs->ftw, uc->uc_mcontext.fpregs->fop, 
         indent(4).c_str(), uc->uc_mcontext.fpregs->rip, uc->uc_mcontext.fpregs->rdp, uc->uc_mcontext.fpregs->mxcsr, uc->uc_mcontext.fpregs->mxcr_mask);
#endif
     return regs_image;
}

_Unwind_Reason_Code unwind_callback(struct _Unwind_Context *context, void *arg) {
    err_context_t * const s = static_cast<err_context_t *const>(arg);
    // get the pc register
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0x0) {
        s->frames[s->size++] = pc;
    }
    if (s->size != STACK_FRAMES_MAX) {
        return _URC_NO_REASON;
    } else {
        return _URC_END_OF_STACK;
    }
}

std::string dump_stacktrace(const err_context_t *err_context) {
    std::ostringstream oss;
    uint64_t addr = get_fault_address(err_context->sc);
    const char *emsg = get_error_string(
        err_context->si->si_signo, err_context->si->si_code, addr
    );
    
    oss << emsg << std::endl;
    oss << get_register_snapshot(err_context->sc) << std::endl;
    oss << "backtrace: " << std::endl;
    
    // get the symbol info
    for (int index = 0; index < err_context->size; ++index) {
        uintptr_t pc = err_context->frames[index];
        // get the start address
        Dl_info stack_info;
        void *const addr = (void *) pc;
        if (dladdr(addr, &stack_info) != 0 && stack_info.dli_fname != nullptr) {
            oss << std::setw(5) << "#" << std::setw(2) << std::setfill('0') << index;
            oss << indent(2) << "pc" << indent(2) << addr;
            if (stack_info.dli_fbase == 0) {
                // No valid map associated with this frame.
                oss << "  <unknown>";
            } else if (stack_info.dli_fname) {
                std::string so_name = std::string(stack_info.dli_fname);
                oss << "  " << so_name;
            } else {
                oss << android::base::StringPrintf(
                    "  <anonymous:%" PRIx64 ">", 
                    (uint64_t) stack_info.dli_fbase
                );
            }
            if (stack_info.dli_sname) {
                char *demangled_name = abi::__cxa_demangle(
                    stack_info.dli_sname, nullptr, nullptr, nullptr
                );
                
                if (demangled_name == nullptr) {
                    oss << " (" << stack_info.dli_sname;
                } else {
                    oss << " (" << demangled_name;
                    free(demangled_name);
                }
                if (stack_info.dli_saddr != 0) {
                    uintptr_t offset = pc - (uintptr_t) stack_info.dli_saddr;
                    oss << android::base::StringPrintf("+%" PRId64, (uint64_t) offset);
                }
                oss << ")";
            }
            oss << std::setw(0) << std::setfill(' ')  << std::endl;
        }
    }
    
    return oss.str();
}

