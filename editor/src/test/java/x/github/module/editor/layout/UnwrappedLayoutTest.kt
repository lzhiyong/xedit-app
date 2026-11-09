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

import x.github.module.editor.testing.FakeMeasurer
import x.github.module.editor.testing.FakeTextSource
import x.github.module.editor.testing.assertSameLayout

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class UnwrappedLayoutTest {

    private val measurer = FakeMeasurer(textSize = 10f)

    private fun layoutOf(source: FakeTextSource) = UnwrappedLayout(source, measurer).apply { relayout() }

    @Test
    fun aNewLayoutHasOneEmptyRow() {
        val layout = UnwrappedLayout(FakeTextSource(), measurer)

        assertEquals(1, layout.count())
        assertEquals(0, layout.width())
        assertEquals(0, layout.getLineWidth(1))
    }

    @Test
    fun everyLineIsOneRowAsWideAsItsText() {
        val source = FakeTextSource("abc", "", "abcdef", "W")
        val layout = layoutOf(source)

        assertEquals(4, layout.count())
        assertEquals(30, layout.getLineWidth(1))
        assertEquals(0, layout.getLineWidth(2))
        assertEquals(60, layout.getLineWidth(3))
        assertEquals(20, layout.getLineWidth(4), "W is twice as wide")
        assertEquals(60, layout.width(), "the widest line")
        assertEquals(4 * measurer.lineHeight, layout.height())
    }

    @Test
    fun aRowIsItsBufferLineFromStartToEnd() {
        val layout = layoutOf(FakeTextSource("abc", "abcdef"))

        assertEquals(LineBreakResult(2, 0, 6, 60), layout.getLineResult(2))
        assertEquals(2, layout.getBufferLine(2))
        assertEquals(0, layout.getLineStart(2))
        assertEquals(6, layout.getLineEnd(2))
        // a buffer line has exactly one row, whatever the column
        assertEquals(1, layout.getStartIndex(2))
        assertEquals(1, layout.getEndIndex(2))
        assertEquals(1, layout.getIndexAt(2, 4))
    }

    @Test
    fun aRowNumberPastTheEndMeansTheLastRow() {
        val layout = layoutOf(FakeTextSource("abc", "abcdef"))

        assertEquals(60, layout.getLineWidth(99))
        assertEquals(2, layout.getBufferLine(99))
        assertEquals(30, layout.getLineWidth(0))
    }

    @Test
    fun relayoutReportsCompletionOnlyWhenItFinished() {
        val source = FakeTextSource("abc", "abcdef")
        val layout = UnwrappedLayout(source, measurer)

        var completed = false
        layout.relayout(onCompleted = { completed = true })
        assertTrue(completed)
    }

    // A cancelled relayout must leave no trace: the old numbers stay, complete.
    @Test
    fun aCancelledRelayoutChangesNothing() {
        val source = FakeTextSource("abc", "abcdef")
        val layout = layoutOf(source)

        source.lines[0] = "abcdefghij"
        source.lines.add("x")
        var completed = false
        layout.relayout(onCompleted = { completed = true }, isCancelled = { true })

        assertFalse(completed)
        assertEquals(2, layout.count())
        assertEquals(30, layout.getLineWidth(1))
        assertEquals(60, layout.width())
    }

    // #region update

    @Test
    fun typingOnTheWidestLineWidensTheLayout() {
        val source = FakeTextSource("abc", "abcdef")
        val layout = layoutOf(source)

        source.edit(2, "abcdefgh", 0, emptyList())
        layout.update(2, 0, 0)

        assertEquals(80, layout.width())
    }

    @Test
    fun shorteningTheWidestLineFallsBackToTheNextWidest() {
        val source = FakeTextSource("abcd", "abcdef", "ab")
        val layout = layoutOf(source)

        source.edit(2, "a", 0, emptyList())
        layout.update(2, 0, 0)

        assertEquals(40, layout.width())
    }

    @Test
    fun deletingTheWidestLineFallsBackToTheNextWidest() {
        val source = FakeTextSource("abcd", "ab", "abcdefgh", "abc")
        val layout = layoutOf(source)

        // line 2 stays as it is, line 3 after it is removed
        source.edit(2, "ab", 1, emptyList())
        layout.update(2, 0, 1)

        assertEquals(3, layout.count())
        assertEquals(40, layout.width())
        assertEquals(30, layout.getLineWidth(3))
    }

    @Test
    fun anEditOfAnotherLineDoesNotMeasureTheWholeText() {
        val source = FakeTextSource(*Array(1000) { "abcdef" })
        val layout = layoutOf(source)
        measurer.measureCalls = 0

        source.edit(500, "ab", 0, emptyList())
        layout.update(500, 0, 0)

        assertEquals(1, measurer.measureCalls, "only the edited line")
        assertEquals(60, layout.width())
    }

    @Test
    fun insertedLinesAreMeasuredAndTheRestMovesDown() {
        val source = FakeTextSource("a", "bb", "ccc")
        val layout = layoutOf(source)

        source.edit(1, "a", 0, listOf("dddd", "eeeee"))
        layout.update(1, 2, 0)

        assertEquals(5, layout.count())
        assertEquals(listOf(10, 40, 50, 20, 30), (1..5).map { layout.getLineWidth(it) })
        assertEquals(50, layout.width())
    }

    @Test
    fun manyInsertedLinesGrowTheStorage() {
        val source = FakeTextSource("a")
        val layout = layoutOf(source)

        val inserted = List(500) { "x".repeat(it % 7) }
        source.edit(1, "a", 0, inserted)
        layout.update(1, inserted.size, 0)

        assertSameLayout("after a large paste", layoutOf(source), layout)
    }

    // The layout has lost track of the text (here: recycled). An edit must bring it
    // back in step instead of failing.
    @Test
    fun anEditThatDoesNotFitTheLayoutStartsOver() {
        val source = FakeTextSource("abc", "abcdef", "ab")
        val layout = layoutOf(source)
        layout.recycle()
        assertEquals(0, layout.count())
        assertEquals(0, layout.getLineWidth(1), "an empty layout still answers")

        source.edit(3, "abcd", 0, emptyList())
        layout.update(3, 0, 0)

        assertSameLayout("after recovering", layoutOf(source), layout)
    }

    // The test that matters most here: whatever sequence of edits is applied, the
    // layout kept up to date edit by edit must equal one built from scratch.
    @Test
    fun incrementalUpdatesAlwaysMatchAFullRelayout() {
        val random = Random(20261007)
        val alphabet = "ab W.,(){} xy"
        fun randomLine() = String(CharArray(random.nextInt(31)) { alphabet[random.nextInt(alphabet.length)] })

        repeat(200) { trial ->
            val source = FakeTextSource(*Array(1 + random.nextInt(6)) { randomLine() })
            val layout = layoutOf(source)

            repeat(60) { step ->
                val lineCount = source.getLineCount()
                val startLine = 1 + random.nextInt(lineCount)
                val deleted = random.nextInt(Math.min(3, lineCount - startLine) + 1)
                val inserted = List(random.nextInt(4)) { randomLine() }

                source.edit(startLine, randomLine(), deleted, inserted)
                layout.update(startLine, inserted.size, deleted)

                assertSameLayout(
                    "trial $trial step $step (line $startLine, +${inserted.size} -$deleted)",
                    layoutOf(source), layout
                )
            }
        }
    }

    // #endregion

    // #region rescale

    @Test
    fun rescaleGivesTheSameWidthsAsMeasuringAtTheNewSize() {
        val source = FakeTextSource("abc", "abcdefW", "", "ab")
        val layout = layoutOf(source)

        measurer.textSize = 25f
        assertTrue(layout.rescale(25f / 10f))

        assertSameLayout("after zooming in", layoutOf(source), layout)

        measurer.textSize = 10f
        assertTrue(layout.rescale(10f / 25f))

        assertSameLayout("after zooming back out", layoutOf(source), layout)
    }

    @Test
    fun rescaleMeasuresNothing() {
        val layout = layoutOf(FakeTextSource(*Array(100) { "abcdef" }))
        measurer.measureCalls = 0

        layout.rescale(2f)

        assertEquals(0, measurer.measureCalls)
        assertEquals(120, layout.width())
    }

    // #endregion

    @Test
    fun savedRowsCanBeRestoredIntoAnotherLayout() {
        val source = FakeTextSource("abc", "abcdef", "")
        val saved = layoutOf(source).getBreakResults()

        val restored = UnwrappedLayout(source, measurer)
        restored.setBreakResults(saved)

        assertEquals(3, saved.size)
        assertSameLayout("restored", layoutOf(source), restored)
    }

    // Roughly: the inserted lines are there at once, with a width from their length;
    // the relayout after it gives the same layout as from scratch.
    @Test
    fun aRoughUpdateHasEveryLineAndARelayoutMakesItExact() {
        val source = FakeTextSource("a", "b")
        val layout = layoutOf(source)
        source.edit(1, "a", 0, List(50) { "x".repeat(it) })

        layout.updateRoughly(1, 50, 0)
        assertEquals(52, layout.count())

        layout.relayout()
        val fresh = layoutOf(source)
        for (row in 1..52) {
            assertEquals(fresh.getLineWidth(row), layout.getLineWidth(row), "row $row")
        }
        assertEquals(fresh.width(), layout.width())
    }
}
