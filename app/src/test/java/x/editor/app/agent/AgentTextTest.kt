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

package x.editor.app.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTextTest {

    @Test
    fun replacesTheOneOccurrence() {
        assertEquals("a X c", AgentText.replace("a b c", "b", "X", all = false))
    }

    @Test
    fun refusesTextNotThere() {
        val e = assertFailsWith<ToolException> { AgentText.replace("a b c", "d", "X", all = false) }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test
    fun refusesTextThereMoreThanOnceUnlessAll() {
        val e = assertFailsWith<ToolException> { AgentText.replace("x x x", "x", "y", all = false) }
        assertTrue(e.message!!.contains("3 times"))
        assertEquals("y y y", AgentText.replace("x x x", "x", "y", all = true))
    }

    @Test
    fun refusesEmptyOrUnchanged() {
        assertFailsWith<ToolException> { AgentText.replace("abc", "", "x", all = false) }
        assertFailsWith<ToolException> { AgentText.replace("abc", "b", "b", all = false) }
    }

    @Test
    fun matchesAcrossWindowsLineEnds() {
        val text = "int a;\r\nint b;\r\n"
        assertEquals("int a;\r\nlong b;\r\nlong c;\r\n", AgentText.replace(text, "int a;\nint b;", "int a;\nlong b;\nlong c;", all = false))
    }

    @Test
    fun numbersLines() {
        val lines = AgentText.lines("one\r\ntwo\nthree")
        assertEquals(listOf("one", "two", "three"), lines)
        assertEquals("     2\ttwo\n     3\tthree\n", AgentText.numbered(lines, 2, 3))
    }

    @Test
    fun globs() {
        val kotlin = AgentText.globRegex("*.kt")
        assertTrue(kotlin.matches("Main.kt"))
        assertFalse(kotlin.matches("Main.kts"))
        val c = AgentText.globRegex("*.{c,h}")
        assertTrue(c.matches("a.c") && c.matches("a.H"))
        assertFalse(c.matches("a.cpp"))
        assertTrue(AgentText.globRegex("a?.txt").matches("ab.txt"))
    }

    @Test
    fun clipsLongTextKeepingBothEnds() {
        val text = "a".repeat(100) + "z".repeat(100)
        val clipped = AgentText.clip(text, 50)
        assertTrue(clipped.startsWith("aaaaaaaaaa"))
        assertTrue(clipped.endsWith("z".repeat(40)))
        assertTrue(clipped.contains("150 characters left out"))
        assertEquals("short", AgentText.clip("short", 50))
    }

    @Test
    fun diffsOldThenNew() {
        assertEquals("- a\n- b\n+ c", AgentText.diff("a\nb", "c"))
        assertEquals("+ x", AgentText.diff("", "x"))
    }

    @Test
    fun pathsStayInTheWorkspace() {
        val root = File("/storage/emulated/0/proj")
        assertEquals(File("/storage/emulated/0/proj/src/a.c"), Workspace.resolveIn(root, "src/a.c"))
        assertEquals(root, Workspace.resolveIn(root, ""))
        assertEquals(File("/storage/emulated/0/proj/b.c"), Workspace.resolveIn(root, "src/../b.c"))
        assertEquals(File("/storage/emulated/0/proj/x"), Workspace.resolveIn(root, "/storage/emulated/0/proj/x"))
        assertFailsWith<ToolException> { Workspace.resolveIn(root, "../other/a.c") }
        assertFailsWith<ToolException> { Workspace.resolveIn(root, "/etc/hosts") }
        // a sibling whose name begins with the root's is not inside it
        assertFailsWith<ToolException> { Workspace.resolveIn(root, "/storage/emulated/0/proj2/a.c") }
    }
}
