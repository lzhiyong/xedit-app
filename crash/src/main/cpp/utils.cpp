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

#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <signal.h>

#include "log.h"
#include "utils.h"

const std::string indent(int count) {
    // whitespace ascii code is 0x20
    const std::string spaces(count, '\x20');
    return spaces;
}

// get the process and thread name
// @proc process /proc/%d/cmdline gettpid()
// @proc thread /proc/%d/comm gettid()
const char *get_propery_name(const char *proc, const pid_t id) {
    char *path = (char *) calloc(1, PATH_MAX);
    char *name = (char *) calloc(1, NAME_MAX);
    snprintf(path, PATH_MAX, proc, id);
    
    FILE *fp = fopen(path, "r");
    if (fp == nullptr) {
        LOGE("%s\n", strerror(errno));
        return nullptr;
    }
    
    fgets(name, NAME_MAX, fp);
        
    int length = strlen(name);
    if (name[length - 1] == '\n') {
        name[length - 1] = '\0';
    }
    
    free(path);
    fclose(fp);
    // the name need to be free
    return name;
}

const char * get_error_string(const int signal, const int code, const uint64_t addr) {
    char *result = nullptr;
    const char *format = "signal: %d (%s), code: %d (%s), fault addr: 0x%x (%s)";
    const char *format2 = "signal: %d (%s), fault addr: 0x%x (%s)";
    
    switch (signal) {
        case SIGILL:
            switch (code) {
                case ILL_ILLOPC:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_ILLOPC", addr, "Illegal opcode");
                    break;
                case ILL_ILLOPN:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_ILLOPN", addr, "Illegal operand");
                    break;                   
                case ILL_ILLADR:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_ILLADR", addr, "Illegal addressing mode");
                    break;
                case ILL_ILLTRP:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_ILLTRP", addr, "Illegal trap");
                    break;
                case ILL_PRVOPC:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_PRVOPC", addr, "Privileged opcode");
                    break;
                case ILL_PRVREG:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_PRVREG", addr, "Privileged register");
                    break;
                case ILL_COPROC:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_COPROC", addr, "Coprocessor error");
                    break;
                case ILL_BADSTK:
                    asprintf(&result, format, signal, "SIGILL", code, "ILL_BADSTK", addr, "Internal stack error");
                    break;
                default:
                    asprintf(&result, format2, signal, "SIGILL", addr, "Illegal operation");
                    break;
            }
            break;
        case SIGFPE:
            switch (code) {
                case FPE_INTDIV:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_INTDIV", addr, "Integer divide by zero");
                    break;
                case FPE_INTOVF:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_INTOVF", addr, "Integer overflow");
                    break;
                case FPE_FLTDIV:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_INTDIV", addr, "Floating-point divide by zero");
                    break;
                case FPE_FLTOVF:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_FLTOVF", addr, "Floating-point overflow");
                    break;
                case FPE_FLTUND:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_FLTUND", addr, "Floating-point underflow");
                    break;
                case FPE_FLTRES:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_FLTRES", addr, "Floating-point inexact result");
                    break;
                case FPE_FLTINV:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_FLTINV", addr, "Invalid floating-point operation");
                    break;
                case FPE_FLTSUB:
                    asprintf(&result, format, signal, "SIGFPE", code, "FPE_FLTSUB", addr, "Subscript out of range");
                    break;
                default:
                    asprintf(&result, format2, signal, "SIGFPE", addr, "Floating-point");
                    break;
            }
            break;
        case SIGSEGV:
            switch (code) {
                case SEGV_MAPERR:
                    asprintf(&result, format, signal, "SIGSEGV", code, "SEGV_MAPERR", addr, "Address not mapped to object");  
                    break;
                case SEGV_ACCERR:
                    asprintf(&result, format, signal, "SIGSEGV", code, "SEGV_ACCERR", addr, "Invalid permissions for mapped object");
                    break;
                default:
                    asprintf(&result, format2, signal, "SIGSEGV", code, "SEGV_ACCERR", addr, "Segmentation violation");
                    break;
            }
            break;
        case SIGBUS:
            switch (code) {
                case BUS_ADRALN:
                    asprintf(&result, format, signal, "SIGBUS", code, "BUS_ADRALN", addr, "Invalid address alignment");
                    break;
                case BUS_ADRERR:
                    asprintf(&result, format, signal, "SIGBUS", code, "BUS_ADRERR", addr, "Nonexistent physical address");
                    break;
                case BUS_OBJERR:
                    asprintf(&result, format, signal, "SIGBUS", code, "BUS_OBJERR", addr, "Object-specific hardware error");
                    break;
                default:
                    asprintf(&result, format2, signal, "SIGBUS", addr, "Bus error");
                    break;
            }
            break;
        case SIGTRAP:
            switch (code) {
                case TRAP_BRKPT:
                    asprintf(&result, format, signal, "SIGTRAP", code, "TRAP_BRKPT", addr, "Process breakpoint");
                    break;
                case TRAP_TRACE:
                    asprintf(&result, format, signal, "SIGTRAP", code, "TRAP_TRACE", addr, "Process trace trap");
                    break;
                default:
                    asprintf(&result, format, signal, "SIGTRAP", addr, "Trap");
                    break;
            }
            break;
        case SIGCHLD:
            switch (code) {
                case CLD_EXITED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_EXITED", addr, "Child has exited");
                    break;
                case CLD_KILLED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_KILLED", addr, "Child has terminated abnormally and did not create a core file");
                    break;
                case CLD_DUMPED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_DUMPED", addr, "Child has terminated abnormally and created a core file");
                    break;
                case CLD_TRAPPED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_TRAPPED", addr, "Traced child has trapped");
                    break;
                case CLD_STOPPED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_STOPPED", addr, "Child has stopped");
                    break;
                case CLD_CONTINUED:
                    asprintf(&result, format, signal, "SIGCHLD", code, "CLD_CONTINUED", addr, "Stopped child has continued");
                    break;
                default:
                    asprintf(&result, format, signal, "SIGCHLD", addr, "Child");
                    break;
            }
            break;
        case SIGPOLL:
            switch (code) {
                case POLL_IN:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_IN", addr, "Data input available");
                    break;
                case POLL_OUT:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_OUT", addr, "Output buffers available");
                    break;
                case POLL_MSG:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_MSG", addr, "Input message available");
                    break;
                case POLL_ERR:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_ERR", addr, "I/O error");
                    break;
                case POLL_PRI:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_PRI", addr, "High priority input available");
                    break;
                case POLL_HUP:
                    asprintf(&result, format, signal, "SIGPOLL", code, "POLL_HUP", addr, "Device disconnected");
                    break;
                default:
                    asprintf(&result, format2, signal, "SIGPOLL", addr, "Pool");
                    break;
            }
            break;
        case SIGABRT:
            asprintf(&result, format2, signal, "SIGABRT", addr, "Process abort signal");
            break;
        case SIGALRM:
            asprintf(&result, format2, signal, "SIGALRM", addr, "Alarm clock");
            break;
        case SIGCONT:
            asprintf(&result, format2, signal, "SIGCONT", addr, "Continue executing, if stopped");
            break;
        case SIGHUP:
            asprintf(&result, format2, signal, "SIGHUP", addr, "Hangup");
            break;
        case SIGINT:
            asprintf(&result, format2, signal, "SIGINT", addr, "Terminal interrupt signal");
            break;
        case SIGKILL:
            asprintf(&result, format2, signal, "SIGKILL", addr, "Kill");
            break;
        case SIGPIPE:
            asprintf(&result, format2, signal, "SIGPIPE", addr, "Write on a pipe with no one to read it");
            break;
        case SIGQUIT:
            asprintf(&result, format2, signal, "SIGQUIT", addr, "Terminal quit signal");
            break;
        case SIGSTOP:
            asprintf(&result, format2, signal, "SIGSTOP", addr, "Stop executing");
            break;
        case SIGTERM:
            asprintf(&result, format2, signal, "SIGTERM", addr, "Termination signal");
            break;
        case SIGTSTP:
            asprintf(&result, format2, signal, "SIGTSTP", addr, "Terminal stop signal");
            break;
        case SIGTTIN:
            asprintf(&result, format2, signal, "SIGTTIN", addr, "Background process attempting read");
            break;
        case SIGTTOU:
            asprintf(&result, format2, signal, "SIGTTOU", addr, "Background process attempting write");
            break;
        case SIGUSR1:
            asprintf(&result, format2, signal, "SIGUSR1", addr, "User-defined signal 1");
            break;
        case SIGUSR2:
            asprintf(&result, format2, signal, "SIGUSR2", addr, "User-defined signal 2");
            break;
        case SIGPROF:
            asprintf(&result, format2, signal, "SIGPROF", addr, "Profiling timer expired");
            break;
        case SIGSYS:
            asprintf(&result, format2, signal, "SIGSYS", addr, "Bad system call");
            break;
        case SIGVTALRM:
            asprintf(&result, format2, signal, "SIGVTALRM", addr, "Virtual timer expired");
            break;
        case SIGURG:
            asprintf(&result, format2, signal, "SIGURG", addr, "High bandwidth data is available at a socket");
            break;
        case SIGXCPU:
            asprintf(&result, format2, signal, "SIGXCPU", addr, "CPU time limit exceeded");
            break;
        case SIGXFSZ:
            asprintf(&result, format2, signal, "SIGXFSZ", addr, "File size limit exceeded");
            break;
        default:
            switch (code) {
                case SI_USER:
                    asprintf(&result, format, signal, "Unknown", code, "SI_USER", addr, "Signal sent by kill()");
                    break;
                case SI_QUEUE:
                    asprintf(&result, format, signal, "Unknown", code, "SI_QUEUE", addr, "Signal sent by the sigqueue()");
                    break;
                case SI_TIMER:
                    asprintf(&result, format, signal, "Unknown", code, "SI_TIMER", addr, "Signal generated by expiration of a timer set by timer_settime()");
                    break;
                case SI_ASYNCIO:
                    asprintf(&result, format, signal, "Unknown", code, "SI_ASYNCIO", addr, "Signal generated by completion of an asynchronous I/O request");
                    break;
                case SI_MESGQ:
                    asprintf(&result, format, signal, "Unknown", code, "SI_MESGQ", addr, "Signal generated by arrival of a message on an empty message queue");
                    break;
                default:
                    asprintf(&result, format, signal, "Unknown", code, "Unknown", addr, "Unknown signal");
                    break;
            }
            break;
    }
    
    // return the format string
    return result;
}

