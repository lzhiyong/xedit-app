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
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range

/**
 * PieceTreeBase: insert, delete, offset/position mapping, line access, CRLF, snapshots.
 */
class PieceTreeEditingTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    // ----------------------------------------------------------- basic editing

    @Test
    fun insertAtHeadMiddleTail() {
        val tree = TestSupport.buildTree("")
        tree.insert(0, "world", true)
        tree.insert(0, "hello ", true)
        tree.insert(tree.getLength(), "!", true)
        tree.insert(6, "big ", true)

        assertEquals("hello big world!", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun deleteHeadTailMiddleAndWholeNode() {
        val tree = TestSupport.buildTree("0123456789")

        tree.delete(0, 1)
        assertEquals("123456789", tree.getLinesRawContent())

        tree.delete(8, 1)
        assertEquals("12345678", tree.getLinesRawContent())

        tree.delete(3, 2)
        assertEquals("123678", tree.getLinesRawContent())

        tree.delete(0, tree.getLength())
        assertEquals("", tree.getLinesRawContent())
        assertEquals(1, tree.getLineCount())
        assertEquals(0, tree.getLength())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun deleteAcrossMultipleNodes() {
        val tree = TestSupport.buildTree(listOf("abc", "def", "ghi", "jkl"))
        assertEquals("abcdefghijkl", tree.getLinesRawContent())

        tree.delete(2, 8) // spans 4 pieces
        assertEquals("abkl", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun deleteWithZeroOrNegativeCountIsNoop() {
        val tree = TestSupport.buildTree("abc")
        tree.delete(1, 0)
        tree.delete(1, -5)
        assertEquals("abc", tree.getLinesRawContent())
    }

    @Test
    fun insertAndDeleteKeepLineMetadataInSync() {
        val tree = TestSupport.buildTree("line1\nline2\nline3")
        assertEquals(3, tree.getLineCount())

        tree.insert(tree.getOffsetAt(2, 1), "inserted\n", true)
        assertEquals(4, tree.getLineCount())
        assertEquals("inserted", tree.getLineContent(2))
        assertEquals("line2", tree.getLineContent(3))

        tree.delete(tree.getOffsetAt(2, 1), "inserted\n".length)
        assertEquals(3, tree.getLineCount())
        assertEquals("line1\nline2\nline3", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }

    // ----------------------------------------------------------- positions

    @Test
    fun offsetAndPositionRoundTrip() {
        val text = "hello\nbrave\nnew\nworld\n"
        val tree = TestSupport.buildTree(text)

        for (offset in 0..text.length) {
            val pos = tree.getPositionAt(offset)
            assertEquals(offset, tree.getOffsetAt(pos.lineNumber, pos.column), "offset=$offset")
        }
        TestSupport.assertLineStarts(text, tree)
    }

    @Test
    fun positionAtKnownOffsets() {
        val tree = TestSupport.buildTree("abc\ndefg\nhi")
        assertEquals(Position(1, 1), tree.getPositionAt(0))
        assertEquals(Position(1, 4), tree.getPositionAt(3))
        assertEquals(Position(2, 1), tree.getPositionAt(4))
        assertEquals(Position(3, 1), tree.getPositionAt(9))
        assertEquals(Position(3, 3), tree.getPositionAt(11))
    }

    @Test
    fun lineLengthAndContent() {
        val tree = TestSupport.buildTree("abc\ndefg\n\nhi")
        assertEquals(4, tree.getLineCount())
        assertEquals(3, tree.getLineLength(1))
        assertEquals(4, tree.getLineLength(2))
        assertEquals(0, tree.getLineLength(3))
        assertEquals(2, tree.getLineLength(4))
        assertEquals("defg", tree.getLineContent(2))
        assertEquals("", tree.getLineContent(3))
        assertEquals("defg\n", tree.getLineContentWithEOL(2))
    }

    @Test
    fun getValueInRangeAcrossLinesAndNodes() {
        val tree = TestSupport.buildTree(listOf("abc\nde", "f\nghi"))
        assertEquals("abc\ndef\nghi", tree.getLinesRawContent())
        assertEquals("", tree.getValueInRange(Range(1, 2, 1, 2)))
        assertEquals("bc", tree.getValueInRange(Range(1, 2, 1, 4)))
        assertEquals("bc\ndef\ng", tree.getValueInRange(Range(1, 2, 3, 2)))
        // across a piece boundary
        assertEquals("def", tree.getValueInRange(Range(2, 1, 2, 4)))
    }

    @Test
    fun getCharCodeAndLineCharCode() {
        val tree = TestSupport.buildTree("AB\nCD")
        assertEquals('A'.code, tree.getCharCode(0))
        assertEquals('B'.code, tree.getCharCode(1))
        assertEquals('\n'.code, tree.getCharCode(2))
        assertEquals('C'.code, tree.getCharCode(3))
        assertEquals('A'.code, tree.getLineCharCode(1, 0))
        assertEquals('D'.code, tree.getLineCharCode(2, 1))
    }

    @Test
    fun getNearestChunkReturnsRemainderOfPiece() {
        val tree = TestSupport.buildTree("012345678")
        tree.insert(3, "ABC", true)
        assertEquals("012ABC345678", tree.getLineContent(1))
        assertEquals("ABC", tree.getNearestChunk(3))
        assertEquals("345678", tree.getNearestChunk(6))
    }

    // ----------------------------------------------------------- CRLF

    @Test
    fun insertingLfAfterCrMergesIntoCrlf() {
        val tree = TestSupport.buildTree("a\rb", normalizeEOL = false)
        assertEquals(2, tree.getLineCount())

        tree.insert(2, "\n") // "a\r" + "\n" => "a\r\nb"
        assertEquals("a\r\nb", tree.getLinesRawContent())
        assertEquals(2, tree.getLineCount())
        assertEquals("a", tree.getLineContent(1))
        assertEquals("b", tree.getLineContent(2))
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun deletingCrFromCrlfKeepsLineCount() {
        val tree = TestSupport.buildTree("a\r\nb", normalizeEOL = false)
        assertEquals(2, tree.getLineCount())

        tree.delete(1, 1) // drop the \r
        assertEquals("a\nb", tree.getLinesRawContent())
        assertEquals(2, tree.getLineCount())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun insertingBetweenCrAndLfSplitsTheLine() {
        val tree = TestSupport.buildTree("a\r\nb", normalizeEOL = false)
        tree.insert(2, "X") // a \r X \n b
        assertEquals("a\rX\nb", tree.getLinesRawContent())
        assertEquals(3, tree.getLineCount())
        TestSupport.assertTreeInvariants(tree)
    }

    // ----------------------------------------------------------- large text and chunking

    @Test
    fun insertTextLargerThanAverageBufferSize() {
        val tree = TestSupport.buildTree("head\ntail")
        val big = "x".repeat(70_000) // > AverageBufferSize(65535), gets split into several pieces
        tree.insert(5, big, true)

        assertEquals(9 + big.length, tree.getLength())
        assertEquals(2, tree.getLineCount())
        assertEquals("head", tree.getLineContent(1))
        assertEquals(big + "tail", tree.getLineContent(2))
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun manyLinesDocument() {
        val sb = StringBuilder()
        for (i in 0 until 20_000) {
            sb.append("line ").append(i).append('\n')
        }
        val text = sb.toString()
        val tree = TestSupport.buildTree(text)

        assertEquals(20_001, tree.getLineCount())
        assertEquals("line 0", tree.getLineContent(1))
        assertEquals("line 9999", tree.getLineContent(10_000))
        assertEquals("line 19999", tree.getLineContent(20_000))
        assertEquals("", tree.getLineContent(20_001))
        assertEquals(text.length, tree.getLength())
    }

    // ----------------------------------------------------------- other API

    @Test
    fun equalComparesContentNotStructure() {
        val a = TestSupport.buildTree("abc\ndef")
        val b = TestSupport.buildTree(listOf("ab", "c\nd", "ef"))
        val c = TestSupport.buildTree("abc\ndeg")
        val d = TestSupport.buildTree("abc\ndefg")

        assertTrue(a.equal(b))
        assertFalse(a.equal(c))
        assertFalse(a.equal(d))
    }

    @Test
    fun setEolRewritesTheWholeDocument() {
        val tree = TestSupport.buildTree("a\nb\nc")
        assertEquals("\n", tree.getEOL())

        tree.setEOL("\r\n")
        assertEquals("\r\n", tree.getEOL())
        assertEquals("a\r\nb\r\nc", tree.getLinesRawContent())
        assertEquals(3, tree.getLineCount())
        assertEquals("b", tree.getLineContent(2))

        tree.setEOL("\n")
        assertEquals("a\nb\nc", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun snapshotIsImmutableAgainstLaterEdits() {
        val tree = TestSupport.buildTree("abc\ndef")
        val snapshot = tree.createSnapshot("")

        tree.insert(0, "XYZ", true)
        tree.delete(5, 2)

        assertEquals("abc\ndef", TestSupport.readSnapshot(snapshot))
    }

    @Test
    fun snapshotKeepsBom() {
        val tree = TestSupport.buildTree("abc")
        assertEquals("\uFEFFabc", TestSupport.readSnapshot(tree.createSnapshot("\uFEFF")))
    }
}
