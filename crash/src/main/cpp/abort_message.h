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

#pragma once

namespace crash {

/*
 * Returns the message set by android_set_abort_message() - the text debuggerd
 * prints as "Abort message: '...'" in a tombstone. Typical sources:
 * LOG_ALWAYS_FATAL/CHECK, async_safe_fatal (fdsan, FORTIFY, pthread misuse),
 * Scudo/GWP-ASan/HWASan reports, and libc++abi's "terminating with uncaught
 * exception ..." for uncaught C++ exceptions.
 *
 * Returns:
 *   ""          no abort message was set in this process
 *   text        the message, truncated to 4095 bytes on a UTF-8 boundary
 *               (safe to pass to NewStringUTF)
 *   "<abort message present, unrecognized layout>"
 *               the mapping exists but bionic's layout has changed
 *
 * Contract:
 *   - The pointer refers to a static buffer overwritten by every call.
 *   - Not thread-safe; call it from the crash reporter thread only.
 *   - Uses open/read/strtoull only: no heap, no stdio, since the abort may
 *     have come out of the allocator itself. Async-signal-safe in practice,
 *     but there is no need to call it from a signal handler: the message is
 *     written before abort()/the fault and never changes afterwards.
 */
const char *read_abort_message();

}  // namespace crash
