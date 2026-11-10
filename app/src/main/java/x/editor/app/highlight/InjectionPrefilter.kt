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

package x.editor.app.highlight

import x.github.module.treesitter.TSQueryPredicateArgs

/**
 * What the text of a part in an injected language must have for its highlights query to
 * give any styled capture: the comments of a file, which the comment grammar only styles
 * where they have a TODO, a NOTE, ..., an issue number (#123) or a URL. A part whose text
 * has none of it is not parsed at all (see TreeSitterEngine.mayCapture), which is most
 * comments: parsing them was most of the time a block of a large C file took.
 *
 * Worked out from the query, so that it follows the query files as they are updated: for
 * every pattern with a capture the theme styles, one thing its matches cannot do without
 *
 *  - the strings of a positive any-of? / eq? / contains? on a capture the pattern always
 *    has: the text of the capture is one of them (or has one), so the text has it;
 *  - the items a lua-match? pattern starts with that must be there (`^#[0-9]+$`: a `#`
 *    and a digit after it), as a Lua pattern found anywhere in the text;
 *  - for a pattern of one node and its captures, without predicates (`(uri) @url`), what
 *    the grammar makes such a node of: [NODE_NEEDS], from the grammars' grammar.js.
 *
 * If any styled pattern has none of these, nothing is worked out ([of] is null), and every
 * part is parsed as before. The needs are necessary, not sufficient: a part that has one
 * is parsed, and its captures are what they would be; one that has none would give none.
 */
internal class InjectionPrefilter private constructor(
    // the text must have the strings of one, or match the pattern of one
    private val strings: List<List<String>>,
    private val patterns: List<Regex>
) {

    /** Whether a part with [text] may give a styled capture: if not, it need not be parsed. */
    fun mayCapture(text: CharSequence): Boolean {
        for (group in strings) {
            for (value in group) if (text.contains(value)) return true
        }
        for (regex in patterns) if (regex.containsMatchIn(text)) return true
        return false
    }

    companion object {
        // The nodes a grammar makes of a lexical pattern alone, per injected language: a
        // pattern of such a node needs what the lexical pattern starts with.
        private val NODE_NEEDS: Map<String, Map<String, Regex>> = mapOf(
            // tree-sitter-comment: uri is /https?:\/\/(...)+/
            "comment" to mapOf("uri" to Regex("https?://"))
        )

        // TSQuantifier: the capture is always there (one, one or more)
        private const val ONE = 3
        private const val ONE_OR_MORE = 4

        /** What [layer]'s highlights need of a text, with [theme]; null if it cannot be told. */
        fun of(layer: TreeSitterInjections.Layer, theme: HighlightTheme): InjectionPrefilter? = try {
            build(layer, theme)
        } catch (e: Exception) {
            null
        }

        private fun build(layer: TreeSitterInjections.Layer, theme: HighlightTheme): InjectionPrefilter? {
            val query = layer.highlights
            val names = query.captureNames
            val source = layer.source.encodeToByteArray()
            val strings = ArrayList<List<String>>()
            val patterns = ArrayList<Regex>()
            for (pattern in 0U until query.patternCount) {
                // the captures it has, and whether any of them is styled
                val styled = names.indices.any { capture ->
                    val name = names[capture]
                    query.captureQuantifier(pattern, capture.toUInt()) != 0 &&
                        !name.startsWith("_") && theme.styleFor(name, layer.name) != null
                }
                if (!styled) continue

                fun always(capture: String): Boolean {
                    val index = names.indexOf(capture)
                    if (index < 0) return false
                    val quantifier = query.captureQuantifier(pattern, index.toUInt())
                    return quantifier == ONE || quantifier == ONE_OR_MORE
                }

                var found = false
                for (predicate in query.predicatesFor(pattern)) {
                    val args = predicate.args
                    val capture = (args.firstOrNull() as? TSQueryPredicateArgs.Capture)?.value ?: continue
                    val literals = args.drop(1).map { (it as? TSQueryPredicateArgs.Literal)?.value }
                    if (literals.any { it == null } || !always(capture)) continue
                    val values = literals.filterNotNull()
                    when (predicate.name) {
                        "eq?", "any-eq?", "any-of?", "contains?", "any-contains?" -> if (values.isNotEmpty()) {
                            strings += values
                            found = true
                        }
                        "lua-match?", "any-lua-match?" -> {
                            val start = values.firstOrNull()?.let(::leadingItems)
                            if (!start.isNullOrEmpty()) {
                                patterns += QueryPredicates.luaToRegex(start)
                                found = true
                            }
                        }
                    }
                    if (found) break
                }
                if (found) continue

                // one node and its captures, without predicates
                val from = query.startByteForPattern(pattern).toInt()
                val to = query.endByteForPattern(pattern).toInt()
                if (from < 0 || to > source.size || to < from) return null
                val text = source.decodeToString(from, to).lines()
                    .joinToString("\n") { it.substringBefore(';') }.trim()
                val node = SINGLE_NODE.matchEntire(text)?.groupValues?.get(1) ?: return null
                patterns += NODE_NEEDS[layer.name]?.get(node) ?: return null
            }
            return InjectionPrefilter(strings, patterns)
        }

        private val SINGLE_NODE = Regex("""\(\s*([A-Za-z_]\w*)\s*\)(\s*@[\w.]+)+""")

        /**
         * The items a Lua pattern starts with that every match has, as a Lua pattern to be
         * found anywhere: `^#[0-9]+$` -> `#[0-9]`, `^__builtin_` -> `__builtin_`. Only plain
         * ASCII chars, escapes, classes and sets; it stops at the first item that may be
         * missing (`*`, `-`, `?`), after one that may repeat (`+`), and at anything else.
         */
        fun leadingItems(pattern: String): String {
            var i = if (pattern.startsWith("^")) 1 else 0
            val out = StringBuilder()
            while (i < pattern.length) {
                val itemStart = i
                val c = pattern[i]
                when {
                    c == '%' -> {
                        val next = pattern.getOrNull(i + 1) ?: break
                        // a balance, a frontier, a back-reference: not one char
                        if (next == 'b' || next == 'f' || next.isDigit() || next.code >= 128) break
                        i += 2
                    }
                    c == '[' -> {
                        // the set, as Lua finds its end
                        var j = i + 1
                        if (pattern.getOrNull(j) == '^') j++
                        var closed = false
                        while (j < pattern.length) {
                            val d = pattern[j++]
                            if (d.code >= 128) break
                            if (d == '%' && j < pattern.length) j++
                            if (pattern.getOrNull(j) == ']') { closed = true; break }
                        }
                        if (!closed) break
                        i = j + 1
                    }
                    c.code >= 128 || c in "().$^*+-?" -> break
                    else -> i++
                }
                val item = pattern.substring(itemStart, i)
                when (pattern.getOrNull(i)) {
                    '*', '-', '?' -> break
                    '+' -> { out.append(item); break }
                    else -> out.append(item)
                }
            }
            return out.toString()
        }
    }
}
