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

package x.github.module.editor.render

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

import x.github.module.editor.render.RowCache.Action

class RowCacheTest {

    // A recording: which text it shows, and whether styled. Stands in for a RenderNode.
    private class Node {
        var text: Int = -1
        var styled = false
        var hasRecording = false
    }

    private fun cache(capacity: Int = 64) = RowCache(capacity = { capacity }, create = { Node() })

    // Draws the rows [visible] as TextRenderer does, with [text] (the text id of each row,
    // index row - 1) and a frame that can style [budget] rows that may wait.
    private fun frame(cache: RowCache<Node>, visible: IntRange, text: List<Int>, budget: Int) {
        var left = budget
        for (row in visible) {
            if (row > text.size) break
            val entry = cache.entryFor(row, visible)
            val node = entry.node
            val styled: Boolean? = when (cache.actionFor(entry, node.hasRecording)) {
                Action.NONE -> null
                Action.RECORD -> left-- > 0
                Action.RECORD_STYLED -> { left--; true }
                Action.RESTYLE -> if (left > 0) { left--; true } else null
            }
            if (styled != null) {
                node.text = text[row - 1]
                node.styled = styled
                node.hasRecording = true
                cache.recorded(entry, styled)
            }
        }
        cache.endFrame()
    }

    private fun drawnAll(cache: RowCache<Node>, visible: IntRange, text: List<Int>) =
        frame(cache, visible, text, budget = Int.MAX_VALUE)

    @Test
    fun aNewRowMayWaitAndIsStyledLater() {
        val cache = cache()
        val entry = cache.entryFor(1, 1..10)
        assertEquals(Action.RECORD, cache.actionFor(entry, hasRecording = false))
        cache.recorded(entry, styled = false)
        assertEquals(RowCache.State.PLAIN, entry.state)
        // plain: styled when there is time, kept until then
        assertEquals(Action.RESTYLE, cache.actionFor(entry, hasRecording = true))
        cache.recorded(entry, styled = true)
        assertEquals(Action.NONE, cache.actionFor(entry, hasRecording = true))
    }

    @Test
    fun anEditedStyledRowIsStyledAtOnce() {
        val cache = cache()
        val entry = cache.entryFor(3, 1..10)
        cache.recorded(entry, styled = true)
        cache.endFrame()
        cache.invalidateText(3, 3)
        assertEquals(Action.RECORD_STYLED, cache.actionFor(entry, hasRecording = true))
    }

    @Test
    fun newStylesKeepWhatIsShownUntilThereIsTime() {
        val cache = cache()
        val entry = cache.entryFor(3, 1..10)
        cache.recorded(entry, styled = true)
        cache.invalidateStyles(1, 5)
        assertEquals(RowCache.State.STALE_STYLES, entry.state)
        assertEquals(Action.RESTYLE, cache.actionFor(entry, hasRecording = true))
        cache.invalidateAllStyles()
        assertEquals(Action.RESTYLE, cache.actionFor(entry, hasRecording = true))
    }

    @Test
    fun aPlainRowWhoseTextChangedMayStillWait() {
        val cache = cache()
        val entry = cache.entryFor(3, 1..10)
        cache.recorded(entry, styled = false)
        cache.endFrame()
        cache.invalidateText(3, 3)
        assertEquals(RowCache.State.NEW, entry.state)
        // edited rows are styled when recorded before the next frame
        assertEquals(Action.RECORD_STYLED, cache.actionFor(entry, hasRecording = true))
        cache.endFrame()
        assertEquals(Action.RECORD, cache.actionFor(entry, hasRecording = true))
    }

    @Test
    fun aLostRecordingIsRecordedAgain() {
        val cache = cache()
        val entry = cache.entryFor(3, 1..10)
        cache.recorded(entry, styled = true)
        assertEquals(Action.RECORD, cache.actionFor(entry, hasRecording = false))
    }

    @Test
    fun enterMovesTheRowsBelowWithTheirRecordings() {
        val cache = cache()
        val text = (1..10).toList()
        drawnAll(cache, 1..10, text)
        val nodesBefore = (1..10).map { cache.peek(it)!!.node }

        // Enter on row 5: rows 5..5 became rows 5..6
        cache.onEdited(firstRow = 5, lastRowBefore = 5, lastRowAfter = 6)

        for (row in 1..4) {
            assertSame(nodesBefore[row - 1], cache.peek(row)!!.node)
            assertEquals(Action.NONE, cache.actionFor(cache.peek(row)!!, true))
        }
        // the edited row: at once, styled
        assertEquals(Action.RECORD_STYLED, cache.actionFor(cache.peek(5)!!, true))
        // the new row: nothing recorded yet, but styled when it is
        assertNull(cache.peek(6))
        val newRow = cache.entryFor(6, 1..11)
        assertEquals(Action.RECORD_STYLED, cache.actionFor(newRow, newRow.node.hasRecording))
        // the old rows 6..10 are rows 7..11 now, as recorded
        for (row in 7..11) {
            val entry = cache.peek(row)!!
            assertSame(nodesBefore[row - 2], entry.node)
            assertEquals(Action.NONE, cache.actionFor(entry, true))
        }
    }

    @Test
    fun joiningLinesMovesTheRowsBelowUp() {
        val cache = cache()
        drawnAll(cache, 1..10, (1..10).toList())
        val nodesBefore = (1..10).map { cache.peek(it)!!.node }

        // Backspace at the start of row 6: rows 5..6 became row 5
        cache.onEdited(firstRow = 5, lastRowBefore = 6, lastRowAfter = 5)

        assertEquals(Action.RECORD_STYLED, cache.actionFor(cache.peek(5)!!, true))
        for (row in 6..9) {
            val entry = cache.peek(row)!!
            assertSame(nodesBefore[row], entry.node)
            assertEquals(Action.NONE, cache.actionFor(entry, true))
        }
        assertNull(cache.peek(10))
        // the recording of the row that went is reused, not lost
        val reused = cache.entryFor(10, 1..10)
        assertSame(nodesBefore[5], reused.node)
        assertEquals(RowCache.State.NEW, reused.state)
    }

    @Test
    fun rowsOfEditsNotDrawnYetMoveAlong() {
        val cache = cache()
        drawnAll(cache, 1..20, (1..20).toList())
        // two lines typed in before a frame: Enter on row 10, then Enter on row 3
        cache.onEdited(10, 10, 11)
        cache.onEdited(3, 3, 4)
        // the row typed in first is row 12 now, and still styled when recorded
        val first = cache.entryFor(12, 1..22)
        assertEquals(Action.RECORD_STYLED, cache.actionFor(first, first.node.hasRecording))
        assertEquals(Action.RECORD_STYLED, cache.actionFor(cache.entryFor(4, 1..22), false))
    }

    @Test
    fun invalidatingAllTextStylesEveryRowAtOnce() {
        val cache = cache()
        drawnAll(cache, 1..10, (1..10).toList())
        cache.invalidateAllText()
        for (row in 1..10) assertEquals(Action.RECORD_STYLED, cache.actionFor(cache.peek(row)!!, true))
        // rows that come on screen with it too (a larger text size shows fewer, a smaller more)
        assertEquals(Action.RECORD_STYLED, cache.actionFor(cache.entryFor(11, 1..12), false))
    }

    @Test
    fun recordingsOfRowsOffScreenAreReused() {
        val cache = cache(capacity = 4)
        drawnAll(cache, 1..4, (1..4).toList())
        val farthest = cache.peek(1)!!.node
        // scrolled by one row: row 5 comes, row 1 is the one farthest off screen
        val entry = cache.entryFor(5, 2..5)
        assertSame(farthest, entry.node)
        assertEquals(RowCache.State.NEW, entry.state)
        assertNull(cache.peek(1))
        // all on screen: one more is made
        val extra = cache.entryFor(6, 2..6)
        assertTrue(extra.node !== farthest)
        assertNotSame(cache.peek(2)!!.node, extra.node)
    }

    @Test
    fun clearForgetsEverything() {
        val cache = cache()
        drawnAll(cache, 1..5, (1..5).toList())
        assertEquals(5, cache.clear().size)
        assertTrue(cache.isEmpty)
    }

    /**
     * The point of it all, on random edits, however little time a frame has:
     *  - a line that was on screen styled is styled on the next frame, wherever the edit
     *    moved it;
     *  - text that was not on screen (an edited line, one pulled up from below) in the
     *    place of styled text is styled on the next frame;
     *  - every row shows the text it has.
     * Only a line that was plain (its styling waited) may stay plain a little longer,
     * where it is or where it moved to: nothing on screen loses its colors.
     */
    @Test
    fun styledRowsNeverFlickerAndRowsShowTheirText() {
        repeat(300) { seed ->
            val random = Random(seed)
            val cache = cache(capacity = 24)
            // the text of each row: an id, new for each edit
            var nextId = 1000
            val text = MutableList(60) { it + 1 }
            var top = 1
            val height = 20
            fun visible() = top..Math.min(text.size, top + height - 1)

            frame(cache, visible(), text, budget = Int.MAX_VALUE)
            repeat(80) {
                // what is on screen before: by row, whether it is styled; by line (the
                // text a recording shows), whether it is styled
                val beforeByRow = HashMap<Int, Boolean>()
                val beforeByLine = HashMap<Int, Boolean>()
                for (row in visible()) {
                    val node = cache.peek(row)?.node ?: continue
                    if (!node.hasRecording) continue
                    beforeByRow[row] = node.styled
                    beforeByLine[node.text] = node.styled
                }
                when (random.nextInt(7)) {
                    0 -> { // typing on a row
                        val row = random.nextInt(1, text.size + 1)
                        text[row - 1] = nextId++
                        cache.onEdited(row, row, row)
                    }
                    1 -> { // Enter on a row
                        val row = random.nextInt(1, text.size + 1)
                        text[row - 1] = nextId++
                        text.add(row, nextId++)
                        cache.onEdited(row, row, row + 1)
                    }
                    2 -> if (text.size > height + 2) { // joining a row with the next one
                        val row = random.nextInt(1, text.size)
                        text[row - 1] = nextId++
                        text.removeAt(row)
                        cache.onEdited(row, row + 1, row)
                    }
                    3 -> { // the parse after an edit: other styles for some rows
                        val first = random.nextInt(1, text.size + 1)
                        cache.invalidateStyles(first, first + random.nextInt(10))
                    }
                    4 -> { // several changes at once: everything below the first is new
                        val row = random.nextInt(1, text.size + 1)
                        for (r in row..text.size) text[r - 1] = nextId++
                        cache.invalidateText(row, Int.MAX_VALUE)
                    }
                    5 -> top = (top + random.nextInt(-3, 4)).coerceIn(1, Math.max(1, text.size - height + 1))
                    else -> {}
                }
                // a frame with little time: at most one row that may wait is styled
                frame(cache, visible(), text, budget = random.nextInt(0, 2))

                for (row in visible()) {
                    val node = cache.peek(row)!!.node
                    assertTrue(node.hasRecording, "seed $seed: row $row has a recording")
                    assertEquals(text[row - 1], node.text, "seed $seed: row $row shows its text")
                    when (beforeByLine[node.text]) {
                        // on screen before, styled: still styled
                        true -> assertTrue(node.styled, "seed $seed: line ${node.text} (row $row) lost its colors")
                        // on screen before, plain: may still wait
                        false -> {}
                        // not on screen before, where styled text was: styled at once
                        null -> if (beforeByRow[row] == true) {
                            assertTrue(node.styled, "seed $seed: new text on row $row in the place of styled text is plain")
                        }
                    }
                }
            }
        }
    }
}
