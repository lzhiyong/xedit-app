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

/**
 * Takes back the last step of a dragged handle when the finger lifts off.
 *
 * A finger does not leave the screen straight up: in the last moments it rolls, and
 * the position under it moves one step too far. So when the finger lifts right after
 * a move ([delayAfterMillis]) that came after the handle had rested
 * ([delayBeforeMillis]), the position it rested at is the one meant. (The same rule
 * as Android's Editor.HandleView.filterOnTouchUp.)
 *
 * Positions are opaque here (the handle code packs line and column into a Long);
 * times are passed in, so that it works without a clock. Main thread.
 */
class TouchUpFilter(
    private val historySize: Int = 5,
    private val delayAfterMillis: Long = 150L,
    private val delayBeforeMillis: Long = 350L
) {

    private val positions = LongArray(historySize)
    private val times = LongArray(historySize)
    private var index = 0
    private var count = 0

    /** A drag starts at [position] at [time]: what was before is forgotten. */
    fun start(position: Long, time: Long) {
        count = 0
        add(position, time)
    }

    /** The handle got to [position] at [time]. */
    fun add(position: Long, time: Long) {
        index = (index + 1) % historySize
        positions[index] = position
        times[index] = time
        count++
    }

    /**
     * The finger lifted at [time]: the position to go back to, or null to stay where
     * the handle is.
     */
    fun positionOnTouchUp(time: Long): Long? {
        var steps = 0
        var at = index
        val maxSteps = Math.min(count, historySize)
        // skip the positions of the last moments ...
        while (steps < maxSteps && time - times[at] < delayAfterMillis) {
            steps++
            at = (index - steps + historySize) % historySize
        }
        // ... and go back to the one before them, if the handle had rested there
        if (steps > 0 && steps < maxSteps && time - times[at] > delayBeforeMillis) {
            return positions[at]
        }
        return null
    }
}
