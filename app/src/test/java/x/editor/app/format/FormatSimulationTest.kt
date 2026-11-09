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

import kotlin.test.Test
import kotlin.test.assertEquals
import x.github.module.lsp.LspFormattingOptions

/** What the formatting rules alone make of a text (the preview without a server). */
class FormatSimulationTest {

    private val spaces4 = LspFormattingOptions(
        tabSize = 4, insertSpaces = true,
        trimTrailingWhitespace = true, insertFinalNewline = true, trimFinalNewlines = true,
    )

    // [text] through the simulation, as the preview runs it: (result, lines changed)
    private fun run(text: String, options: LspFormattingOptions, tabWidth: Int = 4): Pair<String, Int> {
        val lines = text.split('\n')
        val fallback = if (options.insertSpaces) options.tabSize else tabWidth
        val unit = FormatSimulation.guessIndentWidth(lines.iterator(), tabWidth, fallback)
        val simulation = FormatSimulation(options, tabWidth, unit)
        val out = StringBuilder()
        simulation.run(lines.iterator(), "\n") { out.append(it) }
        return out.toString() to simulation.changedLines
    }

    @Test
    fun twoSpacesALevelBecomeFour() {
        assertEquals("a {\n    b {\n        c\n    }\n}\n" to 4, run("a {\n  b {\n    c\n  }\n}", spaces4))
    }

    @Test
    fun spacesBecomeTabsAndAlignmentStays() {
        val tabs = spaces4.copy(insertSpaces = false)
        assertEquals("a {\n\tb {\n\t\tc\n\t}\n}\n" to 4, run("a {\n  b {\n    c\n  }\n}", tabs))
        // six columns at four a level: a level and two spaces of alignment
        assertEquals(
            "a {\n\tb\n\tc(1,\n\t  2)\n\td {\n\t\te\n\t}\n}\n" to 7,
            run("a {\n    b\n    c(1,\n      2)\n    d {\n        e\n    }\n}", tabs)
        )
    }

    @Test
    fun whiteSpaceAtTheEnds() {
        // trailing white space off, a line of it only made empty, blank lines at the end off
        assertEquals("x\n\ny\n" to 4, run("x  \n  \t\ny\n\n\n", spaces4))
        // a final newline put in
        assertEquals("x\n" to 1, run("x", spaces4))
    }

    @Test
    fun rulesLeftToTheServerAreNotApplied() {
        val none = spaces4.copy(trimTrailingWhitespace = null, insertFinalNewline = null, trimFinalNewlines = null)
        assertEquals("x  \n\ny" to 0, run("x  \n\ny", none))
        assertEquals("x\n\n\n" to 0, run("x\n\n\n", none))
    }

    @Test
    fun emptyOrBlankTextsStayAsTheyAre() {
        assertEquals("" to 0, run("", spaces4))
        assertEquals("\n\n" to 0, run("\n\n", spaces4))
    }

    @Test
    fun theIndentationOfAFileIsGuessed() {
        assertEquals(4, FormatSimulation.guessIndentWidth(listOf("a", "\tb", "\t\tc").iterator(), 4, 2))
        assertEquals(2, FormatSimulation.guessIndentWidth(listOf("a", "  b", "    c", "  d").iterator(), 4, 8))
        // no step at all: the fallback
        assertEquals(3, FormatSimulation.guessIndentWidth(listOf("a", "b").iterator(), 4, 3))
    }
}
