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

package x.github.module.editor.model

import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.TextChange

/**
 * The undo/redo history of the document, including the "typing burst" rule: edits that
 * arrive within a short delay of each other are merged into one undo step, and only
 * pushed onto the undo stack once the user pauses.
 *
 * Only small edits merge (see [HistoryChange.isMergeable]). A large one, a paste or the
 * deletion of a block, is a step of its own right away: merging works on Strings, and
 * would copy content that the step otherwise holds as references into the buffer.
 *
 * It also knows whether the text is the saved one ([isModified]), from where the
 * history stands relative to the save point; see [UndoManager].
 *
 * It records and hands out [UndoStep]s; turning them into edits and applying them to the
 * buffer stays with whoever owns the buffer ([EditorDocument]). Main thread only, except
 * [isModified].
 */
class EditHistory(
    private val host: EditHistory.Host,
    private val undoManager: UndoManager = UndoManager(),
    // called when an edit joins the current burst, before it is merged
    private val onMergeStarted: () -> Unit = {},
    // called once the burst has landed on the undo stack (e.g. to refresh an undo button)
    private val onMergeCommitted: () -> Unit = {},
    // called when the text becomes modified or unmodified, see isModified
    private val onModifiedChanged: (Boolean) -> Unit = {}
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
    private var pendingChanges = emptyList<HistoryChange>()

    private val commitAction = Runnable { commitPending() }

    /**
     * Whether the text differs from the saved one: it has edits that are not undone back
     * to the save point, or the save point is lost. Undoing back to the saved text makes
     * it false again. Any thread.
     */
    @Volatile
    var isModified: Boolean = false
        private set

    private fun updateModified() {
        val modified = pendingChanges.isNotEmpty() || !undoManager.isAtSavePoint()
        if (modified != isModified) {
            isModified = modified
            onModifiedChanged(modified)
        }
    }

    /**
     * Puts the current burst on the undo stack right now instead of after the delay.
     * Does nothing if there is none. Call it before anything that needs the stacks to
     * be complete: undoing, saving the session.
     */
    fun commitPending() {
        host.removeCallbacks(commitAction)
        if (pendingChanges.isEmpty()) {
            return
        }
        // push to the undo stack
        undoManager.push(UndoStep(pendingChanges))
        // clear the merge list
        pendingChanges = emptyList()
        // now we can undo
        onMergeCommitted()
        updateModified()
    }

    /**
     * Records an edit that was just applied, as its changes in document order. Small
     * edits are merged with the other edits of the current burst and committed [delay] ms
     * after the last one; a large edit is committed at once, as a step of its own.
     */
    fun record(changes: List<HistoryChange>, delay: Long = DEFAULT_MERGE_DELAY_MS) {
        // a new edit: what was undone can no longer be redone
        undoManager.clearRedo()

        if (changes.isEmpty()) {
            updateModified()
            return
        }

        onMergeStarted()

        if (changes.all { it.isMergeable }) {
            // a burst that grew past the merge limit is closed first
            if (pendingChanges.any { !it.isMergeable }) {
                commitPending()
            }
            pendingChanges = if (pendingChanges.isEmpty()) {
                changes
            } else {
                merge(pendingChanges, changes)
            }
            // cancel any pending commit scheduled by a previous edit,
            // so rapid typing doesn't stack up multiple delayed pushes
            host.removeCallbacks(commitAction)
            if (pendingChanges.isNotEmpty()) {
                host.postDelayed(commitAction, delay)
            }
        } else {
            // the burst before it is a step, and so is this edit
            commitPending()
            pendingChanges = changes
            commitPending()
        }
        updateModified()
    }

    /**
     * [record] for the reverse edits of an edit that was applied with the piece tree's
     * applyEdits (computeUndoEdits = true) instead of through the editor.
     */
    fun recordReverseEdits(reverseEdits: List<ReverseEditOperation>?, delay: Long = DEFAULT_MERGE_DELAY_MS) {
        record(
            reverseEdits.orEmpty().map { HistoryChange.of(it.textChange) }.sortedBy { it.oldPosition },
            delay
        )
    }

    // Two edits in a row as one. Only called with mergeable changes, whose contents
    // are Strings: converting them to TextChanges copies nothing.
    private fun merge(previous: List<HistoryChange>, current: List<HistoryChange>): List<HistoryChange> =
        TextChange.compressConsecutiveTextChanges(
            previous.map { it.toTextChange() },
            current.map { it.toTextChange() }
        ).map { HistoryChange.of(it) }

    /**
     * Call before any edit is applied: postpones the commit of the current burst. The
     * merged changes are kept, and committed after the next recorded edit.
     */
    fun cancelPendingCommit() {
        host.removeCallbacks(commitAction)
    }

    /**
     * The step to take back, moved over to the redo stack; null if there is nothing to
     * undo. What was typed a moment ago and is still waiting to be committed counts: it
     * is the last step, and must go before anything older does.
     *
     * Tell [replayed] what the buffer exported while taking it back.
     */
    fun undo(): UndoStep? {
        commitPending()
        return undoManager.undo().also { updateModified() }
    }

    /** The step to apply again, moved back to the undo stack; null if there is nothing to redo. */
    fun redo(): UndoStep? {
        commitPending()
        return undoManager.redo().also { updateModified() }
    }

    /** [step] was undone or redone; [changes] is the step as it holds now, forwards. */
    fun replayed(step: UndoStep, changes: List<HistoryChange>) {
        undoManager.updateStep(step, changes)
    }

    // a burst that is not on the stack yet can be undone too
    fun canUndo() = pendingChanges.isNotEmpty() || undoManager.canUndo()

    fun canRedo() = undoManager.canRedo()

    /**
     * Forgets everything, including a burst that was not committed yet. The text as it
     * is counts as saved: a history is cleared when another text is loaded.
     */
    fun clear() {
        host.removeCallbacks(commitAction)
        pendingChanges = emptyList()
        undoManager.clear()
        updateModified()
    }

    // #region save point

    /** The text as it is now was written to its file. */
    fun markSaved() {
        commitPending()
        undoManager.markSaved()
        updateModified()
    }

    /**
     * Where the history stands now, to be marked saved later with [markSaved], once
     * this text has been written. Ends the current burst: a save point has to be between
     * two steps, not inside one.
     */
    fun mark(): UndoManager.Mark {
        commitPending()
        return undoManager.mark()
    }

    /** The text of [mark] was written to its file; see [mark]. */
    fun markSaved(mark: UndoManager.Mark) {
        undoManager.markSaved(mark)
        updateModified()
    }

    /**
     * The text was changed by an edit that is not part of the history: where the saved
     * text is can no longer be told. It counts as modified until it is saved again.
     */
    fun forgetSavePoint() {
        undoManager.forgetSavePoint()
        updateModified()
    }

    /** The history seen from the saved text; null if the save point is lost. See [UndoManager.alignedToSavePoint]. */
    fun alignedToSavePoint(): AlignedHistory? {
        commitPending()
        return undoManager.alignedToSavePoint()
    }

    // #endregion

    /** A copy of the undo stack. Call [commitPending] first if it has to be complete. */
    fun getUndoStack(): List<UndoStep> = undoManager.getUndoStack()

    /** A copy of the redo stack. */
    fun getRedoStack(): List<UndoStep> = undoManager.getRedoStack()

    /**
     * Replaces both stacks (the next step first in each), e.g. when restoring a session.
     * A burst that was not committed yet belongs to the text that is being replaced, and
     * is dropped. The text they are put on counts as saved.
     */
    fun restore(undoStack: List<UndoStep>, redoStack: List<UndoStep>) {
        host.removeCallbacks(commitAction)
        pendingChanges = emptyList()
        undoManager.setStacks(undoStack, redoStack)
        updateModified()
    }
}
