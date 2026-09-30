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
#include <unwind.h>

#include <string>

typedef struct abort_context {
    siginfo_t *si;
    ucontext_t *sc;
} err_context_t;

std::string dump_stacktrace(const err_context_t *);

#endif // __STACKTRACE_H__

