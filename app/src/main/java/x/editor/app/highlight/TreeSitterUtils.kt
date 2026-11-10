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

package x.editor.app.highlight

/**
 * One capture of a query: its name (`string`, `comment`, `fold`, `indent.begin`) and
 * the node it captured, from [start] to [end] in chars (end exclusive).
 */
data class SyntaxCapture(val name: String, val start: Int, val end: Int)

/**
 * The parts of the tree-sitter support that are plain computation: which grammar
 * a file gets, how a query file inherits from another, how a large text is handed
 * to the parser in pieces, which parts of a range no span covers, and what the
 * captures of a query say about the structure of the text.
 *
 * No Android and no native code in here, so all of it runs in a JVM unit test.
 */
object TreeSitterUtils {

    /** What the name of every grammar symbol starts with: `tree_sitter_kotlin`. */
    const val SYMBOL_PREFIX = "tree_sitter_"

    // "; inherits: c" or "; inherits: c,cpp" (the form nvim-treesitter uses)
    private val INHERITS = Regex(""";+\s*inherits\s*:\s*([\w\-]+(?:\s*,\s*[\w\-]+)*)""")

    /**
     * The grammar symbol (such as `tree_sitter_c`) for a file, from a map of file
     * names and extensions to symbols.
     *
     * The whole name is tried first (`Makefile`, `CMakeLists.txt`), then the
     * extension with its dot (`.kt`). null if the map has neither.
     */
    fun symbolFor(fileName: String, fileTypes: Map<String, String>): String? {
        fileTypes[fileName]?.let { return it }
        val dot = fileName.lastIndexOf('.')
        return if (dot >= 0) fileTypes[fileName.substring(dot)] else null
    }

    /** The grammar name a symbol stands for: `tree_sitter_Kotlin` gives `kotlin`. */
    fun grammarNameOf(symbol: String): String =
        symbol.removePrefix(SYMBOL_PREFIX).lowercase()

    /**
     * The grammars a query file inherits from, as named in its `; inherits:`
     * comment, in the order given there. Empty if it has no such comment.
     */
    fun inheritedGrammars(querySource: String): List<String> {
        val match = INHERITS.find(querySource) ?: return emptyList()
        return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The complete query pattern of the grammar [name]: the patterns of the
     * grammars it inherits from (and theirs, and so on), followed by its own, so
     * that its own patterns come last, as tree-sitter queries expect of the more
     * specific ones.
     *
     * @param read the text of the query file of a grammar, or null if there is none
     * @return null if the grammar [name] itself has no query file. An inherited
     *   grammar without one is left out; one that is reached twice (also in a
     *   circle) is included once.
     */
    fun resolvePattern(name: String, read: (String) -> String?): String? {
        val result = StringBuilder()
        val visited = HashSet<String>()

        fun append(grammar: String): Boolean {
            if (!visited.add(grammar)) return true
            val source = read(grammar) ?: return false
            for (parent in inheritedGrammars(source)) {
                append(parent)
            }
            result.append(source)
            // a file that does not end in a line break must not run into the next
            if (!source.endsWith("\n")) {
                result.append('\n')
            }
            return true
        }

        return if (append(name)) result.toString() else null
    }

    /**
     * Where the piece of text that starts at [offset] ends, when a text of
     * [length] chars is handed out in blocks of [blockSize]: at the next multiple
     * of the block size, or at the end of the text.
     *
     * The answer depends on the offset alone, not on what was asked before: a
     * parser that reuses parts of an old tree asks for offsets in any order, and
     * skips far ahead.
     *
     * @return an offset in `offset..length`; [offset] itself only at or past the end
     */
    fun chunkEnd(offset: Int, length: Int, blockSize: Int): Int {
        require(blockSize > 0) { "blockSize must be positive: $blockSize" }
        // at or past the end: there is no text left
        if (offset >= length) return offset
        val start = maxOf(offset, 0)
        // Long: the next multiple can be beyond Int.MAX_VALUE
        val next = (start.toLong() / blockSize + 1) * blockSize
        return minOf(next, length.toLong()).toInt()
    }

    /**
     * The parts of [start, end) that none of [covered] covers, in order.
     *
     * [covered] are half-open ranges (first inclusive, second exclusive) in any
     * order; they may overlap each other, be the same, or reach outside of
     * [start, end).
     */
    fun uncoveredGaps(
        covered: List<Pair<Int, Int>>,
        start: Int,
        end: Int
    ): List<Pair<Int, Int>> {
        val gaps = ArrayList<Pair<Int, Int>>()
        // everything before this position is either covered or already a gap
        var position = start
        for ((from, to) in covered.sortedBy { it.first }) {
            if (position >= end) break
            if (to <= position) continue
            if (from > position) {
                gaps.add(Pair(position, minOf(from, end)))
            }
            position = maxOf(position, to)
        }
        if (position < end) {
            gaps.add(Pair(position, end))
        }
        return gaps
    }

    // #region structure

    // capture names (and their prefixes) of text that is not code: a bracket in it
    // does not count
    private val NOT_CODE = listOf("string", "comment", "character")

    // the last line of a block that ends with one of these words closes it, like a
    // closing brace does
    private val CLOSING_WORDS = setOf("end", "fi", "done", "esac", "endif", "endfor", "endwhile", "endfunction")

    /**
     * Whether the char at [offset] is code, as far as [captures] (of the highlights
     * query) tell: the smallest capture around it decides. In a string or a comment
     * it is not; inside a string, in an interpolation that has captures of its own
     * (`"${ f(x) }"`), it is again.
     */
    fun isCodeAt(captures: List<SyntaxCapture>, offset: Int): Boolean {
        var innermost: SyntaxCapture? = null
        for (capture in captures) {
            if (offset < capture.start || offset >= capture.end) continue
            val current = innermost
            if (current == null || capture.end - capture.start < current.end - current.start) {
                innermost = capture
            }
        }
        val name = innermost?.name ?: return true
        return NOT_CODE.none { name == it || name.startsWith("$it.") }
    }

    /**
     * Whether [text], the part of the last line of a block up to where the block
     * ends (without the indentation), only closes the block: `}`, `});`, `end`.
     * Such a line is not inside the block, and its indent guide stops above it.
     */
    fun isClosingText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.none { it.isLetterOrDigit() } || trimmed in CLOSING_WORDS
    }

    /** The 1-based column of the first char of [line] that is not whitespace; 1 for a blank line. */
    fun firstNonWhitespaceColumn(line: CharSequence): Int {
        for (index in line.indices) {
            if (!line[index].isWhitespace()) return index + 1
        }
        return 1
    }

    // #endregion
}
