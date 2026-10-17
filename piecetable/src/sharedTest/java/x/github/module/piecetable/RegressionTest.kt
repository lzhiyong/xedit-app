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
import x.github.module.piecetable.common.IntList
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.Strings

/**
 * Regression tests for the bugs that were fixed. Every case maps to one concrete change,
 * please keep them.
 */
class RegressionTest {

    // no shared mutable state is left; the reset is kept as a cheap safety net
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    // ----------------------------------------------------------- getLinesContent

    /** Before the fix the middle lines of a piece were concatenated into ["a","ab","abc","d"] */
    @Test
    fun getLinesContentDoesNotConcatenateMiddleLines() {
        val buffer = TestSupport.buildBuffer("a\nb\nc\nd")
        assertEquals(listOf("a", "b", "c", "d"), buffer.getLinesContent())
    }

    @Test
    fun getLinesContentMatchesGetLineContent() {
        val text = "line1\nline2\nline3\nline4\nline5"
        val buffer = TestSupport.buildBuffer(text)
        val expect = (1..buffer.getLineCount()).map { buffer.getLineContent(it) }
        assertEquals(expect, buffer.getLinesContent())
    }

    @Test
    fun getLinesContentOnMixedEol() {
        val buffer = TestSupport.buildBuffer("a\r\nb\nc\rd", normalizeEOL = false)
        assertEquals(listOf("a", "b", "c", "d"), buffer.getLinesContent())
    }

    // ----------------------------------------------------------- bounds checking

    /** Before the fix an out of range offset spun in updateTreeMetadata and clobbered the shared NULL (now DETACHED) node */
    @Test
    fun outOfRangeDeleteThrowsInsteadOfHanging() {
        val tree = TestSupport.buildTree("abc")
        try {
            tree.delete(2, 100)
            fail("an out of range delete must throw IndexOutOfBoundsException")
        } catch (expected: IndexOutOfBoundsException) {
            // expected
        }
        assertEquals(3, tree.getLength())
        assertEquals("abc", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun outOfRangeInsertThrowsInsteadOfHanging() {
        val tree = TestSupport.buildTree("abc")
        try {
            tree.insert(100, "x", true)
            fail("an out of range insert must throw IndexOutOfBoundsException")
        } catch (expected: IndexOutOfBoundsException) {
            // expected
        }
        assertEquals(3, tree.getLength())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun negativeOffsetThrows() {
        val tree = TestSupport.buildTree("abc")
        try {
            tree.insert(-1, "x", true)
            fail("a negative offset must throw IndexOutOfBoundsException")
        } catch (expected: IndexOutOfBoundsException) {
            // expected
        }
    }

    /** A stale Range is clamped by applyEdits instead of reaching the tree */
    @Test
    fun staleRangeIsClampedByApplyEdits() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        buffer.applyEdits(
            listOf(SingleEditOperation(Range(1, 1, 99, 99), "X")),
            false,
            false
        )
        assertEquals("X", buffer.toString())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    @Test
    fun insertingEmptyStringIsANoop() {
        val tree = TestSupport.buildTree("abc")
        tree.insert(1, "")
        assertEquals("abc", tree.getLinesRawContent())
    }

    // ----------------------------------------------------------- applyEdits

    /** Before the fix candidates[i - 1] was read before the i > 0 check, always out of bounds */
    @Test
    fun recordTrimAutoWhitespaceDoesNotCrash() {
        val buffer = TestSupport.buildBuffer("\n")
        val result = buffer.applyEdits(
            listOf(
                SingleEditOperation(
                    range = Range(1, 1, 1, 1),
                    text = "  ",
                    identifier = null,
                    forceMoveMarkers = false,
                    isAutoWhitespaceEdit = true
                )
            ),
            recordTrimAutoWhitespace = true,
            computeUndoEdits = false
        )

        assertEquals("  \n", buffer.toString())
        assertTrue(result.changes.isNotEmpty())
        assertNotNull(result.trimAutoWhitespaceLineNumbers)
    }

    // ----------------------------------------------------------- non BMP characters

    /** Before the fix a codePointAt result was never a high surrogate, so emoji counted twice */
    @Test
    fun characterCountTreatsSurrogatePairAsOneCharacter() {
        val buffer = TestSupport.buildBuffer("a\uD83D\uDE0Db") // a😍b
        assertEquals(4, buffer.length)
        assertEquals(3, buffer.getCharacterCountInRange(Range(1, 1, 1, 5)))
    }

    /** Before the fix get(i) returned a Char truncated from a code point */
    @Test
    fun charSequenceGetReturnsUtf16CodeUnits() {
        val text = "a\uD83D\uDE0Db"
        val buffer = TestSupport.buildBuffer(text)
        for (i in text.indices) {
            assertEquals(text[i], buffer[i], "index=$i")
        }
        assertEquals(text, buffer.subSequence(0, text.length).toString())
    }

    /** Before the fix CharArray.joinToString() inserted ", " between the two surrogates */
    @Test
    fun toCharsDoesNotInsertSeparators() {
        assertEquals("\uD83D\uDE0D", Strings.toChars(0x1F60D))
        assertEquals("\uFEFF", Strings.toChars(0xFEFF))
    }

    /** Text larger than AverageBufferSize must not be split in the middle of a surrogate pair */
    @Test
    fun largeInsertKeepsSurrogatePairsIntact() {
        val tree = TestSupport.buildTree("")
        val text = "a".repeat(65534) + "\uD83D\uDE0D" + "b".repeat(10)
        tree.insert(0, text, true)
        assertEquals(text, tree.getLinesRawContent())
    }

    // ----------------------------------------------------------- builder edge cases

    /** Before the fix the chunk was empty after stripping the BOM and codePointAt(-1) threw */
    @Test
    fun bomOnlyInputBuildsEmptyDocument() {
        val builder = PieceTreeTextBufferBuilder()
        builder.acceptChunk(Strings.UTF8_BOM_CHARACTER)
        val buffer = builder.build()

        assertEquals(Strings.UTF8_BOM_CHARACTER, buffer.getBOM())
        assertEquals(0, buffer.length)
        assertEquals(1, buffer.getLineCount())
    }

    /** Before the fix the held back \r was written twice and the content became "\r\r" */
    @Test
    fun singleCarriageReturnInputIsNotDuplicated() {
        val builder = PieceTreeTextBufferBuilder()
        builder.acceptChunk("\r")
        val buffer = builder.build(normalizeEOL = false)

        assertEquals("\r", buffer.getPieceTree().getLinesRawContent())
        assertEquals(2, buffer.getLineCount())
    }

    /** Same as above, a lone high surrogate is held back too */
    @Test
    fun singleHighSurrogateInputIsNotDuplicated() {
        val builder = PieceTreeTextBufferBuilder()
        builder.acceptChunk("\uD83D")
        val buffer = builder.build(normalizeEOL = false)

        assertEquals(1, buffer.length)
        assertEquals("\uD83D", buffer.getPieceTree().getLinesRawContent())
    }

    // ----------------------------------------------------------- IntList

    @Test
    fun intListBehavesLikeTheListItReplaced() {
        val list = IntList.of(0)
        assertEquals(1, list.size)
        assertEquals(0, list[0])

        for (i in 1..1000) {
            list.add(i)
        }
        assertEquals(1001, list.size)
        assertEquals(1000, list.last())
        assertEquals(500, list[500])

        val other = IntList.of(10, 20, 30)
        list.clear()
        list.addAll(other, 1)
        assertEquals(2, list.size)
        assertEquals(20, list[0])
        assertEquals(30, list[1])

        list.shiftAll(5)
        assertEquals(25, list[0])
        assertEquals(35, list[1])

        assertEquals(35, list.removeLast())
        assertEquals(1, list.size)

        try {
            list[7]
            fail("an out of range read must throw IndexOutOfBoundsException")
        } catch (expected: IndexOutOfBoundsException) {
            // expected
        }
    }

    @Test
    fun intListAddAllWithDelta() {
        val target = IntList.of(0)
        val source = IntList.of(0, 3, 7)
        target.addAll(source, 1, 100)
        assertEquals(3, target.size)
        assertEquals(0, target[0])
        assertEquals(103, target[1])
        assertEquals(107, target[2])
    }

    // ----------------------------------------------------------- string helpers

    @Test
    fun isBasicAsciiMatchesTheOldRegexBehaviour() {
        assertTrue(Strings.isBasicASCII(""))
        assertTrue(Strings.isBasicASCII("abc XYZ 123 ~!@#"))
        assertTrue(Strings.isBasicASCII("tab\there\nand\r\nnewlines"))
        assertTrue(!Strings.isBasicASCII("Züricha"))
        assertTrue(!Strings.isBasicASCII("\uD83D\uDE0D"))
        assertTrue(!Strings.isBasicASCII("\u0000"))
    }

    /**
     * The oracles are spelled out on purpose: `\p{Punct}` is platform dependent. On the JVM it is
     * the POSIX set, on Android (ICU) the Unicode "P" category. The former isPunctuation was
     * checked against the platform regex and failed on the device at char 36, '$'.
     */
    @Test
    fun isAsciiPunctuationIsThePosixSet() {
        val posixPunct = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        assertEquals(32, posixPunct.length)
        for (code in 0..127) {
            val char = code.toChar()
            assertEquals(char in posixPunct, Strings.isAsciiPunctuation(char), "char=$code")
        }
        // nothing outside ASCII
        assertFalse(Strings.isAsciiPunctuation('，'))
        assertFalse(Strings.isAsciiPunctuation('。'))
    }

    @Test
    fun isUnicodePunctuationFollowsTheUnicodeCategories() {
        val posixPunct = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        val asciiSymbols = "\$+<=>^`|~" // POSIX punct, but Unicode symbols
        for (code in 0..127) {
            val char = code.toChar()
            val expect = char in posixPunct && char !in asciiSymbols
            assertEquals(expect, Strings.isUnicodePunctuation(char), "char=$code")
        }
        for (char in "，。、！？；：（）《》「」…—·") {
            assertTrue(Strings.isUnicodePunctuation(char), "char=$char")
        }
        for (char in "￥×→a字1 \uD83D") {
            assertFalse(Strings.isUnicodePunctuation(char), "char=$char")
        }
    }

    // ----------------------------------------------------------- shared BufferCursor

    /**
     * Before the fix createNewPieces / appendToNode updated _lastChangeBufferPos in place, but
     * that object is also the end of the previous piece: its boundary moved and the '_'
     * placeholder leaked into the document ("\n\r" became "\n\r_").
     */
    @Test
    fun bufferCursorsAreNotSharedAcrossPieces() {
        val tree = TestSupport.buildTree("")
        tree.insert(0, "\r")
        tree.insert(0, "\n")
        TestSupport.assertLinesContent("\n\r", tree)
        TestSupport.assertTreeInvariants(tree)
    }

    // ----------------------------------------------------------- reads must not hang

    /** Before the fix the "not found" marker NPOS.node (NULL) was walked with next(), which never returned */
    @Test
    fun readsOnAnEmptyDocumentDoNotHang() {
        val buffer = TestSupport.buildBuffer("")
        TestSupport.runWithTimeout {
            assertEquals("", buffer.getNearestChunk(0))
            assertEquals(0, buffer.getPieceTree().getCharCode(0))
            assertEquals(0, buffer.getLineCharCode(1, 0))
            assertFailsWith<IndexOutOfBoundsException> { buffer[0] }
        }
    }

    @Test
    fun readsPastTheEndDoNotHang() {
        val buffer = TestSupport.buildBuffer("abc")
        val tree = buffer.getPieceTree()
        TestSupport.runWithTimeout {
            assertEquals("", buffer.getNearestChunk(3))
            assertEquals("", buffer.getNearestChunk(10))
            assertEquals(0, tree.getCharCode(3))
            assertFailsWith<IndexOutOfBoundsException> { tree.getCharCode(4) }
            assertFailsWith<IndexOutOfBoundsException> { buffer[3] }
            assertFailsWith<IndexOutOfBoundsException> { buffer.getNearestChunk(-1) }
            // the tree walk past the last line terminates and reports "not found" as 0
            assertEquals(0, tree.getLineCharCode(5, 0))
            // the buffer rejects a line that does not exist (see lineBasedQueriesRejectLinesThatDoNotExist)
            assertFailsWith<IndexOutOfBoundsException> { buffer.getLineCharCode(5, 0) }
        }
    }

    /** A start past the end with an end inside the document used to start walking from the NULL marker */
    @Test
    fun staleOrReversedRangesDoNotHang() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        TestSupport.runWithTimeout {
            assertEquals("", buffer.getValueInRange(Range(9, 1, 1, 2)))
            assertEquals("bc\ndef", buffer.getValueInRange(Range(1, 2, 99, 99)))
            assertEquals("", buffer.getPieceTree().getValueInRange(Range(9, 1, 1, 2)))
        }
    }

    @Test
    fun subSequenceRejectsInvalidBounds() {
        val buffer = TestSupport.buildBuffer("abc")
        assertEquals("bc", buffer.subSequence(1, 3).toString())
        assertFailsWith<IndexOutOfBoundsException> { buffer.subSequence(2, 1) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.subSequence(0, 4) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.subSequence(-1, 2) }
    }

    // ----------------------------------------------------------- EOL of inserted text

    /** Before the fix countEOL reported only the LAST kind, so mixed text was not normalized */
    @Test
    fun countEolReportsMixedLineBreaksAsInvalid() {
        assertEquals(EndOfLine.TextDefined, Strings.countEOL("abc").eol)
        assertEquals(EndOfLine.LF, Strings.countEOL("a\nb\n").eol)
        assertEquals(EndOfLine.CRLF, Strings.countEOL("a\r\nb").eol)
        assertEquals(EndOfLine.Invalid, Strings.countEOL("a\rb").eol)
        assertEquals(EndOfLine.Invalid, Strings.countEOL("a\r\nb\nc").eol)
        assertEquals(EndOfLine.Invalid, Strings.countEOL("a\nb\r\nc").eol)
        assertEquals(2, Strings.countEOL("a\r\nb\nc").eolCount)
    }

    @Test
    fun mixedLineBreaksAreNormalizedByApplyEdits() {
        val lf = TestSupport.buildBuffer("x\ny")
        lf.applyEdits(listOf(SingleEditOperation(Range(1, 2, 1, 2), "a\r\nb\nc")), false, false)
        assertEquals("xa\nb\nc\ny", lf.toString())
        assertEquals("xa", lf.getLineContent(1))
        assertEquals(listOf("xa", "b", "c", "y"), lf.getLinesContent())
        TestSupport.assertLinesContent("xa\nb\nc\ny", lf.getPieceTree())

        val crlf = TestSupport.buildBuffer("1\r\n2")
        crlf.applyEdits(listOf(SingleEditOperation(Range(1, 2, 1, 2), "a\nb\r\nc")), false, false)
        assertEquals("1a\r\nb\r\nc\r\n2", crlf.toString())
        TestSupport.assertTreeInvariants(crlf.getPieceTree())
    }

    // ----------------------------------------------------------- regex search

    private fun wholeRange(buffer: PieceTreeTextBuffer): Range {
        val lastLine = buffer.getLineCount()
        return Range(1, 1, lastLine, buffer.getLineMaxColumn(lastLine))
    }

    /** Before the fix `^` / `$` were evaluated against whole buffer strings, not lines */
    @Test
    fun regexAnchorsMatchAtLineBoundaries() {
        val buffer = TestSupport.buildBuffer("first\nfoo second\nfoo")
        val all = wholeRange(buffer)

        assertEquals(
            listOf(Range(2, 1, 2, 4), Range(3, 1, 3, 4)),
            buffer.find(Regex("^foo"), all, 100) { false }
        )
        assertEquals(listOf(Range(3, 1, 3, 4)), buffer.find(Regex("foo$"), all, 100) { false })
        // the multiline engine agrees
        assertEquals(
            listOf(Range(2, 1, 2, 4), Range(3, 1, 3, 4)),
            buffer.find(Regex("^foo", RegexOption.MULTILINE), all, 100) { false }
        )
        // the start of the search range is not the start of a line
        val bbb = TestSupport.buildBuffer("bbb")
        assertEquals(0, bbb.find(Regex("^b"), Range(1, 2, 1, 4), 100) { false }.size)
    }

    /** Lines of the change buffer are not lines of the document */
    @Test
    fun regexAnchorsIgnoreBufferLineBoundaries() {
        val buffer = TestSupport.buildBuffer("foobar")
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "abc\n")), false, false)
        // "xyz" now starts a line of the change buffer ("abc\nxyz"), but not of the document
        buffer.applyEdits(listOf(SingleEditOperation(Range(2, 4, 2, 4), "xyz")), false, false)
        assertEquals("abc\nfooxyzbar", buffer.toString())

        val all = wholeRange(buffer)
        assertEquals(0, buffer.find(Regex("^xyz"), all, 100) { false }.size)
        assertEquals(0, buffer.find(Regex("^xyz", RegexOption.MULTILINE), all, 100) { false }.size)
        assertEquals(listOf(Range(2, 1, 2, 4)), buffer.find(Regex("^foo"), all, 100) { false })
        assertEquals(listOf(Range(2, 4, 2, 7)), buffer.find(Regex("xyz"), all, 100) { false })
    }

    /** Before the fix `\s+` could run across a line break and was reported as a 1-line range */
    @Test
    fun lineByLineRegexNeverSpansLines() {
        val buffer = TestSupport.buildBuffer("a \nb")
        val all = wholeRange(buffer)
        assertEquals(0, buffer.find(Regex("a\\s+b"), all, 100) { false }.size)
        // explicitly multiline, or a pattern that names a line break, does span lines
        assertEquals(
            listOf(Range(1, 1, 2, 2)),
            buffer.find(Regex("a\\s+b", RegexOption.MULTILINE), all, 100) { false }
        )
        val ab = TestSupport.buildBuffer("a\nb")
        assertEquals(listOf(Range(1, 1, 2, 2)), ab.find(Regex("a\\nb"), wholeRange(ab), 100) { false })
    }

    /**
     * Before the fix the multiline engine ran on the text of the range alone: `^` matched where
     * the range started, `$` where it ended, and lookbehinds / `\b` saw nothing before it
     */
    @Test
    fun multilineRegexRespectsTheSearchRangeBounds() {
        val buffer = TestSupport.buildBuffer("xab\nab x")
        // the range starts inside line 1: not a line start
        assertEquals(
            listOf(Range(2, 1, 2, 3)),
            buffer.find(Regex("^ab", RegexOption.MULTILINE), Range(1, 2, 2, 9), 100) { false }
        )
        // the range ends inside line 2: not a line end
        assertEquals(
            listOf(Range(1, 2, 1, 4)),
            buffer.find(Regex("ab$", RegexOption.MULTILINE), Range(1, 1, 2, 3), 100) { false }
        )
        // `\b` sees the "x" in front of the range
        assertEquals(0, buffer.find(Regex("\\bab\\n"), Range(1, 2, 2, 1), 100) { false }.size)

        // CRLF: each \n of the joined text stands for two chars of the document
        val crlf = TestSupport.buildBuffer("xab\r\nab x")
        assertEquals(
            listOf(Range(2, 1, 2, 3)),
            crlf.find(Regex("^ab", RegexOption.MULTILINE), Range(1, 2, 2, 9), 100) { false }
        )
        assertEquals(listOf(Range(1, 2, 2, 3)), crlf.find(Regex("ab\\nab"), Range(1, 2, 2, 9), 100) { false })
    }

    // ----------------------------------------------------------- word search

    /** Before the fix the window skipped a match starting on its last line */
    @Test
    fun wordSearchFindsMatchStartingOnTheLastLineOfThePreviousHit() {
        val buffer = TestSupport.buildBuffer("a\nb a\nb")
        assertEquals(
            listOf(Range(1, 1, 2, 2), Range(2, 3, 3, 2)),
            buffer.find("a\nb", wholeRange(buffer), 100) { false }
        )
    }

    /** Before the fix the columns of the search range were ignored */
    @Test
    fun wordSearchRespectsColumns() {
        val buffer = TestSupport.buildBuffer("foo foo foo")
        assertEquals(
            listOf(Range(1, 5, 1, 8), Range(1, 9, 1, 12)),
            buffer.find("foo", Range(1, 2, 1, 12), 100) { false }
        )
        assertEquals(
            listOf(Range(1, 1, 1, 4), Range(1, 5, 1, 8)),
            buffer.find("foo", Range(1, 1, 1, 8), 100) { false }
        )
    }

    @Test
    fun wordSearchMatchesAnyLineBreakKind() {
        val crlf = TestSupport.buildBuffer("abc\r\nabc\r\nxyz")
        assertEquals(listOf(Range(1, 1, 2, 4)), crlf.find("abc\nabc", wholeRange(crlf), 100) { false })
        assertEquals(listOf(Range(1, 1, 2, 4)), crlf.find("abc\r\nabc", wholeRange(crlf), 100) { false })

        val lf = TestSupport.buildBuffer("hello\nworld")
        assertEquals(listOf(Range(1, 1, 2, 6)), lf.find("hello\r\nworld", wholeRange(lf), 100) { false })
    }

    @Test
    fun wordSearchWithEmptyTextFindsNothing() {
        val buffer = TestSupport.buildBuffer("abc")
        val result = TestSupport.runWithTimeout { buffer.find("", wholeRange(buffer), 100) { false } }
        assertEquals(0, result.size)
    }

    // ----------------------------------------------------------- applyEdits errors

    /** Before the fix this was kotlin.Error, which `catch (e: Exception)` does not catch */
    @Test
    fun overlappingRangesThrowIllegalArgumentException() {
        val buffer = TestSupport.buildBuffer("abcdef")
        assertFailsWith<IllegalArgumentException> {
            buffer.applyEdits(
                listOf(
                    SingleEditOperation(Range(1, 1, 1, 4), "X"),
                    SingleEditOperation(Range(1, 3, 1, 6), "Y")
                ),
                false,
                false
            )
        }
        assertEquals("abcdef", buffer.toString())
    }

    // ----------------------------------------------------------- builder

    /** Before the fix one huge chunk became one huge buffer and piece */
    @Test
    fun builderSlicesLargeInputIntoBoundedBuffers() {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 200_000) {
            sb.append("line-").append(i++).append('\n')
        }
        val text = sb.toString()
        val tree = PieceTreeTextBufferBuilder(text).build().getPieceTree()

        // walk the document chunk by chunk, the way an incremental parser does
        val joined = StringBuilder()
        var offset = 0
        var chunkCount = 0
        while (offset < tree.getLength()) {
            val chunk = tree.getNearestChunk(offset)
            assertTrue(chunk.length in 1..AverageBufferSize + 2, "chunk of ${chunk.length} chars")
            joined.append(chunk)
            offset += chunk.length
            chunkCount++
        }
        assertEquals(text, joined.toString())
        assertTrue(chunkCount >= 4, "expected several buffers, got $chunkCount")
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun builderSlicingKeepsCrlfAndSurrogatePairsTogether() {
        val crlf = "a".repeat(AverageBufferSize - 1) + "\r\n" + "b"
        val tree = TestSupport.buildTree(crlf)
        assertEquals(crlf, tree.getLinesRawContent())
        assertEquals(2, tree.getLineCount())
        assertEquals("b", tree.getLineContent(2))
        TestSupport.assertTreeInvariants(tree)

        val emoji = "a".repeat(AverageBufferSize - 1) + "\uD83D\uDE0D" + "b"
        val buffer = TestSupport.buildBuffer(emoji)
        assertEquals(emoji, buffer.getPieceTree().getLinesRawContent())
        assertEquals(emoji.length, buffer.length)
        assertEquals(AverageBufferSize + 1, buffer.getCharacterCountInRange(Range(1, 1, 1, emoji.length + 1)))
    }

    /** Before the fix a chunk made of nothing but "\r" dropped the "\r" held back before it */
    @Test
    fun builderKeepsEveryHeldBackCharacter() {
        val tree = TestSupport.buildTree(listOf("a\r", "\r", "b"), normalizeEOL = false)
        assertEquals("a\r\rb", tree.getLinesRawContent())
        assertEquals(3, tree.getLineCount())

        val surrogates = TestSupport.buildTree(listOf("x\uD83D", "\uD83D", "\uDE0Dy"), normalizeEOL = false)
        assertEquals("x\uD83D\uD83D\uDE0Dy", surrogates.getLinesRawContent())
    }

    /** A BOM is only a BOM at the very start of the input, not after a held back "\r" */
    @Test
    fun bomIsOnlyStrippedAtTheVeryStart() {
        val buffer = TestSupport.buildBuffer(listOf("\r", Strings.UTF8_BOM_CHARACTER + "x"), normalizeEOL = false)
        assertEquals("", buffer.getBOM())
        assertEquals("\r" + Strings.UTF8_BOM_CHARACTER + "x", buffer.getPieceTree().getLinesRawContent())
    }

    /**
     * A built buffer shares the builder's chunks; a chunk accepted afterwards could be appended
     * to one of them (a held back "\r" goes into the last chunk on build())
     */
    @Test
    fun builderRejectsChunksAfterBuild() {
        val builder = PieceTreeTextBufferBuilder()
        builder.acceptChunk("abc\r")
        val first = builder.build(EndOfLine.LF, normalizeEOL = false)
        assertFailsWith<IllegalStateException> { builder.acceptChunk("\r") }
        // building again is allowed and leaves the first buffer alone
        val second = builder.build(EndOfLine.LF, normalizeEOL = true)
        assertEquals("abc\r", first.toString())
        assertEquals("abc\r\n", second.toString())
    }

    /** Before the fix every requested EOL except LF (also TextDefined) gave "\r\n" for a single line */
    @Test
    fun builderUsesLfForTextWithoutLineBreaksUnlessCrlfIsRequested() {
        fun eolOf(eol: EndOfLine) = PieceTreeTextBufferBuilder("abc").build(eol).getEOL()
        assertEquals("\n", eolOf(EndOfLine.LF))
        assertEquals("\r\n", eolOf(EndOfLine.CRLF))
        assertEquals("\n", eolOf(EndOfLine.TextDefined))
    }

    // ----------------------------------------------------------- line length

    /** Before the fix getLineLength subtracted _EOLLength even for lines with another break */
    @Test
    fun lineLengthIsExactWithoutEolNormalization() {
        val buffer = TestSupport.buildBuffer("a\r\nbb\nccc", normalizeEOL = false)
        assertEquals("\n", buffer.getEOL())
        assertEquals(1, buffer.getLineLength(1))
        assertEquals(2, buffer.getLineLength(2))
        assertEquals(3, buffer.getLineLength(3))
        assertEquals(2, buffer.getLineMaxColumn(1))

        // the edit is clamped to the real end of line 1, the "\r\n" survives
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 99), "X")), false, false)
        assertEquals("X\r\nbb\nccc", buffer.toString())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    // ----------------------------------------------------------- concurrent reads

    /**
     * Before the fix getLineContent updated the cached VisitedLine in place, line number first
     * and text afterwards, so a second reader could get the text of another line. Races are
     * probabilistic: the fixed code always passes, the old code failed most runs (also on a
     * single core, through preemption) but not necessarily every run. The document is built
     * from small chunks so the readers also hit the search cache with many different nodes.
     */
    @Test
    fun concurrentReadersGetConsistentLinesAndChars() {
        val lines = (1..6).map { n -> "line $n ".repeat(n * 3) }
        val text = lines.joinToString("\n")
        val buffer = TestSupport.buildBuffer(text.chunked(7))

        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until 4).map { seed ->
            val worker = Thread {
                gate.await()
                val random = kotlin.random.Random(seed.toLong())
                repeat(50_000) {
                    val line = random.nextInt(lines.size) + 1
                    val content = buffer.getLineContent(line)
                    if (content != lines[line - 1]) {
                        failures.add("getLineContent($line) = \"$content\"")
                    }
                    val offset = random.nextInt(text.length)
                    if (buffer[offset] != text[offset]) {
                        failures.add("get($offset) = ${buffer[offset]}")
                    }
                }
            }
            worker.isDaemon = true
            worker.start()
            worker
        }
        gate.countDown()
        workers.forEach { it.join(60_000) }

        assertTrue(workers.none { it.isAlive }, "reader threads did not finish")
        assertTrue(failures.isEmpty(), "${failures.size} wrong reads, first: ${failures.peek()}")
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    // ----------------------------------------------------------- removed nodes

    /**
     * A removed node links to DETACHED, which links to itself. Walking from one used to loop
     * forever (the walk never reached the sentinel); now it fails right away.
     */
    @Test
    fun walkingFromARemovedNodeFailsInsteadOfHanging() {
        val tree = TestSupport.buildTree(listOf("abc", "def"))
        val first = leftest(tree.root, tree.sentinel)
        assertEquals("abc", tree.getValueInRange(Range(1, 1, 1, 4)))

        tree.delete(0, 3) // removes the whole first piece, `first` is detached now
        assertEquals("def", tree.getLinesRawContent())
        assertSame(DETACHED, first.parent)

        TestSupport.runWithTimeout {
            assertFailsWith<IllegalStateException> { first.next(tree.sentinel) }
            assertFailsWith<IllegalStateException> { first.prev(tree.sentinel) }
            assertFailsWith<IllegalStateException> { first.root(tree.sentinel) }
            assertFailsWith<IllegalStateException> { DETACHED.next(tree.sentinel) }
        }
        TestSupport.assertTreeInvariants(tree)
    }

    // ----------------------------------------------------------- per-tree sentinel

    /**
     * Before the change all trees shared one SENTINEL, and rbDelete writes to it: two buffers
     * edited on two threads at the same time corrupted each other. Each tree owns its sentinel
     * now, so this needs no lock at all. (Probabilistic like every race test: the shared version
     * failed most runs, the per-tree version always passes.)
     */
    @Test
    fun differentBuffersCanBeEditedOnDifferentThreadsAtTheSameTime() {
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val gate = java.util.concurrent.CountDownLatch(1)
        val trees = (0 until 4).map { TestSupport.buildTree(TestSupport.randomString(kotlin.random.Random(it.toLong()), 500), normalizeEOL = false) }
        val workers = trees.mapIndexed { index, tree ->
            val worker = Thread {
                gate.await()
                val random = kotlin.random.Random(100L + index)
                var model = tree.getLinesRawContent()
                try {
                    repeat(3000) { step ->
                        // deletes as often as inserts: rbDelete is what wrote to the sentinel
                        if (random.nextBoolean() || model.isEmpty()) {
                            val text = TestSupport.randomString(random, random.nextInt(5) + 1)
                            val offset = random.nextInt(model.length + 1)
                            tree.insert(offset, text)
                            model = model.substring(0, offset) + text + model.substring(offset)
                        } else {
                            val offset = random.nextInt(model.length)
                            val count = minOf(model.length - offset, random.nextInt(5) + 1)
                            tree.delete(offset, count)
                            model = model.substring(0, offset) + model.substring(offset + count)
                        }
                        if (step % 500 == 499 && tree.getLinesRawContent() != model) {
                            failures.add("tree $index diverged at step $step")
                            return@Thread
                        }
                    }
                    if (tree.getLinesRawContent() != model) failures.add("tree $index: wrong content")
                } catch (e: Throwable) {
                    failures.add("tree $index: $e")
                }
            }
            worker.isDaemon = true
            worker.start()
            worker
        }
        gate.countDown()
        workers.forEach { it.join(60_000) }

        assertTrue(workers.none { it.isAlive }, "editor threads did not finish")
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
        trees.forEach { TestSupport.assertTreeInvariants(it) }
        // and they really are separate objects
        assertEquals(trees.size, trees.map { it.sentinel }.distinct().size) // identity: TreeNode is no data class
    }

    // ----------------------------------------------------------- line numbers

    /** Before the check line 0 threw StringIndexOutOfBoundsException deep in the tree, lineCount + 1 gave "" */
    @Test
    fun lineBasedQueriesRejectLinesThatDoNotExist() {
        val buffer = TestSupport.buildBuffer("ab\ncd")
        for (line in listOf(0, -1, 3, 99)) {
            assertFailsWith<IndexOutOfBoundsException>("getLineContent($line)") { buffer.getLineContent(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineContentWithEOL($line)") { buffer.getLineContentWithEOL(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineLength($line)") { buffer.getLineLength(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineMinColumn($line)") { buffer.getLineMinColumn(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineMaxColumn($line)") { buffer.getLineMaxColumn(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineCharCode($line)") { buffer.getLineCharCode(line, 0) }
            assertFailsWith<IndexOutOfBoundsException>("getLineFirstNonWhitespaceColumn($line)") { buffer.getLineFirstNonWhitespaceColumn(line) }
            assertFailsWith<IndexOutOfBoundsException>("getLineLastNonWhitespaceColumn($line)") { buffer.getLineLastNonWhitespaceColumn(line) }
        }
        // the lines that exist, including the last one
        assertEquals("ab", buffer.getLineContent(1))
        assertEquals("cd", buffer.getLineContent(2))
        assertEquals(2, buffer.getLineLength(2))
        assertEquals('c'.code, buffer.getLineCharCode(2, 0))
        // the empty document has exactly one line
        val empty = TestSupport.buildBuffer("")
        assertEquals("", empty.getLineContent(1))
        assertFailsWith<IndexOutOfBoundsException> { empty.getLineContent(2) }
    }

    /** Range based methods clamp instead, like getValueInRange and applyEdits always did */
    @Test
    fun rangeBasedQueriesClamp() {
        val buffer = TestSupport.buildBuffer("ab\ncd")
        assertEquals(2, buffer.getValueLengthInRange(Range(1, 1, 1, 99)))
        assertEquals(5, buffer.getValueLengthInRange(Range(1, 1, 99, 99)))
        assertEquals(5, buffer.getCharacterCountInRange(Range(0, 0, 99, 99)))
        assertEquals(5, buffer.getOffsetAt(99, 99))
        assertEquals("ab\ncd", buffer.getValueInRange(Range(-5, -5, 99, 99)))
    }
}
