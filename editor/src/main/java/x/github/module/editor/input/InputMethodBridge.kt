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

package x.github.module.editor.input

import x.github.module.editor.model.EditorDocument
import x.github.module.piecetable.common.Range

/**
 * Stands between the input method and the document.
 *
 * An input method counts in offsets: chars from the start of the text. The editor
 * counts in lines and columns. This class does the converting, in both directions,
 * and so is what [InputComposer] works on:
 *
 *  - it tells the composer where the selection is, as offsets;
 *  - it turns the composer's "replace offsets a to b" into an edit of a range;
 *  - it keeps the composing region available as a range, for drawing its underline.
 *
 * It depends on the document and on a small [Host] only, so the conversions can be
 * tested without a view.
 */
class InputMethodBridge(
    private val document: EditorDocument,
    private val host: InputMethodBridge.Host
) : InputComposer.Host {

    /** What the bridge needs from the editor. Implemented by EditorView. */
    interface Host {
        /** The selection; with nothing selected, an empty range at the cursor. */
        val selectionRange: Range

        fun isEditable(): Boolean

        /** Replaces [range] with [text], as an ordinary edit. */
        fun applyInputEdit(range: Range, text: String)

        /** Selects [range], or places the cursor if it is empty. */
        fun selectRange(range: Range)

        /** [composingRange] changed: redraw. */
        fun onComposingRangeChanged()

        /**
         * An edit of the input method could not be carried out (read-only, or busy).
         * It now believes in text that is not there and has to be made to start over.
         */
        fun onInputEditRefused()
    }

    /** Carries out what the input method asks for, on this bridge. */
    val composer = InputComposer(this)

    /** The text the input method is composing, as a range; null if there is none. */
    var composingRange: Range? = null
        private set

    // #region offsets <-> positions

    /** The range between two offsets. */
    fun rangeBetween(start: Int, end: Int): Range {
        val startPosition = document.getPosition(start)
        val endPosition = document.getPosition(end)
        return Range(
            startPosition.lineNumber, startPosition.column,
            endPosition.lineNumber, endPosition.column
        )
    }

    /**
     * The offset of a position, or of the nearest one the text has. The input method
     * may ask at any moment, also in the short time after another text was loaded in
     * which the cursor or the selection still point into the old one.
     */
    fun offsetOf(lineNumber: Int, column: Int): Int {
        val line = lineNumber.coerceIn(1, document.getLineCount())
        val columnInLine = column.coerceIn(1, document.getLineEndColumn(line))
        return document.getOffset(line, columnInLine)
    }

    /**
     * Whether the selection (or the cursor) is at a position the text really has.
     * While it is not, there is nothing true to tell the input method about it.
     */
    fun isSelectionInsideDocument(): Boolean {
        val selection = host.selectionRange
        val lineCount = document.getLineCount()
        return selection.startLine in 1..lineCount && selection.endLine in 1..lineCount
    }

    // #endregion

    // #region InputComposer.Host

    override val documentLength: Int get() = document.length

    override fun getDocumentText(start: Int, end: Int): String =
        document.getValueInRange(rangeBetween(start, end))

    override val selectionStartOffset: Int
        get() = host.selectionRange.let { offsetOf(it.startLine, it.startColumn) }

    override val selectionEndOffset: Int
        get() = host.selectionRange.let { offsetOf(it.endLine, it.endColumn) }

    override fun replaceDocumentText(start: Int, end: Int, text: String): Boolean {
        if (!host.isEditable()) {
            // the input method thinks its text went in; make it look again
            host.onInputEditRefused()
            return false
        }
        host.applyInputEdit(rangeBetween(start, end), text)
        return true
    }

    override fun selectDocumentRange(start: Int, end: Int) {
        host.selectRange(rangeBetween(start, end))
    }

    override fun onComposingRegionChanged() {
        composingRange = if (composer.isComposing) {
            rangeBetween(composer.composingStart, composer.composingEnd)
        } else {
            null
        }
        host.onComposingRangeChanged()
    }

    // #endregion
}
