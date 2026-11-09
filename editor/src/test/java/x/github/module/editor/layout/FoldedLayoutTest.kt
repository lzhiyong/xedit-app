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

import x.github.module.editor.model.EditorDocument
import x.github.module.editor.structure.FoldingModel
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.testing.FakeMeasurer
import x.github.module.editor.testing.FakeTextSource
import x.github.module.piecetable.common.Range

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class FoldedLayoutTest {

    private val measurer = FakeMeasurer(textSize = 10f)
    private val folds = FoldingModel()

    private fun unwrapped(source: FakeTextSource) = UnwrappedLayout(source, measurer).apply { relayout() }

    // room for 10 chars
    private fun wrapped(source: FakeTextSource) = WordwrapLayout(source, measurer) { 100 }.apply { relayout() }

    private fun folded(inner: TextLayout, source: FakeTextSource) = FoldedLayout(inner, source, measurer, folds)

    @Test
    fun withNothingFoldedItIsTheInnerLayout() {
        val source = FakeTextSource("a", "b", "c")
        val layout = folded(unwrapped(source), source)

        assertEquals(3, layout.count())
        for (row in 1..3) {
            assertEquals(row, layout.getBufferLine(row))
        }
        assertEquals(1, layout.getStartIndex(2))
    }

    @Test
    fun foldedLinesHaveNoRows() {
        val source = FakeTextSource("l1", "l2", "l3", "l4", "l5", "l6")
        val layout = folded(unwrapped(source), source)

        folds.fold(FoldingRange(2, 4))

        assertEquals(4, layout.count())
        assertEquals(4 * measurer.lineHeight, layout.height())
        assertEquals(listOf(1, 2, 5, 6), (1..4).map { layout.getBufferLine(it) })
        // buffer line -> row (0-based)
        assertEquals(2, layout.getStartIndex(5))
        assertEquals(3, layout.getIndexAt(6, 1))
    }

    @Test
    fun aHiddenLineBelongsToTheRowOfTheFoldStart() {
        val source = FakeTextSource("l1", "l2", "l3", "l4", "l5")
        val layout = folded(unwrapped(source), source)

        folds.fold(FoldingRange(2, 4))

        assertEquals(1, layout.getStartIndex(3))
        assertEquals(1, layout.getIndexAt(4, 2))
    }

    @Test
    fun aRowNumberPastTheEndIsTheLastRow() {
        val source = FakeTextSource("l1", "l2", "l3", "l4")
        val layout = folded(unwrapped(source), source)

        folds.fold(FoldingRange(1, 3))

        assertEquals(2, layout.count())
        assertEquals(4, layout.getBufferLine(99))
    }

    @Test
    fun withWordWrapEveryRowOfAFoldedLineIsHidden() {
        // line 2 takes two rows: "one two " | "three four"
        val source = FakeTextSource("short", "one two three four", "x", "y")
        val layout = folded(wrapped(source), source)
        assertEquals(5, layout.count())

        folds.fold(FoldingRange(1, 2))

        assertEquals(3, layout.count())
        assertEquals(listOf(1, 3, 4), (1..3).map { layout.getBufferLine(it) })
        assertEquals(LineBreakResult(3, 0, 1, 10), layout.getLineResult(2))
        assertEquals(0, layout.getIndexAt(2, 12), "on the row of the fold start")
        assertTrue(layout.isWrapping)
    }

    @Test
    fun unfoldingBringsTheRowsBack() {
        val source = FakeTextSource("l1", "l2", "l3", "l4")
        val layout = folded(unwrapped(source), source)
        folds.fold(FoldingRange(1, 3))
        assertEquals(2, layout.count())

        folds.unfold(1)

        assertEquals(4, layout.count())
        assertEquals(3, layout.getBufferLine(3))
    }

    // The editor updates the inner layout and the folds; the folded layout notices
    // both by their versions.
    @Test
    fun itFollowsEditsOfTheText() {
        val source = FakeTextSource("l1", "l2", "l3", "l4", "l5")
        val inner = unwrapped(source)
        val layout = folded(inner, source)
        folds.fold(FoldingRange(2, 4))
        assertEquals(3, layout.count())

        // a new line after line 1
        source.edit(1, "l1", 0, listOf("new"))
        inner.update(1, 1, 0)
        folds.onTextChanged(
            EditorDocument.AppliedChange(
                range = Range(1, 3, 1, 3), rangeOffset = 2,
                insertedLinesCnt = 1, insertedTextLength = 4,
                deletedLinesCnt = 0, deletedTextLength = 0,
                finalLineNumber = 2, finalColumn = 4
            )
        )

        assertEquals(4, layout.count())
        assertEquals(listOf(1, 2, 3, 6), (1..4).map { layout.getBufferLine(it) })
    }
}
