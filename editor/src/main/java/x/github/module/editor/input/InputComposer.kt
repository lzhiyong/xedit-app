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

/**
 * What an input method does to the text, kept apart from the InputConnection plumbing
 * so that it can be reasoned about (and tested) as plain operations on offsets.
 *
 * An input method does not only insert finished text. While a word is being typed
 * (pinyin being turned into characters, a swipe being recognised, a word being
 * auto-completed) it keeps a **composing region**: a stretch of text already in the
 * document that it will go on replacing until it is satisfied. This class keeps track
 * of that region and carries out the operations defined on it:
 *
 *  - [setComposingText]: replace the region (or, if there is none, the selection)
 *    with new text, which becomes the region;
 *  - [commitText]: the same, but the text is final and there is no region afterwards;
 *  - [finishComposingText]: keep the text as it is and forget the region;
 *  - [setComposingRegion]: declare existing text to be the region, e.g. to correct
 *    a word that was typed earlier.
 *
 * Everything is in **document offsets** (chars from the start of the text), which is
 * the unit input methods think in.
 *
 * The region is only meaningful as long as nobody else changes the text. Any edit
 * that does not come through this class ends it, see [onDocumentEdited].
 */
class InputComposer(private val host: InputComposer.Host) {

    /** The document as the input method sees it. Implemented by EditorView. */
    interface Host {
        /** The length of the whole text. */
        val documentLength: Int

        /** The text between two offsets. */
        fun getDocumentText(start: Int, end: Int): String

        /** Where the selection starts and ends; the same offset twice for a bare cursor. */
        val selectionStartOffset: Int
        val selectionEndOffset: Int

        /**
         * Replaces the text between two offsets and leaves the cursor after the new
         * text. Returns false, having changed nothing, if the editor does not accept
         * edits right now (read-only, or busy laying out).
         */
        fun replaceDocumentText(start: Int, end: Int, text: String): Boolean

        /** Selects the text between two offsets, or places the cursor if they are equal. */
        fun selectDocumentRange(start: Int, end: Int)

        /** The composing region appeared, moved or went away: redraw its underline. */
        fun onComposingRegionChanged()
    }

    companion object {
        /** The value of [composingStart] and [composingEnd] when there is no region. */
        const val NO_REGION = -1
    }

    /** Where the composing region starts, or [NO_REGION]. */
    var composingStart = NO_REGION
        private set

    /** Whether text is selected, as opposed to a mere cursor. */
    val hasSelection: Boolean get() = host.selectionStartOffset != host.selectionEndOffset

    /** Where the composing region ends (exclusive), or [NO_REGION]. */
    var composingEnd = NO_REGION
        private set

    val isComposing: Boolean get() = composingStart != NO_REGION

    // true while an edit made by this class is on its way through the editor
    private var applyingOwnEdit = false

    // beginBatchEdit / endBatchEdit nesting
    private var batchDepth = 0

    /**
     * Whether the input method is in the middle of a group of operations. What it is
     * told about the selection should wait until the group is complete.
     */
    val isBatchEditing: Boolean get() = batchDepth > 0

    fun beginBatchEdit() {
        batchDepth++
    }

    fun endBatchEdit() {
        if (batchDepth > 0) {
            batchDepth--
        }
    }

    // #region the composing region

    private fun setRegion(start: Int, end: Int) {
        val newStart = if (start < end) start else NO_REGION
        val newEnd = if (start < end) end else NO_REGION
        if (newStart != composingStart || newEnd != composingEnd) {
            composingStart = newStart
            composingEnd = newEnd
            host.onComposingRegionChanged()
        }
    }

    private fun clearRegion() = setRegion(NO_REGION, NO_REGION)

    /** Keeps the composing text as ordinary text and forgets the region. */
    fun finishComposingText() {
        clearRegion()
    }

    /**
     * Declares the existing text between two offsets to be the composing region.
     * Equal offsets remove the region.
     */
    fun setComposingRegion(start: Int, end: Int) {
        val length = host.documentLength
        val a = Math.min(start, end).coerceIn(0, length)
        val b = Math.max(start, end).coerceIn(0, length)
        setRegion(a, b)
    }

    /**
     * Call after every change to the text, whoever made it. A change that did not come
     * from this class (a paste, an undo, another file) ends the composing region: its
     * offsets no longer mean what the input method thinks they mean.
     */
    fun onDocumentEdited() {
        if (!applyingOwnEdit) {
            clearRegion()
        }
    }

    /** Forgets everything, e.g. when the whole text was replaced. */
    fun reset() {
        batchDepth = 0
        clearRegion()
    }

    // #endregion

    // #region inserting

    /**
     * Replaces the composing region (or the selection, if there is none) with [text],
     * which becomes the new composing region.
     *
     * @param newCursorPosition where the cursor goes, as input methods specify it:
     *        1 is right after the text, 2 one further, ...; 0 is right before the
     *        text, -1 one before that, ...
     * @return false if the editor refused the edit
     */
    fun setComposingText(text: String, newCursorPosition: Int): Boolean {
        return insert(text, newCursorPosition, composing = true)
    }

    /**
     * Replaces the composing region (or the selection, if there is none) with [text]
     * for good: there is no composing region afterwards.
     *
     * @return false if the editor refused the edit
     */
    fun commitText(text: String, newCursorPosition: Int): Boolean {
        return insert(text, newCursorPosition, composing = false)
    }

    private fun insert(text: String, newCursorPosition: Int, composing: Boolean): Boolean {
        // what gets replaced
        val start: Int
        val end: Int
        if (isComposing) {
            start = composingStart
            end = composingEnd
        } else {
            start = Math.min(host.selectionStartOffset, host.selectionEndOffset)
            end = Math.max(host.selectionStartOffset, host.selectionEndOffset)
        }

        if (!replace(start, end, text)) {
            // the editor and the input method now disagree about the text;
            // at least do not keep a region that points at nothing
            clearRegion()
            return false
        }

        if (composing) {
            setRegion(start, start + text.length)
        } else {
            clearRegion()
        }

        // the edit left the cursor right after the text
        val textEnd = start + text.length
        val requested = if (newCursorPosition > 0) textEnd + newCursorPosition - 1 else start + newCursorPosition
        val cursor = requested.coerceIn(0, host.documentLength)
        if (cursor != textEnd) {
            host.selectDocumentRange(cursor, cursor)
        }
        return true
    }

    private fun replace(start: Int, end: Int, text: String): Boolean {
        if (start == end && text.isEmpty()) {
            // nothing to do
            return true
        }
        applyingOwnEdit = true
        try {
            return host.replaceDocumentText(start, end, text)
        } finally {
            applyingOwnEdit = false
        }
    }

    // #endregion

    // #region deleting

    /**
     * Deletes [beforeLength] chars before and [afterLength] chars after the cursor.
     * "The cursor" here is the selection and the composing region taken together:
     * neither of them is deleted, only what surrounds them.
     *
     * A deletion never cuts a surrogate pair (an emoji, say) in half: the half that
     * would be left behind goes too.
     *
     * @return false if the editor refused the edit
     */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val length = host.documentLength
        val selectionStart = Math.min(host.selectionStartOffset, host.selectionEndOffset)
        val selectionEnd = Math.max(host.selectionStartOffset, host.selectionEndOffset)

        // what must survive
        var keepStart = selectionStart
        var keepEnd = selectionEnd
        if (isComposing) {
            keepStart = Math.min(keepStart, composingStart)
            keepEnd = Math.max(keepEnd, composingEnd)
        }

        val deleteFrom = alignToCodePoint((keepStart - Math.max(0, beforeLength)).coerceAtLeast(0), forward = false)
        val deleteTo = alignToCodePoint((keepEnd + Math.max(0, afterLength)).coerceAtMost(length), forward = true)

        // the part after first, so the offsets of the part before stay valid
        if (deleteTo > keepEnd && !replace(keepEnd, deleteTo, "")) {
            return false
        }
        val deletedBefore = keepStart - deleteFrom
        if (deletedBefore > 0) {
            if (!replace(deleteFrom, keepStart, "")) {
                return false
            }
            // everything behind the deleted text moved up
            if (isComposing) {
                setRegion(composingStart - deletedBefore, composingEnd - deletedBefore)
            }
        }

        if (deleteTo > keepEnd || deletedBefore > 0) {
            // the edits moved the cursor to where they happened: put it back
            host.selectDocumentRange(selectionStart - deletedBefore, selectionEnd - deletedBefore)
        }
        return true
    }

    /** Like [deleteSurroundingText], with the lengths given in code points instead of chars. */
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val length = host.documentLength
        var keepStart = Math.min(host.selectionStartOffset, host.selectionEndOffset)
        var keepEnd = Math.max(host.selectionStartOffset, host.selectionEndOffset)
        if (isComposing) {
            keepStart = Math.min(keepStart, composingStart)
            keepEnd = Math.max(keepEnd, composingEnd)
        }

        // a code point is at most two chars
        val before = host.getDocumentText((keepStart - Math.max(0, beforeLength) * 2).coerceAtLeast(0), keepStart)
        val beforeCodePoints = Math.min(Math.max(0, beforeLength), before.codePointCount(0, before.length))
        val beforeChars = before.length - before.offsetByCodePoints(before.length, -beforeCodePoints)

        val after = host.getDocumentText(keepEnd, (keepEnd + Math.max(0, afterLength) * 2).coerceAtMost(length))
        val afterCodePoints = Math.min(Math.max(0, afterLength), after.codePointCount(0, after.length))
        val afterChars = after.offsetByCodePoints(0, afterCodePoints)

        return deleteSurroundingText(beforeChars, afterChars)
    }

    // If [offset] falls between the two halves of a surrogate pair, moves it out of
    // the pair, forward or backward.
    private fun alignToCodePoint(offset: Int, forward: Boolean): Int {
        if (offset <= 0 || offset >= host.documentLength) {
            return offset
        }
        val around = host.getDocumentText(offset - 1, offset + 1)
        val splitsPair = around.length == 2 &&
            Character.isHighSurrogate(around[0]) && Character.isLowSurrogate(around[1])
        return when {
            !splitsPair -> offset
            forward -> offset + 1
            else -> offset - 1
        }
    }

    // #endregion

    // #region reading and selecting

    /** Where the selection starts (the cursor, if nothing is selected). */
    val selectionStart: Int
        get() = Math.min(host.selectionStartOffset, host.selectionEndOffset)

    /** Where the selection ends (the cursor, if nothing is selected). */
    val selectionEnd: Int
        get() = Math.max(host.selectionStartOffset, host.selectionEndOffset)

    /** Up to [length] chars before the cursor (the start of the selection). */
    fun getTextBeforeCursor(length: Int): String {
        val cursor = Math.min(host.selectionStartOffset, host.selectionEndOffset)
        return host.getDocumentText((cursor - Math.max(0, length)).coerceAtLeast(0), cursor)
    }

    /** Up to [length] chars after the cursor (the end of the selection). */
    fun getTextAfterCursor(length: Int): String {
        val cursor = Math.max(host.selectionStartOffset, host.selectionEndOffset)
        return host.getDocumentText(cursor, (cursor + Math.max(0, length)).coerceAtMost(host.documentLength))
    }

    /** The selected text, or null if nothing is selected. */
    fun getSelectedText(): String? {
        val start = Math.min(host.selectionStartOffset, host.selectionEndOffset)
        val end = Math.max(host.selectionStartOffset, host.selectionEndOffset)
        return if (start == end) null else host.getDocumentText(start, end)
    }

    /** Selects the text between two offsets, or places the cursor if they are equal. */
    fun setSelection(start: Int, end: Int) {
        val length = host.documentLength
        host.selectDocumentRange(start.coerceIn(0, length), end.coerceIn(0, length))
    }

    // #endregion
}
