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
 * Indenting as the keys ask for it: Enter carries the indentation over to the new
 * line, Tab indents the lines of a selection (or types one level), Shift+Tab takes
 * one level away. The edits are IndentEdits'; this decides which, applies them
 * through [Host] and keeps the selection on its text. Taken out of EditorView, which
 * keeps the public methods (setIndentUnit, indentLines, ...). Main thread.
 */
class Indenter(private val host: Host) {

    /** What indenting needs from the editor. */
    interface Host {
        val isEditable: Boolean
        /** The selection, or the cursor as an empty range. */
        val selection: Range
        val hasSelection: Boolean
        val eol: String
        /** How many spaces wide a tab stop is. */
        val tabSize: Int
        fun line(lineNumber: Int): String
        /** Types [text] in place of the selection (or at the cursor). */
        fun insert(text: String)
        /** Applies [edits] as one undo step of their own. */
        fun applyAsOwnStep(edits: List<SingleEditOperation>)
        fun select(range: Range)
    }

    /**
     * One level of indentation: four spaces (the default), another number of spaces,
     * or a tab. Tab types it; Backspace takes spaces away one at a time, like any char.
     */
    var unit: String = "    "
        set(value) {
            require(value.isNotEmpty() && value.all { it == ' ' || it == '\t' }) {
                "not a run of spaces or tabs: \"$value\""
            }
            field = value
        }

    /** Whether Enter starts the new line with the indentation of the line it was pressed on. */
    var autoIndent: Boolean = true

    /**
     * Enter: a line break, and with auto-indent the indentation of the line, as far
     * as it goes before the cursor (or the start of the selection it replaces).
     */
    fun onEnter() {
        if (!autoIndent) {
            host.insert(host.eol)
            return
        }
        val start = host.selection.getStartPosition()
        host.insert(host.eol + IndentEdits.newLineIndent(host.line(start.lineNumber), start.column))
    }

    /** Tab: with a selection over several lines it indents them, otherwise it types one level. */
    fun onTab() {
        val range = host.selection
        if (host.hasSelection && range.startLine != range.endLine) indent() else host.insert(unit)
    }

    /**
     * One level of indentation more on the lines of the selection, or on the cursor's
     * line; one undo step. The selection stays on the text it was on.
     *
     * @return false if refused (not editable, busy)
     */
    fun indent(): Boolean {
        if (!host.isEditable) return false
        val range = host.selection
        val selected = host.hasSelection
        val lines = IndentEdits.linesOf(range)
        host.applyAsOwnStep(IndentEdits.indent(lines, unit))
        // every line of it got the unit at its start: what was after the start moves
        val shift = unit.length
        fun moved(line: Int, column: Int) = if (line in lines && (column > 1 || !selected)) column + shift else column
        host.select(
            Range(
                range.startLine, moved(range.startLine, range.startColumn),
                range.endLine, moved(range.endLine, range.endColumn)
            )
        )
        return true
    }

    /**
     * One level of indentation less on the lines of the selection, or on the cursor's
     * line, where they have one; one undo step.
     *
     * @return false if refused (not editable, busy) or if no line had any indentation
     */
    fun outdent(): Boolean {
        if (!host.isEditable) return false
        val range = host.selection
        val lines = IndentEdits.linesOf(range)
        val tabSize = host.tabSize
        val removed = lines.associateWith { IndentEdits.outdentLength(host.line(it), unit, tabSize) }
        val edits = IndentEdits.outdent(lines, { host.line(it) }, unit, tabSize)
        if (edits.isEmpty()) return false
        host.applyAsOwnStep(edits)
        fun moved(line: Int, column: Int) = Math.max(1, column - (removed[line] ?: 0))
        host.select(
            Range(
                range.startLine, moved(range.startLine, range.startColumn),
                range.endLine, moved(range.endLine, range.endColumn)
            )
        )
        return true
    }
}
