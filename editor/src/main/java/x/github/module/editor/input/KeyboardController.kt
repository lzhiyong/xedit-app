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

import android.view.KeyCharacterMap
import android.view.KeyEvent


/**
 * Turns key presses into editor actions: a hardware keyboard, and the keys a soft
 * keyboard sends as key events (arrow keys on some, enter and backspace on many).
 *
 * It only decides what a key *means*. Every branch ends in a call to a [Host] method
 * named after the intent, which EditorView carries out.
 *
 *  | keys                              | action                                   |
 *  |-----------------------------------|------------------------------------------|
 *  | arrows, Home, End, Page Up/Down   | move the caret                           |
 *  | Ctrl + Left / Right               | move by words                            |
 *  | Ctrl + Home / End                 | start / end of the text                  |
 *  | Shift + any of the above          | the same, selecting on the way           |
 *  | Enter, Backspace, Delete, Tab     | edit                                     |
 *  | Ctrl + A / C / X / V              | select all, copy, cut, paste             |
 *  | Ctrl + Z, Ctrl + Y, Ctrl+Shift+Z  | undo, redo                               |
 *  | Escape                            | drop the selection                       |
 *  | anything that produces a char     | type it                                  |
 */
class KeyboardController(private val host: KeyboardController.Host) {

    /** What a key can ask the editor to do. Implemented by EditorView. */
    interface Host {
        /** Moves the caret; with [extendSelection] the selection follows it. */
        fun onCaretMove(move: CaretMove, extendSelection: Boolean)

        /** A key produced text. */
        fun onTextTyped(text: String)

        fun onEnterPressed()

        /** Tab without a modifier: indent the selected lines, or type an indentation. */
        fun onTabPressed()

        /** Shift+Tab: one level of indentation less on the selected lines. */
        fun onOutdentPressed()
        /** Delete the character before the caret, or the selection. */
        fun onBackspacePressed()
        /** Delete the character after the caret, or the selection. */
        fun onForwardDeletePressed()

        /** One of android.R.id.selectAll, cut, copy, paste. Returns whether it was carried out. */
        fun onContextMenuAction(id: Int): Boolean

        fun onUndoRequested()
        fun onRedoRequested()

        /** Returns whether there was anything to escape from (a selection). */
        fun onEscapePressed(): Boolean
    }

    /**
     * Call from View.onKeyDown (and for key events that arrive through the input
     * connection).
     *
     * @return true if the key meant something to the editor; false leaves it to the
     *         system (back, volume, keys of other views, ...)
     */
    fun onKeyDown(event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> host.onCaretMove(if (ctrl) CaretMove.WORD_LEFT else CaretMove.LEFT, shift)
            KeyEvent.KEYCODE_DPAD_RIGHT -> host.onCaretMove(if (ctrl) CaretMove.WORD_RIGHT else CaretMove.RIGHT, shift)
            KeyEvent.KEYCODE_DPAD_UP -> host.onCaretMove(CaretMove.UP, shift)
            KeyEvent.KEYCODE_DPAD_DOWN -> host.onCaretMove(CaretMove.DOWN, shift)
            KeyEvent.KEYCODE_MOVE_HOME ->
                host.onCaretMove(if (ctrl) CaretMove.DOCUMENT_START else CaretMove.LINE_START, shift)
            KeyEvent.KEYCODE_MOVE_END ->
                host.onCaretMove(if (ctrl) CaretMove.DOCUMENT_END else CaretMove.LINE_END, shift)
            KeyEvent.KEYCODE_PAGE_UP -> host.onCaretMove(CaretMove.PAGE_UP, shift)
            KeyEvent.KEYCODE_PAGE_DOWN -> host.onCaretMove(CaretMove.PAGE_DOWN, shift)

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> host.onEnterPressed()
            KeyEvent.KEYCODE_DEL -> host.onBackspacePressed()
            KeyEvent.KEYCODE_FORWARD_DEL -> host.onForwardDeletePressed()
            KeyEvent.KEYCODE_TAB -> {
                // with Ctrl or Alt it is for moving the focus or switching apps
                if (ctrl || alt) return false
                if (shift) host.onOutdentPressed() else host.onTabPressed()
            }
            KeyEvent.KEYCODE_ESCAPE -> return host.onEscapePressed()

            else -> return if (ctrl && !alt) onShortcut(event.keyCode, shift) else onCharacterKey(event)
        }
        return true
    }

    private fun onShortcut(keyCode: Int, shift: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_A -> host.onContextMenuAction(android.R.id.selectAll)
            KeyEvent.KEYCODE_C -> host.onContextMenuAction(android.R.id.copy)
            KeyEvent.KEYCODE_X -> host.onContextMenuAction(android.R.id.cut)
            KeyEvent.KEYCODE_V -> host.onContextMenuAction(android.R.id.paste)
            KeyEvent.KEYCODE_Z -> if (shift) host.onRedoRequested() else host.onUndoRequested()
            KeyEvent.KEYCODE_Y -> host.onRedoRequested()
            else -> return false
        }
        return true
    }

    // a key that stands for a character, with whatever Shift / Alt make of it
    // the accent of a dead key pressed last, waiting for its letter; 0 for none
    private var pendingAccent = 0

    private fun onCharacterKey(event: KeyEvent): Boolean {
        val unicode = event.unicodeChar
        // a dead key: an accent waiting for its letter (´ then e gives é)
        if ((unicode and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            pendingAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (unicode == 0 || Character.isISOControl(unicode)) {
            return false
        }
        var codePoint = unicode
        val accent = pendingAccent
        if (accent != 0) {
            pendingAccent = 0
            // the letter with the accent, if there is one; otherwise the letter alone
            // (and a space after a dead key gives the accent itself)
            val combined = KeyCharacterMap.getDeadChar(accent, codePoint)
            if (combined != 0) codePoint = combined
        }
        host.onTextTyped(String(Character.toChars(codePoint)))
        return true
    }
}
