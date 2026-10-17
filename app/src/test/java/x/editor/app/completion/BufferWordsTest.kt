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

package x.editor.app.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Completion from the words of the text: the index, fuzzy matching, ranking, what to highlight. */
class BufferWordsTest {

    private val text = """
        int get_line(struct buffer *buf) { return buf->line_count; }
        int getLine2(void); static int line_count = 0; int linecount;
        // printf printf printf print printer
        x = pri
    """.trimIndent()
    private val typed = text.lastIndexOf("pri")
    private val index = BufferWords.index(text, skipStart = typed, skipEnd = typed + 3)

    @Test
    fun theWordsAndHowOften() {
        assertEquals(3, index["printf"])
        assertEquals(2, index["line_count"])
        // the word being typed, numbers and one-char words are not offered
        assertFalse("pri" in index || "0" in index || "x" in index, index.keys.toString())
    }

    @Test
    fun aWordAcrossChunksIsOneWord() {
        val big = "a".repeat(64 * 1024 - 3) + " alphabet_soup " + "z"
        assertTrue("alphabet_soup" in BufferWords.index(big))
    }

    @Test
    fun fuzzyMatching() {
        assertNotNull(BufferWords.match("printf", "pri"))
        assertNull(BufferWords.match("sprintf", "pri"), "the first char must match")
        assertEquals(listOf(0, 4), BufferWords.match("get_line", "gl")?.second?.toList())
        assertEquals(listOf(0, 3), BufferWords.match("getLine2", "gL")?.second?.toList())
        assertNull(BufferWords.match("printf", "pfi"), "in order")
        assertTrue(BufferWords.match("line_count", "lin")!!.first > BufferWords.match("linecount", "lnt")!!.first)
    }

    @Test
    fun rankedBestFirst() {
        val near = BufferWords.near(text, typed, 4000, typed, typed + 3)
        val ranked = BufferWords.rank(index, near, "pri", 10).map { it.word }
        assertEquals("printf", ranked.first(), ranked.toString())
        assertTrue(ranked.containsAll(listOf("print", "printer")))
        assertFalse("pri" in BufferWords.rank(mapOf("pri" to 5, "prism" to 1), emptyMap(), "pri", 10).map { it.word })
        assertEquals(setOf("get_line", "getLine2"), BufferWords.rank(index, near, "gl", 10).take(2).map { it.word }.toSet())
    }

    @Test
    fun whatToHighlightInALabel() {
        assertEquals(listOf(0, 1, 2), BufferWords.highlight("printf", "pri")?.toList())
        // clangd puts a space or a bullet before its labels
        assertEquals(listOf(1, 2, 3), BufferWords.highlight(" printf(const char *, ...)", "pri")?.toList())
        assertEquals(listOf(1, 2, 3), BufferWords.highlight("•printf(const char *, ...)", "pri")?.toList())
        assertEquals(listOf(5, 6, 7), BufferWords.highlight("std::vector", "vec")?.toList())
        assertNull(BufferWords.highlight("buffer", "xyz"))
        assertNull(BufferWords.highlight("buffer", ""))
    }
}
