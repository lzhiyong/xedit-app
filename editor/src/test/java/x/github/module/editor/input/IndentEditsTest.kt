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
import x.github.module.piecetable.common.SingleEditOperation

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class IndentEditsTest {

    @Test
    fun aNewLineTakesTheIndentationBeforeTheCursor() {
        assertEquals("    ", IndentEdits.newLineIndent("    abc", 8))
        assertEquals("\t ", IndentEdits.newLineIndent("\t abc", 4))
        // the cursor inside the indentation: only what is before it
        assertEquals("  ", IndentEdits.newLineIndent("    abc", 3))
        assertEquals("", IndentEdits.newLineIndent("abc", 4))
    }

    @Test
    fun aSelectionOfWholeLinesDoesNotIndentTheLineItEndsAt() {
        assertEquals(2..3, IndentEdits.linesOf(Range(2, 1, 4, 1)))
        assertEquals(2..4, IndentEdits.linesOf(Range(2, 1, 4, 2)))
        assertEquals(3..3, IndentEdits.linesOf(Range(3, 5, 3, 5)), "a cursor: its line")
    }

    @Test
    fun indentingPutsTheUnitAtTheStartOfEachLine() {
        assertEquals(
            listOf(SingleEditOperation(Range(1, 1, 1, 1), "  "), SingleEditOperation(Range(2, 1, 2, 1), "  ")),
            IndentEdits.indent(1..2, "  ")
        )
    }

    @Test
    fun outdentingTakesOneLevelAway() {
        // the unit itself, a tab, or up to as many spaces as the unit is wide
        assertEquals(4, IndentEdits.outdentLength("    x", "    ", 4))
        assertEquals(1, IndentEdits.outdentLength("\tx", "    ", 4))
        assertEquals(2, IndentEdits.outdentLength("  x", "    ", 4))
        assertEquals(4, IndentEdits.outdentLength("        x", "\t", 4), "a tab unit takes four spaces")
        assertEquals(0, IndentEdits.outdentLength("x", "\t", 4))
    }

    @Test
    fun outdentingLeavesLinesWithoutIndentationAlone() {
        val lines = mapOf(1 to "\tone", 2 to "two", 3 to "  three")

        val edits = IndentEdits.outdent(1..3, { lines.getValue(it) }, "\t", 4)

        assertEquals(
            listOf(SingleEditOperation(Range(1, 1, 1, 2), null), SingleEditOperation(Range(3, 1, 3, 3), null)),
            edits
        )
        assertTrue(IndentEdits.outdent(2..2, { lines.getValue(it) }, "\t", 4).isEmpty())
    }
}
