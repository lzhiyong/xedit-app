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

package x.github.module.editor.highlight

import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.render.LineStyler

/**
 * A syntax highlighting engine, as the editor sees it.
 *
 * The editor does not know how highlighting is worked out: with a tree-sitter
 * grammar, with TextMate rules, with regular expressions, by a language server. It
 * knows this interface, and whatever implements it can be plugged in with
 * EditorView.setSyntaxHighlighter.
 *
 * An engine does two things:
 *
 *  - it **styles lines** (it is a [LineStyler]): asked about a line that is about to
 *    be painted, it answers with colors and styles;
 *  - it **follows the text**: it is told when the text is replaced as a whole and
 *    about every edit, so that whatever it keeps about the text (a syntax tree, a
 *    state per line) stays true.
 *
 * Everything here is called on the main thread. [styleLine] is called while drawing
 * and has to be quick. An engine that needs time (parsing a large file) does that
 * on a thread of its own, answers from what it has in the meantime, and calls
 * [Host.invalidateStyles] when it knows more.
 *
 * Only [styleLine] has to be implemented; the rest does nothing by default.
 *
 * An engine that parses the text (tree-sitter) knows its structure too. It can also
 * implement BracketMatcher, IndentGuideProvider and FoldingRangeProvider of the
 * structure package and be set for those as well (EditorView.setBracketMatcher, ...),
 * so that one syntax tree serves all of them.
 */
interface SyntaxHighlighter : LineStyler {

    /** What an engine can ask of the editor it is attached to. */
    interface Host {
        /** The text, as it is right now. */
        val buffer: EditorBuffer

        /**
         * The styles have changed without the text having changed (parsing finished,
         * another theme): has the text painted again. Can be called from any thread.
         */
        fun invalidateStyles()

        /**
         * Like [invalidateStyles], for the lines [startLine] to [endLine] only (1-based,
         * inclusive): what changed after an edit, say from tree-sitter's changed
         * ranges. The other lines stay as they were drawn, which saves styling them
         * again. Any thread.
         */
        fun invalidateStyles(startLine: Int, endLine: Int) = invalidateStyles()

        /**
         * What the engine knows about the structure of the text has changed (parsing
         * finished): if it also provides matching brackets, indent guides or folding
         * ranges (see the structure package), they are asked again. Any thread.
         */
        fun invalidateStructure()
    }

    /** The engine was given to an editor. The text it will style is [Host.buffer]. */
    fun onAttached(host: Host) {}

    /** The editor has another engine now, or none. Let go of whatever is held. */
    fun onDetached() {}

    /**
     * The text was replaced as a whole: another file was loaded, a saved state was
     * restored. Whatever was known about the old text is void.
     */
    fun onTextReplaced(buffer: EditorBuffer) {}

    /**
     * One change was made to the text. Called once per change of an edit, right
     * after the change, with the text already changed.
     */
    fun onTextChanged(change: EditorDocument.AppliedChange) {}

    /**
     * An edit is complete: all its changes have been reported with [onTextChanged].
     * The moment to bring derived data up to date (parse again).
     */
    fun onEditFinished() {}
}
