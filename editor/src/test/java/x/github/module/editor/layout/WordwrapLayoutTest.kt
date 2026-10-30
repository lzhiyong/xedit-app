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
import kotlin.test.Test

class WordwrapLayoutTest {

    private val measurer = FakeMeasurer(textSize = 10f)
    // room for 10 chars
    private var wrapWidth = 100

    private fun layoutOf(source: FakeTextSource) =
        WordwrapLayout(source, measurer) { wrapWidth }.apply { relayout() }

    @Test
    fun aLongLineTakesSeveralRows() {
        val source = FakeTextSource("short", "one two three four", "")
        val layout = layoutOf(source)

        // "one two " | "three four"
        assertEquals(4, layout.count())
        assertEquals(LineBreakResult(1, 0, 5, 50), layout.getLineResult(1))
        assertEquals(LineBreakResult(2, 0, 8, 80), layout.getLineResult(2))
        assertEquals(LineBreakResult(2, 8, 18, 100), layout.getLineResult(3))
        assertEquals(LineBreakResult(3, 0, 0, 0), layout.getLineResult(4))

        assertEquals(100, layout.width(), "the wrap width")
        assertEquals(4 * measurer.lineHeight, layout.height(), "one line height per row")
    }

    @Test
    fun rowsOfABufferLineCanBeFound() {
        val layout = layoutOf(FakeTextSource("short", "one two three four", ""))

        assertEquals(1, layout.getStartIndex(2))
        assertEquals(2, layout.getEndIndex(2))
        assertEquals(1, layout.getIndexAt(2, 1))
        // column 9 is offset 8, the first char of the second row
        assertEquals(2, layout.getIndexAt(2, 9))
        assertEquals(3, layout.getStartIndex(3))
        assertEquals(2, layout.getBufferLine(3))
    }

    @Test
    fun aRowNumberPastTheEndMeansTheLastRow() {
        val layout = layoutOf(FakeTextSource("abc", "de"))

        assertEquals(LineBreakResult(2, 0, 2, 20), layout.getLineResult(99))
    }

    @Test
    fun aRecycledLayoutStillAnswers() {
        val layout = layoutOf(FakeTextSource("abc", "de"))

        layout.recycle()

        assertEquals(0, layout.count())
        assertEquals(LineBreakResult(), layout.getLineResult(1))
    }

    @Test
    fun wrappingCannotBeRescaled() {
        val layout = layoutOf(FakeTextSource("one two three four"))

        assertFalse(layout.rescale(2f))
    }

    @Test
    fun aRelayoutFollowsTheWrapWidth() {
        val source = FakeTextSource("one two three four")
        val layout = layoutOf(source)
        assertEquals(2, layout.count())

        wrapWidth = 50
        layout.relayout()

        assertSameLayout("narrower", layoutOf(source), layout)
        assertEquals(50, layout.width())
    }

    // A cancelled relayout must leave no trace: the old rows stay, complete.
    @Test
    fun aCancelledRelayoutChangesNothing() {
        val source = FakeTextSource("one two three four")
        val layout = layoutOf(source)

        wrapWidth = 50
        source.lines.add("more")
        var completed = false
        layout.relayout(onCompleted = { completed = true }, isCancelled = { true })

        assertFalse(completed)
        assertEquals(2, layout.count())
        assertEquals(100, layout.width())
    }

    @Test
    fun anEditAfterTheWrapWidthChangedLaysEverythingOutAgain() {
        val source = FakeTextSource("one two three four", "five six seven")
        val layout = layoutOf(source)

        wrapWidth = 60
        source.edit(2, "five six", 0, emptyList())
        layout.update(2, 0, 0)

        assertSameLayout("after the width changed", layoutOf(source), layout)
    }

    @Test
    fun savedRowsCanBeRestoredIntoAnotherLayout() {
        val source = FakeTextSource("short", "one two three four", "")
        val saved = layoutOf(source).getBreakResults()

        val restored = WordwrapLayout(source, measurer) { wrapWidth }.apply { relayout() }
        restored.setBreakResults(saved)

        assertSameLayout("restored", layoutOf(source), restored)
    }

    // Like the one for UnwrappedLayout: edit by edit must equal from scratch.
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

    // Roughly: one row per inserted line, however long, until the relayout breaks them.
    @Test
    fun aRoughUpdateGivesOneRowPerInsertedLineUntilTheRelayout() {
        val source = FakeTextSource("a", "b")
        val layout = layoutOf(source)
        // 24 chars: three rows of at most 10 once broken
        source.edit(1, "a", 0, listOf("xxxx xxxx xxxx xxxx xxxx", "y"))

        layout.updateRoughly(1, 2, 0)
        assertEquals(4, layout.count())

        layout.relayout()
        assertSameLayout("after the relayout", layoutOf(source), layout)
    }

    // A large edit, line by line, must not take time in proportion to the rows times
    // the lines (it did, one list shift per row): 20 000 lines in and out at once.
    @Test
    fun aLargeEditIsAppliedInOneGo() {
        val source = FakeTextSource("a", "b")
        val layout = layoutOf(source)
        val lines = List(20_000) { "line $it" }
        source.edit(1, "a", 0, lines)
        layout.update(1, lines.size, 0)
        assertSameLayout("inserted", layoutOf(source), layout)

        source.edit(1, "a", lines.size, emptyList())
        layout.update(1, 0, lines.size)
        assertSameLayout("deleted", layoutOf(source), layout)
    }
}
