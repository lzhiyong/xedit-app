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

package x.editor.app.crash

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns crash logs into the one text that CrashActivity shows, copies, exports
 * and uploads. Plain Kotlin: no Android, no I/O.
 */
object CrashReportFormatter {

    /**
     * One crash to list.
     *
     * @param time epoch millis of its newest occurrence
     * @param label the kind of crash, null if not known
     * @param count how many times it happened before it was seen
     */
    class Entry(
        val time: Long,
        val label: String?,
        val count: Int,
        val content: String
    )

    private const val HEADING_RULE = "========================="
    private const val DEFAULT_LABEL = "Crash"
    private const val INLINE_TITLE = "Latest crash"

    /**
     * All crashes in one text, in the order given (the caller passes them newest
     * first), each under a heading with its date, its kind and, if it happened more
     * than once, how often.
     *
     * @param inlineText the text of the crash that could not be written to disk, if
     *   any. It is the newest one, so it goes first.
     * @return the report without trailing whitespace; empty if there is nothing to show
     */
    fun format(
        entries: List<Entry>,
        inlineText: String?,
        dateFormat: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    ): String {
        return buildString {
            if (!inlineText.isNullOrEmpty()) {
                appendHeading(INLINE_TITLE)
                append(inlineText).append("\n\n")
            }
            for (entry in entries) {
                val label = entry.label ?: DEFAULT_LABEL
                val times = if (entry.count > 1) "  ×${entry.count}" else ""
                appendHeading("${dateFormat.format(Date(entry.time))}  $label$times")
                append(entry.content).append("\n\n")
            }
        }.trimEnd()
    }

    /** The name of the file a report exported at [date] gets: crash_<yyyyMMdd>_<HHmmss>.txt */
    fun exportFileName(date: Date): String =
        "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date) + ".txt"

    private fun StringBuilder.appendHeading(title: String) {
        append(HEADING_RULE).append(' ')
            .append(title)
            .append(' ').append(HEADING_RULE).append('\n')
    }
}
