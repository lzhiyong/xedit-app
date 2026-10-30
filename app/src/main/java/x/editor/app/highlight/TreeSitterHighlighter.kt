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

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan


import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.PieceTreeTextBuffer

/**
 * Syntax highlighting with tree-sitter, as an engine that is plugged into an editor
 * ([SyntaxHighlighter]) rather than built into one.
 *
 *  - it answers "how is this line styled" from the syntax tree;
 *  - it keeps the syntax tree in step with the text: every change is reported to the
 *    tree as it happens, and the tree is parsed again when the edit is complete;
 *  - the same tree tells the editor the structure of the text: matching brackets,
 *    indent guides, folding ranges ([structure], see [TreeSitterStructure]).
 *
 * [syntaxEngine] and [textBuffer] are asked for each time, so the parser can be
 * created lazily and the editor can be given another buffer.
 */
class TreeSitterHighlighter(
    private val syntaxEngine: () -> TreeSitterEngine,
    private val textBuffer: () -> PieceTreeTextBuffer,
    tabSize: Int = 4
) : SyntaxHighlighter {

    /** Matching brackets, indent guides and folding ranges from the same syntax tree. */
    val structure = TreeSitterStructure(syntaxEngine, tabSize)

    // the editor this is attached to, while it is (read by refresh on any thread)
    @Volatile
    private var host: SyntaxHighlighter.Host? = null

    /**
     * Styles [editor]'s text and follows its edits, and tells it the structure of the
     * text: matching brackets, indent guides and code folding (the `@fold` captures
     * of folds.scm, or the indentation without them), each of which can be left out.
     */
    fun attachTo(
        editor: EditorView,
        bracketMatching: Boolean = true,
        indentGuides: Boolean = true,
        folding: Boolean = true
    ) {
        editor.setSyntaxHighlighter(this)
        editor.setBracketMatcher(if (bracketMatching) structure.bracketMatcher else null)
        editor.setIndentGuideProvider(if (indentGuides) structure else null)
        editor.setFoldingRangeProvider(if (folding) structure else null)
    }

    fun detachFrom(editor: EditorView) {
        if (editor.getSyntaxHighlighter() === this) {
            editor.setSyntaxHighlighter(null)
        }
        if (editor.getBracketMatcher() === structure.bracketMatcher) {
            editor.setBracketMatcher(null)
        }
        if (editor.getIndentGuideProvider() === structure) {
            editor.setIndentGuideProvider(null)
        }
        if (editor.getFoldingRangeProvider() === structure) {
            editor.setFoldingRangeProvider(null)
        }
    }

    /**
     * The engine has a syntax tree it did not have before, or none any more, without
     * the text having been edited: after [TreeSitterEngine.prepare] or
     * [TreeSitterEngine.release], after other styles were loaded. Has the text styled
     * and its structure worked out again. Any thread.
     */
    fun refresh() {
        host?.let {
            it.invalidateStyles()
            it.invalidateStructure()
        }
    }

    override fun onAttached(host: SyntaxHighlighter.Host) {
        this.host = host
        // a parse after an edit is done in the background: style what changed with
        // its tree
        syntaxEngine().onTreeChanged = { changed -> onTreeChanged(changed) }
        // blocks found in the background: their lines are styled with them
        syntaxEngine().onStylesReady = { range -> invalidateLines(listOf(range)) }
        // and structure: the guides and markers are drawn with it
        syntaxEngine().onStructureReady = { this.host?.invalidateStructure() }
    }

    // The lines of [changed] (chars) are styled again, the others keep what was drawn
    // of them; the structure (folds, guides, brackets) is asked for again. Main thread.
    private fun onTreeChanged(changed: List<IntRange>) {
        invalidateLines(changed)
        host?.invalidateStructure()
    }

    // The lines of [changed] (chars) are styled again, the others keep what was drawn
    // of them. Main thread.
    private fun invalidateLines(changed: List<IntRange>) {
        val host = host ?: return
        val buffer = host.buffer
        val length = buffer.length
        // as lines, the ones that touch or overlap merged
        val lines = changed
            .map { range ->
                val first = buffer.getPositionAt(range.first.coerceIn(0, length)).lineNumber
                val last = if (range.last >= length) Int.MAX_VALUE
                    else buffer.getPositionAt(range.last.coerceIn(0, length)).lineNumber
                first..last
            }
            .sortedBy { it.first }
        var current: IntRange? = null
        for (range in lines) {
            val open = current
            current = when {
                open == null -> range
                range.first - 1 <= open.last -> open.first..maxOf(open.last, range.last)
                else -> {
                    host.invalidateStyles(open.first, open.last)
                    range
                }
            }
        }
        current?.let { host.invalidateStyles(it.first, it.last) }
    }

    override fun onDetached() {
        host = null
        syntaxEngine().onTreeChanged = null
        syntaxEngine().onStylesReady = null
        syntaxEngine().onStructureReady = null
    }

    // the styles of tree-sitter are found in the background: final once their blocks are in
    override fun isStyleReady(bufferLine: Int, lineStartOffset: Int, start: Int, end: Int): Boolean {
        val engine = syntaxEngine()
        return !engine.isEnabled || engine.stylesReady(lineStartOffset + start, lineStartOffset + end)
    }

    override fun styleLine(
        text: String, bufferLine: Int, lineStartOffset: Int, start: Int, end: Int
    ): Spanned? {
        val engine = syntaxEngine()
        if (engine.isEnabled) return engine.highlightLine(text, lineStartOffset, start, end)
        // no syntax highlighting for the text (no grammar for it): its hex colors still
        return if (colorPreview) colorSpans(text, start, end) else null
    }

    /**
     * Whether colors written in hex (`#D4D4D4`) are shown in their own color, in every
     * file, with a grammar or without (see HexColors). Main thread.
     */
    var colorPreview: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            syntaxEngine().colorPreview = value
            host?.invalidateStyles()
        }

    // The hex colors of text[start, end) as spans, for text without syntax
    // highlighting; null if there are none (plain text).
    private fun colorSpans(text: String, start: Int, end: Int): Spanned? {
        val matches = HexColors.find(text, start, end, alphaLast = false)
        if (matches.isEmpty()) return null
        val spannable = SpannableString(text)
        for (match in matches) {
            val from = maxOf(match.start, start)
            val to = minOf(match.end, end)
            spannable.setSpan(BackgroundColorSpan(match.color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(match.textColor), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    /**
     * The background of the lines of code blocks (Markdown's), across the whole
     * width of the editor; null: none. Main thread; the editor has to be drawn again
     * after a change (EditorView.invalidate).
     */
    var codeBlockBackground: Int? = null

    override fun lineBackground(bufferLine: Int): Int? {
        val color = codeBlockBackground ?: return null
        return if (syntaxEngine().isInCodeBlock(bufferLine)) color else null
    }

    // update the abstract syntax tree
    // this must be running on main thread
    override fun onTextChanged(change: EditorDocument.AppliedChange) {
        val engine = syntaxEngine()
        if (engine.isEnabled) {
            engine.applyEdit(
                change.range,
                change.rangeOffset,
                change.insertedTextLength,
                change.deletedTextLength,
                change.finalLineNumber,
                change.finalColumn
            )
        }
    }

    // parse the text again, in the background (the tree, told about the edit, is
    // drawn from meanwhile; refresh follows when the new one is there)
    override fun onEditFinished() {
        val engine = syntaxEngine()
        if (engine.isEnabled) {
            engine.reparse(textBuffer())
        }
    }
}
