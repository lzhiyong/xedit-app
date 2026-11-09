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
 * Randomized applyEdits + undo / redo round trips on documents with CRLF and mixed line breaks.
 *
 * Every document starts from text that mixes "\n", "\r\n" and "\r", is built from random
 * chunks (cut anywhere, also inside "\r\n") and normalized to the EOL the builder picks. Each
 * step is a random batch of 1-3 edits whose texts again mix all line break kinds, or an undo /
 * redo using the reverse edits applyEdits returned. After every step the buffer must equal a
 * plain String model; at the end every remaining edit is undone and the buffer must be back at
 * the text it was built from.
 *
 * Fixed seeds: a failure names its seed and step and reproduces exactly.
 */
class PieceTreeUndoRoundTripTest {

    companion object {
        val SEEDS = (1L..24L).toList()

        // no break, each break kind, mixes, a break at either end, surrogate pairs
        val TEXTS = listOf(
            "", "x", "yz", "hello",
            "\n", "\r\n", "\r", "\n\n", "\r\r\n",
            "a\nb", "a\r\nb", "a\rb", "a\r\nb\nc\rd", "\r\n\n\r",
            "end\r", "\nstart",
            "\uD83D\uDE00", "x\uD83D\uDE00\r\ny"
        )
    }

    // reverse edits of one batch, and the model text applying them leads to
    private class Entry(val ops: List<SingleEditOperation>, val text: String)

    private fun inverse(result: ApplyEditsResult): List<SingleEditOperation> =
        result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) }

    private fun assertContent(expected: String, eol: String, buffer: PieceTreeTextBuffer, random: Random, where: String) {
        assertEquals(expected, buffer.toString(), "$where: content")
        assertEquals(expected.length, buffer.length, "$where: length")
        val lines = RandomEditing.lines(expected, eol)
        assertEquals(lines.size, buffer.getLineCount(), "$where: line count")
        assertEquals(lines, buffer.getLinesContent(), "$where: lines")
        // one line through the cached single-line path as well
        val line = 1 + random.nextInt(lines.size)
        assertEquals(lines[line - 1], buffer.getLineContent(line), "$where: line $line")
        assertEquals(lines[line - 1].length, buffer.getLineLength(line), "$where: length of line $line")
    }

    /** @return the EOL the document got */
    private fun roundTrip(seed: Long, steps: Int, crlfBias: Boolean, texts: List<String>): String {
        val random = Random(seed)
        val raw = RandomEditing.randomDocument(random, 50 + random.nextInt(400), crlfBias = crlfBias)
        val buffer = RandomEditing.build(raw, random)
        val eol = RandomEditing.detectEOL(raw)
        assertEquals(eol, buffer.getEOL(), "seed $seed: EOL picked by the builder")

        val initial = RandomEditing.normalize(raw, eol)
        var model = initial
        assertContent(model, eol, buffer, random, "seed $seed, after build")

        val undo = mutableListOf<Entry>()
        val redo = mutableListOf<Entry>()
        for (step in 0 until steps) {
            val where = "seed $seed, step $step"
            val r = random.nextInt(10)
            if (r < 2 && undo.isNotEmpty()) {
                val entry = undo.removeAt(undo.size - 1)
                val result = buffer.applyEdits(entry.ops, false, true)
                redo.add(Entry(inverse(result), model))
                model = entry.text
            } else if (r < 3 && redo.isNotEmpty()) {
                val entry = redo.removeAt(redo.size - 1)
                val result = buffer.applyEdits(entry.ops, false, true)
                undo.add(Entry(inverse(result), model))
                model = entry.text
            } else {
                val edit = RandomEditing.randomEdit(random, model, eol, texts)
                val result = buffer.applyEdits(edit.ops, false, true)
                undo.add(Entry(inverse(result), model))
                redo.clear()
                model = edit.expected
            }
            assertContent(model, eol, buffer, random, where)
            if (step % 25 == 24) {
                TestSupport.assertTreeInvariants(buffer.getPieceTree())
            }
        }

        // undo everything that is left
        while (undo.isNotEmpty()) {
            val entry = undo.removeAt(undo.size - 1)
            buffer.applyEdits(entry.ops, false, true)
            model = entry.text
            assertContent(model, eol, buffer, random, "seed $seed, unwinding (${undo.size} left)")
        }
        assertEquals(initial, buffer.toString(), "seed $seed: not back at the start")
        TestSupport.assertTreeInvariants(buffer.getPieceTree())
        return eol
    }

    @Test
    fun crlfDocumentsWithMixedLineBreaksRoundTrip() {
        val eols = SEEDS.map { roundTrip(it, steps = 300, crlfBias = true, texts = TEXTS) }
        // the documents are generated to be mostly CRLF; make sure that is what got tested
        assertTrue(eols.count { it == "\r\n" } >= SEEDS.size / 2, "too few CRLF documents: $eols")
    }

    @Test
    fun lfDocumentsWithMixedLineBreaksRoundTrip() {
        val eols = SEEDS.map { roundTrip(1000 + it, steps = 300, crlfBias = false, texts = TEXTS) }
        assertTrue(eols.count { it == "\n" } >= SEEDS.size / 2, "too few LF documents: $eols")
    }

    /** Pastes over 64K chars are stored in new buffers of their own (createNewPieces). */
    @Test
    fun largePastesWithMixedLineBreaksRoundTrip() {
        for (seed in listOf(7L, 8L, 9L)) {
            val paste = RandomEditing.randomDocument(
                Random(seed),
                AverageBufferSize + 5000,
                crlfBias = seed % 2 == 0L
            )
            roundTrip(2000 + seed, steps = 40, crlfBias = seed % 2 == 1L, texts = TEXTS + paste)
        }
    }
}
