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

package x.github.module.editor.input

import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * The edits behind indenting: what Enter carries over to the new line, and what
 * Tab and Shift+Tab do to the lines of a selection. Plain computation on line texts,
 * applied by the editor as one edit (one undo step).
 *
 * [unit] is one level of indentation: a tab, or a number of spaces.
 */
object IndentEdits {

    /**
     * The indentation a new line started at [column] (1-based) of [line] gets: the
     * whitespace the line starts with, as far as it goes before [column].
     */
    fun newLineIndent(line: CharSequence, column: Int): String {
        var end = 0
        val limit = Math.min(line.length, column - 1)
        while (end < limit && (line[end] == ' ' || line[end] == '\t')) end++
        return line.subSequence(0, end).toString()
    }

    /**
     * The lines a selection [range] indents: from its start line to its end line,
     * except an end line the selection only reaches the start of (a selection of
     * whole lines ends there).
     */
    fun linesOf(range: Range): IntRange {
        val last = if (range.endLine > range.startLine && range.endColumn == 1) range.endLine - 1 else range.endLine
        return range.startLine..last
    }

    /** One more level of indentation at the start of each of [lines]. */
    fun indent(lines: IntRange, unit: String): List<SingleEditOperation> =
        lines.map { SingleEditOperation(Range(it, 1, it, 1), unit) }

    /**
     * How many chars at the start of [line] make one level of indentation, to be
     * removed: the [unit] itself, or a tab, or up to as many spaces as the unit is
     * wide ([tabSize] for a tab). 0 for a line that is not indented.
     */
    fun outdentLength(line: CharSequence, unit: String, tabSize: Int): Int {
        if (line.startsWith(unit)) return unit.length
        if (line.startsWith("\t")) return 1
        val width = if (unit == "\t") tabSize else unit.length
        var spaces = 0
        while (spaces < width && spaces < line.length && line[spaces] == ' ') spaces++
        return spaces
    }

    /** One level of indentation less at the start of each of [lines] that has one. */
    fun outdent(lines: IntRange, lineText: (Int) -> CharSequence, unit: String, tabSize: Int): List<SingleEditOperation> =
        lines.mapNotNull { line ->
            val length = outdentLength(lineText(line), unit, tabSize)
            if (length == 0) null else SingleEditOperation(Range(line, 1, line, 1 + length), null)
        }
}
