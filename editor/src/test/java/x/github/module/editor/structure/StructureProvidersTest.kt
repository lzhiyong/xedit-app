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

package x.github.module.editor.structure

import x.github.module.editor.model.PieceTreeBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.Range

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** The providers of the structure package that go by the text alone. */
class StructureProvidersTest {

    private fun buffer(vararg lines: String) =
        PieceTreeBuffer(PieceTreeTextBufferBuilder().apply { acceptChunk(lines.joinToString("\n")) }.build())

    // #region Indentation

    @Test
    fun aTabReachesToTheNextTabStop() {
        assertEquals(4, Indentation.indentOf("\tx", 4))
        assertEquals(4, Indentation.indentOf("  \tx", 4))
        assertEquals(6, Indentation.indentOf("\t  x", 4))
        assertEquals(-1, Indentation.indentOf("   ", 4), "blank")
    }

    @Test
    fun columnAtCountsCharsNotColumns() {
        assertEquals(2, Indentation.columnAt("\t\tx", 4, 4), "after one tab")
        assertEquals(5, Indentation.columnAt("        x", 4, 4), "after four spaces")
        assertEquals(3, Indentation.columnAt("", 2, 4), "past the end, one column per column")
    }

    // #endregion

    // #region brackets

    private val matcher = PlainBracketMatcher()

    @Test
    fun theBracketBeforeTheCursorIsMatchedFirst() {
        val text = buffer("f(a[0])")
        // after the "("
        val pair = matcher.findBracketPair(text, 1, 3)!!
        assertEquals(Range(1, 2, 1, 3), pair.open)
        assertEquals(Range(1, 7, 1, 8), pair.close)
    }

    @Test
    fun aClosingBracketFindsItsOpeningOneAcrossLines() {
        val text = buffer("{", "  { }", "}")
        val pair = matcher.findBracketPair(text, 3, 1)!!
        assertEquals(Range(1, 1, 1, 2), pair.open)
        assertEquals(Range(3, 1, 3, 2), pair.close)
    }

    @Test
    fun noPairWithoutABracketOrWithoutAPartner() {
        assertNull(matcher.findBracketPair(buffer("abc"), 1, 2))
        assertNull(matcher.findBracketPair(buffer("(abc"), 1, 2))
    }

    @Test
    fun aSubclassCanLeaveBracketsInStringsOut() {
        val quoted = object : PlainBracketMatcher() {
            override fun isBracket(buffer: x.github.module.editor.model.EditorBuffer, lineNumber: Int, index: Int): Boolean {
                val line = buffer.getLineContent(lineNumber)
                return line.substring(0, index).count { it == '"' } % 2 == 0
            }
        }
        val text = buffer("(\")\")")
        val pair = quoted.findBracketPair(text, 1, 2)!!
        assertEquals(Range(1, 5, 1, 6), pair.close)
    }

    // #endregion

    // #region indent guides

    @Test
    fun guidesRunThroughIndentedLinesAndOverBlankOnes() {
        val text = buffer(
            "fun a() {",        // 1
            "    if (b) {",     // 2
            "        c()",      // 3
            "",                 // 4: blank, takes 4 (the smaller of 8 and 4)
            "    }",            // 5
            "}"                 // 6
        )
        val guides = IndentationGuideProvider(4).getIndentGuides(text, 1, 6).sortedBy { it.column }

        assertEquals(listOf(IndentGuide(2, 5, 1), IndentGuide(3, 3, 5)), guides)
    }

    @Test
    fun aGuideIsMeasuredOnALineThatIsNotBlank() {
        val text = buffer("a", "", "\tb", "c")
        val guide = IndentationGuideProvider(4).getIndentGuides(text, 1, 4).single()

        assertEquals(IndentGuide(3, 3, 1, 3), guide)
    }

    // #endregion

    // #region folding

    @Test
    fun aLineFollowedByDeeperLinesStartsARange() {
        val text = buffer(
            "fun a() {",        // 1
            "    if (b) {",     // 2
            "        c()",      // 3
            "    }",            // 4
            "",                 // 5
            "}",                // 6
            "x"                 // 7
        )
        val ranges = IndentationFoldingProvider(4).getFoldingRanges(text, 1, 7)

        assertEquals(listOf(FoldingRange(1, 4), FoldingRange(2, 3)), ranges)
    }

    @Test
    fun onlyRangesStartingOnTheLinesAskedForAreReported() {
        val text = buffer("a", "  b", "  c", "d", "  e")
        val ranges = IndentationFoldingProvider(4).getFoldingRanges(text, 2, 4)

        assertEquals(listOf(FoldingRange(4, 5)), ranges)
    }

    // #endregion
}
