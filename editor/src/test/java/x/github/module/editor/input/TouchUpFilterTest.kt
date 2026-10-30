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

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class TouchUpFilterTest {

    // keeps 5 positions; a step in the last 150 ms after a rest of more than 350 ms is taken back
    private val filter = TouchUpFilter(historySize = 5, delayAfterMillis = 150L, delayBeforeMillis = 350L)

    @Test
    fun aStepRightBeforeLiftingAfterARestIsTakenBack() {
        filter.start(10L, time = 0L)
        // rested at 10 for a while, then one step while lifting
        filter.add(11L, time = 1_000L)

        assertEquals(10L, filter.positionOnTouchUp(time = 1_050L))
    }

    @Test
    fun aHandleThatRestedBeforeLiftingStays() {
        filter.start(10L, time = 0L)
        filter.add(11L, time = 1_000L)

        assertNull(filter.positionOnTouchUp(time = 1_500L))
    }

    @Test
    fun aHandleStillMovingWhenLiftedStays() {
        filter.start(10L, time = 0L)
        filter.add(11L, time = 900L)
        filter.add(12L, time = 1_000L)

        // the one before the last step had not rested: it was moving too
        assertNull(filter.positionOnTouchUp(time = 1_050L))
    }

    @Test
    fun withoutAnyMoveThereIsNothingToTakeBack() {
        filter.start(10L, time = 0L)

        assertNull(filter.positionOnTouchUp(time = 50L))
    }
}
