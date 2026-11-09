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
 * Two chars that are typed together: typing [open] types [close] right after the
 * cursor too. A quote is a pair of one char with itself (`"`, `'`, `` ` ``).
 *
 * @property expandOnEnter whether Enter between the two (`{|}`) puts [close] on a
 *   line of its own and the cursor on an indented line in between; by default for
 *   brackets, not for quotes
 */
data class AutoPair(
    val open: Char,
    val close: Char,
    val expandOnEnter: Boolean = open != close
) {
    /** Whether it is a quote: the same char opens and closes it. */
    val isQuote: Boolean get() = open == close

    companion object {
        /** `()`, `[]`, `{}`, `""`, `''` and backticks, as nvim-autopairs has them. */
        val DEFAULTS: List<AutoPair> = listOf(
            AutoPair('(', ')'), AutoPair('[', ']'), AutoPair('{', '}'),
            AutoPair('"', '"'), AutoPair('\'', '\''), AutoPair('`', '`')
        )

        /**
         * Pairs written as text, each two chars, separated by white space:
         * `"() [] {} \"\" ''"`. What is not two chars is left out.
         */
        fun parse(text: String): List<AutoPair> =
            text.split(' ', '\t', '\n', ',')
                .filter { it.length == 2 }
                .map { AutoPair(it[0], it[1]) }
                .distinctBy { it.open }

        /** [pairs] as [parse] reads them. */
        fun format(pairs: List<AutoPair>): String =
            pairs.joinToString(" ") { "${it.open}${it.close}" }
    }
}

/**
 * Says whether a pair is completed at a place, beyond the rules of [AutoPairs]:
 * not inside a string or a comment, say, which only a parser knows. Set with
 * EditorView.setAutoPairFilter.
 */
fun interface AutoPairFilter {
    /**
     * Whether typing the open char of [pair] with the cursor at [offset] (0-based,
     * in the document) also types its close char.
     */
    fun shouldPair(pair: AutoPair, offset: Int): Boolean
}

/**
 * Pairs typed together, as nvim-autopairs does it:
 *
 *  - the open char of a pair types the close char after the cursor too: `(` gives
 *    `(|)`; with text selected, the pair goes around it (the text stays selected);
 *  - not when the char after the cursor is a letter, a digit or one of `%'["`.$`
 *    (typing `(` before `foo` gives `(foo`), and a quote not right after a letter
 *    or a digit (`don't`);
 *  - not when the line has more close chars of the pair than open ones (`|a)`: the
 *    `)` is waiting for this `(`); a balanced line (`f(|)`) gets the pair;
 *  - the close char typed right before the same close char steps over it, so that
 *    typing `()` by hand still gives `()`;
 *  - Backspace between an empty pair takes both away;
 *  - Enter between a bracket pair puts the close bracket on a line of its own, the
 *    cursor on an indented line in between.
 *
 * Which pairs are [pairs] (none: all of this is off); [filter] may say no for a
 * place. Main thread.
 */
class AutoPairs(private val host: Host) {

    /** What it needs of the editor. */
    interface Host {
        val isEditable: Boolean
        /** The selection, or the cursor as an empty range. */
        val selection: Range
        val hasSelection: Boolean
        val eol: String
        /** One level of indentation, and whether Enter carries the indentation over. */
        val indentUnit: String
        val autoIndent: Boolean
        fun line(lineNumber: Int): String
        /** The 0-based offset in the document of a 1-based line and column. */
        fun offsetAt(lineNumber: Int, column: Int): Int
        fun textIn(range: Range): String
        /** Replaces [range] with [text], as typing does; the cursor ends up after it. */
        fun replace(range: Range, text: String)
        /** Selects [range], or puts the cursor there if it is empty. */
        fun select(range: Range)
    }

    /** The pairs that are typed together; empty for none (the default). */
    var pairs: List<AutoPair> = emptyList()
        set(value) {
            field = value.distinctBy { it.open }
        }

    /** Says no for some places, see [AutoPairFilter]; null: the rules alone decide. */
    var filter: AutoPairFilter? = null

    /** Whether the open char of a pair, typed over a selection, puts the pair around it. */
    var wrapSelection: Boolean = true

    /** Whether any pair is typed together. */
    val isEnabled: Boolean get() = pairs.isNotEmpty()

    /**
     * [text] is being typed (a key, a char of the input method, an extra key). Returns
     * true if it was typed here, as part of a pair or by stepping over a close char;
     * false if it is to be typed as it is.
     */
    fun onTyped(text: String): Boolean {
        if (text.length != 1 || pairs.isEmpty() || !host.isEditable) return false
        val char = text[0]
        val range = host.selection

        if (host.hasSelection) {
            if (!wrapSelection) return false
            val pair = pairs.firstOrNull { it.open == char } ?: return false
            wrap(range, pair)
            return true
        }

        val lineNumber = range.startLine
        val column = range.startColumn
        val line = host.line(lineNumber)
        val index = column - 1
        val next = line.getOrNull(index)
        val previous = line.getOrNull(index - 1)

        // the close char of a pair, typed right before that very char: step over it
        if (next == char && pairs.any { it.close == char }) {
            host.select(Range(lineNumber, column + 1, lineNumber, column + 1))
            return true
        }

        val pair = pairs.firstOrNull { it.open == char } ?: return false
        if (next != null && isIgnoredNext(next)) return false
        if (pair.isQuote && previous != null && (isWordChar(previous) || previous == char)) return false
        if (!pair.isQuote && hasUnmatchedClose(line, pair)) return false
        if (filter?.shouldPair(pair, host.offsetAt(lineNumber, column)) == false) return false

        host.replace(range, "${pair.open}${pair.close}")
        host.select(Range(lineNumber, column + 1, lineNumber, column + 1))
        return true
    }

    /**
     * Backspace: between the two chars of an empty pair (`(|)`), takes both away and
     * returns true; anywhere else returns false.
     */
    fun onBackspace(): Boolean {
        val range = enclosingEmptyPair() ?: return false
        host.replace(range, "")
        return true
    }

    /**
     * Enter: between a bracket pair (`{|}`), the close bracket goes on a line of its
     * own with the indentation of the line, the cursor on a line in between with one
     * level more, and returns true; anywhere else returns false.
     */
    fun onEnter(): Boolean {
        val pairRange = enclosingEmptyPair(expandingOnly = true) ?: return false
        val lineNumber = pairRange.startLine
        val column = pairRange.startColumn + 1
        val indent = if (host.autoIndent) IndentEdits.newLineIndent(host.line(lineNumber), column) else ""
        val inner = if (host.autoIndent) indent + host.indentUnit else ""
        val eol = host.eol
        host.replace(Range(lineNumber, column, lineNumber, column), eol + inner + eol + indent)
        host.select(Range(lineNumber + 1, inner.length + 1, lineNumber + 1, inner.length + 1))
        return true
    }

    // The two chars around the cursor if they are the open and close char of a pair
    // (of one that expands on Enter, with [expandingOnly]); null if not, or with text
    // selected.
    private fun enclosingEmptyPair(expandingOnly: Boolean = false): Range? {
        if (pairs.isEmpty() || !host.isEditable || host.hasSelection) return null
        val range = host.selection
        val lineNumber = range.startLine
        val column = range.startColumn
        val line = host.line(lineNumber)
        val previous = line.getOrNull(column - 2) ?: return null
        val next = line.getOrNull(column - 1) ?: return null
        val pair = pairs.firstOrNull { it.open == previous && it.close == next } ?: return null
        if (expandingOnly && !pair.expandOnEnter) return null
        return Range(lineNumber, column - 1, lineNumber, column + 1)
    }

    // the pair around the selected text, which stays selected
    private fun wrap(range: Range, pair: AutoPair) {
        val inner = host.textIn(range)
        host.replace(range, "${pair.open}$inner${pair.close}")
        val eol = host.eol
        val lastBreak = inner.lastIndexOf(eol)
        val breaks = if (lastBreak < 0) 0 else countOf(inner, eol)
        val endLine = range.startLine + breaks
        val endColumn = if (breaks == 0) range.startColumn + 1 + inner.length else inner.length - (lastBreak + eol.length) + 1
        host.select(Range(range.startLine, range.startColumn + 1, endLine, endColumn))
    }

    private fun countOf(text: String, part: String): Int {
        var count = 0
        var index = text.indexOf(part)
        while (index >= 0) {
            count++
            index = text.indexOf(part, index + part.length)
        }
        return count
    }

    // Whether [line] has more close chars of [pair] than open ones: a close typed
    // earlier waits for its open, which is what is being typed now (`|a)`), and a pair
    // would leave one close too many. A balanced line (`f(|)`) gets the pair.
    private fun hasUnmatchedClose(line: String, pair: AutoPair): Boolean {
        var opens = 0
        var closes = 0
        for (char in line) {
            if (char == pair.open) opens++ else if (char == pair.close) closes++
        }
        return closes > opens
    }

    private companion object {
        // after the cursor, these keep a pair from being completed (nvim-autopairs'
        // ignored_next_char: [%w%%%'%[%"%.%`%$])
        const val IGNORED_NEXT = "%'[\"`.$"

        fun isWordChar(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

        fun isIgnoredNext(char: Char): Boolean = isWordChar(char) || char in IGNORED_NEXT
    }
}
