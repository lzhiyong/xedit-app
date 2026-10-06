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

package x.editor.app

object Constants {

    // ---------------------------------------------------------------------
    // Crash report
    // ---------------------------------------------------------------------

    // TODO: replace with your server endpoint. It receives an HTTP POST with a
    // text/plain UTF-8 body. Use https: cleartext http is blocked by default on API 28+.
    const val CRASH_REPORT_URL = "https://example.com/api/crash-report"

    // Must match the <intent-filter> action of CrashActivity in AndroidManifest.xml
    const val ACTION_CRASH_REPORT = "x.editor.app.CRASH_REPORT"

    // Inline crash text, only used when the log could not be written to disk.
    // Normally CrashActivity reads every log from disk and needs no extras.
    const val EXTRA_CRASH_STACK_TRACE = "CRASH_STACK_TRACE"

    // true: showing crashes from a previous launch while the app is running normally
    const val EXTRA_CRASH_FROM_PREVIOUS_LAUNCH = "CRASH_FROM_PREVIOUS_LAUNCH"
}
