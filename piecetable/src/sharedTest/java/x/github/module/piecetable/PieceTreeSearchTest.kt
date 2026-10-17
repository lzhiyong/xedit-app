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
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Searching: by word, by regex line by line, by regex across lines, limits and cancellation.
 */
class PieceTreeSearchTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    private fun wholeRange(buffer: PieceTreeTextBuffer): Range {
        val lastLine = buffer.getLineCount()
        return Range(1, 1, lastLine, buffer.getLineMaxColumn(lastLine))
    }

    // ----------------------------------------------------------- search by word

    @Test
    fun findWordOnASingleLine() {
        val buffer = TestSupport.buildBuffer("foo bar foo baz foo")
        val result = buffer.find("foo", wholeRange(buffer), 100) { false }

        assertEquals(3, result.size)
        assertEquals(Range(1, 1, 1, 4), result[0])
        assertEquals(Range(1, 9, 1, 12), result[1])
        assertEquals(Range(1, 17, 1, 20), result[2])
    }

    @Test
    fun findWordAcrossLines() {
        val buffer = TestSupport.buildBuffer("hello\nworld\nhello\nworld")
        val result = buffer.find("hello\nworld", wholeRange(buffer), 100) { false }

        assertEquals(2, result.size)
        assertEquals(Range(1, 1, 2, 6), result[0])
        assertEquals(Range(3, 1, 4, 6), result[1])
    }

    @Test
    fun findWordRespectsResultLimit() {
        val buffer = TestSupport.buildBuffer("aaaa")
        val result = buffer.find("a", wholeRange(buffer), 2) { false }
        assertEquals(2, result.size)
    }

    @Test
    fun findWordHonoursCancellation() {
        val buffer = TestSupport.buildBuffer("foo foo foo")
        val result = buffer.find("foo", wholeRange(buffer), 100) { true }
        assertEquals(0, result.size)
    }

    @Test
    fun findWordWithNoMatch() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        assertEquals(0, buffer.find("zzz", wholeRange(buffer), 100) { false }.size)
    }

    // ----------------------------------------------------------- regex, line by line

    @Test
    fun findRegexLineByLine() {
        val buffer = TestSupport.buildBuffer("abc\nabc\nabc")
        val result = buffer.find(Regex("abc"), wholeRange(buffer), 100) { false }

        assertEquals(3, result.size)
        assertEquals(Range(1, 1, 1, 4), result[0])
        assertEquals(Range(2, 1, 2, 4), result[1])
        assertEquals(Range(3, 1, 3, 4), result[2])
    }

    @Test
    fun findRegexWithCharacterClass() {
        val buffer = TestSupport.buildBuffer("a1b22c333")
        val result = buffer.find(Regex("[0-9]+"), wholeRange(buffer), 100) { false }

        assertEquals(3, result.size)
        assertEquals(Range(1, 2, 1, 3), result[0])
        assertEquals(Range(1, 4, 1, 6), result[1])
        assertEquals(Range(1, 7, 1, 10), result[2])
    }

    @Test
    fun findRegexInsideSubRange() {
        val buffer = TestSupport.buildBuffer("def\ndbcabc")
        val result = buffer.find(Regex("a"), Range(2, 3, 2, 7), 100) { false }

        assertEquals(1, result.size)
        assertEquals(Range(2, 4, 2, 5), result[0])
    }

    @Test
    fun findRegexOnEmptyBuffer() {
        val buffer = TestSupport.buildBuffer("")
        val result = buffer.find(Regex("abc"), Range(1, 1, 1, 1), 100) { false }
        assertEquals(0, result.size)
    }

    // ----------------------------------------------------------- regex, multiline

    @Test
    fun findRegexMultiline() {
        val buffer = TestSupport.buildBuffer("abc\nabc\nxyz")
        val result = buffer.find(
            Regex("abc\\nabc", RegexOption.MULTILINE),
            wholeRange(buffer),
            100
        ) { false }

        assertEquals(1, result.size)
        assertEquals(Range(1, 1, 2, 4), result[0])
    }

    @Test
    fun findRegexMultilineOnCrlfDocument() {
        // multiline search runs on text joined with \n, so CRLF offsets have to be mapped back
        val buffer = TestSupport.buildBuffer("abc\r\nabc\r\nxyz")
        assertEquals("\r\n", buffer.getEOL())

        val result = buffer.find(
            Regex("abc\\nabc", RegexOption.MULTILINE),
            wholeRange(buffer),
            100
        ) { false }

        assertEquals(1, result.size)
        assertEquals(Range(1, 1, 2, 4), result[0])
    }

    @Test
    fun findRegexMultilineRespectsLimit() {
        val buffer = TestSupport.buildBuffer("ab\nab\nab\nab")
        val result = buffer.find(
            Regex("ab", RegexOption.MULTILINE),
            wholeRange(buffer),
            2
        ) { false }
        assertEquals(2, result.size)
    }

    // ----------------------------------------------------------- searching after an edit

    @Test
    fun searchStillWorksAfterEdits() {
        val buffer = TestSupport.buildBuffer("one\ntwo\nthree")
        buffer.applyEdits(
            listOf(SingleEditOperation(Range(2, 1, 2, 4), "two two")),
            false,
            false
        )
        assertEquals("two two", buffer.getLineContent(2))

        val result = buffer.find("two", wholeRange(buffer), 100) { false }
        assertEquals(2, result.size)
        assertTrue(result.all { it.startLine == 2 })
    }
}
