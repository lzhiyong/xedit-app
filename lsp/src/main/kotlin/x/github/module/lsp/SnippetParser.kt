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

package x.github.module.lsp

/**
 * A snippet as text: [text] to insert, and the [tabStops] in it (offsets in [text]),
 * in the order the cursor goes through them: 1, 2, ... and 0, the last place.
 * A tab stop with a placeholder spans it (it is selected); one without is empty.
 */
data class Snippet(val text: String, val tabStops: List<TabStop>) {

    /** A place the cursor goes to: [start] to [end] in the text, [index] as in `$1`. */
    data class TabStop(val index: Int, val start: Int, val end: Int)

    /** Where the cursor goes first: the first tab stop, or the end of the text. */
    val cursor: TabStop
        get() = tabStops.firstOrNull() ?: TabStop(0, text.length, text.length)
}

/**
 * Turns the snippets of completions (InsertTextFormat.Snippet) into text and tab
 * stops, as the protocol writes them:
 *
 *  - `$1`, `${1}`: a tab stop; `$0` the last one;
 *  - `${1:placeholder}`: with a placeholder, which may hold snippets itself;
 *  - `${1|one,two|}`: a choice, the first is taken;
 *  - a tab stop again (`for (${1:i} = 0; $1 < n; $1++)`): the same text as where it
 *    has its placeholder (a mirror);
 *  - `$NAME`, `${NAME}`, `${NAME:default}`: variables; the editor has none of
 *    them here, so the default (or nothing) is taken;
 *  - `\$`, `\}`, `\\` (and `\,` `\|` in a choice): the character itself.
 *
 * Anything else is taken as it is: a lone `$`, an unclosed `${`.
 */
object SnippetParser {

    fun parse(snippet: String): Snippet {
        val parser = Parser(snippet)
        val text = StringBuilder()
        val stops = ArrayList<Snippet.TabStop>()
        parser.parseInto(text, stops, until = null)
        // 1, 2, ... in order (a tab stop repeated: where it is first), then 0
        val ordered = stops
            .groupBy { it.index }
            .map { (_, sameIndex) -> sameIndex.first() }
            .sortedWith(compareBy<Snippet.TabStop> { if (it.index == 0) Int.MAX_VALUE else it.index })
        return Snippet(text.toString(), ordered)
    }

    private class Parser(private val source: String) {
        private var i = 0

        // the text of each tab stop with a placeholder, for its mirrors
        private val placeholders = HashMap<Int, String>()

        // Reads into [out] until [until] (not taken; null: the end), and the tab stops into [stops].
        fun parseInto(out: StringBuilder, stops: MutableList<Snippet.TabStop>, until: Char?) {
            while (i < source.length) {
                val c = source[i]
                when {
                    until != null && c == until -> return
                    c == '\\' && i + 1 < source.length && source[i + 1] in "\\\$}" -> {
                        out.append(source[i + 1])
                        i += 2
                    }
                    c == '$' -> parseDollar(out, stops)
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseDollar(out: StringBuilder, stops: MutableList<Snippet.TabStop>) {
            val start = i
            i++ // $
            if (i >= source.length) {
                out.append('$')
                return
            }
            val c = source[i]
            when {
                // $1: a mirror if it has a placeholder elsewhere
                c.isDigit() -> {
                    val index = readNumber()
                    val at = out.length
                    out.append(placeholders[index].orEmpty())
                    stops += Snippet.TabStop(index, at, out.length)
                }
                // $NAME
                c.isLetter() || c == '_' -> readName()
                // ${...}
                c == '{' -> parseBraced(out, stops, start)
                else -> out.append('$')
            }
        }

        private fun parseBraced(out: StringBuilder, stops: MutableList<Snippet.TabStop>, start: Int) {
            i++ // {
            if (i < source.length && source[i].isDigit()) {
                val index = readNumber()
                val at = out.length
                when {
                    // ${1}: like $1
                    peek('}') -> {
                        i++
                        out.append(placeholders[index].orEmpty())
                        stops += Snippet.TabStop(index, at, out.length)
                    }
                    // ${1:placeholder}
                    peek(':') -> {
                        i++
                        parseInto(out, stops, until = '}')
                        if (peek('}')) i++
                        placeholders.putIfAbsent(index, out.substring(at))
                        stops += Snippet.TabStop(index, at, out.length)
                    }
                    // ${1|one,two|}
                    peek('|') -> {
                        i++
                        val first = readChoices()
                        out.append(first)
                        placeholders.putIfAbsent(index, first)
                        stops += Snippet.TabStop(index, at, out.length)
                    }
                    else -> takeLiterally(out, start)
                }
                return
            }
            if (i < source.length && (source[i].isLetter() || source[i] == '_')) {
                readName()
                when {
                    // ${NAME}
                    peek('}') -> i++
                    // ${NAME:default}
                    peek(':') -> {
                        i++
                        parseInto(out, stops, until = '}')
                        if (peek('}')) i++
                    }
                    else -> takeLiterally(out, start)
                }
                return
            }
            takeLiterally(out, start)
        }

        // the options of a choice up to "|}", the first of them
        private fun readChoices(): String {
            val options = ArrayList<String>()
            val current = StringBuilder()
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '\\' && i + 1 < source.length && source[i + 1] in "\\,|" -> {
                        current.append(source[i + 1])
                        i += 2
                    }
                    c == ',' -> {
                        options += current.toString()
                        current.clear()
                        i++
                    }
                    c == '|' && i + 1 < source.length && source[i + 1] == '}' -> {
                        options += current.toString()
                        i += 2
                        return options.first()
                    }
                    else -> {
                        current.append(c)
                        i++
                    }
                }
            }
            options += current.toString()
            return options.first()
        }

        // not a snippet after all: the "$" as text, and on from after it
        private fun takeLiterally(out: StringBuilder, start: Int) {
            out.append('$')
            i = start + 1
        }

        private fun peek(c: Char) = i < source.length && source[i] == c

        private fun readNumber(): Int {
            val start = i
            while (i < source.length && source[i].isDigit()) i++
            return source.substring(start, i).toIntOrNull() ?: 0
        }

        private fun readName() {
            while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
        }
    }
}
