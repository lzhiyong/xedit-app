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

package x.editor.app.lsp

/**
 * What an edit does to completion: plain computation, see LspController.
 *
 * Typing is not one char at a time: most soft keyboards compose a word (setComposingText),
 * and every key replaces the word composed so far with one char more (`ab` -> `abc`). So
 * what counts is where the cursor is after the edit and what is before it, not the shape
 * of the edit: text put in with the cursor right after it, and a word before the cursor,
 * is typing.
 */
internal object CompletionTyping {

    sealed class Action {
        /** Nothing to do. */
        object None : Action() { override fun toString() = "None" }

        /** The list goes. */
        object Dismiss : Action() { override fun toString() = "Dismiss" }

        /** The list shown is filtered again with the word now before the cursor. */
        object Refilter : Action() { override fun toString() = "Refilter" }

        /** Completions are asked for: at once after a [trigger], after a pause on a word. */
        data class Request(val trigger: String?, val immediate: Boolean) : Action()
    }

    // more than that put in at once is not typing (a paste)
    const val MAX_TYPED = 64

    /**
     * [lineBeforeCursor]: the text of the line before the cursor, after the edit;
     * [inserted] and [deletedLength]: what the edit put in and took away; [cursorAtInsertEnd]:
     * whether the cursor is right after what was put in; [showing]: whether the list is
     * shown; [hasCompleteResult]: whether the list came from an answer the server said was
     * complete (it may be filtered on the client); [triggers]: the server's trigger
     * characters.
     */
    fun decide(
        lineBeforeCursor: String,
        inserted: String,
        deletedLength: Int,
        cursorAtInsertEnd: Boolean,
        showing: Boolean,
        hasCompleteResult: Boolean,
        triggers: Set<String>,
    ): Action {
        if (inserted.isNotEmpty()) {
            // a line break, a paste, an edit elsewhere (a completion's own #include)
            if (inserted.length > MAX_TYPED || inserted.contains('\n') || !cursorAtInsertEnd) return Action.Dismiss
            // a trigger just typed: `.`, `->` (its last char is what was typed)
            val trigger = triggers.firstOrNull { it.isNotEmpty() && lineBeforeCursor.endsWith(it) && inserted.endsWith(it.last()) }
            if (trigger != null) return Action.Request(trigger, immediate = true)
            if (wordBefore(lineBeforeCursor).isEmpty()) return Action.Dismiss
            return if (showing && hasCompleteResult) Action.Refilter else Action.Request(null, immediate = false)
        }
        if (deletedLength > 0) {
            // deleting does not bring a list up, but changes the one shown
            if (!showing) return Action.None
            if (wordBefore(lineBeforeCursor).isEmpty()) return Action.Dismiss
            return if (hasCompleteResult) Action.Refilter else Action.Request(null, immediate = false)
        }
        return Action.None
    }

    /** The word at the end of [text] (letters, digits, `_`, `$`): what completions match. */
    fun wordBefore(text: String): String {
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start)
    }

    // as CompletionRanker.isWordChar
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
