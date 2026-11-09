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

package x.github.module.piecetable

import kotlin.random.Random
import kotlin.test.*
import x.github.module.piecetable.common.EndOfLine
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * PieceTreeTextBuffer.fork: the same content in a tree of its own, sharing the original chunks;
 * edits of either do not show in the other.
 */
class PieceTreeForkTest {

    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    // every line, its length, offsets and positions, as the model says
    private fun assertContent(model: String, buffer: PieceTreeTextBuffer, where: String) {
        assertEquals(model, buffer.toString(), "$where: content")
        assertEquals(model.length, buffer.length, "$where: length")
        val eol = buffer.getEOL()
        val lines = RandomEditing.lines(model, eol)
        assertEquals(lines.size, buffer.getLineCount(), "$where: line count")
        for (i in lines.indices) {
            assertEquals(lines[i], buffer.getLineContent(i + 1), "$where: line ${i + 1}")
        }
        val random = Random(model.length)
        repeat(20) {
            if (model.isEmpty()) return@repeat
            val offset = random.nextInt(model.length + 1)
            val position = buffer.getPositionAt(offset)
            assertEquals(offset, buffer.getOffsetAt(position.lineNumber, position.column), "$where: offset $offset")
        }
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    @Test
    fun forkOfAFileAsLoaded() {
        val buffer = TestSupport.buildBuffer(listOf("first line\n", "second\nthird", " line\nfourth"))
        val fork = buffer.fork()
        assertContent(buffer.toString(), fork, "fork")
        assertEquals(0, fork.getVersionId())
    }

    @Test
    fun forkOfAnEmptyDocument() {
        val fork = TestSupport.buildBuffer("").fork()
        assertContent("", fork, "empty fork")
    }

    @Test
    fun forkKeepsTypedTextAndCrLf() {
        val builder = PieceTreeTextBufferBuilder()
        listOf("first line\r\n", "second\r", "\nthird line\r\n", "fourth").forEach { builder.acceptChunk(it) }
        val buffer = builder.build(EndOfLine.LF, normalizeEOL = false)
        val tree = buffer.getPieceTree()
        // typed char by char, then a "\r" and a "\n" typed separately (joined in the change buffer)
        for (c in "typed") tree.insert(buffer.length, c.toString())
        tree.insert(buffer.length, "\r")
        tree.insert(buffer.length, "\n")
        tree.insert(3, "mid")
        tree.delete(20, 4)
        val model = buffer.toString()

        val fork = buffer.fork()
        assertEquals(model, fork.toString())
        assertEquals(buffer.getLineCount(), fork.getLineCount())
        for (line in 1..buffer.getLineCount()) {
            assertEquals(buffer.getLineContent(line), fork.getLineContent(line), "line $line")
        }
        TestSupport.assertTreeInvariants(fork.getPieceTree())
    }

    /** Random documents and edits; at each fork, both go on being edited apart. */
    @Test
    fun forksAndTheirSourcesGoTheirOwnWays() {
        for (seed in 1L..10L) {
            val random = Random(seed)
            val raw = RandomEditing.randomDocument(random, 100 + random.nextInt(2000), crlfBias = seed % 2 == 0L)
            val buffer = RandomEditing.build(raw, random)
            val eol = RandomEditing.detectEOL(raw)
            var model = RandomEditing.normalize(raw, eol)

            repeat(60) { step ->
                val edit = RandomEditing.randomEdit(random, model, eol, PieceTreeUndoRoundTripTest.TEXTS)
                buffer.applyEdits(edit.ops, false, false)
                model = edit.expected
                if (step % 15 == 14) {
                    val fork = buffer.fork()
                    assertContent(model, fork, "seed $seed, step $step: fork")
                    // the fork edited: the source stays
                    var forkModel = model
                    repeat(20) {
                        val forkEdit = RandomEditing.randomEdit(random, forkModel, eol, PieceTreeUndoRoundTripTest.TEXTS)
                        fork.applyEdits(forkEdit.ops, false, false)
                        forkModel = forkEdit.expected
                    }
                    assertContent(forkModel, fork, "seed $seed, step $step: fork edited")
                    assertEquals(model, buffer.toString(), "seed $seed, step $step: source after the fork's edits")
                    // the source edited (and compacted): the fork stays
                    val sourceEdit = RandomEditing.randomEdit(random, model, eol, PieceTreeUndoRoundTripTest.TEXTS)
                    buffer.applyEdits(sourceEdit.ops, false, false)
                    model = sourceEdit.expected
                    buffer.compact()
                    assertContent(forkModel, fork, "seed $seed, step $step: fork after the source's edits")
                }
            }
            assertContent(model, buffer, "seed $seed: source")
        }
    }

    @Test
    fun forkSharesTheOriginalChunks() {
        val text = "a".repeat(200_000) + "\n" + "b".repeat(10)
        val buffer = TestSupport.buildBuffer(text)
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "typed")), false, false)
        val fork = buffer.fork()
        val stats = fork.getStorageStats()
        // the file's chunks, not a second copy of them: what is stored is the content once
        assertEquals("typed".length + text.length, stats.storedLength.toInt())
        assertEquals(0, stats.changeBufferLength)
    }
}
