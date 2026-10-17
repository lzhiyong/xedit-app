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

package x.editor.app.completion

import kotlin.math.ln
import kotlin.math.min

/**
 * Completion from the words of the text itself, as the buffer source of blink.cmp: what
 * the editor offers when there is no language server (or it has nothing). Plain
 * computation on a CharSequence (a snapshot of the text), off the main thread.
 *
 *  - [index]: every word of the text (letters, digits, `_`, `$`; 2 to 64 chars) and how
 *    often it is there, but for the word being typed;
 *  - [near]: the words around the cursor, and how far they are from it;
 *  - [match]: fuzzy matching of a word against what is typed: its first char, then the
 *    others in order, with points for a prefix, for chars at the start of a part
 *    (`some_name`, `someName`), for chars that follow each other; points off for gaps;
 *  - [rank]: the words that match, best first: the match, then how often the word is
 *    there and how near the cursor.
 */
internal object BufferWords {

    const val MIN_WORD = 2
    const val MAX_WORD = 64
    // the text is read in chunks this long
    private const val CHUNK = 64 * 1024

    /** One word to offer: [word], its [positions] that match what was typed, its [score]. */
    data class Candidate(val word: String, val positions: IntArray, val score: Int) {
        override fun equals(other: Any?) = other is Candidate && other.word == word
        override fun hashCode() = word.hashCode()
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * The words of [text] from [from] to [to] and how often each is there; the word
     * that covers [skipStart, skipEnd) (the one being typed) is left out.
     */
    fun index(
        text: CharSequence,
        from: Int = 0,
        to: Int = text.length,
        skipStart: Int = -1,
        skipEnd: Int = -1,
    ): HashMap<String, Int> {
        val counts = HashMap<String, Int>()
        forEachWord(text, from, to) { start, end, word ->
            if (start <= skipEnd && end >= skipStart && skipStart >= 0) return@forEachWord
            counts[word] = (counts[word] ?: 0) + 1
        }
        return counts
    }

    /** The words within [window] chars of [cursor], with how far (in chars) the nearest one is. */
    fun near(text: CharSequence, cursor: Int, window: Int, skipStart: Int = -1, skipEnd: Int = -1): HashMap<String, Int> {
        val distances = HashMap<String, Int>()
        val from = (cursor - window).coerceAtLeast(0)
        val to = (cursor + window).coerceAtMost(text.length)
        forEachWord(text, from, to) { start, end, word ->
            if (start <= skipEnd && end >= skipStart && skipStart >= 0) return@forEachWord
            val distance = if (end <= cursor) cursor - end else (start - cursor).coerceAtLeast(0)
            val old = distances[word]
            if (old == null || distance < old) distances[word] = distance
        }
        return distances
    }

    // the words of [text] in [from, to): their start, end and text; a word cut by [from]
    // or [to] is not one (it is a part of one)
    private inline fun forEachWord(text: CharSequence, from: Int, to: Int, action: (Int, Int, String) -> Unit) {
        var start = from
        // a word that starts before [from] is a part of one: it is skipped
        while (start < to && start > 0 && isWordChar(text[start - 1]) && isWordChar(text[start])) start++
        val word = StringBuilder()
        var wordStart = -1
        var chunkStart = start
        while (chunkStart < to) {
            val chunkEnd = min(to, chunkStart + CHUNK)
            val chunk = text.subSequence(chunkStart, chunkEnd).toString()
            for (i in chunk.indices) {
                val c = chunk[i]
                if (isWordChar(c)) {
                    if (wordStart < 0) wordStart = chunkStart + i
                    if (word.length <= MAX_WORD) word.append(c)
                } else if (wordStart >= 0) {
                    emit(word, wordStart, chunkStart + i, action)
                    word.setLength(0)
                    wordStart = -1
                }
            }
            chunkStart = chunkEnd
        }
        // a word at [to] counts only if the text ends there
        if (wordStart >= 0 && (to >= text.length || !isWordChar(text[to]))) emit(word, wordStart, to, action)
    }

    private inline fun emit(word: StringBuilder, start: Int, end: Int, action: (Int, Int, String) -> Unit) {
        val length = end - start
        // a word that starts with a digit is a number
        if (length in MIN_WORD..MAX_WORD && !word[0].isDigit()) action(start, end, word.toString())
    }

    /**
     * How well [candidate] matches [query], with the positions of [candidate] that match;
     * null if it does not: its first char is the first of [query], the others follow in
     * order (case does not matter, but counts).
     */
    fun match(candidate: String, query: String): Pair<Int, IntArray>? {
        if (query.isEmpty() || candidate.length < query.length) return null
        if (!candidate[0].equals(query[0], ignoreCase = true)) return null
        val positions = IntArray(query.length)
        var qi = 0
        var ci = 0
        while (qi < query.length && ci < candidate.length) {
            val q = query[qi]
            // the next char that matches, at the start of a part if one comes before a
            // plain one does not (`gl` in `get_line` takes the `l` of `line`)
            var at = -1
            var j = ci
            while (j < candidate.length) {
                if (candidate[j].equals(q, ignoreCase = true)) {
                    if (at < 0) at = j
                    if (j == ci || isPartStart(candidate, j)) {
                        at = j
                        break
                    }
                    // a plain match right after the previous one is as good
                    if (qi > 0 && j == positions[qi - 1] + 1) break
                }
                j++
            }
            if (at < 0) return null
            positions[qi] = at
            ci = at + 1
            qi++
        }
        if (qi < query.length) return null

        var score = 0
        for (k in positions.indices) {
            val p = positions[k]
            score += 16
            if (p == 0) score += 12
            else if (isPartStart(candidate, p)) score += 10
            if (k > 0 && p == positions[k - 1] + 1) score += 8
            if (k > 0) score -= (p - positions[k - 1] - 1).coerceAtMost(12)
            if (candidate[p] == query[k]) score += 2
        }
        if (candidate.startsWith(query, ignoreCase = true)) score += 20
        // what is left after the last match: a little less for a long word
        score -= (candidate.length - positions.last() - 1) / 4
        return score to positions
    }

    /**
     * The chars of [label] to highlight for [query] in the list: those of [match] if it
     * matches; else, for labels that do not start with the word (clangd puts a space or a
     * `•` before them, `std::vector`), the query as a word inside the label, then as chars
     * in order anywhere. Null if they are not in it.
     */
    fun highlight(label: String, query: String): IntArray? {
        if (query.isEmpty() || label.isEmpty()) return null
        match(label, query)?.let { return it.second }
        // the query where a word of the label starts with it
        var i = label.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            if (i == 0 || !isWordChar(label[i - 1]) || isPartStart(label, i)) return IntArray(query.length) { i + it }
            i = label.indexOf(query, i + 1, ignoreCase = true)
        }
        // in order, anywhere
        val positions = IntArray(query.length)
        var at = 0
        for (k in query.indices) {
            while (at < label.length && !label[at].equals(query[k], ignoreCase = true)) at++
            if (at == label.length) return null
            positions[k] = at++
        }
        return positions
    }

    // the start of a part of a word: after `_` or `$`, a capital after a small letter, a
    // letter after a digit
    private fun isPartStart(word: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = word[i - 1]
        val c = word[i]
        return prev == '_' || prev == '$' || (prev.isLowerCase() && c.isUpperCase()) || (prev.isDigit() && c.isLetter())
    }

    /**
     * The words of [counts] that match [query], best first, at most [limit]: the match,
     * then how often the word is there, then how near the cursor ([near]: chars away).
     * The word that is [query] itself is not offered.
     */
    fun rank(counts: Map<String, Int>, near: Map<String, Int>, query: String, limit: Int): List<Candidate> {
        if (query.isEmpty()) return emptyList()
        val first = query[0]
        // the best [limit] so far: the worst of them on top, to be pushed out
        val order = compareByDescending<Candidate> { it.score }.thenBy { it.word.length }.thenBy { it.word }
        val best = java.util.PriorityQueue(limit + 1, order.reversed())
        fun consider(word: String) {
            // the first char decides most: checked before the whole match
            if (word == query || !word[0].equals(first, ignoreCase = true)) return
            val (score, positions) = match(word, query) ?: return
            val count = (counts[word] ?: 0) + if (near.containsKey(word)) 1 else 0
            val often = (ln(count.coerceAtMost(64).toDouble() + 1) / ln(2.0) * 3).toInt()
            val close = near[word]?.let { (12 - it / 200).coerceAtLeast(0) } ?: 0
            best += Candidate(word, positions, score + often + close)
            if (best.size > limit) best.poll()
        }
        for (word in counts.keys) consider(word)
        for (word in near.keys) if (!counts.containsKey(word)) consider(word)
        return best.sortedWith(order)
    }
}
