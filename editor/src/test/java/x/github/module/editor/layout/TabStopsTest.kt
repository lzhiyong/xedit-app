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

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class TabStopsTest {

    @Test
    fun aTabReachesTheNextStop() {
        assertEquals(40f, TabStops.next(0f, 40f))
        assertEquals(40f, TabStops.next(39f, 40f))
        assertEquals(80f, TabStops.next(40f, 40f), "on a stop: to the next one")
        assertEquals(12f, TabStops.next(12f, 0f), "no stops: no room")
    }

    @Test
    fun tabsGetTheWidthUpToTheirStop() {
        // "a\tbc\t" with chars 10 wide, stops every 40
        val widths = floatArrayOf(10f, 10f, 10f, 10f, 10f)
        TabStops.applyTo("a\tbc\t", 0, 5, widths, 40f)

        assertContentEquals(floatArrayOf(10f, 30f, 10f, 10f, 20f), widths)
    }

    @Test
    fun stopsCountFromTheStartOfWhatIsMeasured() {
        // the second half of "ab\tc", as a row of its own: the tab is first
        val widths = floatArrayOf(10f, 10f)
        TabStops.applyTo("ab\tc", 2, 4, widths, 40f)

        assertContentEquals(floatArrayOf(40f, 10f), widths)
    }

    @Test
    fun hasTabLooksOnlyInTheRange() {
        assertTrue(TabStops.hasTab("a\tb", 0, 3))
        assertFalse(TabStops.hasTab("a\tb", 2, 3))
    }
}
