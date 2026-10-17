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

#include <jni.h>

#include <string>

#include "stacktrace.h"

/*
 * Fallback crash report written by the native layer itself.
 *
 * Before the Kotlin callback runs, the reporter writes the report into the
 * directory set by CrashHandler.nativeSetFallbackDir(), as
 * native_<epoch_ms>_<pid>.log. The header (signal, tid, abort message) is
 * written and fsync'd before the unwinder even starts, so it survives an
 * unwinder that hangs in a corrupted heap.
 *
 * The native side never deletes the file. Its name is passed to
 * CrashHandler.callback(), and Kotlin deletes it right after CrashLogStore has
 * written its own log - before the listener runs, so a listener that throws
 * or hangs cannot leave a duplicate behind, and a failed write (e.g. disk
 * full) keeps the copy. Any fallback file found at the next launch is
 * therefore a crash Kotlin did NOT manage to record;
 * CrashLogStore.adoptNativeFallbacks() converts it into a regular crash log.
 *
 * Usage, on the crash reporter thread only:
 *
 *   crash::FallbackReport fallback;
 *   fallback.begin(err_context, abort_message);  // heap-free, fsync'd
 *   fallback.finish(trace);                      // append backtrace, close
 *   deliver_to_java(env, report, fallback.name()); // Kotlin deletes the file
 *
 * Every step is a silent no-op when no directory was configured or the file
 * could not be created: the fallback must never get in the way of the
 * regular report.
 */
namespace crash {

class FallbackReport {
public:
    FallbackReport() = default;
    ~FallbackReport();
    FallbackReport(const FallbackReport &) = delete;
    FallbackReport &operator=(const FallbackReport &) = delete;

    // Create the file and write the header. Uses no heap.
    void begin(const err_context_t &ctx, const char *abort_message);

    // Append the backtrace, fsync and close the file.
    void finish(const std::string &trace);

    // File name inside the fallback directory, or "" if no file was created
    // (fallback disabled, or openat() failed). Valid until this object dies.
    const char *name() const;

    // Delete the file. Not used by the crash pipeline any more (Kotlin
    // deletes the file instead); kept for callers that want to drop a report.
    void discard();

private:
    int fd_ = -1;
    char name_[64] = { 0 };
};

// CrashHandler.nativeSetFallbackDir(path). Called from CrashHandler.enable(),
// after leftover files from the previous run have been adopted.
// Creates the directory if needed and keeps an fd to it for the crash path.
jboolean JNICALL native_set_fallback_dir(JNIEnv *env, jclass clazz, jstring path);

}  // namespace crash
