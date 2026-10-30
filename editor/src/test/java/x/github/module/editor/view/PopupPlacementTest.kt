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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Popups at the cursor go above or under the anchor, never over it (see PopupPlacement). */
class PopupPlacementTest {

    private fun place(anchorTop: Int, anchorBottom: Int, wanted: Int, preferAbove: Boolean, step: Int = 1) =
        PopupPlacement.vertical(anchorTop, anchorBottom, wanted, top = 0, bottom = 1000, gapAbove = 4, gapBelow = 4,
            preferAbove = preferAbove, step = step)

    private fun assertClear(anchorTop: Int, anchorBottom: Int, placed: PopupPlacement.Vertical) {
        assertTrue(placed.y + placed.height <= anchorTop || placed.y >= anchorBottom, "over the anchor: $placed")
        assertTrue(placed.y >= 0 && placed.y + placed.height <= 1000, "off the room: $placed")
    }

    @Test
    fun onThePreferredSideWhenItFits() {
        val under = place(100, 130, 200, preferAbove = false)
        assertEquals(PopupPlacement.Vertical(134, 200, above = false), under)
        val above = place(500, 530, 200, preferAbove = true)
        assertEquals(PopupPlacement.Vertical(296, 200, above = true), above)
    }

    @Test
    fun onTheOtherSideWhenOnlyThatFits() {
        // the keyboard leaves little room under the cursor: above it
        val placed = place(900, 930, 200, preferAbove = false)
        assertTrue(placed.above)
        assertEquals(200, placed.height)
        assertClear(900, 930, placed)
        // no room above the first rows: under them
        val info = place(40, 70, 200, preferAbove = true)
        assertFalse(info.above)
        assertClear(40, 70, info)
    }

    @Test
    fun cutToTheLargerRoomInWholeRowsWhenNeitherFits() {
        // 496 above, 466 under, 600 wanted
        val placed = place(500, 530, 600, preferAbove = false, step = 34)
        assertTrue(placed.above)
        assertEquals(496 / 34 * 34, placed.height)
        assertClear(500, 530, placed)
    }

    @Test
    fun aSelectionOverSeveralLinesIsNotCovered() {
        val placed = place(300, 700, 400, preferAbove = true)
        assertClear(300, 700, placed)
    }
}
