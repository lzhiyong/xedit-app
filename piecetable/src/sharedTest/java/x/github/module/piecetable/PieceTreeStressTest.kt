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
import kotlin.random.Random

/**
 * Randomized and stress tests.
 *
 * Every case uses a fixed seed, so a failure reproduces exactly.
 * To widen coverage add seeds to SEEDS instead of going back to Math.random().
 */
class PieceTreeStressTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    private companion object {
        const val TAG = "PieceTreeStress"
        val SEEDS = longArrayOf(1L, 20220613L, 987654321L)
    }

    // ----------------------------------------------------------- low level insert / delete

    @Test
    fun randomInsertAndDelete() {
        for (seed in SEEDS) {
            val random = Random(seed)
            val tree = TestSupport.buildTree("", normalizeEOL = false)
            var model = ""

            for (step in 0 until 1000) {
                if (random.nextInt(10) < 6) {
                    val text = TestSupport.randomString(random, random.nextInt(50) + 1)
                    val offset = random.nextInt(model.length + 1)
                    tree.insert(offset, text)
                    model = model.substring(0, offset) + text + model.substring(offset)
                } else {
                    if (model.isEmpty()) continue
                    val offset = random.nextInt(model.length)
                    val count = minOf(model.length - offset, random.nextInt(10))
                    tree.delete(offset, count)
                    model = model.substring(0, offset) + model.substring(offset + count)
                }
                assertEquals(model, tree.getLinesRawContent(), "seed=$seed step=$step")
            }

            TestSupport.assertLinesContent(model, tree, "seed=$seed")
            TestSupport.assertLineStarts(model, tree, "seed=$seed")
            TestSupport.assertTreeInvariants(tree)
        }
    }

    @Test
    fun randomEditsOnChunkedDocument() {
        for (seed in SEEDS) {
            val random = Random(seed)
            val chunks = (0 until 5).map { TestSupport.randomString(random, 1000) }
            val tree = TestSupport.buildTree(chunks, normalizeEOL = false)
            var model = chunks.joinToString("")

            for (step in 0 until 500) {
                val what: String
                if (random.nextInt(10) < 6) {
                    val text = TestSupport.randomString(random, random.nextInt(100) + 1)
                    val offset = random.nextInt(model.length + 1)
                    what = "insert@$offset len=${text.length}"
                    tree.insert(offset, text)
                    model = model.substring(0, offset) + text + model.substring(offset)
                } else {
                    val offset = random.nextInt(model.length)
                    val count = minOf(model.length - offset, random.nextInt(20))
                    what = "delete@$offset count=$count"
                    tree.delete(offset, count)
                    model = model.substring(0, offset) + model.substring(offset + count)
                }
                // assert on every step, so a failure names the exact operation that broke it
                assertEquals(model, tree.getLinesRawContent(), "seed=$seed step=$step $what")
            }

            assertEquals(model, tree.getLinesRawContent(), "seed=$seed")
            TestSupport.assertLinesContent(model, tree, "seed=$seed")
            TestSupport.assertLineStarts(model, tree, "seed=$seed")
            TestSupport.assertTreeInvariants(tree)
        }
    }

    @Test
    fun randomEditsKeepLineQueriesConsistent() {
        val random = Random(42L)
        val tree = TestSupport.buildTree(TestSupport.randomString(random, 2000), normalizeEOL = false)
        var model = tree.getLinesRawContent()

        for (step in 0 until 100) {
            val text = TestSupport.randomString(random, random.nextInt(30) + 1)
            val offset = random.nextInt(model.length + 1)
            tree.insert(offset, text)
            model = model.substring(0, offset) + text + model.substring(offset)

            // a full line level comparison on every step, so the first bad edit is obvious
            TestSupport.assertLinesContent(model, tree, "step=$step")
        }
        TestSupport.assertTreeInvariants(tree)
    }

    // ----------------------------------------------------------- applyEdits and undo round trip

    @Test
    fun applyEditsUndoRoundTrip() {
        // only \n here: applyEdits normalizes the EOL of inserted text, which would make the
        // string model drift away from the buffer
        val alphabet = "abcdefghij\n"
        for (seed in SEEDS) {
            val random = Random(seed)
            val buffer = TestSupport.buildBuffer("initial\ntext\nhere")
            val original = buffer.toString()
            val undoStack = mutableListOf<List<SingleEditOperation>>()

            repeat(200) {
                val length = buffer.length
                val start = random.nextInt(length + 1)
                val end = start + random.nextInt(minOf(12, length - start) + 1)
                val text = buildString {
                    repeat(random.nextInt(8)) { append(alphabet[random.nextInt(alphabet.length)]) }
                }
                val range = Range.fromPositions(buffer.getPositionAt(start), buffer.getPositionAt(end))

                val result = buffer.applyEdits(
                    listOf(SingleEditOperation(range, text)), false, true
                )
                undoStack.add(
                    result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }
                )
            }

            while (undoStack.isNotEmpty()) {
                buffer.applyEdits(undoStack.removeAt(undoStack.size - 1), false, false)
            }

            assertEquals(original, buffer.toString(), "seed=$seed, undoing everything must restore the original text")
            TestSupport.assertTreeInvariants(buffer.getPieceTree())
        }
    }

    // ----------------------------------------------------------- large documents

    @Test
    fun largeDocumentRandomAccess() {
        val lineCount = 50_000
        val sb = StringBuilder(lineCount * 12)
        for (i in 0 until lineCount) {
            sb.append("line-").append(i).append('\n')
        }
        val text = sb.toString()

        val buildStart = System.currentTimeMillis()
        val buffer = TestSupport.buildBuffer(text)
        println("$TAG: build ${text.length} chars in ${System.currentTimeMillis() - buildStart} ms")

        assertEquals(lineCount + 1, buffer.getLineCount())

        val random = Random(7L)
        val readStart = System.currentTimeMillis()
        repeat(20_000) {
            val line = random.nextInt(lineCount) + 1
            assertEquals("line-${line - 1}", buffer.getLineContent(line))
        }
        println("$TAG: 20000 random getLineContent in ${System.currentTimeMillis() - readStart} ms")
    }

    @Test
    fun manyEditsOnLargeDocument() {
        val sb = StringBuilder()
        for (i in 0 until 20_000) {
            sb.append("the quick brown fox jumps over the lazy dog ").append(i).append('\n')
        }
        val buffer = TestSupport.buildBuffer(sb.toString())
        val random = Random(99L)

        val start = System.currentTimeMillis()
        repeat(2000) {
            val line = random.nextInt(buffer.getLineCount() - 1) + 1
            buffer.applyEdits(
                listOf(SingleEditOperation(Range(line, 1, line, 1), "// ")),
                false,
                false
            )
        }
        println("$TAG: 2000 edits on a 20k-line document in ${System.currentTimeMillis() - start} ms")

        TestSupport.assertTreeInvariants(buffer.getPieceTree())
        assertEquals(20_001, buffer.getLineCount())
    }
}
