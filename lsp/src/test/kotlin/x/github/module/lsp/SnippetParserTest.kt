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

class SnippetParserTest {

    private fun stops(snippet: Snippet) = snippet.tabStops.map { Triple(it.index, it.start, it.end) }

    @Test
    fun `plain text stays as it is`() {
        val snippet = SnippetParser.parse("println")
        assertEquals("println", snippet.text)
        assertEquals(emptyList(), snippet.tabStops)
        assertEquals(Snippet.TabStop(0, 7, 7), snippet.cursor)
    }

    @Test
    fun `tab stops in order, the final one last`() {
        val snippet = SnippetParser.parse("foo($1)$0")
        assertEquals("foo()", snippet.text)
        assertEquals(listOf(Triple(1, 4, 4), Triple(0, 5, 5)), stops(snippet))
    }

    @Test
    fun `placeholders are inserted and selected`() {
        val snippet = SnippetParser.parse("printf(\${1:fmt}, \${2:args})\$0")
        assertEquals("printf(fmt, args)", snippet.text)
        assertEquals(listOf(Triple(1, 7, 10), Triple(2, 12, 16), Triple(0, 17, 17)), stops(snippet))
        assertEquals(Snippet.TabStop(1, 7, 10), snippet.cursor)
    }

    @Test
    fun `a tab stop again mirrors its placeholder`() {
        val snippet = SnippetParser.parse("for (\${1:int} \${2:i} = 0; \$2 < \${3:n}; \$2++) {\n\t\$0\n}")
        assertEquals("for (int i = 0; i < n; i++) {\n\t\n}", snippet.text)
        // where each tab stop is first
        assertEquals(listOf(Triple(1, 5, 8), Triple(2, 9, 10), Triple(3, 20, 21), Triple(0, 31, 31)), stops(snippet))
    }

    @Test
    fun `placeholders hold snippets`() {
        val snippet = SnippetParser.parse("\${1:outer(\${2:inner})}")
        assertEquals("outer(inner)", snippet.text)
        assertEquals(listOf(Triple(1, 0, 12), Triple(2, 6, 11)), stops(snippet))
    }

    @Test
    fun `a choice takes its first option`() {
        val snippet = SnippetParser.parse("\${1|one,two,three|}")
        assertEquals("one", snippet.text)
        assertEquals(listOf(Triple(1, 0, 3)), stops(snippet))
    }

    @Test
    fun `variables take their default or nothing`() {
        assertEquals("defaultx", SnippetParser.parse("\${TM_FILENAME:default}x\$HOME").text)
        assertEquals("ab", SnippetParser.parse("a\${CLIPBOARD}b").text)
    }

    @Test
    fun `escapes are the characters themselves`() {
        assertEquals("cost: \$5 } \\", SnippetParser.parse("cost: \\\$5 \\} \\\\").text)
    }

    @Test
    fun `what is no snippet is taken literally`() {
        assertEquals("\$ alone", SnippetParser.parse("\$ alone").text)
        assertEquals("\${unclosed", SnippetParser.parse("\${unclosed").text)
        assertEquals("a\$", SnippetParser.parse("a\$").text)
    }
}
