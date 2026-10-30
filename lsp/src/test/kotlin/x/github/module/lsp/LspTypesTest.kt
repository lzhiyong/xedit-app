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

package x.github.module.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LspTypesTest {

    @Test
    fun `positions from and to 1-based lines and columns`() {
        val position = TextPosition.fromOneBased(line = 3, column = 5)
        assertEquals(TextPosition(2, 4), position)
        assertEquals(3 to 5, position.toOneBased())
    }

    @Test
    fun `positions compare by line, then character`() {
        assertTrue(TextPosition(1, 9) < TextPosition(2, 0))
        assertTrue(TextPosition(2, 1) > TextPosition(2, 0))
    }

    @Test
    fun `a range contains its ends`() {
        val range = TextRange(TextPosition(1, 2), TextPosition(1, 6))
        assertTrue(TextPosition(1, 2) in range)
        assertTrue(TextPosition(1, 6) in range)
        assertFalse(TextPosition(1, 7) in range)
        assertTrue(TextRange.at(TextPosition(0, 0)).isEmpty)
    }

    @Test
    fun `no negative positions, no reversed ranges`() {
        assertFailsWith<IllegalArgumentException> { TextPosition(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TextRange(TextPosition(2, 0), TextPosition(1, 0)) }
    }

    @Test
    fun `completion kinds by the numbers of the protocol`() {
        assertEquals(CompletionKind.TEXT, CompletionKind.of(1))
        assertEquals(CompletionKind.FUNCTION, CompletionKind.of(3))
        assertEquals(CompletionKind.TYPE_PARAMETER, CompletionKind.of(25))
        assertEquals(null, CompletionKind.of(0))
        assertEquals(null, CompletionKind.of(26))
    }
}
