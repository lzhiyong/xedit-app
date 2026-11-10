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
import x.github.module.piecetable.common.*

/**
 * TextSlice, exportSlice, applySliceEdits, applyEditsWithSliceUndo: undo / redo that keeps
 * large removed content as references into the storage instead of copying it.
 */
class PieceTreeSliceTest {

    // ------------------------------------------------------------------ randomized round trips

    // the undo / redo batch of one step, and the model text applying it leads to
    private class Entry(val edits: List<SliceEdit>, val text: String)

    private class Outcome(val referenceSlices: Int, val textSlices: Int)

    private fun assertContent(expected: String, buffer: PieceTreeTextBuffer, random: Random, where: String) {
        assertEquals(expected, buffer.toString(), "$where: content")
        assertEquals(expected.length, buffer.length, "$where: length")
        val lines = expected.split(RandomEditing.LINE_BREAK)
        assertEquals(lines.size, buffer.getLineCount(), "$where: line count")
        assertEquals(lines, buffer.getLinesContent(), "$where: lines")
        val line = 1 + random.nextInt(lines.size)
        assertEquals(lines[line - 1], buffer.getLineContent(line), "$where: line $line")
        assertEquals(lines[line - 1].length, buffer.getLineLength(line), "$where: length of line $line")
    }

    /**
     * Random edits through applyEditsWithSliceUndo, undo / redo through applySliceEdits, checked
     * against a String model after every step; at the end everything is undone.
     *
     * @param threshold the buffer's sliceTextThreshold: 0 keeps every removed text as references
     * @param normalized build with normalizeEOL; false builds a document mixing "\n" and "\r\n"
     *   (no lone "\r": removing the text between a lone "\r" and a "\n" joins them into one line
     *   break, which no position based undo can express)
     * @param compact call compact() now and then: slices must take the replaced storage back
     * @param setEol switch the EOL now and then: older slices must be normalized on insertion
     */
    private fun roundTrip(
        seed: Long,
        steps: Int,
        threshold: Int,
        normalized: Boolean,
        compact: Boolean,
        setEol: Boolean
    ): Outcome {
        val random = Random(seed)
        var raw = RandomEditing.randomDocument(random, 50 + random.nextInt(600), crlfBias = seed % 2 == 0L)
        if (!normalized) {
            raw = raw.replace(Regex("\r(?!\n)"), "\n")
        }
        val buffer = RandomEditing.build(raw, random, normalizeEOL = normalized)
        buffer.sliceTextThreshold = threshold
        var eol = RandomEditing.detectEOL(raw)
        assertEquals(eol, buffer.getEOL(), "seed $seed: EOL")

        // once the buffer is EOL-normalized (built so, or after setEOL), every text it gets back
        // is normalized too; before that, undo restores the exact text
        var normalizedNow = normalized
        fun expected(text: String) = if (normalizedNow) RandomEditing.normalize(text, eol) else text

        val initial = expected(raw)
        var model = initial
        assertContent(model, buffer, random, "seed $seed, after build")

        var referenceSlices = 0
        var textSlices = 0
        fun count(result: SliceEditResult) {
            for (edit in result.reverseEdits) {
                val content = edit.content ?: continue
                if (content.isText) textSlices++ else referenceSlices++
            }
        }

        val undo = mutableListOf<Entry>()
        val redo = mutableListOf<Entry>()
        for (step in 0 until steps) {
            val where = "seed $seed, step $step"
            val r = random.nextInt(20)
            when {
                compact && r == 0 -> buffer.compact()
                setEol && r == 1 -> {
                    eol = if (eol == "\n") "\r\n" else "\n"
                    buffer.setEOL(eol)
                    normalizedNow = true
                    model = RandomEditing.normalize(model, eol)
                }
                r < 6 && undo.isNotEmpty() -> {
                    val entry = undo.removeAt(undo.size - 1)
                    val result = buffer.applySliceEdits(entry.edits)
                    count(result)
                    redo.add(Entry(result.reverseEdits, model))
                    model = expected(entry.text)
                }
                r < 8 && redo.isNotEmpty() -> {
                    val entry = redo.removeAt(redo.size - 1)
                    val result = buffer.applySliceEdits(entry.edits)
                    count(result)
                    undo.add(Entry(result.reverseEdits, model))
                    model = expected(entry.text)
                }
                else -> {
                    val edit = RandomEditing.randomEdit(random, model, eol, PieceTreeUndoRoundTripTest.TEXTS)
                    val result = buffer.applyEditsWithSliceUndo(edit.ops)
                    count(result)
                    undo.add(Entry(result.reverseEdits, model))
                    redo.clear()
                    model = edit.expected
                }
            }
            assertContent(model, buffer, random, where)
            if (step % 25 == 24) {
                TestSupport.assertTreeInvariants(buffer.getPieceTree())
            }
        }

        while (undo.isNotEmpty()) {
            val entry = undo.removeAt(undo.size - 1)
            buffer.applySliceEdits(entry.edits)
            model = expected(entry.text)
            assertContent(model, buffer, random, "seed $seed, unwinding (${undo.size} left)")
        }
        assertEquals(expected(raw), buffer.toString(), "seed $seed: not back at the start")
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
        return Outcome(referenceSlices, textSlices)
    }

    @Test
    fun referenceSlicesRoundTripAcrossCompactAndSetEol() {
        var references = 0
        for (seed in 1L..16L) {
            references += roundTrip(seed, 250, threshold = 0, normalized = true, compact = true, setEol = true).referenceSlices
        }
        assertTrue(references > 1000, "only $references slices held references")
    }

    @Test
    fun textSlicesRoundTrip() {
        for (seed in 101L..108L) {
            val outcome = roundTrip(seed, 250, threshold = Int.MAX_VALUE, normalized = true, compact = true, setEol = true)
            assertEquals(0, outcome.referenceSlices, "seed $seed")
        }
    }

    /** Both forms in one history: a threshold small enough that removed text falls on either side. */
    @Test
    fun mixedSlicesRoundTrip() {
        var references = 0
        var texts = 0
        for (seed in 201L..212L) {
            val outcome = roundTrip(seed, 250, threshold = 8, normalized = true, compact = true, setEol = true)
            references += outcome.referenceSlices
            texts += outcome.textSlices
        }
        assertTrue(references > 100 && texts > 100, "references $references, texts $texts")
    }

    /** Without setEOL, undo restores the exact text of a document with mixed line breaks. */
    @Test
    fun undoRestoresMixedLineBreaksExactly() {
        for (seed in 301L..312L) {
            roundTrip(seed, 250, threshold = 0, normalized = false, compact = true, setEol = false)
        }
    }

    // ------------------------------------------------------------------ memory

    @Test
    fun undoingALargeDeleteCopiesNothing() {
        val text = ("0123456789abcdef".repeat(4) + "\n").repeat(16_000) // about 1 MB, 16 chunks
        val buffer = TestSupport.buildBuffer(text)
        val before = buffer.getStorageStats()
        val lastLine = buffer.getLineCount()

        val delete = buffer.applyEditsWithSliceUndo(
            listOf(SingleEditOperation(Range(1, 1, lastLine, buffer.getLineMaxColumn(lastLine)), ""))
        )
        assertEquals("", buffer.toString())
        val removed = delete.reverseEdits.single().content!!
        assertFalse(removed.isText, "1 MB must be kept as references")
        assertEquals(text.length, removed.length)
        assertEquals(16_000, removed.eolCount)
        assertEquals(before.changeBufferLength, buffer.getStorageStats().changeBufferLength)

        val undo = buffer.applySliceEdits(delete.reverseEdits)
        assertEquals(text, buffer.toString())
        // the text is back in the tree as pieces of the original chunks: nothing was stored anew
        val after = buffer.getStorageStats()
        assertEquals(before.storedLength, after.storedLength)
        assertEquals(0, after.changeBufferLength)
        TestSupport.assertTreeInvariants(buffer.getPieceTree())

        buffer.applySliceEdits(undo.reverseEdits) // redo
        assertEquals("", buffer.toString())
    }

    @Test
    fun shortContentIsKeptAsText() {
        val buffer = TestSupport.buildBuffer("hello world")
        val result = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(Range(1, 1, 1, 6), "")))
        val removed = result.reverseEdits.single().content!!
        assertTrue(removed.isText)
        assertEquals("hello", removed.toText())
    }

    @Test
    fun undoAfterCompactTakesTheOldStorageBack() {
        val buffer = TestSupport.buildBuffer("abc\ndef\nghi")
        buffer.sliceTextThreshold = 0
        val result = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(Range(1, 2, 3, 2), "XY")))
        assertEquals("aXYhi", buffer.toString())

        buffer.compact()
        assertEquals(StorageStats(5, 5L, 0, 1), buffer.getStorageStats())

        buffer.applySliceEdits(result.reverseEdits)
        assertEquals("abc\ndef\nghi", buffer.toString())
        // the chunk the removed text lies in belongs to the storage again
        assertEquals(5L + 11L, buffer.getStorageStats().storedLength)
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    // ------------------------------------------------------------------ line breaks

    @Test
    fun sliceExportedBeforeSetEolIsNormalized() {
        val buffer = TestSupport.buildBuffer("a\nb\nc")
        buffer.sliceTextThreshold = 0
        val slice = buffer.exportSlice(Range(1, 1, 3, 2))
        assertFalse(slice.isText)

        buffer.setEOL("\r\n")
        buffer.applySliceEdits(listOf(SliceEdit(Range(3, 2, 3, 2), slice)))
        assertEquals("a\r\nb\r\nca\r\nb\r\nc", buffer.toString())
        assertEquals(5, buffer.getLineCount())
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }

    @Test
    fun textSliceIsNormalizedToTheBufferEol() {
        val slice = TextSlice.of("a\r\nb\rc")
        assertEquals(6, slice.length)
        assertEquals(2, slice.eolCount)
        assertEquals(1, slice.firstLineLength)
        assertEquals(1, slice.lastLineLength)

        val buffer = TestSupport.buildBuffer("xy")
        buffer.applySliceEdits(listOf(SliceEdit(Range(1, 2, 1, 2), slice)))
        assertEquals("xa\nb\ncy", buffer.toString())
    }

    /**
     * In a document that is not EOL-normalized, inserted pieces can form "\r\n" with a neighbour:
     * the pair is repaired like after a delete, and the line count follows.
     */
    @Test
    fun insertedPiecesJoinANeighbouringCarriageReturnOrLineFeed() {
        // a piece starting with "\n" right after a "\r"
        val first = TestSupport.buildBuffer("p\rq\nr", normalizeEOL = false)
        first.sliceTextThreshold = 0
        val startsWithLf = first.exportSlice(Range(2, 2, 3, 2)) // "\nr"
        assertFalse(startsWithLf.isText)
        first.applySliceEdits(listOf(SliceEdit(Range(2, 1, 2, 1), startsWithLf)))
        assertEquals("p\r\nrq\nr", first.toString())
        assertEquals(listOf("p", "rq", "r"), first.getLinesContent())
        TestSupport.assertTreeInvariants(first.getPieceTree())

        // a piece ending with "\r" right before a "\n"
        val second = TestSupport.buildBuffer("p\rq\nr", normalizeEOL = false)
        second.sliceTextThreshold = 0
        val endsWithCr = second.exportSlice(Range(1, 1, 2, 1)) // "p\r"
        second.applySliceEdits(listOf(SliceEdit(Range(2, 2, 2, 2), endsWithCr)))
        assertEquals("p\rqp\r\nr", second.toString())
        assertEquals(listOf("p", "qp", "r"), second.getLinesContent())
        TestSupport.assertTreeInvariants(second.getPieceTree())
    }

    // ------------------------------------------------------------------ API details

    @Test
    fun exportSliceDescribesTheRangeAndChangesNothing() {
        for (threshold in listOf(0, Int.MAX_VALUE)) {
            val lf = TestSupport.buildBuffer("ab\ncd\nef")
            lf.sliceTextThreshold = threshold
            val version = lf.getVersionId()
            val slice = lf.exportSlice(Range(1, 2, 3, 2))
            assertEquals(threshold != 0, slice.isText, "threshold $threshold")
            assertEquals("b\ncd\ne", slice.toText())
            assertEquals(6, slice.length)
            assertEquals(2, slice.eolCount)
            assertEquals(1, slice.firstLineLength)
            assertEquals(1, slice.lastLineLength)
            assertEquals(version, lf.getVersionId())

            val crlf = TestSupport.buildBuffer("ab\r\ncd\r\nef")
            crlf.sliceTextThreshold = threshold
            val crlfSlice = crlf.exportSlice(Range(1, 2, 3, 2))
            assertEquals("b\r\ncd\r\ne", crlfSlice.toText())
            assertEquals(8, crlfSlice.length)
            assertEquals(2, crlfSlice.eolCount)
            assertEquals(1, crlfSlice.firstLineLength)
            assertEquals(1, crlfSlice.lastLineLength)

            val oneLine = lf.exportSlice(Range(2, 1, 2, 3))
            assertEquals("cd", oneLine.toText())
            assertEquals(0, oneLine.eolCount)
            assertEquals(2, oneLine.firstLineLength)
            assertEquals(2, oneLine.lastLineLength)

            val empty = lf.exportSlice(Range(2, 2, 2, 2))
            assertEquals(0, empty.length)
            assertEquals("", empty.toText())
            // clamped like every range based method
            assertEquals("ab\ncd\nef", lf.exportSlice(Range(0, 0, 99, 99)).toText())
        }
    }

    @Test
    fun slicesReferringToAnotherBufferAreRejected() {
        val a = TestSupport.buildBuffer("hello\nworld")
        a.sliceTextThreshold = 0
        val b = TestSupport.buildBuffer("xyz")
        val references = a.exportSlice(Range(1, 1, 2, 6))
        assertFalse(references.isText)

        val version = b.getVersionId()
        assertFailsWith<IllegalArgumentException> {
            b.applySliceEdits(listOf(SliceEdit(Range(1, 1, 1, 1), references)))
        }
        assertEquals("xyz", b.toString())
        assertEquals(version, b.getVersionId())

        // text moves between buffers, normalized to the target's EOL
        b.applySliceEdits(listOf(SliceEdit(Range(1, 4, 1, 4), TextSlice.of(references.toText()))))
        assertEquals("xyzhello\nworld", b.toString())
        a.sliceTextThreshold = Int.MAX_VALUE
        val crlf = TestSupport.buildBuffer("1\r\n2")
        crlf.applySliceEdits(listOf(SliceEdit(Range(1, 1, 1, 1), a.exportSlice(Range(1, 1, 2, 6)))))
        assertEquals("hello\r\nworld1\r\n2", crlf.toString())
    }

    @Test
    fun overlappingSliceEditsThrowBeforeAnythingChanges() {
        val buffer = TestSupport.buildBuffer("abcdef")
        val version = buffer.getVersionId()
        assertFailsWith<IllegalArgumentException> {
            buffer.applySliceEdits(
                listOf(
                    SliceEdit(Range(1, 1, 1, 4), TextSlice.of("X")),
                    SliceEdit(Range(1, 3, 1, 6), TextSlice.of("Y"))
                )
            )
        }
        assertEquals("abcdef", buffer.toString())
        assertEquals(version, buffer.getVersionId())
    }

    @Test
    fun changesDescribeTheBatch() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        val version = buffer.getVersionId()
        val result = buffer.applyEditsWithSliceUndo(
            listOf(
                SingleEditOperation(Range(1, 1, 1, 2), null),
                SingleEditOperation(Range(2, 1, 2, 4), "XYZW")
            )
        )
        assertEquals("bc\nXYZW", buffer.toString())
        assertNotEquals(version, buffer.getVersionId())

        // bottom up, like ApplyEditsResult.changes
        assertEquals(2, result.changes.size)
        val (second, first) = result.changes
        assertEquals(Range(2, 1, 2, 4), second.range)
        assertEquals(4, second.rangeOffset)
        assertEquals(3, second.rangeLength)
        assertEquals("XYZW", second.content!!.toText())
        assertEquals(Range(1, 1, 1, 2), first.range)
        assertEquals(1, first.rangeLength)
        assertNull(first.content)

        // the reverse batch in the caller's order
        assertEquals(Range(1, 1, 1, 1), result.reverseEdits[0].range)
        assertEquals("a", result.reverseEdits[0].content!!.toText())
        assertEquals(Range(2, 1, 2, 5), result.reverseEdits[1].range)
        assertEquals("def", result.reverseEdits[1].content!!.toText())

        buffer.applySliceEdits(result.reverseEdits)
        assertEquals("abc\ndef", buffer.toString())
    }

    /** Pure inserts: the reverse batch deletes, with no content. */
    @Test
    fun undoOfAnInsertHasNoContent() {
        val buffer = TestSupport.buildBuffer("ab")
        val result = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(Range(1, 2, 1, 2), "1\n2")))
        assertEquals("a1\n2b", buffer.toString())
        val reverse = result.reverseEdits.single()
        assertEquals(Range(1, 2, 2, 2), reverse.range)
        assertNull(reverse.content)
        buffer.applySliceEdits(result.reverseEdits)
        assertEquals("ab", buffer.toString())
    }

    @Test
    fun everyChangeSaysWhatItRemoved() {
        val buffer = TestSupport.buildBuffer("abc\ndef")
        val result = buffer.applyEditsWithSliceUndo(
            listOf(
                SingleEditOperation(Range(1, 1, 1, 2), null),
                SingleEditOperation(Range(2, 1, 2, 1), "new"),
                SingleEditOperation(Range(2, 2, 2, 4), "X")
            )
        )
        // bottom up
        val (replace, insert, delete) = result.changes
        assertEquals("ef", replace.removed!!.toText())
        assertNull(insert.removed, "an insertion removes nothing")
        assertEquals("a", delete.removed!!.toText())
        // the same slices as the reverse batch carries
        assertSame(result.reverseEdits[0].content, delete.removed)
        assertSame(result.reverseEdits[2].content, replace.removed)
    }

    @Test
    fun largeRemovedContentIsReportedAsReferences() {
        val text = "x".repeat(TextSlice.TEXT_THRESHOLD * 2)
        val buffer = TestSupport.buildBuffer(text)

        val removed = buffer.applyEditsWithSliceUndo(listOf(SingleEditOperation(Range(1, 1, 1, text.length + 1), null)))
            .changes.single().removed!!

        assertFalse(removed.isText)
        assertTrue(removed.segmentCount >= 1)
        assertEquals(text.length, removed.length)
    }
}
