/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "utils.h"

#include <dirent.h>
#include <inttypes.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <fstream>
#include <memory>
#include <string>

#include "android-base/file.h"
#include "android-base/stringprintf.h"
#include "android-base/strings.h"

#include "base/stl_util.h"
#include "bit_utils.h"
#include "os.h"

#if defined(__APPLE__)
#include <crt_externs.h>
#include <sys/syscall.h>
#include "AvailabilityMacros.h"  // For MAC_OS_X_VERSION_MAX_ALLOWED
#endif

#if defined(__BIONIC__)
// membarrier(2) is only supported for target builds (b/111199492).
#include <linux/membarrier.h>
#include <sys/syscall.h>
#endif

#if defined(__linux__)
#include <linux/unistd.h>
#include <sys/syscall.h>
#endif

#if defined(_WIN32)
#include <windows.h>
// This include needs to be here due to our coding conventions.  Unfortunately
// it drags in the definition of the dread ERROR macro.
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace art {

using android::base::ReadFileToString;  // NOLINT - ReadFileToString is actually used
using android::base::StringPrintf;

#if defined(__arm__)

namespace {

// Bitmap of caches to flush for cacheflush(2). Must be zero for ARM.
static constexpr int kCacheFlushFlags = 0x0;

// Number of retry attempts when flushing cache ranges.
static constexpr size_t kMaxFlushAttempts = 4;

int CacheFlush(uintptr_t start, uintptr_t limit) {
  // The signature of cacheflush(2) seems to vary by source. On ARM the system call wrapper
  //    (bionic/SYSCALLS.TXT) has the form: int cacheflush(long start, long end, long flags);
  int r = cacheflush(start, limit, kCacheFlushFlags);
  if (r == -1) {
    CHECK_NE(errno, EINVAL);
  }
  return r;
}

bool TouchAndFlushCacheLinesWithinPage(uintptr_t start, uintptr_t limit, size_t attempts) {
  CHECK_LT(start, limit);
  CHECK_EQ(RoundDown(start, kPageSize), RoundDown(limit - 1, kPageSize)) << "range spans pages";
  // Declare a volatile variable so the compiler does not elide reads from the page being touched.
  [[maybe_unused]] volatile uint8_t v = 0;
  for (size_t i = 0; i < attempts; ++i) {
    // Touch page to maximize chance page is resident.
    v = *reinterpret_cast<uint8_t*>(start);

    if (LIKELY(CacheFlush(start, limit) == 0)) {
      return true;
    }
  }
  return false;
}

}  // namespace

bool FlushCpuCaches(void* begin, void* end) {
  // This method is specialized for ARM as the generic implementation below uses the
  // __builtin___clear_cache() intrinsic which is declared as void. On ARMv7 flushing the CPU
  // caches is a privileged operation. The Linux kernel allows these operations to fail when they
  // trigger a fault (e.g. page not resident). We use a wrapper for the ARM specific cacheflush()
  // system call to detect the failure and potential erroneous state of the data and instruction
  // caches.
  //
  // The Android bug for this is b/132205399 and there's a similar discussion on
  // https://reviews.llvm.org/D37788. This is primarily an issue for the dual view JIT where the
  // pages where code is executed are only ever RX and never RWX. When attempting to invalidate
  // instruction cache lines in the RX mapping after writing fresh code in the RW mapping, the
  // page may not be resident (due to memory pressure), and this means that a fault is raised in
  // the midst of a cacheflush() call and the instruction cache lines are not invalidated and so
  // have stale code.
  //
  // Other architectures fair better for reasons such as:
  //
  // (1) stronger coherence between the data and instruction caches.
  //
  // (2) fault handling that allows flushing/invalidation to continue after
  //     a missing page has been faulted in.

  uintptr_t start = reinterpret_cast<uintptr_t>(begin);
  const uintptr_t limit = reinterpret_cast<uintptr_t>(end);
  if (LIKELY(CacheFlush(start, limit) == 0)) {
    return true;
  }

  // A rare failure has occurred implying that part of the range (begin, end] has been swapped
  // out. Retry flushing but this time grouping cache-line flushes on individual pages and
  // touching each page before flushing.
  uintptr_t next_page = RoundUp(start + 1, kPageSize);
  while (start < limit) {
    uintptr_t boundary = std::min(next_page, limit);
    if (!TouchAndFlushCacheLinesWithinPage(start, boundary, kMaxFlushAttempts)) {
      return false;
    }
    start = boundary;
    next_page += kPageSize;
  }
  return true;
}

#else

bool FlushCpuCaches(void* begin, void* end) {
  __builtin___clear_cache(reinterpret_cast<char*>(begin), reinterpret_cast<char*>(end));
  return true;
}

#endif

#if defined(__linux__)
bool IsKernelVersionAtLeast(int reqd_major, int reqd_minor) {
  struct utsname uts;
  int major, minor;
  CHECK_EQ(uname(&uts), 0);
  CHECK_EQ(strcmp(uts.sysname, "Linux"), 0);
  CHECK_EQ(sscanf(uts.release, "%d.%d:", &major, &minor), 2);
  return major > reqd_major || (major == reqd_major && minor >= reqd_minor);
}
#endif

bool CacheOperationsMaySegFault() {
#if defined(__linux__) && defined(__aarch64__)
  // Avoid issue on older ARM64 kernels where data cache operations could be classified as writes
  // and cause segmentation faults. This was fixed in Linux 3.11rc2:
  //
  // https://github.com/torvalds/linux/commit/db6f41063cbdb58b14846e600e6bc3f4e4c2e888
  //
  // This behaviour means we should avoid the dual view JIT on the device. This is just
  // an issue when running tests on devices that have an old kernel.
  return !IsKernelVersionAtLeast(3, 12);
#else
  return false;
#endif
}

uint32_t GetTid() {
#if defined(__APPLE__)
  uint64_t owner;
  CHECK_PTHREAD_CALL(pthread_threadid_np, (nullptr, &owner), __FUNCTION__);  // Requires Mac OS 10.6
  return owner;
#elif defined(__BIONIC__)
  return gettid();
#elif defined(_WIN32)
  return static_cast<pid_t>(::GetCurrentThreadId());
#else
  return syscall(__NR_gettid);
#endif
}

std::string GetThreadName(pid_t tid) {
  std::string result;
#ifdef _WIN32
  UNUSED(tid);
  result = "<unknown>";
#else
  // TODO: make this less Linux-specific.
  if (ReadFileToString(StringPrintf("/proc/self/task/%d/comm", tid), &result)) {
    result.resize(result.size() - 1);  // Lose the trailing '\n'.
  } else {
    result = "<unknown>";
  }
#endif
  return result;
}

std::string PrettySize(uint64_t byte_count) {
  // The byte thresholds at which we display amounts.  A byte count is displayed
  // in unit U when kUnitThresholds[U] <= bytes < kUnitThresholds[U+1].
  static const uint64_t kUnitThresholds[] = {
    0,        // B up to...
    10*KB,    // KB up to...
    10*MB,    // MB up to...
    10ULL*GB  // GB from here.
  };
  static const uint64_t kBytesPerUnit[] = { 1, KB, MB, GB };
  static const char* const kUnitStrings[] = { "B", "KB", "MB", "GB" };
  int i = arraysize(kUnitThresholds);
  while (--i > 0) {
    if (byte_count >= kUnitThresholds[i]) {
      break;
    }
  }
  return StringPrintf("%" PRIu64 "%s",
                      byte_count / kBytesPerUnit[i], kUnitStrings[i]);
}

template <typename StrIn, typename Str>
void Split(const StrIn& s, char separator, std::vector<Str>* out_result) {
  auto split = SplitString(std::string_view(s), separator);
  for (std::string_view p : split) {
    if (p.empty()) {
      continue;
    }
    out_result->push_back(Str(p));
  }
}

template void Split(const char *const& s, char separator, std::vector<std::string>* out_result);
template void Split(const std::string& s, char separator, std::vector<std::string>* out_result);
template void Split(const char *const& s, char separator, std::vector<std::string_view>* out_result);
template void Split(const std::string_view& s,
                    char separator,
                    std::vector<std::string_view>* out_result);
template void Split(const std::string_view& s,
                    char separator,
                    std::vector<std::string>* out_result);

template <typename Str>
void Split(const Str& s, char separator, size_t len, Str* out_result) {
  Str* last = out_result + len;
  auto split = SplitString(std::string_view(s), separator);
  for (std::string_view p : split) {
    if (p.empty()) {
      continue;
    }
    if (out_result == last) {
      return;
    }
    *out_result++ = Str(p);
  }
}

template void Split(const std::string& s, char separator, size_t len, std::string* out_result);
template void Split(const std::string_view& s,
                    char separator,
                    size_t len,
                    std::string_view* out_result);

void SetThreadName(const char* thread_name) {
  bool hasAt = false;
  bool hasDot = false;
  const char* s = thread_name;
  while (*s) {
    if (*s == '.') {
      hasDot = true;
    } else if (*s == '@') {
      hasAt = true;
    }
    s++;
  }
  int len = s - thread_name;
  if (len < 15 || hasAt || !hasDot) {
    s = thread_name;
  } else {
    s = thread_name + len - 15;
  }
#if defined(__linux__) || defined(_WIN32)
  // pthread_setname_np fails rather than truncating long strings.
  char buf[16];       // MAX_TASK_COMM_LEN=16 is hard-coded in the kernel.
  strncpy(buf, s, sizeof(buf)-1);
  buf[sizeof(buf)-1] = '\0';
  errno = pthread_setname_np(pthread_self(), buf);
  if (errno != 0) {
    PLOG(WARNING) << "Unable to set the name of current thread to '" << buf << "'";
  }
#else  // __APPLE__
  pthread_setname_np(thread_name);
#endif
}

void GetTaskStats(pid_t tid, char* state, int* utime, int* stime, int* task_cpu) {
  *utime = *stime = *task_cpu = 0;
#ifdef _WIN32
  // TODO: implement this.
  UNUSED(tid);
  *state = 'S';
#else
  std::string stats;
  // TODO: make this less Linux-specific.
  if (!ReadFileToString(StringPrintf("/proc/self/task/%d/stat", tid), &stats)) {
    return;
  }
  // Skip the command, which may contain spaces.
  stats = stats.substr(stats.find(')') + 2);
  // Extract the three fields we care about.
  std::vector<std::string> fields;
  Split(stats, ' ', &fields);
  *state = fields[0][0];
  *utime = strtoull(fields[11].c_str(), nullptr, 10);
  *stime = strtoull(fields[12].c_str(), nullptr, 10);
  *task_cpu = strtoull(fields[36].c_str(), nullptr, 10);
#endif
}

void SleepForever() {
  while (true) {
    sleep(100000000);
  }
}

std::string GetProcessStatus(const char* key) {
  // Build search pattern of key and separator.
  std::string pattern(key);
  pattern.push_back(':');

  // Search for status lines starting with pattern.
  std::ifstream fs("/proc/self/status");
  std::string line;
  while (std::getline(fs, line)) {
    if (strncmp(pattern.c_str(), line.c_str(), pattern.size()) == 0) {
      // Skip whitespace in matching line (if any).
      size_t pos = line.find_first_not_of(" \t", pattern.size());
      if (UNLIKELY(pos == std::string::npos)) {
        break;
      }
      return std::string(line, pos);
    }
  }
  return "<unknown>";
}

bool IsAddressKnownBackedByFileOrShared(const void* addr) {
  // We use the Linux pagemap interface for knowing if an address is backed
  // by a file or is shared. See:
  // https://www.kernel.org/doc/Documentation/vm/pagemap.txt
  uintptr_t vmstart = reinterpret_cast<uintptr_t>(AlignDown(addr, kPageSize));
  off_t index = (vmstart / kPageSize) * sizeof(uint64_t);
  android::base::unique_fd pagemap(open("/proc/self/pagemap", O_RDONLY | O_CLOEXEC));
  if (pagemap == -1) {
    return false;
  }
  if (lseek(pagemap, index, SEEK_SET) != index) {
    return false;
  }
  uint64_t flags;
  if (read(pagemap, &flags, sizeof(uint64_t)) != sizeof(uint64_t)) {
    return false;
  }
  // From https://www.kernel.org/doc/Documentation/vm/pagemap.txt:
  //  * Bit  61    page is file-page or shared-anon (since 3.5)
  return (flags & (1LL << 61)) != 0;
}

int GetTaskCount() {
  DIR* directory = opendir("/proc/self/task");
  if (directory == nullptr) {
    return -1;
  }

  uint32_t count = 0;
  struct dirent* entry = nullptr;
  while ((entry = readdir(directory)) != nullptr) {
    if ((strcmp(entry->d_name, ".") == 0) || (strcmp(entry->d_name, "..") == 0)) {
      continue;
    }
    ++count;
  }
  closedir(directory);
  return count;
}

}  // namespace art
