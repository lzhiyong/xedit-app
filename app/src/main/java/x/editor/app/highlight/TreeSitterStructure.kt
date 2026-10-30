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

import x.github.module.editor.input.AutoPairFilter
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.structure.BracketMatcher
import x.github.module.editor.structure.BracketPair
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.structure.FoldingRangeProvider
import x.github.module.editor.structure.IndentGuide
import x.github.module.editor.structure.IndentGuideProvider
import x.github.module.editor.structure.IndentationFoldingProvider
import x.github.module.editor.structure.IndentationGuideProvider
import x.github.module.editor.structure.PlainBracketMatcher

import x.editor.app.highlight.TreeSitterEngine.QueryKind

/**
 * The structure of the text, from the syntax tree: which brackets pair up, where the
 * indent guides go, what can be folded. The editor asks, draws the answers and folds
 * what the user folds (see
 * EditorView.setBracketMatcher, setIndentGuideProvider, setFoldingRangeProvider).
 *
 *  - **Brackets**: matched by scanning the text, like [PlainBracketMatcher], but a
 *    bracket in a string, a comment or a char literal does not count. That is told by
 *    the captures of the highlights query, so it needs no query file of its own.
 *  - **Folding**: the `@fold` captures of `folds.scm`.
 *  - **Indent guides**: one guide per block, the `@indent.begin` (or older `@indent`)
 *    captures of `indents.scm`, or else the `@fold` captures. A guide stands under the
 *    first char of the line that opens the block and runs down the lines inside it;
 *    a last line that only closes the block (`}`, `end`) is left out.
 *
 * Without a syntax tree (no grammar for the file, still loading) or without the query
 * file, it falls back to what the indentation says ([IndentationGuideProvider],
 * [IndentationFoldingProvider], [PlainBracketMatcher]).
 *
 * Everything runs on the main thread, like the rest of [TreeSitterEngine]. The editor
 * keeps the answers until the text, the cursor or the lines on screen change; when
 * the engine has a new tree without an edit (prepare finished), tell it with
 * EditorView.invalidateStructure.
 */
class TreeSitterStructure(
    private val syntaxEngine: () -> TreeSitterEngine,
    tabSize: Int = 4
) : IndentGuideProvider, FoldingRangeProvider {

    private val indentationGuides = IndentationGuideProvider(tabSize)
    private val indentationFolding = IndentationFoldingProvider(tabSize)

    /** The brackets, with those in strings and comments left out. */
    val bracketMatcher: BracketMatcher = object : PlainBracketMatcher(maxLines = 1000) {

        // the start of the line asked about last: a scan asks about the brackets of a
        // line one after the other
        private var cachedLine = -1
        private var cachedLineStart = 0

        override fun findBracketPair(buffer: EditorBuffer, lineNumber: Int, column: Int): BracketPair? {
            // the text may have changed since the last time
            cachedLine = -1
            return super.findBracketPair(buffer, lineNumber, column)
        }

        // A look at the node at the bracket (TreeSitterEngine.isCodeAt), not a query of
        // the line: a scan may ask about the brackets of a thousand lines.
        override fun isBracket(buffer: EditorBuffer, lineNumber: Int, index: Int): Boolean {
            val engine = syntaxEngine()
            if (!engine.isEnabled) return true
            if (lineNumber != cachedLine) {
                cachedLineStart = buffer.getOffsetAt(lineNumber, 1)
                cachedLine = lineNumber
            }
            return engine.isCodeAt(cachedLineStart + index)
        }
    }

    /**
     * No pair typed together inside a string or a comment (as nvim-autopairs with
     * check_ts): the chars on both sides of the cursor have to be code. Without a
     * tree, everywhere.
     */
    val autoPairFilter = AutoPairFilter { _, offset ->
        val engine = syntaxEngine()
        !engine.isEnabled || ((offset == 0 || engine.isCodeAt(offset - 1)) && engine.isCodeAt(offset))
    }

    // #region folding

    override fun getFoldingRanges(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<FoldingRange> =
        foldingRanges(buffer, firstLine, lastLine) { kind, start, end -> syntaxEngine().captures(kind, start, end) }!!

    // to draw: from the captures found in the background, null while they are found
    override fun getFoldingRangesToDraw(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<FoldingRange>? =
        foldingRanges(buffer, firstLine, lastLine) { kind, start, end -> syntaxEngine().capturesToDraw(kind, start, end) }

    private inline fun foldingRanges(
        buffer: EditorBuffer, firstLine: Int, lastLine: Int,
        captures: (QueryKind, Int, Int) -> List<SyntaxCapture>?
    ): List<FoldingRange>? {
        val engine = syntaxEngine()
        if (!engine.hasQuery(QueryKind.FOLDS)) {
            return indentationFolding.getFoldingRanges(buffer, firstLine, lastLine)
        }

        // per start line the largest range: a function and its body start on one line
        val ends = HashMap<Int, Int>()
        val found = captures(QueryKind.FOLDS, lineStart(buffer, firstLine), lineEnd(buffer, lastLine)) ?: return null
        for (capture in found) {
            if (capture.name != CAPTURE_FOLD) continue
            val startLine = buffer.getPositionAt(capture.start).lineNumber
            if (startLine < firstLine || startLine > lastLine) continue
            val endLine = endLineOf(buffer, capture.end)
            if (endLine > startLine && endLine > (ends[startLine] ?: 0)) {
                ends[startLine] = endLine
            }
        }
        return ends.map { (start, end) -> FoldingRange(start, end) }.sortedBy { it.startLine }
    }

    // #endregion

    // #region indent guides

    override fun getIndentGuides(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<IndentGuide> =
        indentGuides(buffer, firstLine, lastLine) { kind, start, end -> syntaxEngine().captures(kind, start, end) }!!

    // to draw: from the captures found in the background, null while they are found
    override fun getIndentGuidesToDraw(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<IndentGuide>? =
        indentGuides(buffer, firstLine, lastLine) { kind, start, end -> syntaxEngine().capturesToDraw(kind, start, end) }

    private inline fun indentGuides(
        buffer: EditorBuffer, firstLine: Int, lastLine: Int,
        captures: (QueryKind, Int, Int) -> List<SyntaxCapture>?
    ): List<IndentGuide>? {
        val engine = syntaxEngine()
        val kind = when {
            engine.hasQuery(QueryKind.INDENTS) -> QueryKind.INDENTS
            engine.hasQuery(QueryKind.FOLDS) -> QueryKind.FOLDS
            else -> return indentationGuides.getIndentGuides(buffer, firstLine, lastLine)
        }
        val names = if (kind == QueryKind.INDENTS) INDENT_CAPTURES else setOf(CAPTURE_FOLD)

        // per place a guide stands (line and column) the longest: nested nodes that
        // start on the same line give one guide
        val guides = HashMap<Long, IndentGuide>()
        val found = captures(kind, lineStart(buffer, firstLine), lineEnd(buffer, lastLine)) ?: return null
        for (capture in found) {
            if (capture.name !in names) continue
            val blockLine = buffer.getPositionAt(capture.start).lineNumber
            var lastInside = endLineOf(buffer, capture.end)
            if (lastInside > blockLine && closesBlock(buffer, lastInside, capture.end)) {
                lastInside--
            }
            // the lines inside the block, below the one that opens it
            val startLine = blockLine + 1
            if (lastInside < startLine || lastInside < firstLine || startLine > lastLine) continue

            val column = TreeSitterUtils.firstNonWhitespaceColumn(buffer.getLineContent(blockLine))
            val key = (blockLine.toLong() shl 32) or column.toLong()
            val existing = guides[key]
            if (existing == null || existing.endLine < lastInside) {
                guides[key] = IndentGuide(startLine, lastInside, column, anchorLine = blockLine)
            }
        }
        return guides.values.toList()
    }

    // whether [line], the last line of a block that ends at [blockEnd], only closes it
    private fun closesBlock(buffer: EditorBuffer, line: Int, blockEnd: Int): Boolean {
        val text = buffer.getLineContent(line)
        val start = TreeSitterUtils.firstNonWhitespaceColumn(text) - 1
        val end = (blockEnd - buffer.getOffsetAt(line, 1)).coerceIn(start, text.length)
        return TreeSitterUtils.isClosingText(text.substring(start, end))
    }

    // #endregion

    private fun lineStart(buffer: EditorBuffer, line: Int): Int = buffer.getOffsetAt(line, 1)

    private fun lineEnd(buffer: EditorBuffer, line: Int): Int =
        buffer.getOffsetAt(line, buffer.getLineMaxColumn(line))

    // The last line of a node that ends at [end]. A node that takes the line break of
    // its last line along ends at the start of the next line, which is not part of it.
    private fun endLineOf(buffer: EditorBuffer, end: Int): Int {
        val position = buffer.getPositionAt(end)
        return if (position.column == 1 && position.lineNumber > 1) position.lineNumber - 1 else position.lineNumber
    }

    private companion object {
        const val CAPTURE_FOLD = "fold"
        // nvim-treesitter's names: @indent.begin, and @indent before it was renamed
        val INDENT_CAPTURES = setOf("indent.begin", "indent")
    }
}
