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
 * Filters and orders completions by what was typed, on the client: the server is
 * asked once, and its list follows the typing (unless it said the list is
 * incomplete, CompletionResult.isIncomplete).
 *
 * An item is matched by its filter text (its label if it has none), and ranked:
 *
 *  1. it starts with what was typed, in the same case;
 *  2. it starts with it, in another case;
 *  3. what was typed are the starts of its words, in order (`gIS` and `gis` match
 *     `getInputStream`, `get_input_stream`);
 *  4. what was typed is in it, in order (`gtstrm` matches `getInputStream`);
 *
 * and the rest is dropped. Within a rank, the order of the server (sort text),
 * then the shorter label, then the label.
 */
object CompletionRanker {

    /** The word typed before [column] of [line] (0-based): what the completions are matched against. */
    fun prefixAt(line: CharSequence, column: Int): String {
        var start = column.coerceIn(0, line.length)
        val end = start
        while (start > 0 && isWordChar(line[start - 1])) start--
        return line.substring(start, end)
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** The items that match [prefix], best first; all of them, in the server's order, for an empty one. */
    fun rank(items: List<LspCompletionItem>, prefix: String): List<LspCompletionItem> {
        if (prefix.isEmpty()) {
            return items.sortedWith(compareBy<LspCompletionItem> { it.sortText }.thenBy { it.label })
        }
        return items
            .mapNotNull { item -> score(item.filterText, prefix)?.let { item to it } }
            .sortedWith(
                compareBy<Pair<LspCompletionItem, Int>> { it.second }
                    .thenBy { it.first.sortText }
                    .thenBy { it.first.label.length }
                    .thenBy { it.first.label }
            )
            .map { it.first }
    }

    /** How well [text] matches [prefix]: 0 (best) to 3; null for not at all. */
    fun score(text: String, prefix: String): Int? = when {
        prefix.isEmpty() -> 0
        text.startsWith(prefix) -> 0
        text.startsWith(prefix, ignoreCase = true) -> 1
        matchesWordStarts(text, prefix) -> 2
        isSubsequence(text, prefix) -> 3
        else -> null
    }

    // each char of [prefix] at the start of a word of [text], in order, any case
    private fun matchesWordStarts(text: String, prefix: String): Boolean {
        var p = 0
        for (i in text.indices) {
            if (p == prefix.length) break
            if (isWordStart(text, i) && text[i].equals(prefix[p], ignoreCase = true)) p++
        }
        return p == prefix.length
    }

    // the first char, a capital after a small letter or a digit, a char after _ - . or a space
    private fun isWordStart(text: String, i: Int): Boolean {
        if (i == 0) return true
        val c = text[i]
        val before = text[i - 1]
        return (c.isUpperCase() && (before.isLowerCase() || before.isDigit())) ||
            before == '_' || before == '-' || before == '.' || before == ' '
    }

    private fun isSubsequence(text: String, prefix: String): Boolean {
        var p = 0
        for (c in text) {
            if (p < prefix.length && c.equals(prefix[p], ignoreCase = true)) p++
        }
        return p == prefix.length
    }
}
