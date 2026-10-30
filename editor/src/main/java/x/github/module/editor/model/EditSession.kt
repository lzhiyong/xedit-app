/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.editor.model

import kotlinx.serialization.Serializable

import x.github.module.piecetable.common.TextChange

/**
 * The editing session of one text, without the text: where the cursor was, and
 * what could be undone and redone.
 *
 * The text is not part of it. It can be tens of megabytes and it is on disk
 * already: the app loads the file again (EditorView.setBuffer) and puts the
 * session back on top of it (EditorView.restoreSession).
 *
 * **A session always belongs to the saved text**, the one in the file, also when it
 * is taken while the text has unsaved edits. Those edits are not lost: they are on
 * the redo stack of the session. Restored on the file, the session gives the text
 * as it was saved, and redo brings the unsaved edits back one step at a time.
 * Likewise, when the text was undone to before the saved one, the undone steps are
 * on the undo stack.
 *
 * Undo steps are edits at fixed offsets. Replayed on a text that is not exactly
 * the one they were recorded on, they would garble it. So a session carries what
 * it takes to recognise its text, and the editor checks it before restoring:
 *
 *  - [contentHash]: a hash of the text, computed by the app (the editor never
 *    computes one). It tells whether the file is still the one the session was
 *    saved for.
 *  - [textLength] and [lineCount]: the saved text as the editor had it. They catch
 *    what the hash cannot: the same bytes read into another text (another encoding,
 *    other line breaks), or a hash that was computed for the wrong text.
 *
 * Which file a session belongs to is not part of it either. Files are the app's
 * business: it stores sessions by whatever identifies a file for it.
 *
 * In the session the steps hold their text as Strings (the editor keeps large
 * content as references into the buffer, which do not outlive it). Only the steps
 * nearest the saved text are kept, up to a limit (EditorView.setSessionLimits), so
 * that a session stays small enough to be written quickly.
 *
 * It owns its two stacks: the editor builds them when saving and copies them when
 * restoring, so a session does not change behind the back of whoever holds it.
 */
@Serializable
data class EditSession(
    /** A hash of the saved text, computed by the app. */
    val contentHash: String,
    /** The length of the saved text, in chars. */
    val textLength: Int,
    /** How many lines the saved text has. */
    val lineCount: Int,
    /** Where the cursor goes in the saved text, as an offset. */
    val cursorOffset: Int,
    /** The steps that can be undone, the next one first. */
    @Serializable(with = ArrayDequeSerializer::class)
    val undoStack: ArrayDeque<List<TextChange>>,
    /** The steps that can be redone, the next one first. Unsaved edits are here. */
    @Serializable(with = ArrayDequeSerializer::class)
    val redoStack: ArrayDeque<List<TextChange>>,
    /**
     * What was folded, as start and end line one after the other. Empty when the
     * session was taken with unsaved edits: the folds were lines of that text, not of
     * the saved one. Sessions written before folds were kept have none.
     */
    val foldedLines: List<Int> = emptyList(),
) {

    /** How an attempt to put a session back on a text ended. */
    enum class RestoreResult {
        /** The history and the cursor are back. */
        RESTORED,

        /**
         * The session belongs to another text: the hash, the length or the number
         * of lines differ. It will never fit this text; the app can delete it.
         */
        CONTENT_MISMATCH,

        /**
         * The text was edited since it was loaded, so it is no longer the text the
         * session ends at. The session may still fit the file; keep it.
         */
        TEXT_MODIFIED,

        /**
         * The editor is in the middle of loading a text, laying it out, or zooming.
         * Nothing was checked; try again when it is done.
         */
        BUSY
    }
}
