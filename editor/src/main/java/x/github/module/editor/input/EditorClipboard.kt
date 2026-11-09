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

import android.content.ClipData
import android.content.ClipboardManager

/**
 * Copy, cut and paste between the editor and the system clipboard.
 *
 * It moves text and reports what happened; it shows nothing. Whether and how the
 * user is told ("copied", "selection too large", ...) is up to whoever calls it,
 * which is why [copy] and [cut] return a [CopyResult] instead of a plain yes/no.
 */
class EditorClipboard(
    private val clipboard: ClipboardManager,
    private val host: EditorClipboard.Host
) {

    /** The editor as the clipboard sees it. Implemented by EditorView. */
    interface Host {
        /** The selected text, empty when nothing is selected. */
        fun getSelectedText(): String
        /** The selected text was put on the clipboard by a cut and has to go. */
        fun onSelectionCut()
        /** Text arrived from the clipboard, to be inserted at the cursor / over the selection. */
        fun onTextPasted(text: String)
    }

    /** How a copy (or the copy half of a cut) ended. */
    sealed class CopyResult {
        /** The selection is on the clipboard now. */
        object Copied : CopyResult()

        /** There was no selection; the clipboard was left alone. */
        object NothingSelected : CopyResult()

        /** The selection is longer than the clipboard can take; nothing was copied. */
        class TooLarge(val length: Int, val maxLength: Int) : CopyResult()

        /** The system refused the clip. */
        class Failed(val error: Exception) : CopyResult()
    }

    companion object {
        /**
         * The longest text that is handed to the clipboard, in chars.
         *
         * A clip travels to the system in a binder transaction, and all transactions
         * of a process share one buffer of 1 MB (see TransactionTooLargeException).
         * A char takes 2 bytes of it, so this is about 800 KB and leaves some room
         * for whatever else is in flight.
         */
        const val MAX_TEXT_LENGTH = 400_000
    }

    /** Puts the selected text on the clipboard. */
    fun copy(): CopyResult {
        val text = host.getSelectedText()

        if (text.isEmpty()) {
            return CopyResult.NothingSelected
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return CopyResult.TooLarge(text.length, MAX_TEXT_LENGTH)
        }

        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("content", text))
            CopyResult.Copied
        } catch (e: Exception) {
            CopyResult.Failed(e)
        }
    }

    /**
     * Copies the selected text and, only if that worked, removes it from the editor.
     * A selection that could not be copied is never deleted.
     */
    fun cut(): CopyResult {
        val result = copy()
        if (result is CopyResult.Copied) {
            host.onSelectionCut()
        }
        return result
    }

    /**
     * Inserts the plain text on the clipboard into the editor.
     *
     * @return false if the clipboard holds no text
     */
    fun paste(): Boolean {
        if (!clipboard.hasPrimaryClip()) {
            return false
        }
        // Not checked: the declared type of the clip. Text copied from a web page or
        // a rich text app is declared as HTML or styled text, and carries its plain
        // text all the same. What counts is whether there is text.
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) {
            return false
        }
        // an item can be an uri or an intent, with no text at all
        val text = clip.getItemAt(0).text?.toString() ?: return false

        host.onTextPasted(text)
        return true
    }
}
