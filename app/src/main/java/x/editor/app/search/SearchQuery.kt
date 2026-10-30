/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.search

/** How the text of the search box is to be understood. */
enum class SearchMode {
    /** as a regular expression */
    REGEX,
    /** as the literal text to look for */
    LITERAL
}

/**
 * One search request: what the search box holds and the options that were set when
 * it was made. Immutable, so it can be handed to the thread that searches.
 */
data class SearchQuery(
    val text: String,
    val mode: SearchMode = SearchMode.REGEX,
    val ignoreCase: Boolean = false
) {

    /** Nothing to search for: the result is "no matches", not "everything". */
    val isBlank: Boolean
        get() = text.isEmpty()

    /**
     * The options to compile [text] with in [SearchMode.REGEX]: IGNORE_CASE if asked
     * for, and MULTILINE as soon as the text itself spans lines, so that ^ and $
     * then match at every line of it.
     */
    val regexOptions: Set<RegexOption>
        get() {
            val options = mutableSetOf<RegexOption>()
            if (ignoreCase) {
                options.add(RegexOption.IGNORE_CASE)
            }
            if (text.any { it == '\n' || it == '\r' }) {
                options.add(RegexOption.MULTILINE)
            }
            return options
        }

    /**
     * [text] compiled with [regexOptions].
     *
     * @throws java.util.regex.PatternSyntaxException if it is not a valid regular
     *   expression, which is the normal state of one that is still being typed
     */
    fun toRegex(): Regex = Regex(text, regexOptions)

    companion object {

        /** The result line for a finished search: "Results: 12". */
        fun formatCount(label: String, count: Int): String = "$label: $count"

        /**
         * The result line while stepping through the matches: "Results: 3/12".
         *
         * @param index the zero-based index of the current match
         */
        fun formatPosition(label: String, index: Int, count: Int): String =
            "$label: ${index + 1}/$count"
    }
}
