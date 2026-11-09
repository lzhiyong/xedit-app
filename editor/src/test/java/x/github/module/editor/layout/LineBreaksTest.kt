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

package x.github.module.editor.layout

import java.util.Random

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class LineBreaksTest {

    // every char 10 wide
    private fun widthsOf(text: String) = FloatArray(text.length) { 10f }

    private fun breaksOf(text: String, maxWidth: Float) = computeLineBreaks(text, widthsOf(text), maxWidth)

    // #region computeLineBreaks

    @Test
    fun emptyTextIsOneEmptyRow() {
        assertEquals(listOf(0), breaksOf("", 50f))
    }

    @Test
    fun textThatFitsIsOneRow() {
        assertEquals(listOf(5), breaksOf("hello", 50f))
    }

    @Test
    fun aRowBreaksAfterTheLastSpaceThatFits() {
        // "aaa " | "bbbb": five chars would fit, but that would cut "bbbb"
        assertEquals(listOf(4, 8), breaksOf("aaa bbbb", 50f))
    }

    @Test
    fun aBreakExactlyAtAWordBoundaryStaysThere() {
        // "hello " | "world"
        assertEquals(listOf(6, 11), breaksOf("hello world", 60f))
    }

    @Test
    fun aWordLongerThanARowIsCutWhereItStopsFitting() {
        assertEquals(listOf(3, 6, 8), breaksOf("abcdefgh", 30f))
    }

    @Test
    fun charsThatAreNotWordCharsCanBreakAnywhere() {
        // CJK text has no spaces and is not kept together
        assertEquals(listOf(3, 6, 7), breaksOf("一二三四五六七", 30f))
    }

    @Test
    fun breaksAlwaysCoverTheTextInRowsThatFit() {
        val random = Random(42)
        val alphabet = "ab cd,ef(gh) ij  klm"

        repeat(500) { round ->
            val text = String(CharArray(random.nextInt(60)) { alphabet[random.nextInt(alphabet.length)] })
            val maxWidth = 10f * (1 + random.nextInt(12))
            val breaks = breaksOf(text, maxWidth)
            val message = "round $round, text '$text', maxWidth $maxWidth, breaks $breaks"

            assertEquals(text.length, breaks.last(), "$message: must end at the end of the text")

            var start = 0
            for (end in breaks) {
                assertTrue(end >= start, "$message: breaks must not go backwards")
                assertTrue((end - start) * 10f <= maxWidth, "$message: a row must fit")
                start = end
            }
            if (text.isNotEmpty()) {
                assertTrue(breaks.drop(1).zipWithNext().all { (a, b) -> b > a }, "$message: only the first row may be empty")
            }
        }
    }

    // #endregion

    // #region rows of a buffer line

    // line 1 takes rows 0..1, line 2 row 2, line 3 rows 3..5
    private val rows = listOf(
        LineBreakResult(1, 0, 5, 50),
        LineBreakResult(1, 5, 9, 40),
        LineBreakResult(2, 0, 3, 30),
        LineBreakResult(3, 0, 4, 40),
        LineBreakResult(3, 4, 8, 40),
        LineBreakResult(3, 8, 10, 20)
    )

    @Test
    fun firstAndLastRowOfALine() {
        assertEquals(0, rows.firstRowOf(1))
        assertEquals(1, rows.lastRowOf(1))
        assertEquals(2, rows.firstRowOf(2))
        assertEquals(2, rows.lastRowOf(2))
        assertEquals(3, rows.firstRowOf(3))
        assertEquals(5, rows.lastRowOf(3))
    }

    @Test
    fun theRowOfAColumn() {
        // columns are 1-based: column 1..5 are offsets 0..4, on the first row
        assertEquals(0, rows.rowIndexAt(1, 1))
        assertEquals(0, rows.rowIndexAt(1, 5))
        // column 6 is offset 5, where the second row starts
        assertEquals(1, rows.rowIndexAt(1, 6))
        assertEquals(1, rows.rowIndexAt(1, 9))
        assertEquals(2, rows.rowIndexAt(2, 1))
        assertEquals(3, rows.rowIndexAt(3, 4))
        assertEquals(4, rows.rowIndexAt(3, 5))
        assertEquals(5, rows.rowIndexAt(3, 9))
    }

    @Test
    fun aColumnPastTheEndOfTheLineIsOnItsLastRow() {
        assertEquals(1, rows.rowIndexAt(1, 100))
        assertEquals(5, rows.rowIndexAt(3, 100))
    }

    // #endregion

    @Test
    fun sumRangeAddsTheWidthsOfARange() {
        val widths = floatArrayOf(1f, 2f, 3f, 4f)

        assertEquals(5f, widths.sumRange(1, 3), 0f)
        assertEquals(0f, widths.sumRange(2, 2), 0f)
        // with an offset: the array holds the widths of chars 10..13
        assertEquals(7f, widths.sumRange(12, 14, 10), 0f)
    }

    @Test
    fun digitCountCountsDecimalDigits() {
        assertEquals(1, 1.digitCount)
        assertEquals(1, 9.digitCount)
        assertEquals(2, 10.digitCount)
        assertEquals(3, 999.digitCount)
        assertEquals(4, 1000.digitCount)
        assertEquals(7, 1_000_000.digitCount)
    }

    // A tab reaches the next stop from the start of its row: after a break, the
    // stops count anew.
    @Test
    fun tabsGetTheirWidthOnTheRowTheyEndUpOn() {
        // "aaaa\tb": chars 10 wide, stops every 40, rows 45 wide
        val text = "aaaa\tb"
        val widths = FloatArray(text.length) { 10f }

        val breaks = computeLineBreaks(text, widths, 45f, tabWidth = 40f)

        // "aaaa" fills the first row; the tab starts the second, a whole stop wide
        assertEquals(listOf(4, 5, 6), breaks)
        assertEquals(40f, widths[4])
    }
}
