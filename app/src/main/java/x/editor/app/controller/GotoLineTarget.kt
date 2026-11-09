/*
 * Copyright © 2026 Github Lzhiyong
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

package x.editor.app.controller

/**
 * What is typed into the go-to-line dialog: a line, or `line:column` as in other
 * editors (spaces around are allowed). Plain computation, see GotoLineDialog.
 */
internal sealed class GotoLineTarget {
    /** Nothing typed yet. */
    object Empty : GotoLineTarget()

    /** Not a line, nor line:column. */
    object Invalid : GotoLineTarget()

    /** A line that the file does not have. */
    data class OutOfRange(val line: Long) : GotoLineTarget()

    /** [line] (1-based), and [column] (1-based; 1 when none was typed). */
    data class Valid(val line: Int, val column: Int) : GotoLineTarget()

    companion object {
        private val PATTERN = Regex("""^\s*(\d+)\s*(?::\s*(\d*)\s*)?$""")

        /** [text] read against a file of [lineCount] lines. */
        fun parse(text: CharSequence, lineCount: Int): GotoLineTarget {
            if (text.isBlank()) return Empty
            val match = PATTERN.matchEntire(text) ?: return Invalid
            // as many digits as anyone may type: too many for an Int is past the end
            val line = match.groupValues[1].toLongOrNull() ?: return OutOfRange(Long.MAX_VALUE)
            if (line < 1 || line > lineCount) return OutOfRange(line)
            val columnText = match.groupValues[2]
            // "12:" while the column is being typed: the start of the line
            val column = if (columnText.isEmpty()) 1L else columnText.toLongOrNull() ?: Long.MAX_VALUE
            if (column < 1) return Invalid
            // a column past the end of the line goes to its end (the editor clamps it)
            return Valid(line.toInt(), column.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}
