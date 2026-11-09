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

package x.editor.app.config

/**
 * Edits of a JSONC text (JSON with comments and trailing commas) that keep the rest of it
 * as it is: its comments, its layout, the elements around. Used by the panels whose
 * entries are the elements of one list in one file (packages.json: `"packages"`,
 * keymap.json: `"keymaps"`): to find an entry ([elementsOf]), to take it out
 * ([removeAt]) and to add one at the end ([append]). The text is never parsed and
 * written again (that would lose the comments). Pure Kotlin: no Android in it.
 */
internal object JsoncSpans {

    /** Where an element of a list is in the text: from its first char to after its last. */
    class Span(val start: Int, val end: Int) {
        operator fun contains(offset: Int): Boolean = offset in start until end
    }

    /**
     * The elements of the list under the top-level key [key], in order.
     * IllegalArgumentException if the text is not an object with such a list.
     */
    fun elementsOf(text: String, key: String): List<Span> = elements(text, arrayStart(text, key))

    /** Where the `[` of the list under the top-level key [key] is; IllegalArgumentException if there is none. */
    fun arrayStart(text: String, key: String): Int {
        var pos = skip(text, 0)
        expect(text, pos, '{')
        pos++
        while (true) {
            pos = skip(text, pos)
            if (text.getOrNull(pos) == '}') throw IllegalArgumentException("no \"$key\" list")
            expect(text, pos, '"')
            val keyEnd = stringEnd(text, pos)
            val name = text.substring(pos + 1, keyEnd - 1)
            pos = skip(text, keyEnd)
            expect(text, pos, ':')
            pos = skip(text, pos + 1)
            if (name == key) {
                expect(text, pos, '[')
                return pos
            }
            pos = skip(text, valueEnd(text, pos))
            when (text.getOrNull(pos)) {
                ',' -> pos++
                '}' -> throw IllegalArgumentException("no \"$key\" list")
                else -> throw IllegalArgumentException("not JSON at ${pos + 1}")
            }
        }
    }

    // the elements of the list whose `[` is at [start]
    private fun elements(text: String, start: Int): List<Span> {
        expect(text, start, '[')
        val spans = ArrayList<Span>()
        var pos = start + 1
        while (true) {
            pos = skip(text, pos)
            if (text.getOrNull(pos) == ']') return spans
            val end = valueEnd(text, pos)
            spans += Span(pos, end)
            pos = skip(text, end)
            when (text.getOrNull(pos)) {
                ',' -> pos++
                ']' -> return spans
                else -> throw IllegalArgumentException("not JSON at ${pos + 1}")
            }
        }
    }

    /**
     * The text without the element [index] of [spans] and the comma that goes with it: the
     * one after it (with its line, if nothing else is on it, and a comment after the comma),
     * else, the last, the one after the element before it (a comment after that comma stays
     * with that one); the only one: itself, with its line if nothing else is on it.
     */
    fun removeAt(text: String, spans: List<Span>, index: Int): String {
        val span = spans[index]
        val after = skip(text, span.end)
        val lineStart = text.lastIndexOf('\n', span.start - 1) + 1
        val aloneOnItsLine = text.substring(lineStart, span.start).isBlank()
        if (text.getOrNull(after) == ',') {
            val from = if (aloneOnItsLine) lineStart else span.start
            var to = after + 1
            if (aloneOnItsLine) {
                var k = spacesFrom(text, to)
                if (text.startsWith("//", k)) k = lineEnd(text, k)
                if (k >= text.length || text[k] == '\n') to = minOf(k + 1, text.length)
            } else {
                // on a line with others: the spaces after its comma too
                to = spacesFrom(text, to)
            }
            return text.removeRange(from, to)
        }
        if (index > 0) {
            val comma = skip(text, spans[index - 1].end)
            var keep = comma + 1
            val k = spacesFrom(text, keep)
            if (text.startsWith("//", k)) keep = lineEnd(text, k)
            return text.substring(0, comma) + text.substring(comma + 1, keep) + text.substring(span.end)
        }
        val from = if (aloneOnItsLine) lineStart else span.start
        var to = span.end
        if (aloneOnItsLine) {
            val k = spacesFrom(text, to)
            if (k >= text.length || text[k] == '\n') to = minOf(k + 1, text.length)
        }
        return text.removeRange(from, to)
    }

    /** What [append] made: the new text, and where the element put in starts in it. */
    class Appended(val text: String, val start: Int)

    /**
     * [text] with [element] (a value, one line or more) put at the end of the list under
     * the top-level key [key]: on a line of its own, indented as the last element is (or
     * one step more than the line of the list, when it is empty), its lines after the first
     * indented as much. A comma is put in where one is needed; a trailing comma of the list
     * is kept. IllegalArgumentException if there is no such list.
     */
    fun append(text: String, key: String, element: String): Appended {
        val open = arrayStart(text, key)
        val spans = elements(text, open)
        val listLine = text.substring(text.lastIndexOf('\n', open - 1) + 1, open)
        val listIndent = listLine.takeWhile { it == ' ' || it == '\t' }
        val indent = spans.lastOrNull()?.let { last ->
            val lineStart = text.lastIndexOf('\n', last.start - 1) + 1
            val before = text.substring(lineStart, last.start)
            if (before.isBlank()) before else null
        } ?: (listIndent + INDENT)
        val body = element.trim().lines().mapIndexed { i, line -> if (i == 0) line else indent + line }.joinToString("\n")
        val last = spans.lastOrNull()
        if (last == null) {
            // an empty list: the element on a line of its own, the `]` on the next one (what
            // was between them, a comment, kept after it)
            val close = skip(text, open + 1)
            val between = text.substring(open + 1, close)
            val head = text.substring(0, open + 1) + "\n" + indent
            val tail = if (between.isBlank()) "\n" + listIndent + text.substring(close) else text.substring(open + 1)
            return Appended(head + body + tail, head.length)
        }
        val after = skip(text, last.end)
        return if (text.getOrNull(after) == ',') {
            // a trailing comma: kept after the element before, the new one after it
            val at = after + 1
            val head = text.substring(0, at) + "\n" + indent
            Appended(head + body + text.substring(at), head.length)
        } else {
            // the comma right after the last element; a comment at the end of its line stays
            // with it, the new element on the next line
            val k = spacesFrom(text, last.end)
            val at = if (text.startsWith("//", k)) lineEnd(text, k) else last.end
            val head = text.substring(0, last.end) + "," + text.substring(last.end, at) + "\n" + indent
            Appended(head + body + text.substring(at), head.length)
        }
    }

    /** The line (1-based) and column (1-based, in UTF-16 chars) of [offset] in [text]. */
    fun positionOf(text: CharSequence, offset: Int): Pair<Int, Int> {
        val at = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until at) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (at - lineStart + 1)
    }

    private fun expect(text: String, pos: Int, c: Char) {
        if (text.getOrNull(pos) != c) throw IllegalArgumentException("'$c' expected at ${pos + 1}")
    }

    /** After the spaces, line breaks and comments from [start]. */
    fun skip(text: String, start: Int): Int {
        var pos = start
        while (pos < text.length) {
            val c = text[pos]
            pos = when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF' -> pos + 1
                text.startsWith("//", pos) -> lineEnd(text, pos)
                text.startsWith("/*", pos) -> text.indexOf("*/", pos + 2).let { if (it < 0) text.length else it + 2 }
                else -> return pos
            }
        }
        return pos
    }

    // after the spaces and tabs (and a \r) from [start], on its line
    private fun spacesFrom(text: String, start: Int): Int {
        var pos = start
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\r')) pos++
        return pos
    }

    // where the line of [pos] ends (its \n, or the end of the text)
    private fun lineEnd(text: String, pos: Int): Int = text.indexOf('\n', pos).let { if (it < 0) text.length else it }

    // after the string whose `"` is at [start]
    private fun stringEnd(text: String, start: Int): Int {
        var pos = start + 1
        while (pos < text.length) {
            when (text[pos]) {
                '\\' -> pos += 2
                '"' -> return pos + 1
                else -> pos++
            }
        }
        throw IllegalArgumentException("a string not ended, from ${start + 1}")
    }

    // after the value at [start]: a string, an object or list (to its matching end), a word or number
    private fun valueEnd(text: String, start: Int): Int {
        val first = text.getOrNull(start) ?: throw IllegalArgumentException("a value expected at the end")
        if (first == '"') return stringEnd(text, start)
        if (first == '{' || first == '[') {
            var depth = 0
            var pos = start
            while (pos < text.length) {
                val c = text[pos]
                when {
                    c == '"' -> { pos = stringEnd(text, pos); continue }
                    text.startsWith("//", pos) || text.startsWith("/*", pos) -> { pos = skip(text, pos); continue }
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> if (--depth == 0) return pos + 1
                }
                pos++
            }
            throw IllegalArgumentException("'$first' not closed, from ${start + 1}")
        }
        var pos = start
        while (pos < text.length && text[pos] !in ",]} \t\r\n/") pos++
        if (pos == start) throw IllegalArgumentException("a value expected at ${start + 1}")
        return pos
    }

    // one step of indentation, for an element of a list that was empty
    private const val INDENT = "  "
}
