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

import x.github.module.editor.model.EditHistory
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.testing.FakeScheduler
import x.github.module.editor.testing.RecordingCallback
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversions between the input method's offsets and the editor's lines and
 * columns, on a real document.
 */
class InputMethodBridgeTest {

    private val callback = RecordingCallback()
    private val document = EditorDocument(EditHistory(FakeScheduler()), callback)

    // An editor reduced to a document and a selection. Like the real one, it leaves
    // the cursor after an edit and tells the composer about every edit.
    private inner class FakeEditor : InputMethodBridge.Host {
        override var selectionRange = Range(1, 1, 1, 1)
        var editable = true
        var composingRangeChanges = 0
        var refusals = 0

        override fun isEditable() = editable

        override fun applyInputEdit(range: Range, text: String) {
            document.applyEdits(listOf(SingleEditOperation(range, text)))
            placeCursor(callback.caretLineNumber, callback.caretColumn)
            bridge.composer.onDocumentEdited()
        }

        override fun selectRange(range: Range) {
            selectionRange = range
        }

        override fun onComposingRangeChanged() {
            composingRangeChanges++
        }

        override fun onInputEditRefused() {
            refusals++
        }

        fun placeCursor(lineNumber: Int, column: Int) {
            selectionRange = Range(lineNumber, column, lineNumber, column)
        }
    }

    private val editor = FakeEditor()
    private val bridge = InputMethodBridge(document, editor)

    private fun text(): String =
        (1..document.getLineCount()).joinToString("\n") { document.getLine(it) }

    private fun describe(range: Range?) =
        range?.let { "${it.startLine}:${it.startColumn}-${it.endLine}:${it.endColumn}" }

    // "abc\nde\nf": line 1 is offsets 0..3, line 2 is 4..6, line 3 is 7..8
    private fun startWithThreeLines() {
        document.setText("abc\nde\nf")
    }

    // #region offsets <-> positions

    @Test
    fun offsetsBecomeRangesAcrossLines() {
        startWithThreeLines()

        assertEquals("1:3-2:2", describe(bridge.rangeBetween(2, 5)))
        assertEquals("1:1-1:1", describe(bridge.rangeBetween(0, 0)))
        assertEquals("1:4-2:1", describe(bridge.rangeBetween(3, 4)), "the line break itself")
        assertEquals("3:1-3:2", describe(bridge.rangeBetween(7, 8)))
    }

    @Test
    fun positionsBecomeOffsets() {
        startWithThreeLines()

        assertEquals(0, bridge.offsetOf(1, 1))
        assertEquals(3, bridge.offsetOf(1, 4))
        assertEquals(4, bridge.offsetOf(2, 1))
        assertEquals(8, bridge.offsetOf(3, 2))
        assertEquals(8, bridge.documentLength)
    }

    // The cursor can point into a text that is no longer there (another file was
    // just loaded). Asking for its offset must still give one the text has.
    @Test
    fun aPositionOutsideTheTextGivesTheNearestOffsetInside() {
        startWithThreeLines()

        assertEquals(8, bridge.offsetOf(99, 99), "past the last line: the end of the text")
        assertEquals(3, bridge.offsetOf(1, 99), "past the end of a line: the end of that line")
        assertEquals(0, bridge.offsetOf(0, 0))
        assertEquals(4, bridge.offsetOf(2, -5))
    }

    @Test
    fun readsTheTextBetweenOffsets() {
        startWithThreeLines()

        assertEquals("c\nd", bridge.getDocumentText(2, 5))
        assertEquals("", bridge.getDocumentText(4, 4))
        assertEquals("abc\nde\nf", bridge.getDocumentText(0, 8))
    }

    @Test
    fun theSelectionIsReportedInOffsets() {
        startWithThreeLines()

        editor.selectionRange = Range(1, 2, 2, 2)
        assertEquals(1, bridge.selectionStartOffset)
        assertEquals(5, bridge.selectionEndOffset)

        editor.placeCursor(3, 2)
        assertEquals(8, bridge.selectionStartOffset)
        assertEquals(8, bridge.selectionEndOffset)
    }

    @Test
    fun aSelectionPastTheTextIsRecognised() {
        startWithThreeLines()

        editor.placeCursor(3, 1)
        assertTrue(bridge.isSelectionInsideDocument())

        editor.placeCursor(4, 1)
        assertFalse(bridge.isSelectionInsideDocument())

        editor.selectionRange = Range(2, 1, 7, 1)
        assertFalse(bridge.isSelectionInsideDocument())
    }

    // #endregion

    // #region the composer, working on a real document through the bridge

    @Test
    fun composingGoesIntoTheDocumentAtTheCursor() {
        startWithThreeLines()
        editor.placeCursor(2, 2)

        bridge.composer.setComposingText("n", 1)
        bridge.composer.setComposingText("ni", 1)

        assertEquals("abc\ndnie\nf", text())
        assertEquals("2:2-2:4", describe(bridge.composingRange))
        assertEquals("2:4-2:4", describe(editor.selectionRange), "the cursor is after the composing text")

        bridge.composer.commitText("你", 1)

        assertEquals("abc\nd你e\nf", text())
        assertNull(bridge.composingRange)
        assertEquals(3, editor.composingRangeChanges, "appeared, grew, went away")
    }

    @Test
    fun aComposingRegionCanSpanLines() {
        startWithThreeLines()

        bridge.composer.setComposingRegion(2, 5)

        assertEquals("1:3-2:2", describe(bridge.composingRange))
    }

    @Test
    fun selectingByOffsetsSelectsARange() {
        startWithThreeLines()

        bridge.composer.setSelection(1, 5)

        assertEquals("1:2-2:2", describe(editor.selectionRange))
    }

    @Test
    fun deletingAroundTheCursorCrossesLineBreaks() {
        startWithThreeLines()
        editor.placeCursor(2, 1)

        // the line break before the cursor and the "d" after it
        bridge.composer.deleteSurroundingText(1, 1)

        assertEquals("abce\nf", text())
        assertEquals("1:4-1:4", describe(editor.selectionRange))
    }

    @Test
    fun anEditTheEditorRefusesIsReportedAndChangesNothing() {
        startWithThreeLines()
        editor.placeCursor(1, 1)
        editor.editable = false

        assertFalse(bridge.composer.commitText("x", 1))

        assertEquals("abc\nde\nf", text())
        assertEquals(1, editor.refusals)
        assertNull(bridge.composingRange)
    }

    // #endregion
}
