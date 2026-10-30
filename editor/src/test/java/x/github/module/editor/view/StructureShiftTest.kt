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

package x.github.module.editor.view

import x.github.module.editor.model.EditorDocument
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.structure.IndentGuide
import x.github.module.piecetable.common.Range

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StructureShiftTest {

    // a change from [startLine] to [endLine], with lines inserted and deleted
    private fun change(startLine: Int, endLine: Int, inserted: Int, deleted: Int, endColumn: Int = 1) =
        EditorDocument.AppliedChange(
            range = Range(startLine, 1, endLine, endColumn),
            rangeOffset = 0,
            insertedLinesCnt = inserted,
            insertedTextLength = inserted,
            deletedLinesCnt = deleted,
            deletedTextLength = deleted,
            finalLineNumber = startLine + inserted,
            finalColumn = 1
        )

    @Test
    fun enterAboveMovesEverythingDown() {
        // Enter on line 2
        val shift = StructureShift(change(2, 2, inserted = 1, deleted = 0))
        assertEquals(IndentGuide(6, 11, 5, 5), shift.guide(IndentGuide(5, 10, 5, 4)))
        assertEquals(FoldingRange(6, 11), shift.range(FoldingRange(5, 10)))
    }

    @Test
    fun enterInsideABlockGrowsIt() {
        // Enter on line 7, inside the block of lines 5..10
        val shift = StructureShift(change(7, 7, inserted = 1, deleted = 0))
        assertEquals(IndentGuide(5, 11, 5, 4), shift.guide(IndentGuide(5, 10, 5, 4)))
        assertEquals(FoldingRange(5, 11), shift.range(FoldingRange(5, 10)))
    }

    @Test
    fun enterBelowLeavesItAsItIs() {
        val shift = StructureShift(change(20, 20, inserted = 1, deleted = 0))
        assertEquals(IndentGuide(5, 10, 5, 4), shift.guide(IndentGuide(5, 10, 5, 4)))
        assertEquals(FoldingRange(5, 10), shift.range(FoldingRange(5, 10)))
    }

    @Test
    fun joiningLinesShrinksABlock() {
        // the end of line 7 to the start of line 8: lines 7 and 8 joined
        val shift = StructureShift(change(7, 8, inserted = 0, deleted = 1))
        assertEquals(IndentGuide(5, 9, 5, 4), shift.guide(IndentGuide(5, 10, 5, 4)))
        assertEquals(FoldingRange(5, 9), shift.range(FoldingRange(5, 10)))
    }

    @Test
    fun whatLayInDeletedLinesGoes() {
        // lines 3..8 deleted
        val shift = StructureShift(change(3, 9, inserted = 0, deleted = 6))
        assertNull(shift.guide(IndentGuide(4, 6, 5, 4)))
        assertNull(shift.range(FoldingRange(4, 6)))
        // a block around them keeps its start and loses the lines
        assertEquals(FoldingRange(2, 6), shift.range(FoldingRange(2, 12)))
        // one below moves up
        assertEquals(FoldingRange(10, 14), shift.range(FoldingRange(16, 20)))
    }

    @Test
    fun aRangeLeftOnOneLineGoes() {
        // lines 5..6 joined: the range 5..6 would fold nothing
        val shift = StructureShift(change(5, 6, inserted = 0, deleted = 1))
        assertNull(shift.range(FoldingRange(5, 6)))
    }
}
