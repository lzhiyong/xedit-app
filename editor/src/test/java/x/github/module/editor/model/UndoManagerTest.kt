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

import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.TextChange

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class UndoManagerTest {

    // one step that inserted [text] at [offset]
    private fun insertion(offset: Int, text: String) =
        UndoStep(listOf(HistoryChange.of(TextChange(offset, "", offset, text))))

    // what a step of one inserted text costs, see HistoryChange.cost
    private fun costOf(text: String) = HistoryChange.PER_CHANGE_COST + text.length

    @Test
    fun undoAndRedoOnEmptyStacksReturnNothingInsteadOfThrowing() {
        val manager = UndoManager()

        assertNull(manager.undo())
        assertNull(manager.redo())
        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())
    }

    @Test
    fun stepsAreUndoneNewestFirstAndRedoneInReverse() {
        val manager = UndoManager()
        val first = insertion(0, "a")
        val second = insertion(1, "b")
        manager.push(first)
        manager.push(second)

        assertSame(second, manager.undo())
        assertSame(first, manager.undo())
        assertFalse(manager.canUndo())

        assertSame(first, manager.redo())
        assertSame(second, manager.redo())
        assertFalse(manager.canRedo())
        assertTrue(manager.canUndo())
    }

    @Test
    fun getUndoStackReturnsASnapshot() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))

        val snapshot = manager.getUndoStack()
        manager.push(insertion(1, "b"))

        assertEquals(1, snapshot.size, "later pushes must not reach the snapshot")
    }

    // The bug this guards against: tab A saves its stacks, tab B is restored into the
    // same editor, and A's saved undo history is wiped.
    @Test
    fun setStacksLeavesTheGivenListsAndThePreviousSnapshotAlone() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        val savedA = manager.getUndoStack()

        val savedB = mutableListOf(insertion(0, "x"), insertion(1, "y"))
        manager.setStacks(savedB, emptyList())

        assertEquals(1, savedA.size, "A's saved stack was emptied")

        manager.undo()
        manager.push(insertion(5, "z"))
        assertEquals(2, savedB.size, "B's saved stack changed with the manager")
    }

    @Test
    fun oldestStepsAreForgottenBeyondMaxSteps() {
        val manager = UndoManager()
        manager.maxSteps = 3
        val steps = (0 until 5).map { insertion(it, "x") }
        steps.forEach { manager.push(it) }

        val stack = manager.getUndoStack()
        assertEquals(3, stack.size)
        // newest first; the two oldest are gone
        assertSame(steps[4], stack[0])
        assertSame(steps[2], stack[2])
    }

    @Test
    fun oldestStepsAreForgottenBeyondMaxChars() {
        val manager = UndoManager()
        // room for two of them, not three
        manager.maxChars = costOf("aaaa") * 2 + 1
        manager.push(insertion(0, "aaaa"))
        manager.push(insertion(0, "bbbb"))
        manager.push(insertion(0, "cccc"))

        assertEquals(2, manager.getUndoStack().size)
        assertEquals("cccc", manager.undo()!!.changes[0].newContent.toText())
        assertEquals("bbbb", manager.undo()!!.changes[0].newContent.toText())
        assertFalse(manager.canUndo())
    }

    // a replace-all of many short matches is many objects, which the chars alone hide
    @Test
    fun everyChangeCountsBeyondItsChars() {
        val step = UndoStep((0 until 100).map { HistoryChange.of(TextChange(it * 2, "a", it * 2, "b")) })

        assertEquals(100 * (HistoryChange.PER_CHANGE_COST + 2), step.cost)
        assertEquals(200L, step.length)
    }

    @Test
    fun theNewestStepIsKeptEvenIfItAloneIsOverTheLimit() {
        val manager = UndoManager()
        manager.maxChars = 3
        manager.push(insertion(0, "a"))
        manager.push(insertion(0, "much too long"))

        assertEquals(1, manager.getUndoStack().size)
        assertEquals("much too long", manager.undo()!!.changes[0].newContent.toText())
    }

    @Test
    fun charBudgetFollowsUndoAndRedo() {
        val manager = UndoManager()
        manager.maxChars = costOf("aaaa") * 2
        manager.push(insertion(0, "aaaa"))
        manager.push(insertion(0, "bbbb"))
        // take both off the undo stack, then put new ones on: nothing may be
        // dropped because of chars that are no longer there
        manager.undo()
        manager.undo()
        manager.clearRedo()
        manager.push(insertion(0, "cccc"))
        manager.push(insertion(0, "dddd"))

        assertEquals(2, manager.getUndoStack().size)
    }

    @Test
    fun aReplayedStepIsCountedWithItsNewContents() {
        val manager = UndoManager()
        manager.maxChars = costOf("aaaa") * 2
        val first = insertion(0, "aaaa")
        manager.push(first)
        manager.push(insertion(0, "bbbb"))
        manager.undo()
        manager.undo()
        // redone with a far larger content than it went in with
        manager.redo()
        manager.updateStep(first, listOf(HistoryChange.of(TextChange(0, "", 0, "x".repeat(1000)))))
        manager.push(insertion(0, "cccc"))

        assertEquals(1, manager.getUndoStack().size, "the replayed step no longer fits next to another")
    }

    @Test
    fun loweringALimitTrimsRightAway() {
        val manager = UndoManager()
        repeat(5) { manager.push(insertion(it, "x")) }

        manager.maxSteps = 2

        assertEquals(2, manager.getUndoStack().size)
    }

    @Test
    fun clearEmptiesBothStacks() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        manager.push(insertion(1, "b"))
        manager.undo()

        manager.clear()

        assertFalse(manager.canUndo())
        assertFalse(manager.canRedo())
    }

    // A step that deleted a large block refers to it in the buffer's storage; such
    // steps have a limit of their own, or they would keep any amount of it alive.
    @Test
    fun stepsReferringToMuchStorageAreForgottenBeyondTheirOwnLimit() {
        val tree = PieceTreeTextBufferBuilder().apply { acceptChunk("x".repeat(100_000)) }.build()
        fun deletion(from: Int, length: Int): UndoStep {
            val removed = tree.exportSlice(Range(1, from, 1, from + length))
            return UndoStep(listOf(HistoryChange(from - 1, removed, from - 1, HistoryChange.EMPTY)))
        }
        val manager = UndoManager()
        manager.maxReferencedChars = 30_000L

        val first = deletion(1, 20_000)
        assertFalse(first.changes.single().oldContent.isText, "large content is referred to")
        assertEquals(20_000L, first.referencedLength)
        manager.push(first)
        manager.push(deletion(40_001, 20_000))

        assertEquals(1, manager.getUndoStack().size, "40 000 referred to chars are over the limit")
    }

    // #region save point

    @Test
    fun aNewHistoryIsAtItsSavePoint() {
        assertTrue(UndoManager().isAtSavePoint())
    }

    @Test
    fun undoingBackToTheSavePointIsBeingAtItAgain() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        manager.markSaved()
        manager.push(insertion(1, "b"))
        assertFalse(manager.isAtSavePoint())

        manager.undo()
        assertTrue(manager.isAtSavePoint())

        manager.undo()
        assertFalse(manager.isAtSavePoint(), "undone past it")
        manager.redo()
        assertTrue(manager.isAtSavePoint(), "redone up to it")
    }

    @Test
    fun theSavePointIsLostWhenItIsOnTheRedoStackThatIsDropped() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        manager.markSaved()
        manager.undo()

        manager.clearRedo()
        manager.push(insertion(0, "b"))
        manager.undo()

        assertFalse(manager.isAtSavePoint(), "the saved text cannot be reached any more")
        assertNull(manager.alignedToSavePoint())
    }

    @Test
    fun forgettingTheStepTheSavePointIsAfterMakesItTheBottom() {
        val manager = UndoManager()
        manager.maxSteps = 2
        manager.push(insertion(0, "a"))
        manager.markSaved()
        manager.push(insertion(1, "b"))
        manager.push(insertion(2, "c"))   // "a" goes

        manager.undo()
        manager.undo()

        assertTrue(manager.isAtSavePoint(), "the text after \"a\" is the bottom now")
    }

    @Test
    fun forgettingTheBottomLosesASavePointThere() {
        val manager = UndoManager()
        manager.maxSteps = 1
        manager.push(insertion(0, "a"))
        manager.push(insertion(1, "b"))   // "a" goes, and with it the empty text

        assertNull(manager.alignedToSavePoint())
    }

    @Test
    fun aMarkBecomesTheSavePointLater() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        val mark = manager.mark()
        // typed on while the file was being written
        manager.push(insertion(1, "b"))

        manager.markSaved(mark)

        assertFalse(manager.isAtSavePoint())
        manager.undo()
        assertTrue(manager.isAtSavePoint())
    }

    @Test
    fun aMarkOfAnotherBottomIsNoSavePoint() {
        val manager = UndoManager()
        val mark = manager.mark()
        manager.clear()

        manager.markSaved(mark)

        assertFalse(manager.isAtSavePoint())
    }

    @Test
    fun stepsMadeSinceSavingMoveToTheRedoSide() {
        val manager = UndoManager()
        val a = insertion(0, "a")
        val b = insertion(1, "b")
        val c = insertion(2, "c")
        manager.push(a)
        manager.markSaved()
        manager.push(b)
        manager.push(c)

        val aligned = assertNotNull(manager.alignedToSavePoint())

        assertEquals(listOf(a), aligned.undoStack)
        // undone one after the other, c first: b is the next to redo
        assertEquals(listOf(b, c), aligned.redoStack)
        assertEquals(listOf(c, b), aligned.stepsAhead)
        assertTrue(aligned.stepsBehind.isEmpty())
        // nothing changed in the manager itself
        assertEquals(listOf(c, b, a), manager.getUndoStack())
    }

    @Test
    fun stepsUndonePastTheSavePointMoveBackToTheUndoSide() {
        val manager = UndoManager()
        val a = insertion(0, "a")
        val b = insertion(1, "b")
        val c = insertion(2, "c")
        manager.push(a)
        manager.push(b)
        manager.push(c)
        manager.markSaved()
        manager.undo()
        manager.undo()

        val aligned = assertNotNull(manager.alignedToSavePoint())

        assertEquals(listOf(c, b, a), aligned.undoStack)
        assertTrue(aligned.redoStack.isEmpty())
        assertEquals(listOf(b, c), aligned.stepsBehind)
    }

    @Test
    fun restoredStacksAreAtTheirSavePoint() {
        val manager = UndoManager()
        manager.push(insertion(0, "a"))
        manager.forgetSavePoint()

        manager.setStacks(listOf(insertion(0, "x")), listOf(insertion(1, "y")))

        assertTrue(manager.isAtSavePoint())
    }

    // #endregion
}
