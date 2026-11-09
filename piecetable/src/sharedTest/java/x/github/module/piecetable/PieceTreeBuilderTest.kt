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
import x.github.module.piecetable.common.Strings

/**
 * PieceTreeTextBufferBuilder: chunked input, BOM, EOL sniffing and normalization,
 * surrogate pairs split across chunks.
 */
class PieceTreeBuilderTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    private fun assertFactory(
        text: String,
        expectEOL: String,
        expectNonBasicASCII: Boolean,
        expectRTL: Boolean
    ) {
        val buffer = TestSupport.buildBuffer(text)
        assertEquals(expectNonBasicASCII, buffer.mightContainNonBasicASCII(), "mightContainNonBasicASCII")
        assertEquals(expectRTL, buffer.mightContainRTL(), "mightContainRTL")
        assertEquals(expectEOL, buffer.getEOL(), "EOL")
    }

    // ----------------------------------------------------------- EOL sniffing

    @Test
    fun emptyInputProducesOneEmptyLine() {
        val buffer = TestSupport.buildBuffer("")
        assertEquals(1, buffer.getLineCount())
        assertEquals(0, buffer.length)
        assertEquals("", buffer.getLineContent(1))
        assertEquals("\n", buffer.getEOL())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    @Test
    fun eolDetection() {
        assertFactory("", "\n", false, false)
        assertFactory("Hello world", "\n", false, false)
        assertFactory("Hello world\nHow are you?", "\n", false, false)
        // CRLF only wins when more than half of the line endings are \r\n
        assertFactory("Hello world\r\nHow are you?\nIs everything good?", "\n", false, false)
        assertFactory("Hello world\r\nHow are you?\r\nIs everything good?\nnope", "\r\n", false, false)
        assertFactory("a\r\nb\r\nc\r\n", "\r\n", false, false)
    }

    @Test
    fun loneCarriageReturnIsTreatedAsLineBreak() {
        val tree = TestSupport.buildTree("a\rb\rc", normalizeEOL = false)
        assertEquals(3, tree.getLineCount())
        assertEquals("a", tree.getLineContent(1))
        assertEquals("b", tree.getLineContent(2))
        assertEquals("c", tree.getLineContent(3))
    }

    // ----------------------------------------------------------- BOM

    @Test
    fun bomIsStrippedAndKept() {
        val buffer = TestSupport.buildBuffer(Strings.UTF8_BOM_CHARACTER + "Hello world!")
        assertEquals(Strings.UTF8_BOM_CHARACTER, buffer.getBOM())
        assertEquals("Hello world!", buffer.getLineContent(1))
        assertEquals("Hello world!".length, buffer.length)
    }

    @Test
    fun bomOnlyAppearsOnTheFirstChunk() {
        // a BOM in the second chunk is part of the content and must not be stripped
        val buffer = TestSupport.buildBuffer(listOf("abc", Strings.UTF8_BOM_CHARACTER + "def"))
        assertEquals("", buffer.getBOM())
        assertEquals("abc" + Strings.UTF8_BOM_CHARACTER + "def", buffer.getLineContent(1))
    }

    @Test
    fun bomWithEmoji() {
        assertFactory(Strings.UTF8_BOM_CHARACTER + "Hello 😍 world!", "\n", true, false)
    }

    // ----------------------------------------------------------- RTL / ASCII

    @Test
    fun rtlDetection() {
        assertFactory("Hello world!זוהי עובדה מבוססת שדעתו", "\n", true, true)
        assertFactory("Hello world!זוהי \nעובדה מבוססת שדעתו", "\n", true, true)
    }

    @Test
    fun asciiDetection() {
        assertFactory("Hello world!!\nHow do you do?", "\n", false, false)
        assertFactory("Hello world!!\nHow do you do?Züricha📚📚b", "\n", true, false)
        // a tab is still basic ASCII
        assertFactory("col1\tcol2\tcol3", "\n", false, false)
    }

    @Test
    fun unusualLineTerminators() {
        val buffer = TestSupport.buildBuffer("line1\u2028line2\u2029line3")
        assertTrue(buffer.mightContainUnusualLineTerminators())
        buffer.resetMightContainUnusualLineTerminators()
        assertFalse(buffer.mightContainUnusualLineTerminators())
        // LS / PS are not line breaks, the whole text stays on one line
        assertEquals(1, buffer.getLineCount())
    }

    // ----------------------------------------------------------- chunk boundaries

    @Test
    fun crlfSplitAcrossChunks() {
        // a chunk ending with "\r" has to be held back until the next chunk's "\n" arrives
        val buffer = TestSupport.buildBuffer(listOf("abc\r", "\ndef"))
        assertEquals("\r\n", buffer.getEOL())
        assertEquals(2, buffer.getLineCount())
        assertEquals("abc", buffer.getLineContent(1))
        assertEquals("def", buffer.getLineContent(2))
        assertEquals("abc\r\ndef", buffer.getPieceTree().getLinesRawContent())
    }

    @Test
    fun surrogatePairSplitAcrossChunks() {
        val high = "\uD83D"
        val low = "\uDE0D" // 😍
        val buffer = TestSupport.buildBuffer(listOf("a$high", "${low}b"))
        assertEquals("a$high${low}b", buffer.getPieceTree().getLinesRawContent())
        assertEquals(1, buffer.getLineCount())
        assertEquals(4, buffer.length) // counted in UTF-16 code units
        assertTrue(buffer.mightContainNonBasicASCII())
    }

    @Test
    fun manySmallChunksProduceTheSameDocument() {
        val text = "line1\nline2\nline3\nline4\nline5"
        val chunks = text.chunked(3)
        val buffer = TestSupport.buildBuffer(chunks)
        assertEquals(text, buffer.getPieceTree().getLinesRawContent())
        assertEquals(5, buffer.getLineCount())
        TestSupport.assertLinesContent(text, buffer.getPieceTree())
    }

    // ----------------------------------------------------------- normalization

    @Test
    fun normalizeEolToLf() {
        val tree = TestSupport.buildTree("a\r\nb\nc", normalizeEOL = true, eol = EndOfLine.LF)
        assertEquals("\n", tree.getEOL())
        assertEquals("a\nb\nc", tree.getLinesRawContent())
        assertEquals(3, tree.getLineCount())
    }

    @Test
    fun normalizeEolToCrlf() {
        val tree = TestSupport.buildTree("a\r\nb\r\nc\nd", normalizeEOL = true, eol = EndOfLine.CRLF)
        assertEquals("\r\n", tree.getEOL())
        assertEquals("a\r\nb\r\nc\r\nd", tree.getLinesRawContent())
        assertEquals(4, tree.getLineCount())
    }

    @Test
    fun withoutNormalizationMixedEolIsPreserved() {
        val text = "a\r\nb\nc\rd"
        val tree = TestSupport.buildTree(text, normalizeEOL = false)
        assertEquals(text, tree.getLinesRawContent())
        assertEquals(4, tree.getLineCount())
        assertEquals("a", tree.getLineContent(1))
        assertEquals("b", tree.getLineContent(2))
        assertEquals("c", tree.getLineContent(3))
        assertEquals("d", tree.getLineContent(4))
    }

    @Test
    fun builderAcceptsCharSequenceInConstructor() {
        val buffer = PieceTreeTextBufferBuilder("hello\nworld").build()
        assertEquals(2, buffer.getLineCount())
        assertEquals("hello", buffer.getLineContent(1))
        assertEquals("world", buffer.getLineContent(2))
    }

    @Test
    fun trailingNewLineProducesTrailingEmptyLine() {
        val tree = TestSupport.buildTree("a\nb\n")
        assertEquals(3, tree.getLineCount())
        assertEquals("", tree.getLineContent(3))
        assertEquals(4, tree.getLength())
    }
}
