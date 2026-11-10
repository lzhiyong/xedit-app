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

import x.github.module.editor.testing.FakeScheduler
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.TextChange

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class EditHistoryTest {

    private val scheduler = FakeScheduler()
    private val undoManager = UndoManager()
    private var mergeStarted = 0
    private var mergeCommitted = 0
    private val modifiedChanges = mutableListOf<Boolean>()
    private val history = EditHistory(
        host = scheduler,
        undoManager = undoManager,
        onMergeStarted = { mergeStarted++ },
        onMergeCommitted = { mergeCommitted++ },
        onModifiedChanged = { modifiedChanges.add(it) }
    )

    // what the document records for "typed [text] at [offset]"
    private fun typed(offset: Int, text: String) =
        listOf(HistoryChange.of(TextChange(offset, "", offset, text)))

    // what the document records for "deleted [text] at [offset]"
    private fun deleted(offset: Int, text: String) =
        listOf(HistoryChange.of(TextChange(offset, text, offset, "")))

    private fun UndoStep.single(): TextChange = changes.single().toTextChange()

    @Test
    fun anEditIsOnlyCommittedAfterTheDelay() {
        history.record(typed(0, "a"))

        assertFalse(undoManager.canUndo(), "not on the stack yet")
        assertEquals(1, scheduler.pendingCount)
        assertEquals(1, mergeStarted)
        assertEquals(0, mergeCommitted)

        scheduler.runAll()

        assertTrue(undoManager.canUndo())
        assertEquals(1, mergeCommitted)
    }

    @Test
    fun editsInQuickSuccessionBecomeOneStep() {
        history.record(typed(0, "a"))
        history.record(typed(1, "b"))
        history.record(typed(2, "c"))

        assertEquals(1, scheduler.pendingCount, "each edit replaces the previous delayed commit")
        scheduler.runAll()

        val stack = undoManager.getUndoStack()
        assertEquals(1, stack.size)
        assertEquals(TextChange(0, "", 0, "abc"), stack[0].single())
    }

    @Test
    fun editsSeparatedByAPauseBecomeSeparateSteps() {
        history.record(typed(0, "a"))
        scheduler.runAll()
        history.record(typed(1, "b"))
        scheduler.runAll()

        assertEquals(2, undoManager.getUndoStack().size)
    }

    // Merging works on Strings: a large edit would be copied into one. It is a step
    // of its own, and the burst before it is one too.
    @Test
    fun aLargeEditIsAStepOfItsOwnAtOnce() {
        history.record(typed(0, "a"))
        history.record(typed(1, "x".repeat(HistoryChange.MERGE_LIMIT + 1)))

        assertEquals(0, scheduler.pendingCount, "nothing waits for the delay")
        assertEquals(2, undoManager.getUndoStack().size)
        history.record(typed(0, "b"))
        scheduler.runAll()
        assertEquals(3, undoManager.getUndoStack().size, "the next small edit starts a new burst")
    }

    @Test
    fun whatWasJustTypedCountsAsUndoable() {
        history.record(typed(0, "a"))

        assertTrue(history.canUndo())
    }

    // Typing and undoing within the delay: the burst is the newest step and has to
    // be the one that is undone, not whatever was on the stack before it.
    @Test
    fun undoTakesBackTheUncommittedBurstFirst() {
        history.record(typed(0, "old"))
        scheduler.runAll()
        history.record(typed(3, "new"))

        val undone = assertNotNull(history.undo())

        assertEquals("new", undone.single().newText)
        assertEquals(0, scheduler.pendingCount, "the delayed commit must not fire a second time")
        assertEquals("old", assertNotNull(history.undo()).single().newText)
        assertFalse(history.canUndo())
    }

    @Test
    fun aNewEditClearsTheRedoStack() {
        history.record(typed(0, "a"))
        scheduler.runAll()
        history.undo()
        assertTrue(history.canRedo())

        history.record(typed(0, "b"))

        assertFalse(history.canRedo())
    }

    @Test
    fun cancelPendingCommitKeepsTheBurstForTheNextEdit() {
        history.record(typed(0, "a"))
        history.cancelPendingCommit()
        assertEquals(0, scheduler.pendingCount)

        history.record(typed(1, "b"))
        scheduler.runAll()

        assertEquals(TextChange(0, "", 0, "ab"), undoManager.getUndoStack()[0].single())
    }

    @Test
    fun commitPendingCommitsNowAndOnlyOnce() {
        history.record(typed(0, "a"))

        history.commitPending()
        history.commitPending()
        scheduler.runAll()

        assertEquals(1, undoManager.getUndoStack().size)
        assertEquals(1, mergeCommitted)
    }

    @Test
    fun commitPendingWithNothingPendingDoesNothing() {
        history.commitPending()

        assertFalse(undoManager.canUndo())
        assertEquals(0, mergeCommitted)
    }

    // Switching files within the delay: what was typed into the old file must not
    // end up on the undo stack of the new one.
    @Test
    fun restoreDropsTheUncommittedBurst() {
        history.record(typed(0, "typed into the old file"))

        history.restore(listOf(UndoStep(typed(0, "other"))), emptyList())
        scheduler.runAll()

        val stack = undoManager.getUndoStack()
        assertEquals(1, stack.size)
        assertEquals("other", stack[0].single().newText)
        assertFalse(history.isModified, "a restored history sits on the saved text")
    }

    @Test
    fun clearForgetsStacksAndBurst() {
        history.record(typed(0, "a"))
        scheduler.runAll()
        history.record(typed(1, "b"))

        history.clear()
        scheduler.runAll()

        assertFalse(history.canUndo())
        assertFalse(history.canRedo())
        assertFalse(history.isModified)
    }

    @Test
    fun anEditWithoutChangesSchedulesNothing() {
        history.record(emptyList())
        history.recordReverseEdits(null)

        assertEquals(0, scheduler.pendingCount)
        assertFalse(history.canUndo())
    }

    @Test
    fun reverseEditsOfThePieceTreeAreRecordedLikeAnyEdit() {
        history.recordReverseEdits(
            listOf(ReverseEditOperation(0, null, Range(1, 1, 1, 1), "", TextChange(4, "", 4, "x")))
        )
        scheduler.runAll()

        assertEquals(TextChange(4, "", 4, "x"), undoManager.getUndoStack()[0].single())
    }

    // #region modified

    @Test
    fun typingMakesTheTextModifiedAndUndoingMakesItUnmodified() {
        history.record(typed(0, "a"))
        assertTrue(history.isModified, "while the burst is still pending")

        history.undo()

        assertFalse(history.isModified)
        assertEquals(listOf(true, false), modifiedChanges)
    }

    @Test
    fun typingAndDeletingItWithinOneBurstIsNoChange() {
        history.record(typed(0, "a"))
        history.record(deleted(0, "a"))

        assertFalse(history.isModified)
    }

    @Test
    fun savingMakesTheTextUnmodified() {
        history.record(typed(0, "a"))

        history.markSaved()

        assertFalse(history.isModified)
        history.undo()
        assertTrue(history.isModified, "the saved text has the \"a\"")
    }

    @Test
    fun aMarkTakenBeforeTypingOnStillPointsAtTheSavedText() {
        history.record(typed(0, "a"))
        val mark = history.mark()
        history.record(typed(1, "b"))

        history.markSaved(mark)

        assertTrue(history.isModified, "\"b\" came after the text that was written")
        history.undo()
        assertFalse(history.isModified)
    }

    @Test
    fun anEditOutsideTheHistoryLeavesTheTextModified() {
        history.forgetSavePoint()

        assertTrue(history.isModified)
        assertEquals(null, history.alignedToSavePoint())
    }

    // #endregion
}
