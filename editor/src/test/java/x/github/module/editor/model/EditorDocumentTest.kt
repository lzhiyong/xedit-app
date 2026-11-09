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
import x.github.module.editor.testing.RecordingCallback
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.TextSlice
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The edit pipeline, on a real piece tree buffer.
 *
 * Not covered here: backspaceRange, which needs android.icu and therefore a device
 * (see EditorViewTest).
 */
class EditorDocumentTest {

    private val scheduler = FakeScheduler()
    private val history = EditHistory(scheduler)
    private val callback = RecordingCallback()
    private val document = EditorDocument(history, callback)

    // the whole text, read line by line
    private fun text(): String =
        (1..document.getLineCount()).joinToString("\n") { document.getLine(it) }

    private fun insert(lineNumber: Int, column: Int, text: String) {
        document.applyEdits(listOf(SingleEditOperation(Range(lineNumber, column, lineNumber, column), text)))
    }

    // a document that holds [text] and has nothing to undo and nothing recorded
    private fun startWith(text: String) {
        document.setText(text)
        callback.reset()
    }

    // #region queries

    @Test
    fun aNewDocumentHasOneEmptyLine() {
        assertEquals(1, document.getLineCount())
        assertEquals("", document.getLine(1))
        assertEquals(0, document.getLineLength(1))
    }

    @Test
    fun linesOffsetsAndPositionsAgree() {
        startWith("abc\nde\n")

        assertEquals(3, document.getLineCount())
        assertEquals("abc", document.getLine(1))
        assertEquals("de", document.getLine(2))
        assertEquals("", document.getLine(3))
        assertEquals(3, document.getLineLength(1))

        // line 2 starts after "abc\n"
        assertEquals(4, document.getOffset(2, 1))
        assertEquals(4, document.getLineStartOffset(2))
        assertEquals(6, document.getLineEndOffset(2))
        document.getPosition(4).let {
            assertEquals(2, it.lineNumber)
            assertEquals(1, it.column)
        }

        assertEquals(1, document.getLineStartColumn(1))
        assertEquals(4, document.getLineEndColumn(1))
        assertEquals(1, document.getLineEndColumn(), "last line by default")
        assertEquals('b', document.getLineCharAt(1, 1))
        assertEquals("bc", document.getLineSegment(1, 2, 4))
    }

    // #endregion

    // #region pipeline

    @Test
    fun anEditGoesThroughAllThreeStagesInOrder() {
        startWith("abc")

        insert(1, 2, "X")

        assertEquals("aXbc", text())
        assertEquals(listOf("before", "changed", "after"), callback.events)
    }

    @Test
    fun theCaretLandsAfterTextInsertedInALine() {
        startWith("abc")

        insert(1, 2, "XY")

        assertEquals(1, callback.caretLineNumber)
        assertEquals(4, callback.caretColumn)
    }

    @Test
    fun theCaretLandsOnTheLastLineOfMultiLineText() {
        startWith("abc")

        insert(1, 2, "X\nYZ")

        assertEquals("aX\nYZbc", text())
        assertEquals(2, callback.caretLineNumber)
        assertEquals(3, callback.caretColumn)

        val change = callback.appliedChanges.single()
        assertEquals(1, change.insertedLinesCnt)
        assertEquals(0, change.deletedLinesCnt)
        assertEquals(4, change.insertedTextLength)
    }

    // A range that ends between the two halves of an emoji takes the whole emoji;
    // an insertion there goes in front of it.
    @Test
    fun editsDoNotSplitACharacterOfTwoChars() {
        startWith("a\uD83D\uDE00b")

        // from after "a" to between the halves
        document.applyEdits(listOf(SingleEditOperation(Range(1, 2, 1, 3), null)))
        assertEquals("ab", text())

        startWith("a\uD83D\uDE00b")
        document.applyEdits(listOf(SingleEditOperation(Range(1, 3, 1, 3), "x")))
        assertEquals("ax\uD83D\uDE00b", text())
    }

    // Several changes at once: the caret goes after the one furthest down, in the
    // text as it is after the edit, lines added above it counted.
    @Test
    fun afterABatchEditTheCaretIsWhereTheLastChangeEndsNow() {
        startWith("a\nb\na")

        document.applyEdits(
            listOf(
                SingleEditOperation(Range(1, 1, 1, 2), "x\ny"),
                SingleEditOperation(Range(3, 1, 3, 2), "x\ny")
            )
        )

        assertEquals("x\ny\nb\nx\ny", text())
        assertEquals(5, callback.caretLineNumber)
        assertEquals(2, callback.caretColumn)
    }

    @Test
    fun theCaretLandsWhereDeletedTextStarted() {
        startWith("abc\ndef")

        // from after "a" to after "d": removes "bc\nd"
        document.applyEdits(listOf(SingleEditOperation(Range(1, 2, 2, 2), null)))

        assertEquals("aef", text())
        assertEquals(1, callback.caretLineNumber)
        assertEquals(2, callback.caretColumn)

        val change = callback.appliedChanges.single()
        assertEquals(0, change.insertedLinesCnt)
        assertEquals(1, change.deletedLinesCnt)
        assertEquals(4, change.deletedTextLength)
    }

    @Test
    fun aBatchReportsEveryChange() {
        startWith("a a a")

        document.applyEdits(
            listOf(
                SingleEditOperation(Range(1, 1, 1, 2), "bb"),
                SingleEditOperation(Range(1, 3, 1, 4), "bb"),
                SingleEditOperation(Range(1, 5, 1, 6), "bb")
            )
        )

        assertEquals("bb bb bb", text())
        assertEquals(listOf("before", "changed", "changed", "changed", "after"), callback.events)
    }

    // #endregion

    // #region undo / redo

    @Test
    fun undoAndRedoRestoreTheText() {
        startWith("abc")
        insert(1, 4, "def")
        scheduler.runAll()
        insert(1, 1, "__")
        scheduler.runAll()
        assertEquals("__abcdef", text())

        assertTrue(document.undo(), "one more step to undo")
        assertEquals("abcdef", text())
        assertFalse(document.undo(), "nothing left to undo")
        assertEquals("abc", text())

        assertTrue(document.redo(), "one more step to redo")
        assertEquals("abcdef", text())
        assertFalse(document.redo())
        assertEquals("__abcdef", text())
    }

    @Test
    fun undoWorksWithinTheMergeDelay() {
        startWith("abc")
        insert(1, 4, "d")
        insert(1, 5, "e")
        // no scheduler.runAll(): the burst is still waiting to be committed

        document.undo()

        assertEquals("abc", text(), "the whole burst is one step")
    }

    @Test
    fun undoGoesThroughThePipelineLikeAnyEdit() {
        startWith("abc")
        insert(1, 4, "d")
        scheduler.runAll()
        callback.reset()

        document.undo()

        assertEquals(listOf("before", "changed", "after"), callback.events)
        // the caret goes back to where the undone text was
        assertEquals(1, callback.caretLineNumber)
        assertEquals(4, callback.caretColumn)
    }

    @Test
    fun undoWithNothingToUndoTouchesNothing() {
        startWith("abc")

        assertFalse(document.undo())
        assertFalse(document.redo())

        assertEquals("abc", text())
        assertTrue(callback.events.isEmpty(), "no edit, so no pipeline")
    }

    @Test
    fun aMultiLineEditIsUndoneAsAWhole() {
        startWith("one\ntwo\nthree")
        document.applyEdits(listOf(SingleEditOperation(Range(1, 2, 3, 3), "X\nY\nZ\nW")))
        scheduler.runAll()
        assertEquals("oX\nY\nZ\nWree", text())

        document.undo()

        assertEquals("one\ntwo\nthree", text())
    }

    // The steps on the stacks hold offsets into the old text. Undoing them after the
    // text was replaced would cut the new text up at those offsets.
    @Test
    fun setTextForgetsTheHistoryOfTheTextItReplaces() {
        startWith("abc")
        insert(1, 4, "def")
        scheduler.runAll()
        // one step on the undo stack, and one more still waiting to be committed
        insert(1, 1, "pending")

        document.setText("something else entirely")
        scheduler.runAll()

        assertFalse(history.canUndo())
        assertFalse(history.canRedo())
        assertFalse(document.undo())
        assertFalse(document.redo())
        assertEquals("something else entirely", text())
    }

    @Test
    fun setTextIsNotUndoable() {
        document.setText("abc")
        scheduler.runAll()

        assertFalse(history.canUndo())
    }

    // #endregion

    // #region large content: kept as references, not copied

    // a text of [lines] lines of 100 chars, far above TextSlice.TEXT_THRESHOLD
    private fun largeText(lines: Int): String =
        (1..lines).joinToString("\n") { "line $it ".padEnd(100, 'x') }

    private fun loadLarge(lines: Int): String {
        val text = largeText(lines)
        document.replaceBuffer(PieceTreeBuffer(PieceTreeTextBufferBuilder().apply { acceptChunk(text) }.build()))
        history.clear()
        callback.reset()
        return text
    }

    @Test
    fun aLargeDeletionIsKeptAsReferencesAndUndoneExactly() {
        val text = loadLarge(2000)

        document.applyEdits(listOf(SingleEditOperation(document.getFullRange(), null)))
        assertEquals("", text())

        val removed = history.getUndoStack().single().changes.single().oldContent
        assertFalse(removed.isText, "200 KB removed must not be copied into the history")
        assertEquals(text.length, removed.length)

        document.undo()
        assertEquals(text, document.getText())
        document.redo()
        assertEquals("", document.getText())
        document.undo()
        assertEquals(text, document.getText())
    }

    // A large paste goes into the history as the String it came in. Taking it back
    // exports it from the buffer again: from then on the step holds references.
    @Test
    fun aLargePasteIsHeldAsReferencesOnceUndone() {
        startWith("ab")
        val pasted = largeText(1000)
        insert(1, 2, pasted)
        assertTrue(history.getUndoStack().single().changes.single().newContent.isText)

        document.undo()
        assertEquals("ab", text())
        val redo = history.getRedoStack().single().changes.single()
        assertFalse(redo.newContent.isText, "the pasted text is referred to, not copied")

        document.redo()
        assertEquals("a" + pasted + "b", document.getText())
    }

    @Test
    fun theCallbackLearnsTheShapeOfALargeChangeWithoutItsText() {
        loadLarge(2000)
        document.applyEdits(listOf(SingleEditOperation(document.getFullRange(), null)))
        callback.reset()

        document.undo()

        val change = callback.appliedChanges.single()
        assertEquals(1999, change.insertedLinesCnt)
        assertFalse(assertNotNullContent(change).isText)
        assertEquals(2000, callback.caretLineNumber)
    }

    private fun assertNotNullContent(change: EditorDocument.AppliedChange): TextSlice =
        change.content ?: throw AssertionError("the change has no content")

    @Test
    fun aSliceExportedFromTheDocumentGoesBackWithoutBeingCopied() {
        val text = loadLarge(1000)
        val tree = (document.buffer as PieceTreeBuffer).pieceTree
        val block = tree.exportSlice(Range(1, 1, 500, 1))

        // move the first 499 lines to the end
        document.applySliceEdits(
            listOf(
                SliceEdit(Range(1, 1, 500, 1), null),
                SliceEdit(
                    Range(1000, document.getLineEndColumn(1000), 1000, document.getLineEndColumn(1000)),
                    TextSlice.of("\n")
                ),
                SliceEdit(
                    Range(1000, document.getLineEndColumn(1000), 1000, document.getLineEndColumn(1000)),
                    block
                )
            )
        )
        val lines = text.split("\n")
        assertEquals((lines.subList(499, 1000) + lines.subList(0, 499)).joinToString("\n") + "\n", document.getText())

        document.undo()
        assertEquals(text, document.getText())
    }

    // #endregion

    // #region modified

    @Test
    fun undoingBackToTheSavedTextMakesItUnmodified() {
        startWith("abc")
        assertFalse(history.isModified, "a text just set is the saved one")

        insert(1, 4, "d")
        assertTrue(history.isModified)

        document.undo()
        assertFalse(history.isModified)
    }

    @Test
    fun anEditThatIsNotUndoableLeavesTheTextModified() {
        startWith("abc")

        document.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "x")), computeUndoEdits = false)

        assertTrue(history.isModified)
        assertNull(history.alignedToSavePoint())
    }

    // #endregion

    @Test
    fun replaceBufferSwapsTheTextWithoutRunningThePipeline() {
        startWith("old")

        document.replaceBuffer(PieceTreeBuffer(PieceTreeTextBufferBuilder().build()))

        assertEquals("", text())
        assertTrue(callback.events.isEmpty())
    }

    // #region AppliedChange.of: where the caret goes, without a buffer

    private fun change(range: Range, text: String?) = ContentChange(range, 0, 0, text, false)

    @Test
    fun appliedChangeOfAnInsertionInsideALine() {
        val applied = EditorDocument.AppliedChange.of(change(Range(3, 5, 3, 5), "abc"))

        assertEquals(3, applied.finalLineNumber)
        assertEquals(8, applied.finalColumn)
        assertEquals(0, applied.insertedLinesCnt)
        assertEquals(0, applied.deletedLinesCnt)
    }

    @Test
    fun appliedChangeOfAnInsertionEndingOnANewLine() {
        val applied = EditorDocument.AppliedChange.of(change(Range(3, 5, 3, 5), "ab\ncd\nefg"))

        assertEquals(5, applied.finalLineNumber)
        assertEquals(4, applied.finalColumn, "after the 3 chars of the last line")
        assertEquals(2, applied.insertedLinesCnt)
    }

    @Test
    fun appliedChangeOfAnInsertionEndingWithALineBreak() {
        val applied = EditorDocument.AppliedChange.of(change(Range(3, 5, 3, 5), "ab\n"))

        assertEquals(4, applied.finalLineNumber)
        assertEquals(1, applied.finalColumn)
    }

    @Test
    fun appliedChangeOfADeletion() {
        val applied = EditorDocument.AppliedChange.of(change(Range(3, 5, 6, 2), ""))

        assertEquals(3, applied.finalLineNumber)
        assertEquals(5, applied.finalColumn)
        assertEquals(3, applied.deletedLinesCnt)
        assertEquals(0, applied.insertedTextLength)
    }

    @Test
    fun appliedChangeTreatsMissingTextAsADeletion() {
        val applied = EditorDocument.AppliedChange.of(change(Range(3, 5, 3, 9), null))

        assertEquals(3, applied.finalLineNumber)
        assertEquals(5, applied.finalColumn)
    }

    @Test
    fun appliedChangeOfAReplacementAcrossLines() {
        // lines 2..4 replaced by one line of text
        val applied = EditorDocument.AppliedChange.of(change(Range(2, 3, 4, 1), "xy"))

        assertEquals(2, applied.finalLineNumber)
        assertEquals(5, applied.finalColumn)
        assertEquals(0, applied.insertedLinesCnt)
        assertEquals(2, applied.deletedLinesCnt)
    }

    // #endregion

    // An edit of several changes, some adding lines above others (a formatting): each
    // change is reported when the text is as its range says, so that what reads the lines
    // then (the layout measures them) finds them. All applied before any was reported,
    // undoing such an edit read lines past the end of the text: a crash.
    @Test
    fun severalChangesAreReportedWithTheTextAsTheirRangesSay() {
        lateinit var doc: EditorDocument
        val read = ArrayList<String>()
        val reading = object : EditorDocument.Callback {
            override fun beforeTextChanged(edits: List<SliceEdit>) {}
            override fun onTextChanged(change: EditorDocument.AppliedChange) {
                // as the layout does: the line the change starts at, and the lines it put in
                val first = change.range.startLine
                for (line in first..first + change.insertedLinesCnt) read += doc.getLine(line)
            }
            override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, caretLineNumber: Int, caretColumn: Int) {}
        }
        doc = EditorDocument(EditHistory(scheduler), reading)
        fun textOf() = (1..doc.getLineCount()).joinToString("\n") { doc.getLine(it) }
        val original = (1..10).joinToString("\n") { "line $it" }
        doc.setText(original)
        val five = (1..5).joinToString("") { "\nnew $it" }

        // five lines after line 2, five after line 9: twenty lines
        doc.applyEdits(listOf(
            SingleEditOperation(Range(2, 7, 2, 7), five),
            SingleEditOperation(Range(9, 7, 9, 7), five),
        ))
        scheduler.runAll()
        assertEquals(20, doc.getLineCount())
        val formatted = textOf()
        // the change at line 9 was reported while line 9 was still "line 9"
        assertTrue("line 9" in read, "read: $read")

        // undone: ten lines again; the lower change (at line 14 then) is reported first
        read.clear()
        doc.undo()
        assertEquals(original, textOf())
        assertEquals(listOf("line 9", "line 2"), read)

        doc.redo()
        assertEquals(formatted, textOf())
    }

    // Two inserts at the same place end up in their order, as in a batch of the buffer.
    @Test
    fun insertsAtTheSamePlaceKeepTheirOrder() {
        startWith("line")
        document.applyEdits(listOf(
            SingleEditOperation(Range(1, 1, 1, 1), "A"),
            SingleEditOperation(Range(1, 1, 1, 1), "B"),
            SingleEditOperation(Range(1, 5, 1, 5), "!"),
        ))
        assertEquals("ABline!", text())
    }
}
