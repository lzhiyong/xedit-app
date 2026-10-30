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

package x.github.module.editor.structure

import x.github.module.piecetable.common.Range

import x.github.module.editor.model.EditorBuffer

/** Two brackets that belong together, each a range of one char. */
data class BracketPair(val open: Range, val close: Range)

/**
 * Finds the bracket at the cursor and the one it pairs with, for the editor to
 * highlight both (see EditorView.setBracketMatcher).
 *
 * The editor does not know what a bracket is in a language, nor which brackets are
 * in strings or comments: that is what an implementation answers, from a syntax
 * tree (tree-sitter: the node at the cursor and its sibling), from tokens, or by
 * scanning the text like [PlainBracketMatcher].
 *
 * Called on the main thread when the cursor has moved or the text has changed, and
 * only then: the editor keeps the answer until one of them changes, or until
 * EditorView.invalidateStructure is called (an engine that parses on a thread of its
 * own calls it when it is done). It has to be quick all the same.
 */
fun interface BracketMatcher {

    /**
     * The pair of the bracket right before or right after the cursor at
     * [lineNumber]:[column] (1-based), or null if there is none, or none that matches.
     */
    fun findBracketPair(buffer: EditorBuffer, lineNumber: Int, column: Int): BracketPair?
}

/**
 * Brackets matched by scanning the text: the bracket before the cursor is tried
 * first, then the one after it, and its partner is looked for line by line, counting
 * the brackets of its own kind in between.
 *
 * It does not know strings or comments, so a bracket in a string counts like any
 * other. A subclass that knows better (tokens of a highlighter, a syntax tree)
 * overrides [isBracket] to leave those out.
 *
 * @param pairs the brackets, opening and closing in turn: "()[]{}" by default
 * @param maxLines how many lines are scanned at most in each direction: a partner
 *        further away is not found, and the cursor never waits for a scan of the
 *        whole file
 */
open class PlainBracketMatcher(
    private val pairs: String = "()[]{}",
    private val maxLines: Int = 2000
) : BracketMatcher {

    init {
        require(pairs.length % 2 == 0) { "brackets come in pairs: \"$pairs\"" }
    }

    /**
     * Whether the char at [index] (0-based) of [lineNumber] counts as a bracket; false
     * for one in a string or a comment. Every char counts by default.
     */
    protected open fun isBracket(buffer: EditorBuffer, lineNumber: Int, index: Int): Boolean = true

    override fun findBracketPair(buffer: EditorBuffer, lineNumber: Int, column: Int): BracketPair? {
        if (lineNumber < 1 || lineNumber > buffer.getLineCount()) return null
        val line = buffer.getLineContent(lineNumber)
        // the one before the cursor first: the bracket that was just typed
        for (index in intArrayOf(column - 2, column - 1)) {
            if (index < 0 || index >= line.length) continue
            val kind = pairs.indexOf(line[index])
            if (kind < 0 || !isBracket(buffer, lineNumber, index)) continue
            val partner = if (kind % 2 == 0) {
                scanForward(buffer, lineNumber, index, pairs[kind], pairs[kind + 1])
            } else {
                scanBackward(buffer, lineNumber, index, pairs[kind - 1], pairs[kind])
            } ?: continue
            val here = charRange(lineNumber, index)
            return if (kind % 2 == 0) BracketPair(here, partner) else BracketPair(partner, here)
        }
        return null
    }

    // the closing bracket of the opening one at lineNumber:index
    private fun scanForward(buffer: EditorBuffer, lineNumber: Int, index: Int, open: Char, close: Char): Range? {
        var depth = 0
        val lastLine = Math.min(buffer.getLineCount(), lineNumber + maxLines)
        for (current in lineNumber..lastLine) {
            val text = buffer.getLineContent(current)
            val start = if (current == lineNumber) index + 1 else 0
            for (i in start until text.length) {
                val char = text[i]
                if (char != open && char != close) continue
                if (!isBracket(buffer, current, i)) continue
                if (char == open) {
                    depth++
                } else if (depth == 0) {
                    return charRange(current, i)
                } else {
                    depth--
                }
            }
        }
        return null
    }

    // the opening bracket of the closing one at lineNumber:index
    private fun scanBackward(buffer: EditorBuffer, lineNumber: Int, index: Int, open: Char, close: Char): Range? {
        var depth = 0
        val firstLine = Math.max(1, lineNumber - maxLines)
        for (current in lineNumber downTo firstLine) {
            val text = buffer.getLineContent(current)
            val start = if (current == lineNumber) index - 1 else text.length - 1
            for (i in start downTo 0) {
                val char = text[i]
                if (char != open && char != close) continue
                if (!isBracket(buffer, current, i)) continue
                if (char == close) {
                    depth++
                } else if (depth == 0) {
                    return charRange(current, i)
                } else {
                    depth--
                }
            }
        }
        return null
    }

    private fun charRange(lineNumber: Int, index: Int) = Range(lineNumber, index + 1, lineNumber, index + 2)
}
