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
import kotlin.test.assertNull

class CompletionRankerTest {

    private fun item(label: String, sortText: String = label) =
        LspCompletionItem(label = label, sortText = sortText)

    @Test
    fun `the word before the cursor`() {
        assertEquals("barBaz", CompletionRanker.prefixAt("  foo.barBaz(", 12))
        assertEquals("my_var", CompletionRanker.prefixAt("x = my_var", 10))
        assertEquals("", CompletionRanker.prefixAt("a.", 2))
        assertEquals("", CompletionRanker.prefixAt("", 0))
        // a column past the line counts as its end
        assertEquals("abc", CompletionRanker.prefixAt("abc", 99))
    }

    @Test
    fun `scores from exact prefix down to subsequence`() {
        assertEquals(0, CompletionRanker.score("getter", "get"))
        assertEquals(1, CompletionRanker.score("GetValue", "get"))
        assertEquals(2, CompletionRanker.score("getInputStream", "gIS"))
        assertEquals(2, CompletionRanker.score("get_input_stream", "gis"))
        assertEquals(3, CompletionRanker.score("getInputStream", "gtstrm"))
        assertNull(CompletionRanker.score("println", "xyz"))
    }

    @Test
    fun `ranks by score, then the order of the server`() {
        val items = listOf(
            item("target", sortText = "1"),
            item("GetValue", sortText = "2"),
            item("getter", sortText = "4"),
            item("getInputStream", sortText = "3"),
            item("println", sortText = "0")
        )
        val ranked = CompletionRanker.rank(items, "get").map { it.label }
        assertEquals(listOf("getInputStream", "getter", "GetValue", "target"), ranked)
    }

    @Test
    fun `an empty prefix keeps every item, in the order of the server`() {
        val items = listOf(item("b", sortText = "2"), item("a", sortText = "3"), item("c", sortText = "1"))
        assertEquals(listOf("c", "b", "a"), CompletionRanker.rank(items, "").map { it.label })
    }

    @Test
    fun `items are matched by their filter text`() {
        val items = listOf(LspCompletionItem(label = "size()", filterText = "size"))
        assertEquals(listOf("size()"), CompletionRanker.rank(items, "siz").map { it.label })
    }
}
