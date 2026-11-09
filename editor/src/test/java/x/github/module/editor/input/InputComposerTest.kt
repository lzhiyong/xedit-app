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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputComposerTest {

    // An editor reduced to a string and a selection. Like the real one, it leaves
    // the cursor after replaced text and tells the composer about every edit.
    private class FakeEditor(initialText: String) : InputComposer.Host {
        val text = StringBuilder(initialText)
        var selectionStart = initialText.length
        var selectionEnd = initialText.length
        var editable = true
        var edits = 0
        var regionChanges = 0
        lateinit var composer: InputComposer

        override val documentLength: Int get() = text.length

        override fun getDocumentText(start: Int, end: Int): String = text.substring(start, end)

        override val selectionStartOffset: Int get() = selectionStart
        override val selectionEndOffset: Int get() = selectionEnd

        override fun replaceDocumentText(start: Int, end: Int, text: String): Boolean {
            if (!editable) return false
            edit(start, end, text)
            return true
        }

        override fun selectDocumentRange(start: Int, end: Int) {
            selectionStart = start
            selectionEnd = end
        }

        override fun onComposingRegionChanged() {
            regionChanges++
        }

        // an edit, whoever makes it
        fun edit(start: Int, end: Int, replacement: String) {
            text.replace(start, end, replacement)
            selectionStart = start + replacement.length
            selectionEnd = selectionStart
            edits++
            composer.onDocumentEdited()
        }

        fun placeCursor(offset: Int) = selectDocumentRange(offset, offset)
    }

    private fun editorWith(text: String): FakeEditor {
        val editor = FakeEditor(text)
        editor.composer = InputComposer(editor)
        return editor
    }

    private fun FakeEditor.assertState(text: String, cursor: Int, composing: IntRange?) {
        assertEquals(text, this.text.toString(), "text")
        assertEquals(cursor, selectionStart, "cursor")
        assertEquals(cursor, selectionEnd, "cursor (selection end)")
        if (composing == null) {
            assertFalse(composer.isComposing, "there should be no composing region")
            assertEquals(InputComposer.NO_REGION, composer.composingStart)
            assertEquals(InputComposer.NO_REGION, composer.composingEnd)
        } else {
            // IntRange is inclusive: 0..1 here means the region [0, 2)
            assertEquals(composing.first, composer.composingStart, "composing start")
            assertEquals(composing.last + 1, composer.composingEnd, "composing end")
        }
    }

    // #region composing

    @Test
    fun composingTextIsReplacedUntilItIsCommitted() {
        val editor = editorWith("")
        val composer = editor.composer

        composer.setComposingText("n", 1)
        editor.assertState("n", cursor = 1, composing = 0..0)

        composer.setComposingText("ni", 1)
        editor.assertState("ni", cursor = 2, composing = 0..1)

        composer.commitText("你", 1)
        editor.assertState("你", cursor = 1, composing = null)
    }

    @Test
    fun composingStartsAtTheCursor() {
        val editor = editorWith("abcd")
        editor.placeCursor(2)

        editor.composer.setComposingText("XY", 1)

        editor.assertState("abXYcd", cursor = 4, composing = 2..3)
    }

    @Test
    fun composingReplacesTheSelection() {
        val editor = editorWith("hello world")
        editor.selectDocumentRange(0, 5)

        editor.composer.setComposingText("bye", 1)

        editor.assertState("bye world", cursor = 3, composing = 0..2)
    }

    @Test
    fun aBackwardSelectionIsReplacedJustTheSame() {
        val editor = editorWith("hello world")
        editor.selectDocumentRange(5, 0)

        editor.composer.commitText("bye", 1)

        editor.assertState("bye world", cursor = 3, composing = null)
    }

    @Test
    fun committingWithoutComposingInsertsAtTheCursor() {
        val editor = editorWith("ac")
        editor.placeCursor(1)

        editor.composer.commitText("b", 1)

        editor.assertState("abc", cursor = 2, composing = null)
    }

    @Test
    fun finishComposingKeepsTheTextAndDropsTheRegion() {
        val editor = editorWith("")
        editor.composer.setComposingText("abc", 1)

        editor.composer.finishComposingText()
        editor.assertState("abc", cursor = 3, composing = null)

        // what comes next is new text, not a replacement
        editor.composer.setComposingText("d", 1)
        editor.assertState("abcd", cursor = 4, composing = 3..3)
    }

    @Test
    fun composingTextCanShrinkToNothing() {
        val editor = editorWith("ab")
        editor.composer.setComposingText("xyz", 1)

        editor.composer.setComposingText("", 1)

        editor.assertState("ab", cursor = 2, composing = null)
    }

    @Test
    fun committingNothingWithNothingSelectedIsNotAnEdit() {
        val editor = editorWith("ab")

        assertTrue(editor.composer.commitText("", 1))

        assertEquals(0, editor.edits)
    }

    // Correcting a word typed earlier: the input method first marks it, then
    // replaces it.
    @Test
    fun existingTextCanBecomeTheComposingRegion() {
        val editor = editorWith("teh cat")
        editor.placeCursor(3)

        editor.composer.setComposingRegion(0, 3)
        editor.assertState("teh cat", cursor = 3, composing = 0..2)

        editor.composer.setComposingText("the", 1)
        editor.assertState("the cat", cursor = 3, composing = 0..2)

        editor.composer.finishComposingText()
        editor.assertState("the cat", cursor = 3, composing = null)
    }

    @Test
    fun aComposingRegionIsOrderedAndKeptInsideTheText() {
        val editor = editorWith("abcdef")

        editor.composer.setComposingRegion(4, 2)
        assertEquals(2, editor.composer.composingStart)
        assertEquals(4, editor.composer.composingEnd)

        editor.composer.setComposingRegion(-5, 100)
        assertEquals(0, editor.composer.composingStart)
        assertEquals(6, editor.composer.composingEnd)

        editor.composer.setComposingRegion(3, 3)
        assertFalse(editor.composer.isComposing, "an empty region is no region")
    }

    @Test
    fun theViewIsToldOnlyWhenTheRegionReallyChanges() {
        val editor = editorWith("")

        editor.composer.setComposingText("a", 1)
        assertEquals(1, editor.regionChanges)

        editor.composer.finishComposingText()
        editor.composer.finishComposingText()
        assertEquals(2, editor.regionChanges)
    }

    // #endregion

    // #region where the cursor goes

    @Test
    fun aCursorPositionOfZeroOrLessCountsBackFromTheStartOfTheText() {
        val editor = editorWith("ab")

        // e.g. a pair of brackets with the cursor between... is done with 0: before the text
        editor.composer.commitText("()", 0)
        editor.assertState("ab()", cursor = 2, composing = null)

        editor.composer.commitText("!", -1)
        editor.assertState("ab!()", cursor = 1, composing = null)
    }

    @Test
    fun aCursorPositionAboveOneCountsOnFromTheEndOfTheText() {
        val editor = editorWith("abcd")
        editor.placeCursor(1)

        editor.composer.commitText("X", 2)
        editor.assertState("aXbcd", cursor = 3, composing = null)

        // never outside the text
        editor.composer.commitText("Y", 100)
        editor.assertState("aXbYcd", cursor = 6, composing = null)
    }

    // #endregion

    // #region edits by others, refused edits

    @Test
    fun anEditFromElsewhereEndsTheComposition() {
        val editor = editorWith("abc")
        editor.composer.setComposingText("xy", 1)

        // a paste, an undo, anything that is not the input method
        editor.edit(0, 0, "!")

        assertFalse(editor.composer.isComposing)
        editor.composer.setComposingText("z", 1)
        editor.assertState("!zabcxy", cursor = 2, composing = 1..1)
    }

    @Test
    fun aRefusedEditChangesNothingAndDropsTheRegion() {
        val editor = editorWith("abc")
        editor.composer.setComposingText("x", 1)
        editor.editable = false

        assertFalse(editor.composer.setComposingText("xy", 1))
        assertFalse(editor.composer.commitText("z", 1))

        editor.assertState("abcx", cursor = 4, composing = null)
    }

    @Test
    fun resetForgetsRegionAndBatch() {
        val editor = editorWith("")
        editor.composer.beginBatchEdit()
        editor.composer.setComposingText("abc", 1)

        editor.composer.reset()

        assertFalse(editor.composer.isComposing)
        assertFalse(editor.composer.isBatchEditing)
    }

    // #endregion

    // #region deleting around the cursor

    @Test
    fun deletesBeforeAndAfterTheCursor() {
        val editor = editorWith("abcdef")
        editor.placeCursor(3)

        editor.composer.deleteSurroundingText(1, 2)

        editor.assertState("abf", cursor = 2, composing = null)
    }

    @Test
    fun deletingStopsAtTheEndsOfTheText() {
        val editor = editorWith("abc")
        editor.placeCursor(1)

        editor.composer.deleteSurroundingText(10, 10)

        editor.assertState("", cursor = 0, composing = null)
    }

    @Test
    fun theSelectionSurvivesADeletionAroundIt() {
        val editor = editorWith("abcdef")
        editor.selectDocumentRange(2, 4)

        editor.composer.deleteSurroundingText(1, 1)

        assertEquals("acdf", editor.text.toString())
        assertEquals(1, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
    }

    @Test
    fun theComposingTextSurvivesADeletionAroundItAndMovesWithIt() {
        val editor = editorWith("abcd")
        editor.placeCursor(2)
        editor.composer.setComposingText("XY", 1)
        // "abXYcd", composing "XY", cursor after it

        editor.composer.deleteSurroundingText(1, 1)

        editor.assertState("aXYd", cursor = 3, composing = 1..2)
    }

    @Test
    fun aDeletionNeverLeavesHalfAnEmojiBehind() {
        // the emoji is two chars, at offsets 1 and 2
        val editor = editorWith("a\uD83D\uDE00b")
        editor.placeCursor(3)

        // one char before the cursor is only the second half of the emoji
        editor.composer.deleteSurroundingText(1, 0)

        editor.assertState("ab", cursor = 1, composing = null)
    }

    @Test
    fun deletingByCodePointsCountsAnEmojiAsOne() {
        val editor = editorWith("a\uD83D\uDE00\uD83D\uDE00b\uD83D\uDE00c")
        // between the second emoji and "b"
        editor.placeCursor(5)

        editor.composer.deleteSurroundingTextInCodePoints(2, 2)

        editor.assertState("ac", cursor = 1, composing = null)
    }

    @Test
    fun deletingNothingIsNotAnEdit() {
        val editor = editorWith("abc")
        editor.placeCursor(1)

        assertTrue(editor.composer.deleteSurroundingText(0, 0))

        assertEquals(0, editor.edits)
        editor.assertState("abc", cursor = 1, composing = null)
    }

    // #endregion

    // #region reading

    @Test
    fun readsTheTextAroundTheCursor() {
        val editor = editorWith("abcdef")
        editor.placeCursor(4)

        assertEquals("cd", editor.composer.getTextBeforeCursor(2))
        assertEquals("abcd", editor.composer.getTextBeforeCursor(100))
        assertEquals("e", editor.composer.getTextAfterCursor(1))
        assertEquals("ef", editor.composer.getTextAfterCursor(100))
        assertNull(editor.composer.getSelectedText())
    }

    @Test
    fun aroundTheCursorMeansAroundTheSelection() {
        val editor = editorWith("abcdef")
        editor.selectDocumentRange(2, 4)

        assertEquals("ab", editor.composer.getTextBeforeCursor(100))
        assertEquals("ef", editor.composer.getTextAfterCursor(100))
        assertEquals("cd", editor.composer.getSelectedText())
        assertEquals(2, editor.composer.selectionStart)
        assertEquals(4, editor.composer.selectionEnd)
    }

    @Test
    fun aSelectionRequestIsKeptInsideTheText() {
        val editor = editorWith("abc")

        editor.composer.setSelection(-3, 99)

        assertEquals(0, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
    }

    @Test
    fun batchEditsNest() {
        val composer = editorWith("").composer

        composer.beginBatchEdit()
        composer.beginBatchEdit()
        composer.endBatchEdit()
        assertTrue(composer.isBatchEditing)

        composer.endBatchEdit()
        assertFalse(composer.isBatchEditing)

        // one too many must not go negative
        composer.endBatchEdit()
        composer.beginBatchEdit()
        assertTrue(composer.isBatchEditing)
    }

    // #endregion
}
