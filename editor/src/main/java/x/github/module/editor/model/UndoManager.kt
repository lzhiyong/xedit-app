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

/**
 * The undo and redo stacks, and where on them the saved text is.
 *
 * One entry is one [UndoStep]. *When* edits become a step is decided by [EditHistory],
 * and replaying a step is done by [EditorDocument]; this class only keeps the steps.
 *
 * The undo stack is bounded, by [maxSteps] and by [maxChars] (see [UndoStep.cost]: large
 * content held as references into the buffer counts for little, which is the point of
 * holding it that way). When a limit is passed the oldest steps are forgotten. The most
 * recent step is always kept, however large it is.
 *
 * **The save point.** The text that is saved (the one in the file) is one of the states
 * the history can reach: the state after some step, or the state at the bottom of the
 * undo stack, before its oldest step. The manager remembers which, by the identity of
 * that step, and keeps it right while steps go back and forth between the stacks:
 *
 *  - undo and redo move the current state; the save point stays where it is, so undoing
 *    back to it makes the text unmodified again ([isAtSavePoint]);
 *  - when the redo stack is dropped (a new edit after an undo) with the save point on
 *    it, the saved text can no longer be reached, and the save point is lost;
 *  - when the oldest step is forgotten, the bottom moves up by one step: a save point
 *    after that step is now the bottom, a save point at the old bottom is lost.
 *
 * A lost save point stays lost until the text is saved again ([markSaved]).
 *
 * Main thread only, like the rest of the history.
 */
class UndoManager {

    companion object {
        const val DEFAULT_MAX_STEPS = 1000
        // about 8 MB of text held as Strings
        const val DEFAULT_MAX_CHARS = 4_000_000L
        // about 128 MB of storage the undo steps keep alive by referring to it
        const val DEFAULT_MAX_REFERENCED_CHARS = 64_000_000L
    }

    /**
     * A state the history was in, to be made the save point later on, when the text of
     * that state has been written ([EditHistory.markSaved]). Taken with [mark].
     */
    class Mark internal constructor(
        // the step the state is after; null: the bottom of the undo stack
        internal val step: UndoStep?,
        // which bottom, see bottomGeneration
        internal val generation: Int
    )

    // the most recent step is first
    private var undoStack = ArrayDeque<UndoStep>()
    private var redoStack = ArrayDeque<UndoStep>()

    /** How many steps can be undone at most. */
    var maxSteps: Int = DEFAULT_MAX_STEPS
        set(value) {
            field = value
            trimUndoStack()
        }

    /**
     * How much the undo steps may hold together, in chars, see [UndoStep.cost]. The most
     * recent step is always kept, however large it is.
     */
    var maxChars: Long = DEFAULT_MAX_CHARS
        set(value) {
            field = value
            trimUndoStack()
        }

    /**
     * How much buffer storage the undo steps may keep alive by referring to it, in
     * chars. A step that deleted a large block holds the block as references, not as
     * a copy (see HistoryChange): cheap as long as the block is still in the buffer's
     * storage anyway, which it is until the storage is compacted. Such steps count
     * for little against [maxChars], and without a limit of their own a history of
     * huge deletions could keep a lot of storage from being freed. The oldest steps
     * go beyond this; the most recent one is always kept.
     */
    var maxReferencedChars: Long = DEFAULT_MAX_REFERENCED_CHARS
        set(value) {
            field = value
            trimUndoStack()
        }

    // the cost of the undo stack; negative when it has to be counted again
    private var undoCost = 0L

    private fun referencedChars(): Long {
        var length = 0L
        for (step in undoStack) {
            length += step.referencedLength
        }
        return length
    }

    // the save point: a step (the saved text is the state after it), BOTTOM (the state
    // before the oldest step on the undo stack), or null (lost)
    private var savePoint: Any? = BOTTOM

    // Changes whenever the bottom of the undo stack becomes another state: the oldest
    // step is forgotten, the stacks are cleared or replaced. A Mark of the bottom is
    // only good for the bottom it was taken at.
    private var bottomGeneration = 0

    private fun countUndoCost(): Long {
        if (undoCost < 0) {
            var cost = 0L
            for (step in undoStack) {
                cost += step.cost
            }
            undoCost = cost
        }
        return undoCost
    }

    // forgets the oldest steps until the stack is within its limits again
    private fun trimUndoStack() {
        while (undoStack.size > 1 &&
            (undoStack.size > maxSteps || countUndoCost() > maxChars || referencedChars() > maxReferencedChars)
        ) {
            val oldest = undoStack.removeLast()
            undoCost -= oldest.cost
            bottomGeneration++
            savePoint = when {
                // the saved text was the old bottom: it can no longer be reached
                savePoint === BOTTOM -> null
                // the saved text was after the step that is gone: it is the bottom now
                savePoint === oldest -> BOTTOM
                else -> savePoint
            }
        }
    }

    /** Adds a step on top of the undo stack. */
    fun push(step: UndoStep) {
        undoStack.addFirst(step)
        if (undoCost >= 0) {
            undoCost += step.cost
        }
        trimUndoStack()
    }

    /**
     * Moves the most recent step from the undo to the redo stack.
     *
     * @return the step to take back; null if there is nothing to undo
     */
    fun undo(): UndoStep? {
        val step = undoStack.removeFirstOrNull() ?: return null
        if (undoCost >= 0) {
            undoCost -= step.cost
        }
        redoStack.addFirst(step)
        return step
    }

    /**
     * Moves the most recently undone step back to the undo stack.
     *
     * @return the step to apply again; null if there is nothing to redo
     */
    fun redo(): UndoStep? {
        val step = redoStack.removeFirstOrNull() ?: return null
        // a redone step was within the limits before it was undone: no trimming here
        undoStack.addFirst(step)
        if (undoCost >= 0) {
            undoCost += step.cost
        }
        return step
    }

    /**
     * The step was replayed, and now holds [changes]: what the buffer exported while
     * replaying it. Same step, same place on the stacks, new contents.
     */
    fun updateStep(step: UndoStep, changes: List<HistoryChange>) {
        step.changes = changes
        // its cost changed; counted again when it matters
        undoCost = -1L
    }

    fun canUndo() = undoStack.size > 0

    fun canRedo() = redoStack.size > 0

    /** Forgets the redo stack, e.g. because a new edit was made. */
    fun clearRedo() {
        if (redoStack.isEmpty()) return
        if (savePoint != null && savePoint !== BOTTOM && redoStack.any { it === savePoint }) {
            // the saved text was ahead, on the redo stack, and now cannot be reached
            savePoint = null
        }
        redoStack.clear()
    }

    /**
     * Forgets everything. The text as it is becomes the bottom of an empty history, and
     * counts as saved: a history is cleared when another text is loaded.
     */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        undoCost = 0L
        bottomGeneration++
        savePoint = BOTTOM
    }

    // #region save point

    /** The text as it is now is the saved one. */
    fun markSaved() {
        savePoint = undoStack.firstOrNull() ?: BOTTOM
    }

    /** The state the history is in now, to be made the save point later, see [Mark]. */
    fun mark(): Mark = Mark(undoStack.firstOrNull(), bottomGeneration)

    /**
     * The text of [mark] is the saved one. If that state can no longer be reached (its
     * step was dropped, or the bottom moved), the save point is lost.
     */
    fun markSaved(mark: Mark) {
        val step = mark.step
        savePoint = when {
            step == null -> if (mark.generation == bottomGeneration) BOTTOM else null
            undoStack.any { it === step } || redoStack.any { it === step } -> step
            else -> null
        }
    }

    /**
     * Where the saved text is can no longer be told, e.g. after an edit that is not part
     * of the history. Until the next [markSaved] the text counts as modified.
     */
    fun forgetSavePoint() {
        savePoint = null
    }

    /** Whether the history is at the save point: the text is the saved one. */
    fun isAtSavePoint(): Boolean {
        val point = savePoint ?: return false
        return point === (undoStack.firstOrNull() ?: BOTTOM)
    }

    /**
     * The two stacks as they would be with the saved text instead of the text as it is:
     * the steps made since the save point moved over to the redo stack, or the steps undone
     * past it moved back to the undo stack. Nothing is changed here.
     *
     * @return null if the save point is lost
     */
    fun alignedToSavePoint(): AlignedHistory? {
        val point = savePoint ?: return null
        // how many steps the text is ahead of the saved one
        val ahead = if (point === BOTTOM) undoStack.size else undoStack.indexOfFirst { it === point }
        if (ahead >= 0) {
            val stepsAhead = undoStack.subList(0, ahead).toList()
            return AlignedHistory(
                undoStack = undoStack.subList(ahead, undoStack.size).toList(),
                // undone one by one, the most recent first: the oldest ends up on top
                redoStack = stepsAhead.asReversed() + redoStack,
                stepsAhead = stepsAhead,
                stepsBehind = emptyList()
            )
        }
        // the text is behind the saved one: the save point is on the redo stack
        val behind = redoStack.indexOfFirst { it === point }
        if (behind < 0) {
            return null
        }
        val stepsBehind = redoStack.subList(0, behind + 1).toList()
        return AlignedHistory(
            undoStack = stepsBehind.asReversed() + undoStack,
            redoStack = redoStack.subList(behind + 1, redoStack.size).toList(),
            stepsAhead = emptyList(),
            stepsBehind = stepsBehind
        )
    }

    // #endregion

    /** A copy of the undo stack, the most recent step first. Only the list is copied, not the steps. */
    fun getUndoStack(): List<UndoStep> = undoStack.toList()

    /** A copy of the redo stack, the most recently undone step first. */
    fun getRedoStack(): List<UndoStep> = redoStack.toList()

    /**
     * Replaces both stacks (the most recent step first in each), e.g. with those of a
     * restored session. The text they are put on is the saved one: the save point is
     * the top of the new undo stack.
     */
    fun setStacks(undoStack: List<UndoStep>, redoStack: List<UndoStep>) {
        this.undoStack = ArrayDeque(undoStack)
        this.redoStack = ArrayDeque(redoStack)
        undoCost = -1L
        bottomGeneration++
        savePoint = this.undoStack.firstOrNull() ?: BOTTOM
        trimUndoStack()
    }

    override fun toString(): String {
        return "undo_stack: ${undoStack} -> redo_stack: ${redoStack}"
    }
}

// the save point at the bottom of the undo stack, see UndoManager.savePoint
private object BOTTOM

/**
 * The history seen from the saved text, see [UndoManager.alignedToSavePoint]. Both stacks
 * have their next step first.
 */
class AlignedHistory(
    val undoStack: List<UndoStep>,
    val redoStack: List<UndoStep>,
    /** The steps made since the text was saved, the most recent first. */
    val stepsAhead: List<UndoStep>,
    /** The steps that were saved and are undone now, the next one to redo first. */
    val stepsBehind: List<UndoStep>
)
