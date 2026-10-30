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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPairsTest {

    // A document of lines and a cursor (or selection), written with `|` for the
    // cursor and `[` `]` around a selection: "foo(|)", "[bar]".
    private class FakeHost(state: String) : AutoPairs.Host {
        var text: String
        var start: Int
        var end: Int

        init {
            val selStart = state.indexOf('[')
            if (selStart >= 0) {
                val selEnd = state.indexOf(']') - 1
                text = state.replace("[", "").replace("]", "")
                start = selStart
                end = selEnd
            } else {
                start = state.indexOf('|')
                end = start
                text = state.replace("|", "")
            }
        }

        // the state written the same way back
        fun state(): String = if (start == end) {
            text.substring(0, start) + "|" + text.substring(start)
        } else {
            text.substring(0, start) + "[" + text.substring(start, end) + "]" + text.substring(end)
        }

        private fun position(offset: Int): Pair<Int, Int> {
            val before = text.substring(0, offset)
            val line = before.count { it == '\n' } + 1
            val column = offset - (before.lastIndexOf('\n') + 1) + 1
            return line to column
        }

        override val isEditable = true
        override val selection: Range
            get() {
                val (sl, sc) = position(start)
                val (el, ec) = position(end)
                return Range(sl, sc, el, ec)
            }
        override val hasSelection: Boolean get() = start != end
        override val eol = "\n"
        override val indentUnit = "    "
        override val autoIndent = true
        override fun line(lineNumber: Int): String = text.split('\n')[lineNumber - 1]
        override fun offsetAt(lineNumber: Int, column: Int): Int {
            val lines = text.split('\n')
            return lines.take(lineNumber - 1).sumOf { it.length + 1 } + column - 1
        }
        override fun textIn(range: Range): String =
            text.substring(offsetAt(range.startLine, range.startColumn), offsetAt(range.endLine, range.endColumn))
        override fun replace(range: Range, text: String) {
            val from = offsetAt(range.startLine, range.startColumn)
            val to = offsetAt(range.endLine, range.endColumn)
            this.text = this.text.substring(0, from) + text + this.text.substring(to)
            start = from + text.length
            end = start
        }
        override fun select(range: Range) {
            start = offsetAt(range.startLine, range.startColumn)
            end = offsetAt(range.endLine, range.endColumn)
        }
    }

    // [state] after typing [keys] one by one (Enter as \n, Backspace as \b); a key the
    // pairs do not take is typed as it is
    private fun type(state: String, keys: String, filter: AutoPairFilter? = null): String {
        val host = FakeHost(state)
        val pairs = AutoPairs(host).apply {
            this.pairs = AutoPair.DEFAULTS
            this.filter = filter
        }
        for (key in keys) {
            when (key) {
                '\n' -> if (!pairs.onEnter()) host.replace(host.selection, "\n")
                '\b' -> if (!pairs.onBackspace()) {
                    val range = host.selection
                    host.replace(Range(range.startLine, range.startColumn - 1, range.startLine, range.startColumn), "")
                }
                else -> if (!pairs.onTyped(key.toString())) host.replace(host.selection, key.toString())
            }
        }
        return host.state()
    }

    @Test
    fun anOpenBracketTypesItsClose() {
        assertEquals("foo(|)", type("foo|", "("))
        assertEquals("[{(|)}]", type("|", "[{("))
    }

    @Test
    fun theCloseCharStepsOverTheOneThatIsThere() {
        assertEquals("foo()|", type("foo|", "()"))
        assertEquals("\"abc\"|", type("|", "\"abc\""))
    }

    @Test
    fun nothingIsPairedBeforeAWord() {
        assertEquals("(|foo", type("|foo", "("))
        assertEquals("\"|foo", type("|foo", "\""))
    }

    @Test
    fun aQuoteAfterALetterIsAnApostrophe() {
        assertEquals("don'|t", type("don|t", "'"))
        assertEquals("don'|", type("don|", "'"))
    }

    @Test
    fun aLineWithACloseTooManyIsNotPairedAgain() {
        // the ) is waiting for this (
        assertEquals("x (|) y)", type("x |) y)", "("))
        assertEquals("(|)", type("|)", "("))
        // a balanced line gets the pair
        assertEquals("f((|))", type("f(|)", "("))
    }

    @Test
    fun backspaceInAnEmptyPairTakesBoth() {
        assertEquals("foo|", type("foo|", "(\b"))
        // only an empty pair
        assertEquals("foo(|)", type("foo(a|)", "\b"))
    }

    @Test
    fun enterInABracketPairOpensAnIndentedLine() {
        assertEquals("    if {\n        |\n    }", type("    if |", "{\n"))
        // not between quotes
        assertEquals("\"\n|\"", type("|", "\"\n"))
    }

    @Test
    fun aSelectionIsWrappedAndStaysSelected() {
        assertEquals("([foo])", type("[foo]", "("))
        assertEquals("\"[a\nb]\"", type("[a\nb]", "\""))
    }

    @Test
    fun theFilterCanSayNo() {
        val never = AutoPairFilter { _, _ -> false }
        assertEquals("(|", type("|", "(", never))
        // stepping over is not a pair: the filter is not asked
        assertEquals("()|", type("(|)", ")", never))
    }

    @Test
    fun withoutPairsNothingIsTaken() {
        val host = FakeHost("|")
        val pairs = AutoPairs(host)
        assertFalse(pairs.onTyped("("))
        assertFalse(pairs.onBackspace())
        assertFalse(pairs.onEnter())
    }

    @Test
    fun pairsAreReadFromText() {
        val pairs = AutoPair.parse("() [] <> \"\" x abc")
        assertEquals(listOf('(', '[', '<', '"'), pairs.map { it.open })
        assertTrue(pairs.first { it.open == '"' }.isQuote)
        assertEquals("() [] <> \"\"", AutoPair.format(pairs))
    }
}
