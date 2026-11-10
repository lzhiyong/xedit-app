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

package x.github.module.editor.util

import x.github.module.piecetable.common.Range

import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class RangeSearchTest {

    private val first = Range(1, 1, 1, 4)
    private val second = Range(1, 6, 1, 9)
    private val third = Range(3, 2, 3, 5)
    private val fourth = Range(7, 1, 7, 3)
    // sorted, as search results are
    private val matches = listOf(first, second, third, fourth)

    @Test
    fun findsTheMatchesOnTheLineThatTouchTheColumns() {
        val found = matches.findOverlapping(1, 1, 1, 5)

        assertEquals(1, found.size)
        assertSame(first, found[0])
    }

    @Test
    fun findsSeveralMatchesInDocumentOrder() {
        val found = matches.findOverlapping(1, 1, 1, 20)

        assertEquals(2, found.size)
        assertSame(first, found[0])
        assertSame(second, found[1])
    }

    @Test
    fun aLineWithoutMatchesGivesNothing() {
        assertTrue(matches.findOverlapping(2, 1, 2, 100).isEmpty())
        assertTrue(matches.findOverlapping(5, 1, 5, 100).isEmpty())
        assertTrue(matches.findOverlapping(9, 1, 9, 100).isEmpty())
    }

    @Test
    fun matchesOutsideTheColumnsAreLeftOut() {
        assertTrue(matches.findOverlapping(3, 6, 3, 50).isEmpty())
        assertTrue(matches.findOverlapping(3, 1, 3, 1).isEmpty())
    }

    @Test
    fun findsTheFirstAndTheLastMatchOfTheList() {
        assertSame(first, matches.findOverlapping(1, 1, 1, 2).single())
        assertSame(fourth, matches.findOverlapping(7, 1, 7, 10).single())
    }

    @Test
    fun theRangeOverloadDoesTheSame() {
        assertSame(third, matches.findOverlapping(Range(3, 1, 3, 10)).single())
    }

    @Test
    fun anEmptyListGivesNothing() {
        assertTrue(emptyList<Range>().findOverlapping(1, 1, 1, 10).isEmpty())
    }
}
