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

package x.editor.app.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import x.github.module.lsp.LspFormattingOptions
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/** The preview's view of a document (SnapshotBuffer) and what is built from it (FormattedText). */
class SnapshotBufferTest {

    private fun snapshotOf(vararg chunks: String): SnapshotBuffer {
        val builder = PieceTreeTextBufferBuilder()
        chunks.forEach { builder.acceptChunk(it) }
        return SnapshotBuffer.of(builder.build().createSnapshot())
    }

    @Test
    fun linesOfATextInPieces() {
        // a line break across two chunks
        val buffer = snapshotOf("one\r", "\ntwo\r\n", "three")
        assertEquals(3, buffer.getLineCount())
        assertEquals("one", buffer.getLineContent(1))
        assertEquals("two", buffer.getLineContent(2))
        assertEquals("three", buffer.getLineContent(3))
        assertEquals("two\r\n", buffer.getLineContentWithEOL(2))
        assertEquals(5, buffer.getLineLength(3))
        assertEquals(6, buffer.getLineMaxColumn(3))
        assertEquals('t'.code, buffer.getLineCharCode(2, 0))
    }

    @Test
    fun offsetsAndPositions() {
        val buffer = snapshotOf("ab\ncd\n")
        assertEquals(3, buffer.getLineCount())
        assertEquals(3, buffer.getOffsetAt(2, 1))
        // a column past the end of the line: its end
        assertEquals(5, buffer.getOffsetAt(2, 99))
        assertEquals(listOf(2, 2), buffer.getPositionAt(4).let { listOf(it.lineNumber, it.column) })
        assertEquals(listOf(3, 1), buffer.getPositionAt(6).let { listOf(it.lineNumber, it.column) })
        assertEquals("b\nc", buffer.getValueInRange(Range(1, 2, 2, 2)))
        assertEquals(1, buffer.find("cd", Range(1, 1, 3, 1), 10) { false }.size)
    }

    @Test
    fun anEmptyTextHasOneEmptyLine() {
        val buffer = snapshotOf()
        assertEquals(1, buffer.getLineCount())
        assertEquals("", buffer.getLineContent(1))
        assertEquals(0, buffer.length)
    }

    @Test
    fun itCannotBeEdited() {
        assertFailsWith<UnsupportedOperationException> {
            snapshotOf("x").applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "y")), false)
        }
    }

    @Test
    fun aServersEditsMadeOnACopy() {
        val source = snapshotOf("int a;\n  int b;\nint c;")
        val result = FormattedText.withEdits(
            source,
            listOf(
                // given out of order, as a server may
                Range(3, 7, 3, 7) to "\n",
                Range(2, 1, 2, 3) to "    ",
            )
        )
        assertEquals("int a;\n    int b;\nint c;\n", result.buffer.toString())
        assertEquals(2, result.changes)
        // the source is as it was
        assertEquals("int a;\n  int b;\nint c;", source.getText())
    }

    @Test
    fun theRulesAloneOnASnapshot() = runBlocking {
        val options = LspFormattingOptions(tabSize = 4, insertSpaces = true, trimTrailingWhitespace = true, insertFinalNewline = true, trimFinalNewlines = true)
        val result = FormattedText.simulated(snapshotOf("a {\r\n  b \r\n}\r\n\r\n"), options, 4)
        // the line breaks of the document are kept
        assertEquals("a {\r\n    b\r\n}\r\n", result.buffer.toString())
    }
}
