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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.*
import x.github.module.piecetable.common.*

/**
 * PieceTreeSnapshot: an immutable copy of one version, readable from any thread while the
 * buffer keeps changing.
 */
class PieceTreeSnapshotTest {

    // ------------------------------------------------------------------ helpers

    // Spot checks for a snapshot that is read by several threads: no read(), it is stateful
    private fun spotCheck(snapshot: PieceTreeSnapshot, expected: String, random: Random): String? {
        if (snapshot.length != expected.length) {
            return "length ${snapshot.length}, expected ${expected.length}"
        }
        if (expected.isEmpty()) {
            return if (snapshot.getNearestChunk(0) == "") null else "chunk of an empty snapshot"
        }
        repeat(20) {
            val i = random.nextInt(expected.length)
            if (snapshot[i] != expected[i]) {
                return "get($i)"
            }
        }
        val a = random.nextInt(expected.length + 1)
        val b = a + random.nextInt(expected.length - a + 1)
        if (snapshot.substring(a, b) != expected.substring(a, b)) {
            return "substring($a, $b)"
        }
        val offset = random.nextInt(expected.length)
        val chunk = snapshot.getNearestChunk(offset)
        if (chunk.isEmpty() || !expected.startsWith(chunk, offset)) {
            return "getNearestChunk($offset)"
        }
        return null
    }

    // Everything except read(), on one thread
    private fun assertSnapshot(expected: String, snapshot: PieceTreeSnapshot, random: Random, where: String) {
        assertEquals(expected.length, snapshot.length, "$where: length")
        assertEquals(expected, snapshot.toString(), "$where: toString")

        val pieces = StringBuilder()
        snapshot.readPiecesContent { pieces.append(it) }
        assertEquals(expected, pieces.toString(), "$where: readPiecesContent")

        // the way an incremental parser reads
        val chunks = StringBuilder()
        var offset = 0
        while (true) {
            val chunk = snapshot.getNearestChunk(offset)
            if (chunk.isEmpty()) {
                break
            }
            assertTrue(chunk.length <= AverageBufferSize, "$where: chunk of ${chunk.length} chars")
            chunks.append(chunk)
            offset += chunk.length
        }
        assertEquals(expected, chunks.toString(), "$where: getNearestChunk walk")
        assertEquals("", snapshot.getNearestChunk(expected.length + 5), "$where: chunk past the end")

        assertNull(spotCheck(snapshot, expected, random), where)
    }

    // ------------------------------------------------------------------ content

    /**
     * Each kind of change that touches storage the snapshot could depend on: typing appends to
     * the change buffer, a "\n" typed after "\r" joins them and drops the last line start of the
     * change buffer, compact() and setEOL() replace every buffer.
     */
    @Test
    fun snapshotKeepsItsContentWhileTheBufferChanges() {
        val random = Random(1)
        val builder = PieceTreeTextBufferBuilder()
        listOf("first line\r\n", "second\r", "\nthird line\r\n", "fourth").forEach { builder.acceptChunk(it) }
        val buffer = builder.build(EndOfLine.LF, normalizeEOL = false)
        val tree = buffer.getPieceTree()

        // typing at the end of the document, then a "\r" and a "\n" typed separately
        val typed = "typed text"
        for (i in typed.indices) {
            tree.insert(buffer.length, typed[i].toString())
        }
        tree.insert(buffer.length, "\r")
        val beforeJoin = buffer.toString()
        val snapshot1 = buffer.createSnapshot()
        assertSnapshot(beforeJoin, snapshot1, random, "snapshot1")

        tree.insert(buffer.length, "\n") // joins with the "\r" at the end of the change buffer
        tree.insert(buffer.length, "more")
        tree.delete(3, 10)
        val afterEdits = buffer.toString()
        val snapshot2 = buffer.createSnapshot()

        buffer.compact()
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "head\n")), false, false)
        buffer.setEOL("\r\n")
        buffer.applyEdits(listOf(SingleEditOperation(Range(2, 1, 3, 1), "")), false, false)

        assertSnapshot(beforeJoin, snapshot1, random, "snapshot1 after all edits")
        assertSnapshot(afterEdits, snapshot2, random, "snapshot2 after all edits")
        TestSupport.assertTreeInvariants(tree)
    }

    /** Random documents and edits; every snapshot taken along the way is checked again at the end. */
    @Test
    fun snapshotsStayAtTheirVersion() {
        for (seed in 1L..12L) {
            val random = Random(seed)
            val raw = RandomEditing.randomDocument(random, 100 + random.nextInt(2000), crlfBias = seed % 2 == 0L)
            val buffer = RandomEditing.build(raw, random)
            var eol = RandomEditing.detectEOL(raw)
            var model = RandomEditing.normalize(raw, eol)
            val taken = mutableListOf<Pair<PieceTreeSnapshot, String>>()

            for (step in 0 until 200) {
                when (random.nextInt(40)) {
                    0 -> buffer.compact()
                    1 -> {
                        eol = if (eol == "\n") "\r\n" else "\n"
                        buffer.setEOL(eol)
                        model = RandomEditing.normalize(model, eol)
                    }
                    else -> {
                        val edit = RandomEditing.randomEdit(random, model, eol, PieceTreeUndoRoundTripTest.TEXTS)
                        buffer.applyEdits(edit.ops, false, false)
                        model = edit.expected
                    }
                }
                if (step % 10 == 0) {
                    val snapshot = buffer.createSnapshot()
                    assertEquals(buffer.getVersionId(), snapshot.versionId)
                    assertEquals(buffer.getLineCount(), snapshot.lineCount)
                    assertEquals(eol, snapshot.eol)
                    assertSnapshot(model, snapshot, random, "seed $seed, step $step")
                    taken.add(Pair(snapshot, model))
                }
            }
            assertEquals(model, buffer.toString(), "seed $seed: buffer")
            taken.forEachIndexed { index, (snapshot, text) ->
                assertSnapshot(text, snapshot, random, "seed $seed, snapshot $index at the end")
            }
        }
    }

    /**
     * The use case: the editing thread keeps editing (no lock) and hands out snapshots, other
     * threads read them at the same time. Races are probabilistic; a snapshot that still read
     * the live change buffer or buffer list fails most runs.
     */
    @Test
    fun snapshotsCanBeReadWhileTheBufferIsEdited() {
        val random = Random(42)
        val raw = RandomEditing.randomDocument(random, 3000)
        val buffer = RandomEditing.build(raw, random)
        var eol = RandomEditing.detectEOL(raw)
        var model = RandomEditing.normalize(raw, eol)

        val published = CopyOnWriteArrayList<Pair<PieceTreeSnapshot, String>>()
        published.add(Pair(buffer.createSnapshot(), model))
        val failures = ConcurrentLinkedQueue<String>()
        val reads = AtomicLong()
        val done = AtomicBoolean(false)

        val readers = (0 until 3).map { index ->
            val reader = Thread {
                val r = Random(100L + index)
                try {
                    while (!done.get()) {
                        val (snapshot, text) = published[r.nextInt(published.size)]
                        spotCheck(snapshot, text, r)?.let { failures.add("reader $index: $it") }
                        reads.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    failures.add("reader $index: $e")
                }
            }
            reader.isDaemon = true
            reader.start()
            reader
        }

        try {
            for (step in 0 until 3000) {
                when (random.nextInt(200)) {
                    0 -> buffer.compact()
                    1 -> {
                        eol = if (eol == "\n") "\r\n" else "\n"
                        buffer.setEOL(eol)
                        model = RandomEditing.normalize(model, eol)
                    }
                    else -> {
                        val edit = RandomEditing.randomEdit(random, model, eol, PieceTreeUndoRoundTripTest.TEXTS)
                        buffer.applyEdits(edit.ops, false, false)
                        model = edit.expected
                    }
                }
                if (step % 20 == 0) {
                    published.add(Pair(buffer.createSnapshot(), model))
                }
            }
        } finally {
            done.set(true)
        }
        readers.forEach { it.join(60_000) }

        assertTrue(readers.none { it.isAlive }, "reader threads did not finish")
        assertTrue(failures.isEmpty(), "${failures.size} wrong reads, first: ${failures.peek()}")
        assertTrue(reads.get() > 0, "the readers did not read anything")
        assertEquals(model, buffer.toString())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    // ------------------------------------------------------------------ API details

    @Test
    fun versionIdChangesWithTheContentOnly() {
        val buffer = RandomEditing.build("abc\ndef", Random(1))
        val v0 = buffer.getVersionId()

        buffer.getLineContent(1)
        buffer.find("a", Range(1, 1, 2, 4), 10) { false }
        buffer.createSnapshot()
        assertEquals(v0, buffer.getVersionId(), "reads")

        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "x")), false, false)
        val v1 = buffer.getVersionId()
        assertNotEquals(v0, v1, "applyEdits")

        buffer.compact()
        assertEquals(v1, buffer.getVersionId(), "compact")

        buffer.setEOL("\r\n")
        val v2 = buffer.getVersionId()
        assertNotEquals(v1, v2, "setEOL")

        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 2), "")), false, false)
        assertNotEquals(v2, buffer.getVersionId(), "a delete")
        assertEquals(buffer.getVersionId(), buffer.createSnapshot().versionId)
    }

    @Test
    fun emptyDocumentSnapshot() {
        val snapshot = RandomEditing.build("", Random(1)).createSnapshot()
        assertEquals(0, snapshot.length)
        assertEquals("", snapshot.toString())
        assertEquals("", snapshot.getNearestChunk(0))
        assertEquals("", snapshot.substring(0, 0))
        assertEquals(1, snapshot.lineCount)
        assertFailsWith<IndexOutOfBoundsException> { snapshot[0] }
        // read(): the BOM (empty here) once, then the end
        assertEquals("", snapshot.read())
        assertNull(snapshot.read())
    }

    @Test
    fun readReturnsTheBomOnlyWhenPreserved() {
        val builder = PieceTreeTextBufferBuilder()
        builder.acceptChunk("\uFEFFab")
        builder.acceptChunk("c\nd")
        val buffer = builder.build()
        assertEquals("\uFEFF", buffer.getBOM())

        fun readAll(snapshot: PieceTreeSnapshot): String {
            val text = StringBuilder()
            var chunk = snapshot.read()
            while (chunk != null) {
                text.append(chunk)
                chunk = snapshot.read()
            }
            return text.toString()
        }
        assertEquals("\uFEFFabc\nd", readAll(buffer.createSnapshot(preserveBOM = true)))
        assertEquals("abc\nd", readAll(buffer.createSnapshot()))
        // offsets never count the BOM
        val snapshot = buffer.createSnapshot(preserveBOM = true)
        assertEquals("abc\nd", snapshot.toString())
        assertEquals('a', snapshot[0])
    }

    @Test
    fun invalidArgumentsThrow() {
        val snapshot = RandomEditing.build("hello\nworld", Random(1)).createSnapshot()
        assertFailsWith<IndexOutOfBoundsException> { snapshot[-1] }
        assertFailsWith<IndexOutOfBoundsException> { snapshot[snapshot.length] }
        assertFailsWith<IndexOutOfBoundsException> { snapshot.substring(-1, 2) }
        assertFailsWith<IndexOutOfBoundsException> { snapshot.substring(3, 2) }
        assertFailsWith<IndexOutOfBoundsException> { snapshot.substring(0, snapshot.length + 1) }
        assertFailsWith<IndexOutOfBoundsException> { snapshot.getNearestChunk(-1) }
        assertEquals("lo\nwo", snapshot.subSequence(3, 8).toString())
    }
}
