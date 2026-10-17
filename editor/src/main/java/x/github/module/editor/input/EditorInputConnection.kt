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

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * What the soft keyboard talks to.
 *
 * It only translates: every request of the input method becomes a call on the
 * [InputComposer], which knows what the request means for the text, or on the [Host]
 * for the few things that are not about text (the enter key, copy/paste commands).
 *
 * BaseInputConnection would otherwise answer these requests from a private text
 * buffer of its own that has nothing to do with the editor, so every method that
 * reads or changes text is overridden here.
 */
class EditorInputConnection(
    view: View,
    private val composer: InputComposer,
    private val host: EditorInputConnection.Host
) : BaseInputConnection(view, false) {

    /** What the keyboard can ask for besides text. Implemented by EditorView. */
    interface Host {
        /** Enter was pressed. */
        fun onEnterPressed()

        /** A bare tab committed by the input method: indents like the Tab key. */
        fun onTabPressed()

        /**
         * One char committed by the input method, outside of composing: the editor may
         * type it its own way (a pair typed together, see AutoPairs). Returns whether it
         * did; if not, it is committed as usual.
         */
        fun onCharTyped(text: String): Boolean
        /**
         * The keyboard sent a key instead of text (enter and backspace on many
         * keyboards, arrow keys on some). Returns whether the key was handled.
         */
        fun onKeyEvent(event: KeyEvent): Boolean
        /**
         * A command from the keyboard's own menu: one of android.R.id.selectAll, cut,
         * copy, paste. Returns whether it was carried out.
         */
        fun onContextMenuAction(id: Int): Boolean
    }

    companion object {
        // How much text around the cursor is handed over by getExtractedText. An
        // input method asking for "the text" must not be given a whole large file.
        private const val EXTRACTED_TEXT_WINDOW = 2000
    }

    // #region composing and committing

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        return composer.setComposingText(text.toString(), newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // A bare line break is the enter key of a multi-line field. It goes the same
        // way as the key, so it gets the document's own kind of line break.
        if (!composer.isComposing && text.toString() == "\n") {
            host.onEnterPressed()
            return true
        }
        // Likewise a bare tab is the tab key of the keyboard: it indents, with the
        // editor's indentation (spaces by default), not a tab character.
        if (!composer.isComposing && text.toString() == "\t") {
            host.onTabPressed()
            return true
        }
        // a single char, typed: it may be the open or close char of a pair
        if (!composer.isComposing && text.length == 1 && newCursorPosition == 1 && host.onCharTyped(text.toString())) {
            return true
        }
        return composer.commitText(text.toString(), newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        composer.finishComposingText()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composer.setComposingRegion(start, end)
        return true
    }

    // #endregion

    // #region deleting

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // "One char before the cursor" is what a keyboard sends for its delete key,
        // again and again while the key is held. It goes the way of the key, so that
        // it deletes a whole character whatever that is made of, and so that holding
        // the key speeds up the same way from every keyboard.
        if (beforeLength == 1 && afterLength == 0 && !composer.isComposing && !composer.hasSelection) {
            host.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            return true
        }
        return composer.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return composer.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    // #endregion

    // #region reading

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
        return composer.getTextBeforeCursor(length)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
        return composer.getTextAfterCursor(length)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        return composer.getSelectedText()
    }

    // The text around the cursor, with the offset it starts at: enough for an input
    // method to work with, without copying a whole file for it.
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val before = composer.getTextBeforeCursor(EXTRACTED_TEXT_WINDOW)
        val selected = composer.getSelectedText() ?: ""
        val after = composer.getTextAfterCursor(EXTRACTED_TEXT_WINDOW)

        return ExtractedText().also {
            it.text = before + selected + after
            // where the excerpt starts in the document; the selection below is
            // relative to it
            it.startOffset = composer.selectionStart - before.length
            it.selectionStart = before.length
            it.selectionEnd = before.length + selected.length
            it.partialStartOffset = -1
            it.partialEndOffset = -1
        }
    }

    // code is not prose: never capitalise automatically
    override fun getCursorCapsMode(reqModes: Int): Int = 0

    // #endregion

    // #region selection, batches, commands

    override fun setSelection(start: Int, end: Int): Boolean {
        composer.setSelection(start, end)
        return true
    }

    override fun beginBatchEdit(): Boolean {
        composer.beginBatchEdit()
        return true
    }

    override fun endBatchEdit(): Boolean {
        composer.endBatchEdit()
        return composer.isBatchEditing
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return host.onContextMenuAction(id)
    }

    // the action key of a multi-line editor is the enter key
    override fun performEditorAction(actionCode: Int): Boolean {
        host.onEnterPressed()
        return true
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    // keys go the same way as those of a hardware keyboard
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        host.onKeyEvent(event)
        return true
    }

    // #endregion
}
