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
 * Randomized find() against naive reference implementations that work on a plain String.
 *
 * The documents (LF and CRLF) are built from random chunks and then edited at random, so the
 * text is spread over many pieces and over the change buffer. Search ranges start and end
 * anywhere. The references:
 *  - plain text without a line break: compare the search text at every column of every line,
 *    left to right, without overlaps;
 *  - plain text with line breaks: walk the document String itself, letting each "\n" of the
 *    (normalized) search text match one line break of the document, whatever its kind;
 *  - line-by-line regex: patterns without anchors or lookarounds run on the substring of the
 *    line inside the range (Regex.findAll); the others on the line with the range as a
 *    transparent, non-anchoring region, as documented;
 *  - multiline regex: the lines joined with "\n", the range as such a region, and every match
 *    mapped back to (line, column) by counting "\n" in the joined text, not through offsets.
 * Results must be identical, in order, also when the result count is limited.
 */
class PieceTreeSearchOracleTest {

    companion object {
        val SEEDS = (1L..16L).toList()

        // edits keep the documents rich in matches
        val TEXTS = listOf("", "a", "b", "ab ", "c", "ba", "\n", "\r\n", "\r", "a\nb", "b\r\na", " ")
    }

    /** A regex of the pool, with the engine find() must pick for it and the reference to use. */
    private class Pattern(val regex: Regex, val multiline: Boolean, val plain: Boolean)

    private val patterns = listOf(
        // line by line, no anchors or lookarounds: substring reference
        Pattern(Regex("a"), multiline = false, plain = true),
        Pattern(Regex("ab"), multiline = false, plain = true),
        Pattern(Regex("a+"), multiline = false, plain = true),
        Pattern(Regex("[bc]+"), multiline = false, plain = true),
        Pattern(Regex("b.?a"), multiline = false, plain = true),
        Pattern(Regex("a\\s?b"), multiline = false, plain = true),
        Pattern(Regex("(ab|ba)"), multiline = false, plain = true),
        Pattern(Regex("\\w{2}"), multiline = false, plain = true),
        Pattern(Regex("c*"), multiline = false, plain = true), // empty matches
        Pattern(Regex("A", RegexOption.IGNORE_CASE), multiline = false, plain = true),
        // line by line, anchors and lookarounds: region reference
        Pattern(Regex("^a"), multiline = false, plain = false),
        Pattern(Regex("b$"), multiline = false, plain = false),
        Pattern(Regex("^\\w*"), multiline = false, plain = false),
        Pattern(Regex("\\ba"), multiline = false, plain = false),
        Pattern(Regex("(?<=a)b"), multiline = false, plain = false),
        Pattern(Regex("c$|^b"), multiline = false, plain = false),
        // multiline engine: the source names a line break, or MULTILINE is set
        Pattern(Regex("a\\nb"), multiline = true, plain = false),
        Pattern(Regex("\\n\\n"), multiline = true, plain = false),
        Pattern(Regex("b\\s*\\n"), multiline = true, plain = false),
        Pattern(Regex("\\n^a"), multiline = true, plain = false),
        Pattern(Regex("[^a]\\r?\\nb"), multiline = true, plain = false),
        Pattern(Regex("\\W+"), multiline = true, plain = false),
        Pattern(Regex("^ab", RegexOption.MULTILINE), multiline = true, plain = false),
        Pattern(Regex("a$", RegexOption.MULTILINE), multiline = true, plain = false),
        Pattern(Regex("a\\s+b", RegexOption.MULTILINE), multiline = true, plain = false),
        Pattern(Regex("^$", RegexOption.MULTILINE), multiline = true, plain = false)
    )

    // ------------------------------------------------------------------ references

    private fun columns(lines: List<String>, range: Range, line: Int): IntArray {
        val from = if (line == range.startLine) range.startColumn - 1 else 0
        val to = if (line == range.endLine) range.endColumn - 1 else lines[line - 1].length
        return intArrayOf(from, to)
    }

    private fun naiveTextInLines(lines: List<String>, range: Range, needle: String, limit: Int): List<Range> {
        val result = mutableListOf<Range>()
        for (line in range.startLine..range.endLine) {
            val text = lines[line - 1]
            val (from, to) = columns(lines, range, line)
            var i = from
            while (i + needle.length <= to && result.size < limit) {
                if (text.regionMatches(i, needle, 0, needle.length)) {
                    result.add(Range(line, i + 1, line, i + 1 + needle.length))
                    i += needle.length
                } else {
                    i++
                }
            }
        }
        return result
    }

    // end of a match of [needle] (line breaks normalized to "\n") at [start] of [doc], or -1
    private fun matchAcrossLines(doc: String, start: Int, end: Int, needle: String): Int {
        var q = start
        for (c in needle) {
            if (c == '\n') {
                if (q + 1 < end && doc[q] == '\r' && doc[q + 1] == '\n') {
                    q += 2
                } else if (q < end && (doc[q] == '\n' || doc[q] == '\r')) {
                    q++
                } else {
                    return -1
                }
            } else if (q < end && doc[q] == c) {
                q++
            } else {
                return -1
            }
        }
        return q
    }

    private fun naiveTextAcrossLines(doc: String, start: Int, end: Int, needle: String, limit: Int): List<Range> {
        val normalized = RandomEditing.normalize(needle, "\n")
        val result = mutableListOf<Range>()
        var p = start
        while (p < end && result.size < limit) {
            if (p > 0 && doc[p - 1] == '\r' && doc[p] == '\n') {
                p++ // between "\r" and "\n": not a position
                continue
            }
            val matchEnd = matchAcrossLines(doc, p, end, normalized)
            if (matchEnd >= 0) {
                result.add(Range.fromPositions(RandomEditing.positionOf(doc, p), RandomEditing.positionOf(doc, matchEnd)))
                p = matchEnd
            } else {
                p++
            }
        }
        return result
    }

    private fun referenceRegexInLines(lines: List<String>, range: Range, pattern: Pattern, limit: Int): List<Range> {
        val result = mutableListOf<Range>()
        for (line in range.startLine..range.endLine) {
            val text = lines[line - 1]
            val (from, to) = columns(lines, range, line)
            if (pattern.plain) {
                for (match in pattern.regex.findAll(text.substring(from, to))) {
                    if (result.size >= limit) break
                    result.add(Range(line, from + match.range.first + 1, line, from + match.range.last + 2))
                }
            } else {
                val matcher = pattern.regex.toPattern().matcher(text)
                    .region(from, to)
                    .useTransparentBounds(true)
                    .useAnchoringBounds(false)
                while (result.size < limit && matcher.find()) {
                    result.add(Range(line, matcher.start() + 1, line, matcher.end() + 1))
                }
            }
        }
        return result
    }

    private fun referenceRegexMultiline(lines: List<String>, range: Range, pattern: Pattern, limit: Int): List<Range> {
        val joined = lines.subList(range.startLine - 1, range.endLine).joinToString("\n")
        val lastLineLength = lines[range.endLine - 1].length
        val regionEnd = joined.length - (lastLineLength - (range.endColumn - 1))

        // (line, column) of an index of `joined`, which starts at column 1 of range.startLine
        fun position(index: Int): Position {
            var line = range.startLine
            var lineStart = 0
            for (i in 0 until index) {
                if (joined[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
            }
            return Position(line, index - lineStart + 1)
        }

        val regex = Regex(pattern.regex.pattern, pattern.regex.options + RegexOption.MULTILINE)
        val matcher = regex.toPattern().matcher(joined)
            .region(range.startColumn - 1, regionEnd)
            .useTransparentBounds(true)
            .useAnchoringBounds(false)
        val result = mutableListOf<Range>()
        while (result.size < limit && matcher.find()) {
            result.add(Range.fromPositions(position(matcher.start()), position(matcher.end())))
        }
        return result
    }

    // ------------------------------------------------------------------ the test

    private class Span(val start: Int, val end: Int, val range: Range)

    private fun randomSpan(random: Random, model: String): Span {
        if (random.nextInt(5) == 0) {
            return Span(0, model.length, Range.fromPositions(Position(1, 1), RandomEditing.positionOf(model, model.length)))
        }
        var a = RandomEditing.snap(model, random.nextInt(model.length + 1))
        var b = RandomEditing.snap(model, random.nextInt(model.length + 1))
        if (a > b) {
            val t = a
            a = b
            b = t
        }
        return Span(a, b, Range.fromPositions(RandomEditing.positionOf(model, a), RandomEditing.positionOf(model, b)))
    }

    private fun randomNeedle(random: Random, model: String): String {
        if (model.isEmpty() || random.nextInt(10) == 0) {
            return listOf("zz", "a", "ab", "\n", "b\na")[random.nextInt(5)]
        }
        val start = random.nextInt(model.length)
        val needle = model.substring(start, Math.min(model.length, start + 1 + random.nextInt(6)))
        // the search text may use another line break kind than the document: all must match
        return when (random.nextInt(4)) {
            0 -> RandomEditing.normalize(needle, "\n")
            1 -> RandomEditing.normalize(needle, "\r\n")
            2 -> RandomEditing.normalize(needle, "\r")
            else -> needle
        }
    }

    @Test
    fun findMatchesTheNaiveReference() {
        for (pattern in patterns) {
            val engineIsMultiline = RegexOption.MULTILINE in pattern.regex.options ||
                Strings.isMultilineRegexSource(pattern.regex.pattern)
            assertEquals(pattern.multiline, engineIsMultiline, "pool entry /${pattern.regex.pattern}/")
        }

        var plainHits = 0
        var regexHits = 0
        for (seed in SEEDS) {
            val random = Random(seed)
            val raw = RandomEditing.randomDocument(
                random,
                200 + random.nextInt(600),
                alphabet = "aab c",
                crlfBias = seed % 2 == 0L,
                emoji = false
            )
            val buffer = RandomEditing.build(raw, random)
            val eol = RandomEditing.detectEOL(raw)
            var model = RandomEditing.normalize(raw, eol)
            repeat(40) {
                val edit = RandomEditing.randomEdit(random, model, eol, TEXTS)
                buffer.applyEdits(edit.ops, false, false)
                model = edit.expected
            }
            assertEquals(model, buffer.toString(), "seed $seed: document")
            val lines = RandomEditing.lines(model, eol)

            repeat(150) { query ->
                val span = randomSpan(random, model)
                val range = span.range
                val limit = if (random.nextInt(4) == 0) 1 + random.nextInt(3) else 1000
                val where = "seed $seed, query $query, range $range, limit $limit"

                if (random.nextBoolean()) {
                    val needle = randomNeedle(random, model)
                    val expected = if (needle.any { it == '\r' || it == '\n' }) {
                        naiveTextAcrossLines(model, span.start, span.end, needle, limit)
                    } else {
                        naiveTextInLines(lines, range, needle, limit)
                    }
                    val actual = buffer.find(needle, range, limit) { false }
                    assertEquals(expected, actual, "$where, text \"${Strings.escapeLineFeed(needle)}\"")
                    plainHits += actual.size
                } else {
                    val pattern = patterns[random.nextInt(patterns.size)]
                    val expected = if (pattern.multiline) {
                        referenceRegexMultiline(lines, range, pattern, limit)
                    } else {
                        referenceRegexInLines(lines, range, pattern, limit)
                    }
                    val actual = buffer.find(pattern.regex, range, limit) { false }
                    assertEquals(expected, actual, "$where, regex /${pattern.regex.pattern}/")
                    regexHits += actual.size
                }
            }
        }
        // a reference that never finds anything would agree with a broken search
        assertTrue(plainHits > 1000, "plain text searches found only $plainHits matches")
        assertTrue(regexHits > 1000, "regex searches found only $regexHits matches")
    }

    /** A cancelled search stops; nothing is found after isCancelled() turned true. */
    @Test
    fun cancelledSearchStops() {
        val buffer = RandomEditing.build("ab\n".repeat(100), Random(1))
        val all = Range(1, 1, buffer.getLineCount(), 1)
        var calls = 0
        val found = buffer.find(Regex("a"), all, 1000) { ++calls > 5 }
        assertTrue(found.size <= 5, "found ${found.size} after cancelling")
        val multiline = buffer.find(Regex("a\\nb"), all, 1000) { true }
        assertEquals(0, multiline.size)
    }
}
