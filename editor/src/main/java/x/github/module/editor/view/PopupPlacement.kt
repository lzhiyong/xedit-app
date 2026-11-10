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

import kotlin.math.max

/**
 * Where a popup at the cursor goes (the completion list, the info card): above or under
 * what it is about (the anchor: the row of the cursor, or the selection), never over it.
 * What is about to be typed, or the word asked about, stays in sight.
 */
internal object PopupPlacement {

    /** The top of a popup and its height. */
    data class Vertical(val y: Int, val height: Int, val above: Boolean)

    /**
     * A popup that wants to be [wanted] high, by an anchor from [anchorTop] to
     * [anchorBottom], in the room from [top] to [bottom]: on the preferred side
     * ([preferAbove]) if it fits there whole, else on the other side if it fits there,
     * else on the side with more room, cut to that room: in whole [step]s (the rows of a
     * list), one at least if there is room for one. [gapAbove] and [gapBelow]: what is
     * kept clear between the anchor and the popup (under it also the selection handles).
     */
    fun vertical(
        anchorTop: Int,
        anchorBottom: Int,
        wanted: Int,
        top: Int,
        bottom: Int,
        gapAbove: Int,
        gapBelow: Int,
        preferAbove: Boolean,
        step: Int = 1,
    ): Vertical {
        val roomAbove = anchorTop - gapAbove - top
        val roomBelow = bottom - (anchorBottom + gapBelow)
        val above = when {
            (if (preferAbove) roomAbove else roomBelow) >= wanted -> preferAbove
            (if (preferAbove) roomBelow else roomAbove) >= wanted -> !preferAbove
            else -> roomAbove > roomBelow
        }
        val room = max(0, if (above) roomAbove else roomBelow)
        val height = when {
            room >= wanted -> wanted
            step > 1 && room >= step -> room / step * step
            else -> room
        }
        val y = if (above) anchorTop - gapAbove - height else anchorBottom + gapBelow
        return Vertical(y, height, above)
    }
}
