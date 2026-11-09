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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tab stops of a snippet, kept in step with the edits (see SnippetSession). */
class SnippetSessionTest {

    // `add(a, b)` put in at 10: stops at the a, the b, and after the `)`
    private fun session() = SnippetSession(listOf(14 to 15, 17 to 18, 19 to 19))

    @Test
    fun tabGoesThroughTheStops() {
        val s = session()
        assertEquals(0, s.current)
        assertEquals(1, s.next())
        assertEquals(2, s.next())
        assertTrue(s.isAtLast)
        assertNull(s.next())
        assertEquals(1, s.previous())
    }

    @Test
    fun whatIsTypedInAStopMakesItGrowAndMovesTheOthers() {
        val s = session()
        // the placeholder `a` replaced with `count`
        s.onEdit(14, 1, 5)
        assertEquals(14, s.start(0))
        assertEquals(19, s.end(0))
        assertEquals(21, s.start(1))
        assertEquals(23, s.start(2))
        assertTrue(s.isInCurrent(19))
        assertFalse(s.isInCurrent(21))
    }

    @Test
    fun aCompletionInThePlaceholderStaysInIt() {
        val s = session()
        // `co` typed over the placeholder, then a completion puts `count` in its place
        s.onEdit(14, 1, 2)
        assertTrue(s.isInCurrent(14) && s.isInCurrent(16))
        s.onEdit(14, 2, 5)
        assertEquals(14 to 19, s.start(0) to s.end(0))
        // the whole snippet grew by 4: its end, after the `)`, at 23
        assertTrue(s.isInside(23))
        assertFalse(s.isInside(24))
    }
}
