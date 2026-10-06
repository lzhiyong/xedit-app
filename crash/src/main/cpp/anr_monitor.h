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

/*
 * Opt-in ANR detection by intercepting SIGQUIT (Matrix-style). Independent of
 * the native crash pipeline in crash_handler.cpp: separate thread, separate
 * doorbell, so an ANR never waits behind a crash report or vice versa.
 * See anr_monitor.cpp for the rules that keep the system's own ANR traces
 * intact.
 */
namespace crash {

// Call once from JNI_OnLoad. `handler_class` must be a global ref; it is
// borrowed, not owned. Resolves the optional CrashHandler.onAnrSignal() and
// onAnrConfirm() hooks - if either is missing, enabling simply fails.
void anr_monitor_init(JavaVM *vm, JNIEnv *env, jclass handler_class);

// CrashHandler.nativeEnableAnrMonitor() / nativeDisableAnrMonitor().
// Both MUST be called on the main thread (signal masks are per-thread).
jboolean JNICALL native_enable_anr_monitor(JNIEnv *env, jclass clazz);
void JNICALL native_disable_anr_monitor(JNIEnv *env, jclass clazz);

// Call from JNI_OnUnload BEFORE the handler class global ref is deleted:
// restores SIGQUIT so no handler points into the unmapped library.
void anr_monitor_shutdown();

}  // namespace crash
