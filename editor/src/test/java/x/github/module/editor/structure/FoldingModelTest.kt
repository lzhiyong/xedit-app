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

import x.github.module.editor.model.EditorDocument
import x.github.module.piecetable.common.Range

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class FoldingModelTest {

    private val model = FoldingModel()

    // a change from [startLine] to [endLine], with lines inserted and deleted
    private fun change(
        startLine: Int, endLine: Int, inserted: Int, deleted: Int,
        startColumn: Int = 1, endColumn: Int = 1
    ) =
        EditorDocument.AppliedChange(
            range = Range(startLine, startColumn, endLine, endColumn),
            rangeOffset = 0,
            insertedLinesCnt = inserted,
            insertedTextLength = 0,
            deletedLinesCnt = deleted,
            deletedTextLength = 0,
            finalLineNumber = startLine + inserted,
            finalColumn = 1
        )

    @Test
    fun aFoldHidesTheLinesAfterItsStartLine() {
        assertTrue(model.fold(FoldingRange(2, 4)))

        assertFalse(model.isHidden(2), "the start line stays")
        assertTrue(model.isHidden(3))
        assertTrue(model.isHidden(4))
        assertFalse(model.isHidden(5))
        assertContentEquals(intArrayOf(3, 4), model.hiddenLineRanges())
        assertEquals(FoldingRange(2, 4), model.foldAt(2))
        assertNull(model.foldAt(3))
    }

    @Test
    fun aRangeOfOneLineCannotBeFolded() {
        assertFalse(model.fold(FoldingRange(3, 3)))
        assertTrue(model.isEmpty)
    }

    @Test
    fun nestedAndTouchingFoldsHideTheirLinesOnce() {
        model.fold(FoldingRange(2, 5))
        model.fold(FoldingRange(3, 4))
        model.fold(FoldingRange(6, 8))
        // 3..5 and 7..8: line 6 starts a fold and can be seen
        assertContentEquals(intArrayOf(3, 5, 7, 8), model.hiddenLineRanges())

        model.fold(FoldingRange(5, 6))
        // 3..5, 6, 7..8 all touch now
        assertContentEquals(intArrayOf(3, 8), model.hiddenLineRanges())
    }

    @Test
    fun theVisibleLinesAroundAFold() {
        model.fold(FoldingRange(2, 4))

        assertEquals(5, model.nextVisibleLine(3))
        assertEquals(2, model.previousVisibleLine(4))
        assertEquals(6, model.nextVisibleLine(6), "not hidden: itself")
    }

    @Test
    fun unfoldAndRevealOpenFolds() {
        model.fold(FoldingRange(2, 9))
        model.fold(FoldingRange(4, 6))

        // line 5 is hidden in both
        assertTrue(model.reveal(5))
        assertTrue(model.isEmpty)

        model.fold(FoldingRange(2, 9))
        assertFalse(model.unfold(3), "no fold starts there")
        assertTrue(model.unfold(2))
        assertTrue(model.isEmpty)
    }

    @Test
    fun theVersionChangesWithTheFolds() {
        val before = model.version
        model.fold(FoldingRange(2, 4))
        assertNotEquals(before, model.version)

        val folded = model.version
        model.fold(FoldingRange(2, 4))
        assertEquals(folded, model.version, "the same fold again changes nothing")
    }

    // #region edits

    @Test
    fun aFoldBelowAnEditMovesWithItsLines() {
        model.fold(FoldingRange(5, 8))

        assertTrue(model.onTextChanged(change(2, 2, inserted = 2, deleted = 0)))
        assertEquals(FoldingRange(7, 10), model.foldAt(7))

        model.onTextChanged(change(1, 4, inserted = 0, deleted = 3))
        assertEquals(FoldingRange(4, 7), model.foldAt(4))
    }

    @Test
    fun aFoldAboveAnEditStays() {
        model.fold(FoldingRange(2, 4))

        assertFalse(model.onTextChanged(change(6, 6, inserted = 3, deleted = 0)))
        assertEquals(FoldingRange(2, 4), model.foldAt(2))
    }

    @Test
    fun anEditOnTheStartLineAloneKeepsTheFold() {
        model.fold(FoldingRange(2, 4))

        assertFalse(model.onTextChanged(change(2, 2, inserted = 0, deleted = 0, startColumn = 4, endColumn = 7)))
        assertEquals(FoldingRange(2, 4), model.foldAt(2))
    }

    @Test
    fun aLineTypedRightBeforeTheStartLineMovesTheFold() {
        model.fold(FoldingRange(2, 4))

        // "x\n" inserted at the very start of line 2
        assertTrue(model.onTextChanged(change(2, 2, inserted = 1, deleted = 0)))
        assertEquals(FoldingRange(3, 5), model.foldAt(3))
    }

    @Test
    fun anEditIntoAFoldOpensIt() {
        model.fold(FoldingRange(2, 4))
        model.fold(FoldingRange(10, 12))

        // a new line at the end of the start line goes into the folded part
        assertTrue(model.onTextChanged(change(2, 2, inserted = 1, deleted = 0, startColumn = 10, endColumn = 10)))
        assertNull(model.foldAt(2))
        // the one below moved
        assertEquals(FoldingRange(11, 13), model.foldAt(11))

        // a deletion that reaches into it
        assertTrue(model.onTextChanged(change(9, 12, inserted = 0, deleted = 3)))
        assertTrue(model.isEmpty)
    }

    // #endregion
}
