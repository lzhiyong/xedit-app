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
import x.github.module.piecetable.common.*

/**
 * Helpers for the randomized tests: a plain String is the model of the document, random edits
 * are generated against that model and applied to both.
 *
 * Everything here works on the String alone (positions, line breaks, EOL detection), so it is
 * an independent reference for what the buffer must report.
 */
internal object RandomEditing {

    val LINE_BREAK = Regex("\r\n|\r|\n")

    private val BREAKS = listOf("\n", "\r\n", "\r")

    private const val EMOJI = "\uD83D\uDE00"

    /** [text] with every line break, of any kind, replaced by [eol]. */
    fun normalize(text: String, eol: String): String = text.replace(LINE_BREAK, eol)

    /**
     * The EOL the builder must choose for [text]: CRLF when "\r\n" and lone "\r" are more than
     * half of all line breaks, otherwise LF (also for a text without line breaks, built with
     * the default EndOfLine.LF).
     */
    fun detectEOL(text: String): String {
        var cr = 0
        var lf = 0
        var crlf = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') {
                    crlf++
                    i++
                } else {
                    cr++
                }
            } else if (text[i] == '\n') {
                lf++
            }
            i++
        }
        val total = cr + lf + crlf
        return if (total > 0 && cr + crlf > total / 2) "\r\n" else "\n"
    }

    /**
     * Random text of at least [length] chars: letters of [alphabet], line breaks of every kind
     * (mostly "\r\n" with [crlfBias], mostly "\n" otherwise) and, with [emoji], surrogate pairs.
     */
    fun randomDocument(
        random: Random,
        length: Int,
        alphabet: String = "abcd",
        crlfBias: Boolean = false,
        emoji: Boolean = true
    ): String {
        val text = StringBuilder(length + 2)
        while (text.length < length) {
            val r = random.nextInt(20)
            when {
                r < 2 -> text.append(if (crlfBias) "\r\n" else "\n")
                r == 2 -> text.append(BREAKS[random.nextInt(BREAKS.size)])
                r == 3 && emoji -> text.append(EMOJI)
                else -> text.append(alphabet[random.nextInt(alphabet.length)])
            }
        }
        return text.toString()
    }

    /** Builds a buffer from [text] cut into random chunks (anywhere, also inside "\r\n" or a surrogate pair). */
    fun build(text: String, random: Random, normalizeEOL: Boolean = true): PieceTreeTextBuffer {
        val builder = PieceTreeTextBufferBuilder()
        var start = 0
        while (start < text.length) {
            val end = Math.min(text.length, start + 1 + random.nextInt(256))
            builder.acceptChunk(text.substring(start, end))
            start = end
        }
        return builder.build(EndOfLine.LF, normalizeEOL)
    }

    /** Lines of a model whose line breaks are all [eol]. */
    fun lines(model: String, eol: String): List<String> = model.split(eol)

    /** The (line, column) of [offset] in [text]; "\r\n" is one line break, "\r" and "\n" alone too. */
    fun positionOf(text: String, offset: Int): Position {
        var line = 1
        var lineStart = 0
        var i = 0
        while (i < offset) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                i += 2
                line++
                lineStart = i
                continue
            }
            if (c == '\n' || c == '\r') {
                line++
                lineStart = i + 1
            }
            i++
        }
        return Position(line, offset - lineStart + 1)
    }

    /**
     * [offset] moved back by one when it falls between "\r" and "\n" (no position can express
     * that) or between the two halves of a surrogate pair (edits there would split an emoji).
     */
    fun snap(text: String, offset: Int): Int {
        if (offset > 0 && offset < text.length) {
            val before = text[offset - 1]
            val at = text[offset]
            if ((before == '\r' && at == '\n') || (before.isHighSurrogate() && at.isLowSurrogate())) {
                return offset - 1
            }
        }
        return offset
    }

    /** One applyEdits batch and the model text it must produce. */
    class Edit(val ops: List<SingleEditOperation>, val expected: String)

    /**
     * 1 to 3 non-overlapping (possibly touching, possibly identical empty) ranges, mostly short,
     * each replaced by a text from [texts]. The buffer converts line breaks in inserted text to
     * its EOL, so the model inserts the text normalized to [eol].
     */
    fun randomEdit(random: Random, model: String, eol: String, texts: List<String>): Edit {
        val count = 1 + random.nextInt(3)
        // snap() never moves an offset past a larger one, so the sorted starts stay sorted
        val starts = IntArray(count) { snap(model, random.nextInt(model.length + 1)) }
        starts.sort()

        val ops = ArrayList<SingleEditOperation>(count)
        val ends = IntArray(count)
        val inserted = ArrayList<String>(count)
        for (k in 0 until count) {
            val start = starts[k]
            val limit = if (k + 1 < count) starts[k + 1] else model.length
            val wanted = if (random.nextInt(8) == 0) random.nextInt(40) else random.nextInt(6)
            val end = snap(model, start + Math.min(wanted, limit - start))
            val text = texts[random.nextInt(texts.size)]
            ops.add(
                SingleEditOperation(
                    Range.fromPositions(positionOf(model, start), positionOf(model, end)),
                    text
                )
            )
            ends[k] = end
            inserted.add(normalize(text, eol))
        }

        // apply from the last range to the first, so the earlier offsets stay valid
        var expected = model
        for (k in count - 1 downTo 0) {
            expected = expected.substring(0, starts[k]) + inserted[k] + expected.substring(ends[k])
        }
        return Edit(ops, expected)
    }
}
