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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x.github.module.piecetable.common.Range

/** Commenting a region out and back in (see CommentToggle). */
class CommentToggleTest {

    private val c = CommentToggle.Style("//", "/*", "*/")
    private val css = CommentToggle.Style(null, "/*", "*/")
    private val python = CommentToggle.Style("#", null, null)

    // [text] with the edits of [plan] made (they do not overlap: the last first)
    private fun apply(text: String, plan: CommentToggle.Plan): String {
        val lines = text.split('\n')
        fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
        var result = text
        for ((range, new) in plan.edits.sortedByDescending { offset(it.first.startLine, it.first.startColumn) }) {
            result = result.replaceRange(offset(range.startLine, range.startColumn), offset(range.endLine, range.endColumn), new)
        }
        return result
    }

    private fun toggle(text: String, sl: Int, sc: Int, el: Int, ec: Int, selected: Boolean, style: CommentToggle.Style): Pair<String, CommentToggle.Plan> {
        val lines = text.split('\n')
        val plan = CommentToggle.plan({ lines[it - 1] }, sl, sc, el, ec, selected, style)!!
        return apply(text, plan) to plan
    }

    // the text of [range] in [text]
    private fun selectedIn(text: String, range: Range): String {
        val lines = text.split('\n')
        fun offset(line: Int, column: Int) = lines.take(line - 1).sumOf { it.length + 1 } + column - 1
        return text.substring(offset(range.startLine, range.startColumn), offset(range.endLine, range.endColumn))
    }

    @Test
    fun linesOutAndBackIn() {
        val text = "fun f() {\n    a()\n\n      b()\n}"
        // lines 2 to 4 selected; the blank line is left alone; the mark at the least indentation
        val (out, plan) = toggle(text, 2, 1, 4, 10, true, c)
        assertTrue(plan.commenting)
        assertEquals("fun f() {\n    // a()\n\n    //   b()\n}", out)
        assertEquals("    // a()\n\n    //   b()", selectedIn(out, plan.selection))
        val (back, again) = toggle(out, 2, 1, 4, 14, true, c)
        assertFalse(again.commenting)
        assertEquals(text, back)
    }

    @Test
    fun theLineOfTheCursor() {
        val (out, _) = toggle("x = 1\ny = 2", 2, 3, 2, 3, false, python)
        assertEquals("x = 1\n# y = 2", out)
        val (back, _) = toggle(out, 2, 1, 2, 1, false, python)
        assertEquals("x = 1\ny = 2", back)
    }

    @Test
    fun aSelectionEndingAtTheStartOfALineLeavesThatLine() {
        val (out, _) = toggle("a\nb\nc", 1, 1, 3, 1, true, c)
        assertEquals("// a\n// b\nc", out)
    }

    @Test
    fun mixedLinesAreCommentedOut() {
        val (out, plan) = toggle("// a\nb", 1, 1, 2, 2, true, c)
        assertTrue(plan.commenting)
        assertEquals("// // a\n// b", out)
    }

    @Test
    fun aPartOfALineInABlock() {
        val text = "call(a, b, c)"
        // `b, c` selected
        val (out, plan) = toggle(text, 1, 9, 1, 13, true, c)
        assertEquals("call(a, /* b, c */)", out)
        assertEquals("/* b, c */", selectedIn(out, plan.selection))
        // the selection as it is after: back in
        val (back, again) = toggle(out, plan.selection.startLine, plan.selection.startColumn, plan.selection.endLine, plan.selection.endColumn, true, c)
        assertFalse(again.commenting)
        assertEquals(text, back)
        assertEquals("b, c", selectedIn(back, again.selection))
        // only `b, c` selected inside the marks: back in too
        val (back2, plan2) = toggle(out, 1, 12, 1, 16, true, c)
        assertEquals(text, back2)
        assertEquals("b, c", selectedIn(back2, plan2.selection))
    }

    @Test
    fun noLineCommentsABlockOverLines() {
        val text = "a {\n  color: red;\n}"
        val (out, plan) = toggle(text, 1, 1, 3, 2, true, css)
        assertEquals("/* a {\n  color: red;\n} */", out)
        assertEquals(out, selectedIn(out, plan.selection))
        val (back, _) = toggle(out, 1, 1, 3, 5, true, css)
        assertEquals(text, back)
        // the cursor: its line, without the indentation
        val (line, _) = toggle(text, 2, 4, 2, 4, false, css)
        assertEquals("a {\n  /* color: red; */\n}", line)
    }

    @Test
    fun nothingToDo() {
        assertNull(CommentToggle.plan({ "   " }, 1, 1, 1, 1, false, c))
        assertNull(CommentToggle.plan({ "x" }, 1, 1, 1, 1, false, CommentToggle.Style(null, null, null)))
    }
}
