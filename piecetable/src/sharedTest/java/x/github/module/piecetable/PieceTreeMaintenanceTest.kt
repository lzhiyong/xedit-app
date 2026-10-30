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

import kotlin.random.Random
import kotlin.test.*
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * Long-running-editor maintenance: compact(), getStorageStats(), and isolation between buffers.
 */
class PieceTreeMaintenanceTest {

    // no shared mutable state is left; the reset is kept as a cheap safety net
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    /** Random applyEdits on [buffer]; returns the reverse edits of every step, oldest first */
    private fun randomEdits(buffer: PieceTreeTextBuffer, random: Random, steps: Int): List<List<SingleEditOperation>> {
        val alphabet = "abcdefghij\n"
        val undo = mutableListOf<List<SingleEditOperation>>()
        repeat(steps) {
            val length = buffer.length
            val start = random.nextInt(length + 1)
            val end = start + random.nextInt(minOf(12, length - start) + 1)
            val text = buildString {
                repeat(random.nextInt(8)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val range = Range.fromPositions(buffer.getPositionAt(start), buffer.getPositionAt(end))
            val result = buffer.applyEdits(listOf(SingleEditOperation(range, text)), false, true)
            undo.add(result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) })
        }
        return undo
    }

    /** Every chunk an incremental parser would read, in order */
    private fun chunksOf(tree: PieceTreeBase): List<String> {
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < tree.getLength()) {
            val chunk = tree.getNearestChunk(offset)
            chunks.add(chunk)
            offset += chunk.length
        }
        return chunks
    }

    // ----------------------------------------------------------- compact

    @Test
    fun compactKeepsTextLinesAndEolAndReleasesStorage() {
        val buffer = TestSupport.buildBuffer("line1\nline2\nline3")
        randomEdits(buffer, Random(11L), 400)

        val text = buffer.toString()
        val lines = buffer.getLinesContent()
        val before = buffer.getStorageStats()
        assertTrue(before.pieceCount > 1, "expected a fragmented tree, got $before")
        assertTrue(before.changeBufferLength > 0)
        assertTrue(before.storedLength > before.length)

        buffer.compact()

        assertEquals(text, buffer.toString())
        assertEquals(lines, buffer.getLinesContent())
        assertEquals("\n", buffer.getEOL())
        val after = buffer.getStorageStats()
        val expectedPieces = if (text.isEmpty()) 0 else 1 // the document is far below 64K
        assertEquals(StorageStats(text.length, text.length.toLong(), 0, expectedPieces), after)
        TestSupport.assertLinesContent(text, buffer.getPieceTree())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())

        // and the buffer keeps working
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "head\n")), false, false)
        assertEquals("head\n$text", buffer.toString())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    /** compact() must not normalize anything: mixed line breaks survive, so does the '_' trick */
    @Test
    fun compactKeepsMixedLineBreaksOfANonNormalizedTree() {
        val tree = TestSupport.buildTree("a\r\nb\nc\rd", normalizeEOL = false)
        var model = tree.getLinesRawContent()
        val random = Random(5L)
        repeat(300) {
            if (random.nextInt(10) < 6 || model.isEmpty()) {
                val text = TestSupport.randomString(random, random.nextInt(6) + 1)
                val offset = random.nextInt(model.length + 1)
                tree.insert(offset, text)
                model = model.substring(0, offset) + text + model.substring(offset)
            } else {
                val offset = random.nextInt(model.length)
                val count = minOf(model.length - offset, random.nextInt(4) + 1)
                tree.delete(offset, count)
                model = model.substring(0, offset) + model.substring(offset + count)
            }
            if (it % 50 == 49) {
                tree.compact()
                TestSupport.assertLinesContent(model, tree, "after compact at step $it")
                TestSupport.assertTreeInvariants(tree)
            }
        }
        TestSupport.assertLinesContent(model, tree)
    }

    @Test
    fun compactNeverSplitsCrlfOrSurrogatePairsBetweenChunks() {
        for (middle in listOf("\r\n", "\uD83D\uDE0D")) {
            TestSupport.resetGlobalState()
            val text = "a".repeat(AverageBufferSize - 1) + middle + "b".repeat(100)
            val tree = TestSupport.buildTree(text, normalizeEOL = false)
            // fragment it first, so compact has to re-cut across piece boundaries
            tree.insert(10, "x")
            tree.delete(10, 1)
            tree.compact()

            assertEquals(text, tree.getLinesRawContent())
            val chunks = chunksOf(tree)
            assertTrue(chunks.all { it.length <= AverageBufferSize })
            for (i in 1 until chunks.size) {
                val previous = chunks[i - 1]
                val next = chunks[i]
                assertFalse(previous.endsWith("\r") && next.startsWith("\n"), "CRLF split")
                assertFalse(Character.isHighSurrogate(previous.last()) && Character.isLowSurrogate(next.first()), "pair split")
            }
            TestSupport.assertTreeInvariants(tree)
        }
    }

    @Test
    fun compactOnAnEmptyDocument() {
        val buffer = TestSupport.buildBuffer("")
        buffer.compact()
        assertEquals(0, buffer.length)
        assertEquals(1, buffer.getLineCount())
        assertEquals(StorageStats(0, 0L, 0, 0), buffer.getStorageStats())
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "abc")), false, false)
        assertEquals("abc", buffer.toString())
    }

    /** Undo data holds texts and offsets, never references into buffers */
    @Test
    fun undoDataStaysValidAcrossCompact() {
        val original = "initial\ntext\nhere"
        val buffer = TestSupport.buildBuffer(original)
        val undo = randomEdits(buffer, Random(23L), 200).toMutableList()

        buffer.compact()

        while (undo.isNotEmpty()) {
            buffer.applyEdits(undo.removeAt(undo.size - 1), false, false)
        }
        assertEquals(original, buffer.toString())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    // ----------------------------------------------------------- storage stats

    @Test
    fun storageStatsFollowEdits() {
        val buffer = TestSupport.buildBuffer("abcdef")
        assertEquals(StorageStats(6, 6L, 0, 1), buffer.getStorageStats())

        // delete "cd": the original chunk is still held as a whole
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 3, 1, 5), "")), false, false)
        assertEquals(StorageStats(4, 6L, 0, 2), buffer.getStorageStats())

        // insert "XYZ" in the middle: one more buffer piece, the change buffer holds the text
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 3, 1, 3), "XYZ")), false, false)
        val stats = buffer.getStorageStats()
        assertEquals(7, stats.length)
        assertEquals(3, stats.changeBufferLength)
        assertEquals(9L, stats.storedLength)

        buffer.compact()
        assertEquals(StorageStats(7, 7L, 0, 1), buffer.getStorageStats())
        assertEquals("abXYZef", buffer.toString())
    }

    // ----------------------------------------------------------- isolation

    /**
     * What an edit dying half way through rbDelete leaves behind is a dirty sentinel. It belongs
     * to that one tree now: another buffer is not affected and needs no repair.
     */
    @Test
    fun aBrokenTreeDoesNotAffectAnotherOne() {
        val broken = TestSupport.buildTree("abc\ndef")
        val healthy = TestSupport.buildTree("ghi\njkl")
        assertNotSame(broken.sentinel, healthy.sentinel)

        broken.sentinel.parent = broken.root
        broken.sentinel.size_left = 42

        healthy.insert(3, "!", true)
        healthy.delete(0, 1)
        healthy.delete(2, 3)
        assertEquals("hikl", healthy.getLinesRawContent())
        TestSupport.assertTreeInvariants(healthy)
        assertSame(healthy.sentinel, healthy.sentinel.parent)
    }
}
