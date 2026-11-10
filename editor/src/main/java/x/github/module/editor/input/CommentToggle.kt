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

/**
 * Commenting a region out and back in, as editors do (VS Code's Ctrl+/ and Shift+Alt+A,
 * nvim's gc):
 *
 *  - **line comments**, for whole lines (the selection over one or more lines, or the
 *    line of the cursor), when the language has them: the comment mark at the
 *    indentation of the least indented line, on every line that is not blank. Back in:
 *    the mark (and the space after it) taken off every line, if all of them have it.
 *  - **a block comment** around a part of a line that is selected, or when the language
 *    has no line comments (CSS, HTML): `/* … */` around the selection (or the line).
 *    Back in: the marks taken away from around the selection, also when they are just
 *    outside it (`/* ` and ` */` next to a selected `foo`).
 *
 * The positions are those of the editor: lines and columns from 1.
 */
object CommentToggle {

    /** The comment marks of a language: [line] (`//`), [blockStart] and [blockEnd] (`/*`, `*/`); null: it has none. */
    data class Style(val line: String?, val blockStart: String?, val blockEnd: String?) {
        val hasBlock: Boolean get() = !blockStart.isNullOrEmpty() && !blockEnd.isNullOrEmpty()
        val hasLine: Boolean get() = !line.isNullOrEmpty()
    }

    /**
     * The edits that toggle the comment, as one step: [edits] (in the text as it is, not
     * overlapping), whether they put a comment in ([commenting]), and what to select after
     * them ([selection], in the text as it will be).
     */
    data class Plan(val edits: List<Pair<Range, String>>, val commenting: Boolean, val selection: Range)

    /**
     * What toggling the comment does to the region from [startLine]:[startColumn] to
     * [endLine]:[endColumn] ([selected]: a selection; else the cursor, and its line is the
     * region), with [line] giving the text of a line. Null if there is nothing to do (blank
     * lines only, a language without comments).
     */
    fun plan(
        line: (Int) -> String,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        selected: Boolean,
        style: Style,
    ): Plan? {
        val partial = selected && isPartOfOneLine(line, startLine, startColumn, endLine, endColumn)
        return when {
            style.hasLine && !partial -> lines(line, startLine, endLine, endColumn, selected, style.line!!)
            style.hasBlock -> {
                if (selected) block(line, startLine, startColumn, endLine, endColumn, style)
                else {
                    // the line of the cursor, without its indentation
                    val text = line(startLine)
                    val from = text.indexOfFirst { !it.isWhitespace() }
                    if (from < 0) return null
                    val to = text.trimEnd().length
                    block(line, startLine, from + 1, startLine, to + 1, style)
                }
            }
            style.hasLine -> lines(line, startLine, endLine, endColumn, selected, style.line!!)
            else -> null
        }
    }

    // a selection within one line that leaves some of its text out
    private fun isPartOfOneLine(line: (Int) -> String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean {
        if (startLine != endLine) return false
        val text = line(startLine)
        val first = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return false
        val last = text.trimEnd().length
        return startColumn - 1 > first || endColumn - 1 < last
    }

    private fun lines(line: (Int) -> String, startLine: Int, endLine: Int, endColumn: Int, selected: Boolean, mark: String): Plan? {
        // a selection that ends at the start of a line does not take that line
        val last = if (selected && endLine > startLine && endColumn == 1) endLine - 1 else endLine
        val numbers = (startLine..last).filter { line(it).isNotBlank() }
        if (numbers.isEmpty()) return null
        val uncomment = numbers.all { line(it).trimStart().startsWith(mark) }
        val edits = ArrayList<Pair<Range, String>>()
        val lengthAfter = HashMap<Int, Int>()
        if (uncomment) {
            for (n in numbers) {
                val text = line(n)
                val at = text.indexOf(mark)
                val length = mark.length + if (text.getOrNull(at + mark.length) == ' ') 1 else 0
                edits += Range(n, at + 1, n, at + 1 + length) to ""
                lengthAfter[n] = text.length - length
            }
        } else {
            val indent = numbers.minOf { n -> line(n).takeWhile { it == ' ' || it == '\t' }.length }
            for (n in numbers) {
                edits += Range(n, indent + 1, n, indent + 1) to "$mark "
                lengthAfter[n] = line(n).length + mark.length + 1
            }
        }
        val lastLength = lengthAfter[last] ?: line(last).length
        return Plan(edits, !uncomment, Range(startLine, 1, last, lastLength + 1))
    }

    private fun block(line: (Int) -> String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, style: Style): Plan? {
        val open = style.blockStart!!
        val close = style.blockEnd!!
        val selected = text(line, startLine, startColumn, endLine, endColumn)
        if (selected.isBlank()) return null
        // the place [index] chars into the selection
        fun at(index: Int) = walk(startLine, startColumn, selected.substring(0, index))

        // the marks inside the selection, at its ends (white space around them let be)
        val lead = selected.length - selected.trimStart().length
        val coreEnd = selected.trimEnd().length
        val core = selected.substring(lead, coreEnd)
        if (core.length >= open.length + close.length && core.startsWith(open) && core.endsWith(close)) {
            // `/* x */`: the marks, and one space inside each
            val openLength = open.length + if (core.getOrNull(open.length) == ' ') 1 else 0
            val closeStart = coreEnd - close.length
            val closeFrom = if (selected.getOrNull(closeStart - 1) == ' ' && closeStart - 1 >= lead + openLength) closeStart - 1 else closeStart
            if (lead + openLength > closeFrom) return null
            val (openLine, openColumn) = at(lead)
            val (openEndLine, openEndColumn) = at(lead + openLength)
            val (closeLine, closeColumn) = at(closeFrom)
            val (closeEndLine, closeEndColumn) = at(coreEnd)
            val edits = listOf(
                Range(closeLine, closeColumn, closeEndLine, closeEndColumn) to "",
                Range(openLine, openColumn, openEndLine, openEndColumn) to "",
            )
            val after = selected.removeRange(closeFrom, coreEnd).removeRange(lead, lead + openLength)
            val (selEndLine, selEndColumn) = walk(startLine, startColumn, after)
            return Plan(edits, false, Range(startLine, startColumn, selEndLine, selEndColumn))
        }

        // `/* ` just before the selection and ` */` just after it
        val first = line(startLine)
        val last = line(endLine)
        val before = first.substring(0, (startColumn - 1).coerceIn(0, first.length))
        val rest = last.substring((endColumn - 1).coerceIn(0, last.length))
        val beforeTrim = before.trimEnd()
        val restTrim = rest.trimStart()
        if (beforeTrim.endsWith(open) && restTrim.startsWith(close)) {
            val openColumn = beforeTrim.length - open.length + 1
            val closeEnd = endColumn + (rest.length - restTrim.length) + close.length
            val edits = listOf(
                Range(endLine, endColumn, endLine, closeEnd) to "",
                Range(startLine, openColumn, startLine, startColumn) to "",
            )
            val (selEndLine, selEndColumn) = walk(startLine, openColumn, selected)
            return Plan(edits, false, Range(startLine, openColumn, selEndLine, selEndColumn))
        }

        // in: `/* ` before, ` */` after
        val openText = "$open "
        val closeText = " $close"
        val edits = listOf(
            Range(endLine, endColumn, endLine, endColumn) to closeText,
            Range(startLine, startColumn, startLine, startColumn) to openText,
        )
        val (selEndLine, selEndColumn) = walk(startLine, startColumn, openText + selected + closeText)
        return Plan(edits, true, Range(startLine, startColumn, selEndLine, selEndColumn))
    }

    // where [text] put in at [line]:[column] ends
    private fun walk(line: Int, column: Int, text: String): Pair<Int, Int> {
        val newlines = text.count { it == '\n' }
        return if (newlines == 0) line to column + text.length
        else (line + newlines) to (text.length - text.lastIndexOf('\n'))
    }

    // the text from [startLine]:[startColumn] to [endLine]:[endColumn]
    private fun text(line: (Int) -> String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        if (startLine == endLine) {
            val t = line(startLine)
            return t.substring((startColumn - 1).coerceIn(0, t.length), (endColumn - 1).coerceIn(0, t.length))
        }
        return buildString {
            val first = line(startLine)
            append(first.substring((startColumn - 1).coerceIn(0, first.length)))
            for (n in startLine + 1 until endLine) append('\n').append(line(n))
            val last = line(endLine)
            append('\n').append(last.substring(0, (endColumn - 1).coerceIn(0, last.length)))
        }
    }
}
