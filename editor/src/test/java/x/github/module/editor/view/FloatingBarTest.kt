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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How high the overflow menu of the floating bar is, and where it opens (see FloatingBar). */
class FloatingBarTest {

    private val row = 52
    private val chrome = 52 + 8
    private val max = FloatingBar.MAX_OVERFLOW_ROWS

    @Test
    fun fewRowsShowAllOfThem() {
        assertEquals(0, FloatingBar.visibleRowsHeight(0, row, Int.MAX_VALUE, max))
        assertEquals(1 * row, FloatingBar.visibleRowsHeight(1, row, Int.MAX_VALUE, max))
        assertEquals(4 * row, FloatingBar.visibleRowsHeight(4, row, Int.MAX_VALUE, max))
    }

    @Test
    fun manyRowsShowFourAndHalfOfTheNext() {
        // as the system's: four rows, and half a row that shows the menu scrolls
        assertEquals(4 * row + row / 2, FloatingBar.visibleRowsHeight(5, row, Int.MAX_VALUE, max))
        assertEquals(4 * row + row / 2, FloatingBar.visibleRowsHeight(30, row, Int.MAX_VALUE, max))
    }

    @Test
    fun theLimitCanBeChanged() {
        assertEquals(6 * row + row / 2, FloatingBar.visibleRowsHeight(30, row, Int.MAX_VALUE, 6))
    }

    @Test
    fun opensUpWhenThereIsRoom() {
        val fit = FloatingBar.fitOverflow(30, row, max, roomUp = 2000, roomDown = 2000, chrome = chrome)
        assertTrue(fit.up)
        assertEquals(4 * row + row / 2, fit.viewport)
    }

    @Test
    fun opensDownWhenOnlyThereIsRoom() {
        // the selection near the top of the screen
        val fit = FloatingBar.fitOverflow(30, row, max, roomUp = 150, roomDown = 1500, chrome = chrome)
        assertEquals(false, fit.up)
        assertEquals(4 * row + row / 2, fit.viewport)
    }

    @Test
    fun littleRoomOnBothSidesCutsTheRowsToWhatFits() {
        // a landscape screen with the keyboard up: three rows of room on the larger side
        val roomDown = chrome + 3 * row + 10
        val fit = FloatingBar.fitOverflow(30, row, max, roomUp = 120, roomDown = roomDown, chrome = chrome)
        assertEquals(false, fit.up)
        assertTrue(fit.viewport <= roomDown - chrome, "the menu stays on screen: ${fit.viewport}")
        assertTrue(fit.viewport >= 2 * row, "at least two rows show: ${fit.viewport}")
    }

    @Test
    fun theBarStandsTheGapAboveTheSelection() {
        // one line (40) of room between the row and the bar, as the magnifier has
        val top = FloatingBar.barTop(500, 540, 52, topLimit = 0, bottomLimit = 2000, gapAbove = 40, gapBelow = 40)
        assertEquals(500 - 40 - 52, top)
    }

    @Test
    fun theBarClearsTheKnobOfTheStartHandle() {
        // the knob reaches 45 above the row: the bar ends above it, not on it
        val knobTop = 500 - 45
        val top = FloatingBar.barTop(500, 540, 52, topLimit = 0, bottomLimit = 2000, gapAbove = 45 + 22, gapBelow = 40)
        assertTrue(top + 52 < knobTop, "the bar covers the knob: ${top + 52} > $knobTop")
    }

    @Test
    fun underTheSelectionWhenThereIsNoRoomAbove() {
        val top = FloatingBar.barTop(60, 100, 52, topLimit = 24, bottomLimit = 2000, gapAbove = 40, gapBelow = 70)
        assertEquals(100 + 70, top)
    }

    @Test
    fun neverLessThanOneRowEvenWithoutRoom() {
        val fit = FloatingBar.fitOverflow(30, row, max, roomUp = 40, roomDown = 30, chrome = chrome)
        assertEquals(row, fit.viewport)
    }
}
