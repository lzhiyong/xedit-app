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

import android.util.Log

import x.github.module.treesitter.TSLanguage
import x.github.module.treesitter.TSNode
import x.github.module.treesitter.TSQuery
import x.github.module.treesitter.TSQueryCursor
import x.github.module.treesitter.TSTree

/**
 * A query of the structure of the text (folds.scm, indents.scm), asked about the
 * lines on screen, as the query files of nvim-treesitter are written, without them
 * being changed.
 *
 * ## Why not a query restricted to the lines
 *
 * Some patterns of these files are not rooted in one node: they are a sequence of
 * siblings (`(preproc_include)+ @fold`, `((if_statement) . (ERROR "else" @indent.begin))`).
 * Such a pattern can start at any child of a node the byte range of a cursor lies
 * in, so tree-sitter walks every child of every such node, those before the range
 * and those after it, matches or not: in sqlite3.c the 10 000 nodes at the top of the
 * file, for each block of lines. That was 2 to 4 ms a block on a desktop, many times
 * that on a phone, and every frame of a fast scroll asks for a new block.
 *
 * ## What is asked instead
 *
 * The captures that matter for lines [s, e) are those of nodes that start in them,
 * or that start before them and reach into them (the blocks a line is in: an indent
 * guide runs down from where they start). Two passes find exactly those:
 *
 *  - **inside**: a cursor over the whole tree whose matches have to lie, whole, in
 *    [s, reach) ([TSQueryCursor.containingByteRange]): the cursor does not go into the
 *    nodes before s, nor those after reach. reach is as far as a node that starts in
 *    [s, e) goes: a node that starts in them and ends after e is around the char at
 *    e - 1, so reach is the end of the outermost node around that char that starts at
 *    s or later. Nothing in [s, e) is left out, and the walk stops there.
 *  - **around**: the nodes that start before s and reach into the lines are around the
 *    char at s, one chain up to the root. For each of them, the patterns that start at
 *    the node itself ([TSQueryCursor.maxStartDepth] 0) and the sequences of its
 *    children (depth 1, patterns that are not rooted only: a rooted pattern at a child
 *    is found by the pass of that child, or by the inside pass). These do not depend
 *    on the lines asked about: they are kept per node until the tree changes. The
 *    root, whose children are the thousands of the top of a file, is asked once per
 *    tree, and can be asked on the thread that parsed it ([rootCaptures]).
 *
 * Together they find what a query from the root restricted to the lines finds, as
 * far as the lines go (checked on sqlite3.c with the C queries of nvim-treesitter: the
 * same folds and indent guides for every block). A block costs some 0.3 ms on a
 * desktop, instead of 2 to 4.
 *
 * ## How the query is loaded
 *
 *  - Patterns with none of the captures that are used ([usedCaptures]) are disabled: they
 *    add nothing here, and some walk far (`(compound_statement "}" @indent.end)`
 *    walks every statement of a function to its end).
 *  - A pattern that is an alternation at the top, with one sequence among its
 *    alternatives (`[(for_statement) ... (preproc_include)+] @fold`), is one pattern
 *    to tree-sitter, and not rooted as a whole: each of its alternatives would be
 *    found by the depth 1 pass of every node, the root's included. It is split into
 *    its alternatives (`(for_statement) @fold`, ..., `(preproc_include)+ @fold`),
 *    which match the same. Only in the query here, not in the file: see [split].
 *
 * Main thread, except [rootCaptures].
 */
internal class StructureQuery private constructor(
    private val query: TSQuery,
    // whether an enabled pattern is a sequence (not rooted): then the depth 1 pass is needed
    private val hasSequences: Boolean
) : AutoCloseable {

    /** The captures of the root of a tree for the pass around it, see [rootCaptures]. */
    class NodeCaptures internal constructor(
        internal val tree: TSTree,
        internal val captures: List<SyntaxCapture>
    )

    /** One edit of the text, in chars: [start, oldEnd) was replaced by [start, newEnd). */
    data class CharEdit(val start: Int, val oldEnd: Int, val newEnd: Int)

    // what was found around a node: its chars, to tell whether an edit reached it
    private class Kept(var start: Int, var end: Int, var captures: List<SyntaxCapture>)

    // the captures of the nodes asked about around the lines, by node, for [cacheTree];
    // those of the root apart, see [rootCaptures]
    // (the trees asked about are copies, made for the thread this runs on: what is kept
    // is of the tree they are copies of, which [onEdit] follows; another tree, another
    // lineage)
    private var cacheLineage: Any? = null
    private val cache = HashMap<ULong, Kept>()
    private var rootKept: List<SyntaxCapture>? = null

    /** What was kept is of no use any more (released, another query). */
    fun invalidate() {
        cache.clear()
        rootKept = null
        cacheLineage = null
    }

    /**
     * The tree was told about an edit (TSTree.edit): what was kept follows it rather than
     * being thrown away, which would have the root (thousands of children) asked again on
     * the main thread at the next frame, for every key press.
     *
     *  - The nodes around the edit are not what they were (the edit changed them, and
     *    tree-sitter gives them other ids): what was kept of them goes, they are asked
     *    again, which is quick, but for the root.
     *  - The other nodes are the same, at other places if they come after the edit: their
     *    captures move along.
     *  - The root keeps its captures, moved along. A few may be off by the edit (an
     *    `#include` typed in) until the parse after the edit puts the new tree in
     *    place, worked out with it ([rootCaptures], [seed]).
     */
    fun onEdit(edit: CharEdit) {
        if (cacheLineage == null) return
        rootKept = rootKept?.let { shift(it, edit) }
        val delta = edit.newEnd - edit.oldEnd
        val iterator = cache.values.iterator()
        while (iterator.hasNext()) {
            val kept = iterator.next()
            when {
                // before the edit: as it was
                kept.end < edit.start -> {}
                // after it: moves along
                kept.start > edit.oldEnd -> if (delta != 0) {
                    kept.start += delta
                    kept.end += delta
                    kept.captures = kept.captures.map {
                        SyntaxCapture(it.name, it.start + delta, it.end + delta)
                    }
                }
                // around it, or touching it
                else -> iterator.remove()
            }
        }
    }

    /**
     * The captures of the nodes that start in the chars [start, end) of [tree], or that
     * start before them and reach into them, as [SyntaxCapture]s (chars). The order is
     * not that of the text; a capture may come twice.
     */
    fun captures(tree: TSTree, lineage: Any, start: Int, end: Int): List<SyntaxCapture> {
        val root = tree.rootNode
        if (cacheLineage != lineage) {
            cache.clear()
            rootKept = null
            cacheLineage = lineage
        } else if (cache.size > MAX_CACHED_NODES) {
            // a long scroll passed many nodes: start over, but for the root, which
            // is the one that costs (its thousands of children), and is kept apart
            cache.clear()
        }
        val result = ArrayList<SyntaxCapture>()
        // UTF-16: two bytes a char; at least one char
        val s = start.coerceAtLeast(0).toUInt() * 2U
        val e = maxOf(end.coerceAtLeast(0).toUInt() * 2U, s + 2U)

        // inside: as far as a node that starts in [s, e) goes
        var reach = e
        var inner: TSNode? = root.descendant(e - 2U, e)
        while (inner != null) {
            val current = inner
            if (current.startByte < s) break
            if (current.endByte > reach) reach = current.endByte
            inner = current.parent
        }
        val cursor = query(root)
        try {
            cursor.byteRange = s..e
            cursor.containingByteRange = s..reach
            collect(cursor, result, sequencesOnly = false)
        } finally {
            cursor.close()
        }

        // around: the nodes around the char at s that start before it
        var around: TSNode? = root.descendant(s, s + 2U)
        while (around != null) {
            val current = around
            val parent = current.parent
            if (current.startByte < s) {
                result += if (parent == null) {
                    // the root: kept apart, it follows the edits (see onEdit)
                    rootKept ?: nodeCaptures(current).also { rootKept = it }
                } else {
                    // Kept by the id of the node, and only of use if it is where it was
                    // kept: tree-sitter can give the id of a node that was freed to
                    // another one, once an edit has made a copy of it.
                    val nodeStart = (current.startByte / 2U).toInt()
                    val nodeEnd = (current.endByte / 2U).toInt()
                    val kept = cache[current.id]?.takeIf { it.start == nodeStart && it.end == nodeEnd }
                        ?: Kept(nodeStart, nodeEnd, nodeCaptures(current)).also { cache[current.id] = it }
                    kept.captures
                }
            }
            around = parent
        }
        return result
    }

    /**
     * The captures of the root of [tree] for the pass around the lines, worked out
     * now: on the thread that parsed the tree, before it is used anywhere else (its
     * thousands of children are walked through). Hand them to [seed] with the tree on
     * the main thread. Null if they cannot be had.
     */
    fun rootCaptures(tree: TSTree): NodeCaptures? = try {
        NodeCaptures(tree, nodeCaptures(tree.rootNode))
    } catch (e: Exception) {
        Log.w(TAG, "cannot work out the structure at the root", e)
        null
    }

    /**
     * What [rootCaptures] worked out, for its tree, which is the one in place now. Main
     * thread. [editsSince]: the edits made while it was parsed, which the tree was told
     * about before it was put in place; the captures follow them, as [onEdit] does,
     * rather than being worked out again on the main thread.
     */
    fun seed(captures: NodeCaptures, editsSince: List<CharEdit>, lineage: Any) {
        if (cacheLineage != lineage) {
            cache.clear()
            cacheLineage = lineage
        }
        var kept = captures.captures
        for (edit in editsSince) kept = shift(kept, edit)
        rootKept = kept
    }

    // The captures of the patterns that start at [node] itself, and of the sequences
    // of its children. All of them, wherever they are: they are kept for any lines.
    private fun nodeCaptures(node: TSNode): List<SyntaxCapture> {
        val result = ArrayList<SyntaxCapture>()
        var cursor = query(node)
        try {
            cursor.maxStartDepth = 0U
            collect(cursor, result, sequencesOnly = false)
        } finally {
            cursor.close()
        }
        if (hasSequences) {
            cursor = query(node)
            try {
                cursor.maxStartDepth = 1U
                collect(cursor, result, sequencesOnly = true)
            } finally {
                cursor.close()
            }
        }
        return result
    }

    // The captures of the matches of [cursor], with the predicates of Neovim; of the
    // patterns that are not rooted only if [sequencesOnly].
    private fun collect(cursor: TSQueryCursor, out: MutableList<SyntaxCapture>, sequencesOnly: Boolean) {
        for (match in cursor.matches { match -> QueryPredicates.evaluate(this, match) }) {
            if (sequencesOnly && query.isPatternRooted(match.patternIndex)) continue
            for (capture in match.captures) {
                out += SyntaxCapture(
                    capture.name,
                    (capture.node.startByte / 2U).toInt(),
                    (capture.node.endByte / 2U).toInt()
                )
            }
        }
    }

    override fun close() {
        invalidate()
        query.close()
    }

    companion object {
        private const val TAG = "StructureQuery"

        /**
         * [captures] after [edit], as tree-sitter moves nodes: what starts after what was
         * replaced moves along, what ends after it grows or shrinks with it, what lay in
         * it shrinks to where it was; what is left empty goes.
         */
        fun shift(captures: List<SyntaxCapture>, edit: CharEdit): List<SyntaxCapture> {
            val delta = edit.newEnd - edit.oldEnd
            if (delta == 0 && edit.oldEnd == edit.start) return captures
            val result = ArrayList<SyntaxCapture>(captures.size)
            for (capture in captures) {
                val start = when {
                    capture.start < edit.start -> capture.start
                    capture.start >= edit.oldEnd -> capture.start + delta
                    else -> edit.start
                }
                val end = when {
                    capture.end <= edit.start -> capture.end
                    capture.end >= edit.oldEnd -> capture.end + delta
                    else -> edit.newEnd
                }
                if (end <= start) continue
                result += if (start == capture.start && end == capture.end) capture
                    else SyntaxCapture(capture.name, start, end)
            }
            return result
        }

        // the nodes kept at most for one tree (a long scroll through a large file
        // passes many), before starting over
        private const val MAX_CACHED_NODES = 4096

        /**
         * The query [source] of [language] for the captures [usedCaptures] (`fold`;
         * `indent.begin`, `indent`).
         *
         * @throws IllegalArgumentException if [source] is no query of the language
         *         (TSQueryError), as TSQuery does
         */
        fun create(language: TSLanguage, source: String, usedCaptures: Set<String>): StructureQuery {
            var query = TSQuery(language, source)
            var text = source
            // the alternations with a sequence in them, split
            val split = try {
                split(query, source)
            } catch (e: Exception) {
                Log.w(TAG, "cannot split the alternations of a query", e)
                null
            }
            if (split != null && split != source) {
                try {
                    val splitQuery = TSQuery(language, split)
                    query.close()
                    query = splitQuery
                    text = split
                } catch (e: Exception) {
                    // as it was: slower, but the same
                    Log.w(TAG, "cannot use the query with its alternations split", e)
                }
            }

            // the patterns with none of the captures used: disabled
            val offsets = QueryText(text)
            var hasSequences = false
            for (index in 0U until query.patternCount) {
                val pattern = offsets.pattern(query, index)
                if (QueryText.captureNames(pattern).none { it in usedCaptures }) {
                    query.disablePattern(index)
                } else if (!query.isPatternRooted(index)) {
                    hasSequences = true
                }
            }
            return StructureQuery(query, hasSequences)
        }

        /**
         * [source] with each pattern that is not rooted and is an alternation at the
         * top (`[A B (C)+] @fold`) written as its alternatives (`A @fold`, `B @fold`,
         * `(C)+ @fold`); null if there is none. The same matches: an alternation at the
         * top has no predicates of its own (they would need a group around it), and
         * one with a quantifier of its own (`[A B]+`) is left as it is.
         */
        fun split(query: TSQuery, source: String): String? {
            val offsets = QueryText(source)
            val out = StringBuilder()
            var last = 0
            for (index in 0U until query.patternCount) {
                if (query.isPatternRooted(index)) continue
                val start = offsets.charIndex(query.startByteForPattern(index))
                val end = offsets.charIndex(query.endByteForPattern(index))
                val alternatives = QueryText.splitAlternation(source.substring(start, end)) ?: continue
                out.append(source, last, start).append(alternatives)
                last = end
            }
            if (last == 0) return null
            return out.append(source, last, source.length).toString()
        }
    }
}

/**
 * The text of a query, and the little of its syntax needed to take it apart: where
 * a pattern is (tree-sitter gives byte offsets), which captures it has, the
 * alternatives of an alternation.
 */
internal class QueryText(private val text: String) {

    // The byte offset of each char, as the query was handed to tree-sitter: in
    // modified UTF-8 (JNI's GetStringUTFChars), where a char above U+07FF takes three
    // bytes, a surrogate (half a char above U+FFFF) three too, and U+0000 two.
    private val byteOffsets: IntArray = IntArray(text.length + 1).also { offsets ->
        var bytes = 0
        for (i in text.indices) {
            offsets[i] = bytes
            val c = text[i].code
            bytes += when {
                c == 0 -> 2
                c < 0x80 -> 1
                c < 0x800 -> 2
                else -> 3
            }
        }
        offsets[text.length] = bytes
    }

    /** The index of the char at the byte offset [byte] (or of the first after it). */
    fun charIndex(byte: UInt): Int {
        val target = byte.toInt()
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (byteOffsets[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    /** The text of the pattern [index] of [query], which was made from this text. */
    fun pattern(query: TSQuery, index: UInt): String =
        text.substring(charIndex(query.startByteForPattern(index)), charIndex(query.endByteForPattern(index)))

    companion object {

        private fun isNameChar(c: Char) =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-' || c == '!' || c == '?'

        /** The names of the captures in [pattern] (`@fold` -> `fold`), not in strings or comments. */
        fun captureNames(pattern: String): List<String> {
            val names = ArrayList<String>()
            var i = 0
            while (i < pattern.length) {
                when (pattern[i]) {
                    '"' -> i = stringEnd(pattern, i) ?: return names
                    ';' -> i = lineEnd(pattern, i)
                    '@' -> {
                        var end = i + 1
                        while (end < pattern.length && isNameChar(pattern[end])) end++
                        if (end > i + 1) names += pattern.substring(i + 1, end)
                        i = end
                    }
                    else -> i++
                }
            }
            return names
        }

        /**
         * The alternatives of [pattern] if it is an alternation followed by captures
         * only (`[A B] @c`), each as a pattern of its own with those captures (`A @c`,
         * `B @c`), one per line; null if it is not, or if it cannot be told.
         */
        fun splitAlternation(pattern: String): String? {
            val open = skipTrivia(pattern, 0)
            if (open >= pattern.length || pattern[open] != '[') return null
            val close = matchingClose(pattern, open) ?: return null

            val alternatives = ArrayList<String>()
            var i = skipTrivia(pattern, open + 1)
            while (i < close) {
                val end = itemEnd(pattern, i, close) ?: return null
                alternatives += pattern.substring(i, end)
                i = skipTrivia(pattern, end)
            }
            if (alternatives.size < 2) return null

            // after it: captures, and nothing else (a quantifier of the alternation
            // as a whole cannot be split)
            val captures = ArrayList<String>()
            i = skipTrivia(pattern, close + 1)
            while (i < pattern.length) {
                if (pattern[i] != '@') return null
                var end = i + 1
                while (end < pattern.length && isNameChar(pattern[end])) end++
                if (end == i + 1) return null
                captures += pattern.substring(i, end)
                i = skipTrivia(pattern, end)
            }
            if (captures.isEmpty()) return null

            val suffix = captures.joinToString(" ", prefix = " ")
            return alternatives.joinToString("") { "$it$suffix\n" }
        }

        // past white space and comments (`;` to the end of the line)
        private fun skipTrivia(text: String, from: Int): Int {
            var i = from
            while (i < text.length) {
                val c = text[i]
                i = when {
                    c.isWhitespace() -> i + 1
                    c == ';' -> lineEnd(text, i)
                    else -> return i
                }
            }
            return i
        }

        private fun lineEnd(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i] != '\n') i++
            return i
        }

        // [from] is at a `"`: the index after the closing one; null if there is none
        private fun stringEnd(text: String, from: Int): Int? {
            var i = from + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            return null
        }

        // [from] is at a `(` or `[`: the index of the `)` or `]` that closes it
        private fun matchingClose(text: String, from: Int): Int? {
            var depth = 0
            var i = from
            while (i < text.length) {
                when (text[i]) {
                    '"' -> {
                        i = stringEnd(text, i) ?: return null
                        continue
                    }
                    ';' -> {
                        i = lineEnd(text, i)
                        continue
                    }
                    '(', '[' -> depth++
                    ')', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return null
        }

        // The end of the alternative at [from], before [limit]: a node, an alternation,
        // a string or a name (`_`), with its quantifier and captures.
        private fun itemEnd(text: String, from: Int, limit: Int): Int? {
            var end = when (val c = text[from]) {
                '(', '[' -> (matchingClose(text, from) ?: return null).takeIf { it < limit }?.plus(1) ?: return null
                '"' -> (stringEnd(text, from) ?: return null).takeIf { it <= limit } ?: return null
                else -> {
                    if (!isNameChar(c)) return null
                    var i = from
                    while (i < limit && isNameChar(text[i])) i++
                    i
                }
            }
            while (true) {
                val i = skipTrivia(text, end)
                if (i >= limit) return end
                when (text[i]) {
                    '*', '+', '?' -> end = i + 1
                    '@' -> {
                        var j = i + 1
                        while (j < limit && isNameChar(text[j])) j++
                        if (j == i + 1) return null
                        end = j
                    }
                    else -> return end
                }
            }
        }
    }
}
