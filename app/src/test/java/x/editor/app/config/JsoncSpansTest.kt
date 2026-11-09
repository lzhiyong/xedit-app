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

package x.editor.app.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The edits of a list in JSONC (New, Edit, Delete of the panels whose entries are in one file), and the templates. */
class JsoncSpansTest {

    private val keymap = """
        {
          // the list
          "version": 2,
          "keymaps": [
            { "keys": "<C-c>", "action": "copy" },
            { "keys": "<C-x>", "action": "cut" } // last
          ]
        }
    """.trimIndent()

    @Test
    fun findsTheElements() {
        val spans = JsoncSpans.elementsOf(keymap, "keymaps")
        assertEquals(2, spans.size)
        assertEquals("""{ "keys": "<C-x>", "action": "cut" }""", keymap.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun appendsAtTheEndKeepingComments() {
        val appended = JsoncSpans.append(keymap, "keymaps", """{ "keys": "", "action": "save" }""")
        assertTrue(appended.text.substring(appended.start).startsWith("""{ "keys": """""))
        assertEquals(3, JsoncSpans.elementsOf(appended.text, "keymaps").size)
        assertTrue("// last" in appended.text)
        assertTrue("// the list" in appended.text)
    }

    @Test
    fun appendsToAnEmptyListAndAfterATrailingComma() {
        val empty = "{\n  \"packages\": [\n  ]\n}"
        assertEquals("{\n  \"packages\": [\n    {\"id\":\"b\"}\n  ]\n}", JsoncSpans.append(empty, "packages", "{\"id\":\"b\"}").text)
        val trailing = "{\n  \"packages\": [\n    {\"id\":\"a\"},\n  ]\n}"
        val multiLine = JsoncSpans.append(trailing, "packages", "{\n  \"id\": \"b\"\n}").text
        assertEquals(2, JsoncSpans.elementsOf(multiLine, "packages").size)
        assertTrue("    {\n      \"id\": \"b\"\n    }" in multiLine)
    }

    @Test
    fun removesAnElement() {
        val spans = JsoncSpans.elementsOf(keymap, "keymaps")
        val removed = JsoncSpans.removeAt(keymap, spans, 0)
        assertEquals(1, JsoncSpans.elementsOf(removed, "keymaps").size)
        assertTrue("<C-c>" !in removed)
    }

    @Test
    fun noSuchList() {
        assertFailsWith<IllegalArgumentException> { JsoncSpans.elementsOf("""{"servers":[]}""", "packages") }
        assertFailsWith<IllegalArgumentException> { JsoncSpans.append("[]", "packages", "{}") }
    }

    @Test
    fun positions() {
        assertEquals(1 to 1, JsoncSpans.positionOf("ab\ncd", 0))
        assertEquals(2 to 2, JsoncSpans.positionOf("ab\ncd", 4))
    }

    @Test
    fun placeholders() {
        val template = "{\"a\": \"{{tabSize}}\", // {{tabSize}} of {{language}}\n \"b\": [\"{{ext}}\"]}"
        assertEquals(
            "{\"a\": 4, // {{tabSize}} of go\n \"b\": []}",
            TemplateText.fill(template, values = mapOf("tabSize" to "4", "ext" to ""), text = mapOf("language" to "go")),
        )
        assertEquals("\"a\\\"b\\\\c\\n\"", TemplateText.jsonString("a\"b\\c\n"))
    }

    @Test
    fun theTemplatesAreWhereThePanelsLookForThem() {
        val root = java.io.File("src/main/assets/templates")
        for (path in listOf(
            Templates.LSP_SERVER, Templates.FORMATTING_LANGUAGE, Templates.SNIPPETS_LANGUAGE, Templates.THEME,
            Templates.KEYMAP, Templates.KEYMAP_ENTRY, Templates.TOOLCHAIN, Templates.TOOLCHAIN_ENTRY,
        )) {
            assertTrue(java.io.File(root, path).isFile, path)
        }
        // no placeholder of the old way is left
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            assertTrue(!Regex("(?<!\\{)\\{(name|extension|language|tabSize|insertSpaces)}(?!})").containsMatchIn(file.readText()), file.path)
        }
    }
}
