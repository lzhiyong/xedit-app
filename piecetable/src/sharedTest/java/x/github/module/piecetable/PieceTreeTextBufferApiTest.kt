/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.piecetable

import kotlin.test.*
import x.github.module.piecetable.common.EndOfLine
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * The public PieceTreeTextBuffer API: applyEdits, undo data, range queries, CharSequence.
 */
class PieceTreeTextBufferApiTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    private fun edit(
        startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, text: String?
    ) = SingleEditOperation(Range(startLine, startColumn, endLine, endColumn), text)

    private fun PieceTreeTextBuffer.rawContent() = this.getPieceTree().getLinesRawContent()

    // ----------------------------------------------------------- applyEdits

    @Test
    fun applyEditsInsert() {
        val buffer = TestSupport.buildBuffer("Hello World")
        buffer.applyEdits(listOf(edit(1, 6, 1, 6, " there")), false, false)
        assertEquals("Hello there World", buffer.rawContent())
    }

    @Test
    fun applyEditsDelete() {
        val buffer = TestSupport.buildBuffer("Hello World")
        buffer.applyEdits(listOf(edit(1, 6, 1, 12, "")), false, false)
        assertEquals("Hello", buffer.rawContent())
    }

    @Test
    fun applyEditsReplace() {
        val buffer = TestSupport.buildBuffer("Hello World")
        buffer.applyEdits(listOf(edit(1, 1, 1, 6, "Howdy")), false, false)
        assertEquals("Howdy World", buffer.rawContent())
    }

    @Test
    fun applyEditsMultilineReplace() {
        val buffer = TestSupport.buildBuffer("one\ntwo\nthree")
        buffer.applyEdits(listOf(edit(1, 2, 3, 3, "X\nY")), false, false)
        assertEquals("oX\nYree", buffer.rawContent())
        assertEquals(2, buffer.getLineCount())
    }

    @Test
    fun applyEditsMultipleDisjointOperations() {
        val buffer = TestSupport.buildBuffer("aaa\nbbb\nccc")
        buffer.applyEdits(
            listOf(
                edit(1, 1, 1, 1, "1-"),
                edit(3, 4, 3, 4, "-3"),
                edit(2, 2, 2, 3, "X")
            ),
            false,
            false
        )
        assertEquals("1-aaa\nbXb\nccc-3", buffer.rawContent())
    }

    @Test
    fun applyEditsTouchingRangesAreAllowed() {
        val buffer = TestSupport.buildBuffer("abcdef")
        buffer.applyEdits(
            listOf(edit(1, 1, 1, 3, "X"), edit(1, 3, 1, 5, "Y")),
            false,
            false
        )
        assertEquals("XYef", buffer.rawContent())
    }

    @Test
    fun applyEditsOverlappingRangesThrow() {
        val buffer = TestSupport.buildBuffer("abcdef")
        try {
            buffer.applyEdits(
                listOf(edit(1, 1, 1, 4, "X"), edit(1, 3, 1, 6, "Y")),
                false,
                false
            )
            fail("overlapping edit ranges must be rejected")
        } catch (expected: Throwable) {
            assertTrue(expected.message?.contains("Overlapping") == true)
        }
    }

    @Test
    fun applyEditsNormalizesEolOfInsertedText() {
        val buffer = TestSupport.buildBuffer("a\r\nb\r\nc") // EOL = \r\n
        assertEquals("\r\n", buffer.getEOL())
        buffer.applyEdits(listOf(edit(1, 2, 1, 2, "X\nY")), false, false)
        assertEquals("aX\r\nY\r\nb\r\nc", buffer.rawContent())
        assertEquals(4, buffer.getLineCount())
    }

    @Test
    fun applyEditsReportsContentChanges() {
        val buffer = TestSupport.buildBuffer("hello\nworld")
        val result = buffer.applyEdits(listOf(edit(2, 1, 2, 6, "WORLD")), false, false)

        assertEquals(1, result.changes.size)
        val change = result.changes[0]
        assertEquals(6, change.rangeOffset)
        assertEquals(5, change.rangeLength)
        assertEquals("WORLD", change.text)
        assertEquals(Range(2, 1, 2, 6), change.range)
    }

    @Test
    fun applyEditsWithoutUndoReturnsNoReverseEdits() {
        val buffer = TestSupport.buildBuffer("hello")
        val result = buffer.applyEdits(listOf(edit(1, 6, 1, 6, "!")), false, false)
        assertTrue(result.reverseEdits == null || result.reverseEdits!!.isEmpty())
        assertNull(result.trimAutoWhitespaceLineNumbers)
    }

    // ----------------------------------------------------------- undo and redo data

    @Test
    fun reverseEditsUndoAnInsert() {
        val buffer = TestSupport.buildBuffer("hello world")
        val result = buffer.applyEdits(listOf(edit(1, 6, 1, 6, " there")), false, true)
        assertEquals("hello there world", buffer.rawContent())

        val reverse = result.reverseEdits
        assertNotNull(reverse)
        buffer.applyEdits(
            reverse!!.map { SingleEditOperation(it.range, it.text) },
            false,
            false
        )
        assertEquals("hello world", buffer.rawContent())
    }

    @Test
    fun reverseEditsUndoAMultilineReplace() {
        val original = "one\ntwo\nthree\nfour"
        val buffer = TestSupport.buildBuffer(original)
        val result = buffer.applyEdits(listOf(edit(2, 1, 3, 6, "X\nY\nZ")), false, true)
        assertEquals("one\nX\nY\nZ\nfour", buffer.rawContent())

        buffer.applyEdits(
            result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) },
            false,
            false
        )
        assertEquals(original, buffer.rawContent())
    }

    @Test
    fun reverseEditsUndoMultipleOperations() {
        val original = "aaa\nbbb\nccc"
        val buffer = TestSupport.buildBuffer(original)
        val result = buffer.applyEdits(
            listOf(
                edit(1, 1, 1, 2, "X"),
                edit(2, 1, 2, 4, ""),
                edit(3, 4, 3, 4, "!!")
            ),
            false,
            true
        )
        assertEquals("Xaa\n\nccc!!", buffer.rawContent())

        buffer.applyEdits(
            result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) },
            false,
            false
        )
        assertEquals(original, buffer.rawContent())
    }

    @Test
    fun textChangeCarriesOldAndNewText() {
        val buffer = TestSupport.buildBuffer("hello")
        val result = buffer.applyEdits(listOf(edit(1, 1, 1, 6, "bye")), false, true)
        val change = result.reverseEdits!![0].textChange
        assertEquals("hello", change.oldText)
        assertEquals("bye", change.newText)
        assertEquals(0, change.oldPosition)
        assertEquals(5, change.oldLength)
        assertEquals(3, change.newLength)
    }

    // ----------------------------------------------------------- ranges and lines

    @Test
    fun getValueInRangeWithExplicitEol() {
        val buffer = TestSupport.buildBuffer("a\nb\nc")
        val whole = Range(1, 1, 3, 2)
        assertEquals("a\nb\nc", buffer.getValueInRange(whole))
        assertEquals("a\r\nb\r\nc", buffer.getValueInRange(whole, EndOfLine.CRLF))
        assertEquals("a\nb\nc", buffer.getValueInRange(whole, EndOfLine.LF))
        assertEquals("b", buffer.getValueInRange(2, 1, 2, 2))
    }

    @Test
    fun getValueLengthAndCharacterCount() {
        val buffer = TestSupport.buildBuffer("abc\ndefg")
        assertEquals(0, buffer.getValueLengthInRange(Range(1, 1, 1, 1)))
        assertEquals(2, buffer.getValueLengthInRange(Range(1, 2, 1, 4)))
        assertEquals(5, buffer.getValueLengthInRange(Range(1, 2, 2, 3)))
        // for plain ASCII the character count equals the length
        assertEquals(5, buffer.getCharacterCountInRange(Range(1, 2, 2, 3)))
    }

    @Test
    fun lineColumnHelpers() {
        val buffer = TestSupport.buildBuffer("  abc  \n\nxy")
        assertEquals(1, buffer.getLineMinColumn(1))
        assertEquals(8, buffer.getLineMaxColumn(1))
        assertEquals(3, buffer.getLineFirstNonWhitespaceColumn(1))
        assertEquals(6, buffer.getLineLastNonWhitespaceColumn(1))
        assertEquals(0, buffer.getLineFirstNonWhitespaceColumn(2))
        assertEquals(0, buffer.getLineLastNonWhitespaceColumn(2))
        assertEquals(1, buffer.getLineMaxColumn(2))
        assertEquals(3, buffer.getLineMaxColumn(3))
    }

    @Test
    fun getRangeAtAndOffsetAt() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        assertEquals(Range(1, 2, 2, 2), buffer.getRangeAt(1, 4))
        assertEquals(4, buffer.getOffsetAt(2, 1))
        assertEquals(4, buffer.getOffsetAt(buffer.getPositionAt(4)))
    }

    // ----------------------------------------------------------- content flags

    @Test
    fun flagsAreUpdatedAfterEdits() {
        val buffer = TestSupport.buildBuffer("plain ascii")
        assertFalse(buffer.mightContainNonBasicASCII())
        assertFalse(buffer.mightContainRTL())

        buffer.applyEdits(listOf(edit(1, 1, 1, 1, "😍")), false, false)
        assertTrue(buffer.mightContainNonBasicASCII())

        buffer.applyEdits(listOf(edit(1, 1, 1, 1, "זוהי")), false, false)
        assertTrue(buffer.mightContainRTL())
    }

    // ----------------------------------------------------------- CharSequence

    @Test
    fun charSequenceContractForAscii() {
        val text = "hello\nworld"
        val buffer = TestSupport.buildBuffer(text)

        assertEquals(text.length, buffer.length)
        for (i in text.indices) {
            assertEquals(text[i], buffer[i], "index=$i")
        }
        assertEquals("hello", buffer.subSequence(0, 5).toString())
        assertEquals("world", buffer.subSequence(6, 11).toString())
        assertEquals(text, buffer.toString())
    }

    @Test
    fun readPiecesContentMatchesToString() {
        val buffer = TestSupport.buildBuffer(listOf("abc\n", "def\n", "ghi"))
        buffer.applyEdits(listOf(edit(2, 2, 2, 3, "X")), false, false)

        val sb = StringBuilder()
        buffer.readPiecesContent { sb.append(it) }
        assertEquals(buffer.toString(), sb.toString())
        assertEquals("abc\ndXf\nghi", sb.toString())
    }

    @Test
    fun buffersWithDifferentChunkingAreEqual() {
        val a = TestSupport.buildBuffer(listOf("abc\ndef"))
        val b = TestSupport.buildBuffer(listOf("ab", "c\nde", "f"))
        val c = TestSupport.buildBuffer(listOf("abc\ndeg"))

        assertTrue(a.contentEquals(b))
        assertFalse(a.contentEquals(c))
    }

    @Test
    fun setEolOnBufferKeepsContent() {
        val buffer = TestSupport.buildBuffer("a\nb\nc")
        buffer.setEOL("\r\n")
        assertEquals("\r\n", buffer.getEOL())
        assertEquals(3, buffer.getLineCount())
        assertEquals("a\r\nb\r\nc", buffer.toString())
        assertEquals("b", buffer.getLineContent(2))
    }
}
