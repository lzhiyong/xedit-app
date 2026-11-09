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

import x.editor.app.agent.AgentCodeBlocks.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentCodeBlocksTest {

    @Test
    fun textAlone() {
        assertEquals(listOf(Segment.Text("Hello **there**")), AgentCodeBlocks.split("Hello **there**"))
    }

    @Test
    fun textCodeText() {
        val text = "Save it as `a.py`:\n\n```python\nimport sys\nprint(sys.argv)\n```\n\nThen run it."
        assertEquals(
            listOf(
                Segment.Text("Save it as `a.py`:"),
                Segment.Code("python", "import sys\nprint(sys.argv)", closed = true),
                Segment.Text("Then run it."),
            ),
            AgentCodeBlocks.split(text),
        )
    }

    @Test
    fun blockStillStreamingIn() {
        assertEquals(
            listOf(Segment.Text("Here:"), Segment.Code("kotlin", "fun main() {", closed = false)),
            AgentCodeBlocks.split("Here:\n```kotlin\nfun main() {"),
        )
    }

    @Test
    fun aFenceWithAnInfoStringDoesNotClose() {
        val text = "````md\n```js\nx\n```\n````"
        assertEquals(listOf(Segment.Code("md", "```js\nx\n```", closed = true)), AgentCodeBlocks.split(text))
    }

    @Test
    fun indentedFenceInAListItem() {
        val text = "- run:\n  ```sh\n  ls -l\n    cd x\n  ```"
        assertEquals(
            listOf(Segment.Text("- run:"), Segment.Code("sh", "ls -l\n  cd x", closed = true)),
            AgentCodeBlocks.split(text),
        )
    }

    @Test
    fun backticksInALineAreNotAFence() {
        assertEquals(listOf(Segment.Text("```a``` b")), AgentCodeBlocks.split("```a``` b"))
    }

    @Test
    fun fenceGluedToTheTextBefore() {
        val text = "not asking me to change files.```c\n#include <stdio.h>\nint x;\n```"
        assertEquals(
            listOf(
                Segment.Text("not asking me to change files."),
                Segment.Code("c", "#include <stdio.h>\nint x;", closed = true),
            ),
            AgentCodeBlocks.split(text),
        )
    }

    @Test
    fun fenceWithoutALanguageGluedAfterAColon() {
        assertEquals(
            listOf(Segment.Text("Run this:"), Segment.Code("", "ls", closed = true)),
            AgentCodeBlocks.split("Run this:```\nls\n```"),
        )
    }

    @Test
    fun closingFenceGluedToTheCodeOrToTheTextAfter() {
        assertEquals(
            listOf(Segment.Code("kotlin", "fun a() {}", closed = true), Segment.Text("Done")),
            AgentCodeBlocks.split("```kotlin\nfun a() {}```\nDone"),
        )
        assertEquals(
            listOf(Segment.Code("c", "int a;", closed = true), Segment.Text("这样就可以了。")),
            AgentCodeBlocks.split("```c\nint a;\n```这样就可以了。"),
        )
    }

    @Test
    fun backticksAtTheEndOfASentenceAreNotAFence() {
        assertEquals(listOf(Segment.Text("Wrap it in ```.")), AgentCodeBlocks.split("Wrap it in ```."))
        assertEquals(listOf(Segment.Text("Use `a```")), AgentCodeBlocks.split("Use `a```"))
    }

    @Test
    fun fileNamesOfLanguages() {
        assertEquals("snippet.py", AgentCodeBlocks.fileNameOf("py"))
        assertEquals("snippet.python", AgentCodeBlocks.fileNameOf("Python"))
        assertEquals("snippet.sh", AgentCodeBlocks.fileNameOf("shell"))
        assertEquals("snippet.cpp", AgentCodeBlocks.fileNameOf("c++"))
        assertEquals("Makefile", AgentCodeBlocks.fileNameOf("makefile"))
        assertNull(AgentCodeBlocks.fileNameOf(""))
        assertNull(AgentCodeBlocks.fileNameOf("text"))
    }

    @Test
    fun readFileWithoutItsNumbers() {
        val result = AgentText.numbered(listOf("a", "\tb", "c"), 2, 3) + "\n(lines 2-3 of 3)"
        val shown = AgentCodeBlocks.unnumbered(result)
        assertEquals("\tb\nc", shown.code)
        assertEquals(2, shown.firstLine)
        assertEquals("lines 2-3 of 3", shown.note)
    }

    @Test
    fun readFileEmptyIsLeftAsItIs() {
        val shown = AgentCodeBlocks.unnumbered("(a.txt is empty)")
        assertEquals("(a.txt is empty)", shown.code)
        assertNull(shown.firstLine)
    }
}
