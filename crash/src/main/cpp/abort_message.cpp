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
 * Abort message reader - see abort_message.h for the contract.
 *
 * debuggerd gets the message through __libc_shared_globals()->abort_msg,
 * which is private to libc. We locate the mmap'd block itself instead, using
 * the layout bionic documents with static_asserts precisely so that tools
 * outside libc can rely on it:
 *
 *   bionic/libc/bionic/android_set_abort_message.cpp
 *     struct magic_abort_msg_t { uint64_t magic1, magic2; size_t size; char msg[]; }
 *     prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, map, size, "abort message");
 *
 * The magic is not a public API. If bionic ever changes it, the named mapping
 * still matches but validation fails, and the reader reports
 * kAbortLayoutUnknown instead of silently returning nothing.
 */

#include "abort_message.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

namespace crash {
namespace {

// Layout copied from bionic/libc/bionic/android_set_abort_message.cpp:
//   struct magic_abort_msg_t { uint64_t magic1, magic2; size_t size; char msg[]; }
// where `size` is the WHOLE allocation (header + text + NUL).
constexpr uint64_t kAbortMagic1 = 0xb18e40886ac388f0ULL;
constexpr uint64_t kAbortMagic2 = 0xc6dfba755a1de0b5ULL;
constexpr size_t kAbortHeaderSize = 2 * sizeof(uint64_t) + sizeof(size_t);
// Reported when the named mapping exists but fails validation.
constexpr char kAbortLayoutUnknown[] = "<abort message present, unrecognized layout>";
// Unnamed fallback candidates larger than this are skipped (see below).
constexpr uintptr_t kMaxUnnamedCandidate = 64 * 1024;

// Fixed buffer, NOT std::string: an abort very often comes out of the
// allocator itself (Scudo / fdsan / heap corruption checks), so the read path
// must not touch the heap.
char abort_message[4096] = { 0 };

// Validate the magic at `start` and copy the text out. Returns true on a hit.
bool try_abort_mapping(uintptr_t start, uintptr_t end) {
    if (end <= start || end - start < kAbortHeaderSize + 1) return false;
    const unsigned char *base = reinterpret_cast<const unsigned char *>(start);

    uint64_t magic1 = 0, magic2 = 0;
    size_t total = 0;
    memcpy(&magic1, base, sizeof(magic1));
    memcpy(&magic2, base + sizeof(uint64_t), sizeof(magic2));
    if (magic1 != kAbortMagic1 || magic2 != kAbortMagic2) return false;
    memcpy(&total, base + 2 * sizeof(uint64_t), sizeof(total));
    if (total <= kAbortHeaderSize || total > end - start) return false;

    const unsigned char *text = base + kAbortHeaderSize;
    size_t len = total - kAbortHeaderSize - 1;  // minus the trailing NUL
    if (len >= sizeof(abort_message)) {
        len = sizeof(abort_message) - 1;
        // Never cut a multi-byte UTF-8 character in half: the result goes
        // through NewStringUTF, and with CheckJNI on an invalid sequence
        // aborts the reporter thread and the whole report is lost. If the
        // byte right after the cut is a continuation byte, back off to the
        // lead byte of that character and drop it.
        while (len > 0 && (text[len] & 0xC0) == 0x80) --len;
    }
    memcpy(abort_message, text, len);
    abort_message[len] = '\0';
    return true;
}

// Handle one /proc/self/maps line:
//   "start-end perms offset dev inode   [pathname]"
bool check_maps_line(const char *line) {
    char *p = nullptr;
    const uintptr_t start = static_cast<uintptr_t>(strtoull(line, &p, 16));
    if (p == nullptr || *p != '-') return false;
    const uintptr_t end = static_cast<uintptr_t>(strtoull(p + 1, &p, 16));
    while (*p == ' ') ++p;
    const char *perms = p;
    if (perms[0] != 'r' || perms[1] != 'w') return false;  // must be readable

    // Skip perms, offset, dev, inode to reach the (optional) pathname.
    for (int field = 0; field < 4; ++field) {
        while (*p != '\0' && *p != ' ') ++p;
        while (*p == ' ') ++p;
    }
    const char *name = p;

    // Primary: bionic names the mapping, so /proc shows it explicitly.
    if (strcmp(name, "[anon:abort message]") == 0) {
        if (try_abort_mapping(start, end)) return true;
        // The mapping is there but its layout is not what we expect, i.e.
        // bionic changed something. Report that instead of silently
        // returning "no message".
        strcpy(abort_message, kAbortLayoutUnknown);
        return true;
    }
    // Fallback for builds/kernels without the VMA name: small UNNAMED
    // anonymous mappings, identified by the 128-bit magic. Named regions are
    // never probed - ART's heap spaces are all named, and some of them may be
    // userfaultfd-registered, where a read could block on the GC.
    if (name[0] == '\0' && end - start <= kMaxUnnamedCandidate) {
        return try_abort_mapping(start, end);
    }
    return false;
}

}  // namespace

/*
 * debuggerd gets the pointer from __libc_shared_globals(), which is private
 * to libc. We instead locate the mmap'd block itself: open/read/strtoull
 * only, no stdio, no malloc. Called on the reporter thread; the text was
 * written before abort()/the fault, so it is already stable here.
 */
const char *read_abort_message() {
    abort_message[0] = '\0';

    int fd = TEMP_FAILURE_RETRY(open("/proc/self/maps", O_RDONLY | O_CLOEXEC));
    if (fd < 0) return abort_message;

    char buf[4096];
    size_t len = 0;
    bool found = false;
    while (!found) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf + len, sizeof(buf) - 1 - len));
        if (n <= 0) break;
        len += static_cast<size_t>(n);
        buf[len] = '\0';

        char *line = buf;
        while (!found) {
            char *nl = strchr(line, '\n');
            if (nl == nullptr) break;
            *nl = '\0';
            found = check_maps_line(line);
            line = nl + 1;
        }

        // Carry the incomplete tail line over to the next read. A line that
        // fills the whole buffer cannot be ours (our name is short); drop it.
        size_t rest = len - static_cast<size_t>(line - buf);
        if (rest >= sizeof(buf) - 1) rest = 0;
        memmove(buf, line, rest);
        len = rest;
    }
    close(fd);
    return abort_message;
}

}  // namespace crash
