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

package x.github.module.editor.view

import x.github.module.editor.UndoManager
import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.TextChange

/**
 * The undo/redo history of the document, including the "typing burst" rule: edits that
 * arrive within a short delay of each other are merged into one undo step, and only
 * pushed onto the undo stack once the user pauses.
 *
 * It records and replays [TextChange]s; turning them back into edit operations and
 * applying them to the buffer stays with whoever owns the buffer.
 */
class EditHistory(
    private val host: EditHistory.Host,
    private val undoManager: UndoManager = UndoManager(),
    // called when an edit joins the current burst, before it is merged
    private val onMergeStarted: () -> Unit = {},
    // called once the burst has landed on the undo stack (e.g. to refresh an undo button)
    private val onMergeCommitted: () -> Unit = {}
) {

    /** Delayed scheduling only; both methods are inherited from View as-is. */
    interface Host {
        fun postDelayed(action: Runnable, delayMillis: Long): Boolean
        fun removeCallbacks(action: Runnable): Boolean
    }

    companion object {
        const val DEFAULT_MERGE_DELAY_MS = 500L
    }

    // the edits of the current burst, merged but not on the undo stack yet
    private var pendingChanges = emptyList<TextChange>()

    private val commitAction = Runnable {
        // push to the undo stack
        undoManager.push(pendingChanges)
        // clear the merge list
        pendingChanges = emptyList()
        // now we can undo
        onMergeCommitted()
    }

    /**
     * Records the reverse of an edit that was just applied. It is merged with the other
     * edits of the current burst and committed [delay] ms after the last one.
     */
    fun record(reverseEdits: List<ReverseEditOperation>?, delay: Long = DEFAULT_MERGE_DELAY_MS) {
        // clear the redo stack when a new edit pushed
        undoManager.clearRedo()

        onMergeStarted()

        reverseEdits?.let { reverses ->
            pendingChanges = TextChange.compressConsecutiveTextChanges(
                pendingChanges,
                reverses.map { it.textChange }
            )

            // cancel any pending commit scheduled by a previous edit,
            // so rapid typing doesn't stack up multiple delayed pushes
            host.removeCallbacks(commitAction)
            host.postDelayed(commitAction, delay)
        }
    }

    /**
     * Call before any edit is applied: postpones the commit of the current burst. The
     * merged changes are kept, and committed after the next recorded edit.
     */
    fun cancelPendingCommit() {
        host.removeCallbacks(commitAction)
    }

    fun undo(): List<TextChange> = undoManager.undo()

    fun redo(): List<TextChange> = undoManager.redo()

    fun canUndo() = undoManager.canUndo()

    fun canRedo() = undoManager.canRedo()

    /** Forgets everything, including a burst that was not committed yet. */
    fun clear() {
        host.removeCallbacks(commitAction)
        pendingChanges = emptyList()
        undoManager.clear()
    }

    fun getUndoStack() = undoManager.getUndoStack()

    fun getRedoStack() = undoManager.getRedoStack()

    /** Replaces both stacks, e.g. when restoring a saved state. */
    fun restore(
        undoStack: ArrayDeque<List<TextChange>>,
        redoStack: ArrayDeque<List<TextChange>>
    ) {
        undoManager.setUndoStack(undoStack)
        undoManager.setRedoStack(redoStack)
    }
}
