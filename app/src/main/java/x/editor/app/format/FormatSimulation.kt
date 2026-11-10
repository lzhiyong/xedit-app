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

package x.editor.app.format

import x.github.module.lsp.LspFormattingOptions

/**
 * What the formatting rules alone make of a text, for the preview of the formatting sheet
 * when no language server formats the document (or it failed): what a formatter does
 * with white space, and nothing else (no braces, no line breaks, no quotes).
 *
 *  - the indentation: each line's leading white space measured in columns (a tab up to
 *    the next stop of [tabWidth], the editor's), taken as levels of the file's own
 *    indentation ([indentWidth], see [guessIndentWidth]) and written again as the rules
 *    want them: [LspFormattingOptions.tabSize] spaces a level, or a tab. What is left over
 *    (alignment) stays spaces;
 *  - trimTrailingWhitespace: white space at the ends of lines taken off (a line of white
 *    space only becomes empty);
 *  - trimFinalNewlines: the blank lines at the end taken off (the last newline stays);
 *  - insertFinalNewline: a newline at the end if there is none.
 *
 * A rule that is null is left to the server: nothing done for it here. It works line by
 * line ([run]), so that a large text is not held twice as a String.
 */
internal class FormatSimulation(
    private val options: LspFormattingOptions,
    private val tabWidth: Int,
    private val indentWidth: Int,
) {

    /** How many lines [run] changed (taken off or added at the end included). */
    var changedLines = 0
        private set

    private val unit = indentWidth.coerceIn(1, MAX_INDENT)
    private val tab = tabWidth.coerceIn(1, MAX_INDENT)

    /**
     * Runs over the [lines] of a text (without their line breaks; a text that ends with a
     * line break has an empty last line), and hands the result to [emit], piece by piece,
     * lines joined with [eol].
     */
    fun run(lines: Iterator<String>, eol: String, emit: (String) -> Unit) {
        changedLines = 0
        var first = true
        // empty lines not yet written: they may be the blank lines at the end
        var pendingEmpty = 0
        var written = false
        fun write(line: String) {
            if (!first) emit(eol)
            first = false
            if (line.isNotEmpty()) emit(line)
        }
        for (text in lines) {
            val out = formatLine(text)
            if (out != text) changedLines++
            if (out.isEmpty()) {
                pendingEmpty++
                continue
            }
            repeat(pendingEmpty) { write("") }
            pendingEmpty = 0
            write(out)
            written = true
        }
        // pendingEmpty: the empty lines at the end; the last of them, if the text has more
        // than one line, is what follows the final newline
        var trailing = pendingEmpty
        if (options.trimFinalNewlines == true && written && trailing > 1) {
            changedLines += trailing - 1
            trailing = 1
        }
        if (options.insertFinalNewline == true && written && trailing == 0) {
            changedLines++
            trailing = 1
        }
        repeat(trailing) { write("") }
    }

    /** One line as the rules make it. */
    fun formatLine(text: String): String {
        val indentEnd = text.indexOfFirst { it != ' ' && it != '\t' }
        // white space only
        if (indentEnd < 0) return if (options.trimTrailingWhitespace == true) "" else text
        val body = if (options.trimTrailingWhitespace == true) text.substring(indentEnd).trimEnd(' ', '\t') else text.substring(indentEnd)
        if (indentEnd == 0) return body
        val columns = columnsOf(text, indentEnd)
        return indentOf(columns / unit, columns % unit) + body
    }

    // the width of text[0, end) in columns, a tab up to the next stop
    private fun columnsOf(text: String, end: Int): Int {
        var column = 0
        for (i in 0 until end) column = if (text[i] == '\t') (column / tab + 1) * tab else column + 1
        return column
    }

    // [levels] of indentation as the rules want it, then [extra] spaces
    private fun indentOf(levels: Int, extra: Int): String {
        val size = options.tabSize.coerceIn(1, MAX_INDENT)
        val out = StringBuilder()
        if (options.insertSpaces) repeat(levels * size) { out.append(' ') } else repeat(levels) { out.append('\t') }
        repeat(extra) { out.append(' ') }
        return out.toString()
    }

    companion object {
        private const val MAX_INDENT = 16

        /** Lines looked at by [guessIndentWidth]: enough to tell, few enough to be quick. */
        const val GUESS_LINES = 10_000

        /**
         * The width of one level of indentation of a text, in columns: the step from a line
         * to a more indented one that comes most often among its first lines (a tab is
         * [tabWidth] columns), from 1 to 8 columns. [fallback] if there is no such step.
         */
        fun guessIndentWidth(lines: Iterator<String>, tabWidth: Int, fallback: Int): Int {
            val tab = tabWidth.coerceIn(1, MAX_INDENT)
            val counts = IntArray(9)
            var previous = -1
            var seen = 0
            while (lines.hasNext() && seen < GUESS_LINES) {
                val line = lines.next()
                seen++
                val end = line.indexOfFirst { it != ' ' && it != '\t' }
                // blank lines say nothing
                if (end < 0) continue
                var column = 0
                for (i in 0 until end) column = if (line[i] == '\t') (column / tab + 1) * tab else column + 1
                if (previous >= 0) {
                    val step = column - previous
                    if (step in 1..8) counts[step]++
                }
                previous = column
            }
            var best = 0
            for (step in 1..8) if (counts[step] > counts[best]) best = step
            return if (best == 0) fallback.coerceIn(1, MAX_INDENT) else best
        }
    }
}
