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

package x.editor.app.lsp

import x.editor.app.lsp.HoverMarkdown.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Markdown of hovers as text and marks (what clangd and pyright send). */
class HoverMarkdownTest {

    private fun HoverMarkdown.Rendered.marked(style: Style) = marks.filter { it.style == style }.map { text.substring(it.start, it.end) }

    private val clangd = HoverMarkdown.render("""
        ### function `rb_insert`

        ---
        → `void`
        Parameters:
        - `struct rb_root * root`
        - `struct rb_node * node`

        Inserts **node** into the tree, *balanced*.

        ---
        ```cpp
        // In rbtree.c
        static void rb_insert(struct rb_root *root, struct rb_node *node)
        ```
    """.trimIndent())

    @Test
    fun noMarkdownLeft() {
        assertTrue(listOf("###", "```", "**", "`").none { clangd.text.contains(it) }, clangd.text)
        assertTrue(!clangd.text.contains("\n\n\n"))
        assertTrue(clangd.marks.all { it.start >= 0 && it.end <= clangd.text.length && it.start < it.end })
    }

    @Test
    fun blocksAndInlines() {
        assertEquals(listOf("function rb_insert"), clangd.marked(Style.HEADING))
        assertEquals(listOf("rb_insert", "void", "struct rb_root * root", "struct rb_node * node"), clangd.marked(Style.CODE))
        assertTrue(clangd.text.contains("• struct rb_root * root"))
        assertEquals(listOf("node"), clangd.marked(Style.BOLD))
        assertEquals(listOf("balanced"), clangd.marked(Style.ITALIC))
        assertEquals(2, clangd.marked(Style.RULE).size)
    }

    @Test
    fun codeBlocks() {
        assertTrue(clangd.marked(Style.CODE_BLOCK).single().startsWith("// In rbtree.c"))
        assertEquals(listOf("// In rbtree.c"), clangd.marked(Style.COMMENT))
        assertEquals(listOf("static", "struct", "struct"), clangd.marked(Style.KEYWORD))
        assertEquals(listOf("void"), clangd.marked(Style.TYPE))
        assertEquals(listOf("rb_insert"), clangd.marked(Style.FUNCTION))
        val python = HoverMarkdown.render("```python\ndef area(r: float = 1.5) -> float  # the area\n    return \"x\"\n```")
        assertEquals(listOf("# the area"), python.marked(Style.COMMENT))
        assertEquals(listOf("\"x\""), python.marked(Style.STRING))
        assertEquals(listOf("1.5"), python.marked(Style.NUMBER))
        // in C, # starts a preprocessor line, not a comment
        assertTrue(HoverMarkdown.render("```c\n#include <stdio.h>\n```").marked(Style.COMMENT).isEmpty())
    }

    @Test
    fun linksEscapesAndSnakeCase() {
        val r = HoverMarkdown.render("Calls rb_insert_color and [the docs](https://x.org/a). Escaped \\*star\\*.")
        assertEquals("Calls rb_insert_color and the docs. Escaped *star*.", r.text)
        assertEquals(listOf("the docs"), r.marked(Style.LINK))
        assertTrue(r.marked(Style.ITALIC).isEmpty())
    }
}
