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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SparseArray
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import x.github.module.editor.input.EditorClipboard
import x.github.module.editor.model.EditSession
import x.github.module.editor.render.DrawableFoldStyle
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.structure.IndentationFoldingProvider
import x.github.module.editor.structure.PlainBracketMatcher
import x.github.module.editor.model.EditorDocument
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.Range

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor as a whole, on a device: the parts that need a real View, a real paint
 * or android.icu and therefore cannot run in the JVM tests.
 *
 * The view is created but never attached to a window. That is enough for editing,
 * and it has a useful side effect: nothing posted with a delay ever runs, so the
 * "typed a moment ago, not committed yet" state lasts for the whole test.
 */
@RunWith(AndroidJUnit4::class)
class EditorViewTest {

    // remembers what the editor reported about copy / cut
    private class TestEditor(context: Context) : EditorView(context) {
        var lastCopyResult: EditorClipboard.CopyResult? = null

        override fun onCopyResult(result: EditorClipboard.CopyResult) {
            lastCopyResult = result
        }

        // the pinch gesture, without the fingers: these are hooks for subclasses,
        // not something the outside can call
        fun beginZoom() = onZoomBegin()
        fun endZoom() = onZoomEnd()

        // a magnifier needs a window to look into, and there is none here
        override fun showMagnifier() {}
        override fun dismissMagnifier() {}

        // where the cursor is, as line and column
        fun cursorAt(): Pair<Int, Int> = cursor.lineNumber to cursor.column
    }

    private lateinit var editor: TestEditor

    // Everything that touches the view runs on the main thread, like in the app.
    // A failed assertion is carried back to the test thread: thrown on the main
    // thread it would take the whole test process down instead of failing one test.
    private fun <T> onMainThread(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result = block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // Text sizes, buffers and word wrap the way an app sets them off the main thread:
    // laid out right here, on the test thread, and the steps that posted to the main
    // thread have run by the time this returns. On the main thread they would go to
    // the layout thread instead, and the editor refuses edits until it is done: a
    // test that went on in the same main-thread block would race it.
    private fun offMainThread(block: () -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "not on the test thread" }
        block()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Before
    fun createEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        editor = onMainThread { TestEditor(context) }
    }

    @Test
    fun setTextAndGetTextRoundTrip() = onMainThread {
        assertTrue(editor.setText("one\ntwo\nthree"))

        assertEquals("one\ntwo\nthree", editor.getText())
        assertEquals(3, editor.getLineCount())
        assertEquals("two", editor.getLine(2))
    }

    @Test
    fun insertAndDeleteAtAGivenRange() = onMainThread {
        editor.setText("abc")

        editor.insert("XY", Range(1, 2, 1, 2))
        assertEquals("aXYbc", editor.getText())

        editor.delete(Range(1, 2, 1, 4))
        assertEquals("abc", editor.getText())
    }

    @Test
    fun typingGoesInAtTheCursorAndBackspaceTakesItOut() = onMainThread {
        editor.setText("abc")
        // the cursor is at the end of the text that was just set

        editor.insert("d")
        assertEquals("abcd", editor.getText())

        press(KeyEvent.KEYCODE_ENTER)
        assertEquals(2, editor.getLineCount())

        press(KeyEvent.KEYCODE_DEL)
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("abc", editor.getText())
    }

    // An emoji is two chars (or more); backspace has to remove all of it.
    @Test
    fun backspaceRemovesAWholeEmoji() = onMainThread {
        editor.setText("a\uD83D\uDE00")

        press(KeyEvent.KEYCODE_DEL)

        assertEquals("a", editor.getText())
    }

    @Test
    fun backspaceAtTheVeryStartDoesNothing() = onMainThread {
        editor.setText("abc")
        editor.setSelection(Range(1, 1, 1, 1))

        press(KeyEvent.KEYCODE_DEL)

        assertEquals("abc", editor.getText())
    }

    // #region the soft keyboard, through the same connection an input method gets

    private fun inputConnection() = editor.onCreateInputConnection(EditorInfo())

    // Typing "ni" in pinyin and picking a character: the letters are shown while
    // they are typed and are replaced, not added to, at every step.
    @Test
    fun composingTextIsReplacedUntilItIsCommitted() = onMainThread {
        editor.setText("abc")
        val ime = inputConnection()

        ime.setComposingText("n", 1)
        assertEquals("abcn", editor.getText())
        ime.setComposingText("ni", 1)
        assertEquals("abcni", editor.getText())
        ime.commitText("\u4F60", 1)
        assertEquals("abc\u4F60", editor.getText())

        ime.commitText("\u597D", 1)
        assertEquals("abc\u4F60\u597D", editor.getText())
    }

    @Test
    fun finishComposingKeepsTheTextAsItIs() = onMainThread {
        editor.setText("abc")
        val ime = inputConnection()

        ime.setComposingText("de", 1)
        ime.finishComposingText()
        ime.setComposingText("f", 1)

        assertEquals("the finished text must not be replaced", "abcdef", editor.getText())
    }

    @Test
    fun aComposedWordIsOneUndoStep() = onMainThread {
        editor.setText("abc")
        val ime = inputConnection()
        ime.setComposingText("n", 1)
        ime.setComposingText("ni", 1)
        ime.commitText("\u4F60", 1)

        editor.undo()

        assertEquals("abc", editor.getText())
    }

    @Test
    fun anEditFromElsewhereEndsTheComposition() = onMainThread {
        editor.setText("abc")
        val ime = inputConnection()
        ime.setComposingText("xy", 1)

        // not through the input method
        editor.insert("!", Range(1, 1, 1, 1))
        ime.setComposingText("z", 1)

        assertEquals("'xy' is ordinary text now, 'z' goes in at the cursor", "!zabcxy", editor.getText())
    }

    @Test
    fun theInputMethodCanReadAndDeleteAroundTheCursor() = onMainThread {
        editor.setText("abcdef")
        val ime = inputConnection()

        assertEquals("def", ime.getTextBeforeCursor(3, 0).toString())
        assertEquals("", ime.getTextAfterCursor(3, 0).toString())

        ime.deleteSurroundingText(2, 0)
        assertEquals("abcd", editor.getText())
    }

    @Test
    fun aLineBreakFromTheKeyboardIsTheEnterKey() = onMainThread {
        editor.setText("abc")

        inputConnection().commitText("\n", 1)

        assertEquals(2, editor.getLineCount())
        assertEquals("abc", editor.getLine(1))
    }

    // #endregion

    // #region a hardware keyboard

    private fun press(keyCode: Int, metaState: Int = 0): Boolean {
        return editor.onKeyDown(keyCode, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
    }

    private val shift = KeyEvent.META_SHIFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON

    @Test
    fun keysThatProduceCharactersTypeThem() = onMainThread {
        editor.setText("")

        press(KeyEvent.KEYCODE_A)
        press(KeyEvent.KEYCODE_B, shift)
        press(KeyEvent.KEYCODE_SPACE)
        press(KeyEvent.KEYCODE_1)

        assertEquals("aB 1", editor.getText())
    }

    // A tab reaches the next tab stop: four spaces wide by default, counted from the
    // start of the row. ("a" is narrower than four spaces in any usual font.)
    @Test
    fun aTabReachesTheNextTabStop() {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            editor.setText("\t|\na\t|\n    |")
            val fourSpaces = editor.getLineRangeWidth(3, 1, 5).toFloat()

            assertEquals("a tab at the start", fourSpaces, editor.getLineRangeWidth(1, 1, 2).toFloat(), 1f)
            assertEquals("a tab after \"a\": the same stop", fourSpaces, editor.getLineRangeWidth(2, 1, 3).toFloat(), 1f)
        }

        offMainThread { editor.setTabSize(2) }
        onMainThread {
            assertEquals(2, editor.getTabSize())
            assertEquals(editor.getLineRangeWidth(3, 1, 3).toFloat(), editor.getLineRangeWidth(1, 1, 2).toFloat(), 1f)
        }
    }

    @Test
    fun enterKeepsTheIndentationOfTheLine() = onMainThread {
        editor.setText("    abc")
        editor.setSelection(Range(1, 8, 1, 8))

        press(KeyEvent.KEYCODE_ENTER)
        editor.insert("x")

        assertEquals("    abc\n    x", editor.getText())

        editor.setAutoIndent(false)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("", editor.getLine(3))
    }

    // the tab key of an input method comes as a committed "\t": it indents too
    @Test
    fun aTabFromTheInputMethodIndents() = onMainThread {
        editor.setText("")
        val connection = editor.onCreateInputConnection(EditorInfo())!!

        connection.commitText("\t", 1)

        assertEquals("    ", editor.getText())
    }

    @Test
    fun tabAndShiftTabIndentAndOutdentTheSelectedLines() = onMainThread {
        editor.setText("one\ntwo\nthree")
        editor.setIndentUnit("  ")
        // the first two lines, whole
        editor.setSelection(Range(1, 1, 3, 1))

        press(KeyEvent.KEYCODE_TAB)
        assertEquals("  one\n  two\nthree", editor.getText())
        assertEquals("the selection stays on the same lines", "  one\n  two\n", editor.getSelectedText())

        press(KeyEvent.KEYCODE_TAB, shift)
        assertEquals("one\ntwo\nthree", editor.getText())

        // one undo step each
        editor.undo()
        assertEquals("  one\n  two\nthree", editor.getText())
    }

    @Test
    fun enterBackspaceAndTabEdit() = onMainThread {
        editor.setText("ab")

        press(KeyEvent.KEYCODE_ENTER)
        press(KeyEvent.KEYCODE_TAB)
        assertEquals(2, editor.getLineCount())
        // a tab is four spaces by default ...
        assertEquals("    ", editor.getLine(2))

        // ... which backspace takes away one at a time
        press(KeyEvent.KEYCODE_DEL)
        assertEquals("   ", editor.getLine(2))
        repeat(4) { press(KeyEvent.KEYCODE_DEL) }
        assertEquals("ab", editor.getText())
    }

    @Test
    fun keysThatMeanNothingToTheEditorAreLeftToTheSystem() = onMainThread {
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse("nothing is selected", press(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun arrowKeysMoveTheCaret() = onMainThread {
        // two lines of the same text, so that "straight up" is the same column
        editor.setText("aaaa\naaaa")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_X)

        assertEquals("aaaxa", editor.getLine(1))
        assertEquals("aaaa", editor.getLine(2))
    }

    @Test
    fun leftAndRightCrossLineBreaksAndWholeEmoji() = onMainThread {
        editor.setText("a\uD83D\uDE00\nb")
        press(KeyEvent.KEYCODE_MOVE_HOME, ctrl)

        // over "a", over the emoji (two chars, one step), over the line break
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_X)
        assertEquals("xb", editor.getLine(2))

        // back over "x", the line break and the emoji
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_Y)
        assertEquals("ay\uD83D\uDE00", editor.getLine(1))
    }

    @Test
    fun ctrlArrowsMoveByWords() = onMainThread {
        editor.setText("foo bar")

        press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
        press(KeyEvent.KEYCODE_X)

        assertEquals("foo xbar", editor.getText())
    }

    // Home goes to where the text of the line starts, a second Home to the very start.
    @Test
    fun homeStopsAtTheIndentationFirst() = onMainThread {
        editor.setText("  abc")

        press(KeyEvent.KEYCODE_MOVE_HOME)
        press(KeyEvent.KEYCODE_X)
        assertEquals("  xabc", editor.getText())

        press(KeyEvent.KEYCODE_MOVE_HOME)
        press(KeyEvent.KEYCODE_MOVE_HOME)
        press(KeyEvent.KEYCODE_Y)
        assertEquals("y  xabc", editor.getText())

        press(KeyEvent.KEYCODE_MOVE_END)
        press(KeyEvent.KEYCODE_Z)
        assertEquals("y  xabcz", editor.getText())
    }

    @Test
    fun shiftArrowsSelectAndTypingReplacesTheSelection() = onMainThread {
        editor.setText("abcdef")

        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        assertEquals("ef", editor.getSelectedText())

        // going back the other way shrinks it again
        press(KeyEvent.KEYCODE_DPAD_RIGHT, shift)
        assertEquals("f", editor.getSelectedText())

        press(KeyEvent.KEYCODE_X)
        assertEquals("abcdex", editor.getText())
    }

    @Test
    fun aSelectionCanGrowPastWhereItStarted() = onMainThread {
        editor.setText("abcdef")
        editor.setSelection(Range(1, 4, 1, 4))

        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        assertEquals("c", editor.getSelectedText())

        // back to nothing, and on to the other side
        press(KeyEvent.KEYCODE_DPAD_RIGHT, shift)
        assertFalse(editor.isSelected())
        press(KeyEvent.KEYCODE_DPAD_RIGHT, shift)
        assertEquals("d", editor.getSelectedText())
    }

    @Test
    fun aPlainArrowKeyDropsTheSelectionAtThatEnd() = onMainThread {
        editor.setText("abcdef")
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse(editor.isSelected())
        press(KeyEvent.KEYCODE_X)

        assertEquals("abcdxef", editor.getText())
    }

    @Test
    fun escapeDropsTheSelection() = onMainThread {
        editor.setText("abcdef")
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)

        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))

        assertFalse(editor.isSelected())
        assertEquals("abcdef", editor.getText())
    }

    @Test
    fun forwardDeleteRemovesWhatFollowsTheCaret() = onMainThread {
        editor.setText("ab\ncd")
        press(KeyEvent.KEYCODE_MOVE_HOME, ctrl)

        press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertEquals("b", editor.getLine(1))

        // at the end of a line it takes the line break
        press(KeyEvent.KEYCODE_MOVE_END)
        press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertEquals("bcd", editor.getText())

        // at the very end there is nothing to take
        press(KeyEvent.KEYCODE_MOVE_END, ctrl)
        press(KeyEvent.KEYCODE_FORWARD_DEL)
        assertEquals("bcd", editor.getText())
    }

    @Test
    fun ctrlShortcutsSelectAllUndoAndRedo() = onMainThread {
        editor.setText("abc")
        press(KeyEvent.KEYCODE_X)
        assertEquals("abcx", editor.getText())

        press(KeyEvent.KEYCODE_Z, ctrl)
        assertEquals("abc", editor.getText())
        press(KeyEvent.KEYCODE_Y, ctrl)
        assertEquals("abcx", editor.getText())
        press(KeyEvent.KEYCODE_Z, ctrl)
        press(KeyEvent.KEYCODE_Z, ctrl or shift)
        assertEquals("abcx", editor.getText())

        press(KeyEvent.KEYCODE_A, ctrl)
        assertEquals("abcx", editor.getSelectedText())
    }

    @Test
    fun aReadOnlyEditorStillLetsTheCaretMove() = onMainThread {
        editor.setText("abcdef")
        editor.setEditable(false)

        press(KeyEvent.KEYCODE_X)
        press(KeyEvent.KEYCODE_DEL)
        press(KeyEvent.KEYCODE_DPAD_LEFT, shift)

        assertEquals("abcdef", editor.getText())
        assertEquals("f", editor.getSelectedText())
    }

    // Some soft keyboards have arrow keys; they arrive through the input connection.
    @Test
    fun keysFromTheSoftKeyboardAreKeysLikeAnyOther() = onMainThread {
        editor.setText("abc")
        val ime = inputConnection()

        ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        ime.commitText("X", 1)

        assertEquals("abXc", editor.getText())
    }

    // #endregion

    @Test
    fun undoWorksRightAfterTyping() = onMainThread {
        editor.setText("abc")
        assertFalse("setText is not undoable", editor.canUndo())

        editor.insert("d", Range(1, 4, 1, 4))
        assertTrue("typed a moment ago counts", editor.canUndo())

        editor.undo()
        assertEquals("abc", editor.getText())
        assertTrue(editor.canRedo())

        editor.redo()
        assertEquals("abcd", editor.getText())
    }

    // #region drawing, without a screen

    // gives the view a size, as a layout pass would
    private fun layOut(view: View, width: Int = 600, height: Int = 400) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
    }

    // Draws the editor into a bitmap. A bitmap canvas is not hardware accelerated,
    // so this goes down the direct drawing path, the one old devices use.
    private fun drawToBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun Bitmap.hasPixel(matches: (red: Int, green: Int, blue: Int) -> Boolean): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { Color.alpha(it) > 200 && matches(Color.red(it), Color.green(it), Color.blue(it)) }
    }

    // Two colors nothing else in the editor is likely to have (the cursor, the
    // handles and the line numbers are drawn too), matched with a little tolerance.
    private val plainColor = Color.rgb(2, 250, 6)
    private val styledColor = Color.rgb(250, 4, 200)

    private fun isAbout(color: Int) = { red: Int, green: Int, blue: Int ->
        Math.abs(red - Color.red(color)) < 16 &&
            Math.abs(green - Color.green(color)) < 16 &&
            Math.abs(blue - Color.blue(color)) < 16
    }

    @Test
    fun theEditorDrawsItsTextWithAndWithoutWordWrap() {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            editor.setTextColor(plainColor)
            editor.setText("first line\nsecond line that is long enough to be wrapped when wrapping is on\nthird")
            layOut(editor, width = 500)

            assertTrue("no text was drawn", drawToBitmap(editor).hasPixel(isAbout(plainColor)))
        }

        offMainThread { editor.setWordwrap(true) }
        onMainThread {
            assertTrue("no text was drawn with word wrap", drawToBitmap(editor).hasPixel(isAbout(plainColor)))
        }
    }

    // Syntax highlighting, the way an app adds it: a styler, no subclass.
    @Test
    fun aLineStylerDecidesHowTheTextLooks() {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            val styledLines = mutableListOf<Int>()
            editor.setTextColor(plainColor)
            editor.setText("MMMM\nMMMM")
            // line 1 in its own color, line 2 left alone
            editor.setLineStyler { text, bufferLine, lineStartOffset, _, _ ->
                styledLines.add(bufferLine)
                assertEquals("the offset of the line in the document", (bufferLine - 1) * 5, lineStartOffset)
                if (bufferLine == 1) {
                    SpannableString(text).apply {
                        setSpan(ForegroundColorSpan(styledColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else {
                    null
                }
            }
            layOut(editor)

            val bitmap = drawToBitmap(editor)

            assertTrue("the styler was not asked about both lines", styledLines.containsAll(listOf(1, 2)))
            assertTrue("line 1 is not in the styled color", bitmap.hasPixel(isAbout(styledColor)))
            assertTrue("line 2 is not in the plain color", bitmap.hasPixel(isAbout(plainColor)))

            // and without the styler everything is plain again
            editor.setLineStyler(null)
            assertFalse(drawToBitmap(editor).hasPixel(isAbout(styledColor)))
        }
    }

    // #region gutter, line numbers, brackets

    @Test
    fun theGutterIsOffByDefaultAndHasAGapBeforeTheText() = onMainThread {
        editor.setText("a\nb")
        assertFalse(editor.isGutterEnabled())
        val inRows = editor.getGutterWidth()

        editor.setGutterEnabled(true)

        assertEquals(inRows + editor.getGutterTextGap(), editor.getGutterWidth())
        editor.setGutterTextGap(0)
        assertEquals(inRows, editor.getGutterWidth())
    }

    // in any font, numbers of as many digits take as much room
    @Test
    fun lineNumbersOfTheSameLengthAreEquallyWide() = onMainThread {
        assertEquals(editor.getLineNumberWidth(20), editor.getLineNumberWidth(21))
        assertEquals(editor.getLineNumberWidth(11), editor.getLineNumberWidth(88))
        assertEquals(2 * editor.getLineNumberWidth(1), editor.getLineNumberWidth(10))
    }

    @Test
    fun theBracketAtTheCursorAndItsPartnerAreFound() = onMainThread {
        editor.setText("f(a[0])")
        editor.setBracketMatcher(PlainBracketMatcher())
        // right after "("
        editor.setSelection(Range(1, 3, 1, 3))

        val pair = editor.getBracketPair()!!
        assertEquals(Range(1, 2, 1, 3), pair.open)
        assertEquals(Range(1, 7, 1, 8), pair.close)

        assertTrue(editor.jumpToMatchingBracket())
        assertEquals("in front of the closing one", 1 to 7, editor.cursorAt())
    }

    @Test
    fun hidingTheLineNumbersGivesTheirRoomToTheText() = onMainThread {
        editor.setText((1..120).joinToString("\n") { "line $it" })
        val withNumbers = editor.getGutterWidth()
        assertTrue(editor.isLineNumbersVisible())

        editor.setLineNumbersVisible(false)
        assertFalse(editor.isLineNumbersVisible())
        val withoutNumbers = editor.getGutterWidth()
        assertTrue("the text did not move left", withoutNumbers < withNumbers)

        // the fold markers keep their room
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        assertTrue(editor.getGutterWidth() > withoutNumbers)
        editor.setFoldingRangeProvider(null)

        editor.setLineNumbersVisible(true)
        assertEquals(withNumbers, editor.getGutterWidth())
    }

    @Test
    fun aMatchingBracketCanBeUnderlined() {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            editor.setText("(MM)")
            editor.setBracketMatcher(PlainBracketMatcher())
            editor.setBracketUnderlineColor(styledColor)
            editor.setBracketMatchStyle(highlight = false, bold = false, underline = true)
            // right after "("
            editor.setSelection(Range(1, 2, 1, 2))
            layOut(editor)

            assertTrue("no underline was drawn", drawToBitmap(editor).hasPixel(isAbout(styledColor)))

            editor.setBracketMatchStyle(underline = false)
            assertFalse("the underline is still there", drawToBitmap(editor).hasPixel(isAbout(styledColor)))
        }
    }

    // #endregion

    // #region folding

    // the app's own drawables: here a placeholder of one color, markers invisible
    @Test
    fun aFoldStyleOfDrawablesDrawsThePlaceholderOfFoldedLines() {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            editor.setText(blocks)
            editor.setFoldingRangeProvider(IndentationFoldingProvider())
            editor.setFoldStyle(
                DrawableFoldStyle(
                    expanded = ColorDrawable(Color.TRANSPARENT),
                    folded = ColorDrawable(Color.TRANSPARENT),
                    placeholder = ColorDrawable(styledColor),
                    tint = false
                )
            )
            layOut(editor)
            assertFalse("a placeholder without a fold", drawToBitmap(editor).hasPixel(isAbout(styledColor)))

            editor.fold(FoldingRange(1, 3))
            assertTrue("no placeholder drawn", drawToBitmap(editor).hasPixel(isAbout(styledColor)))
        }
    }

    // A tap on the placeholder unfolds, unless that is turned off: then it puts the
    // cursor at the end of the folded line, as a tap next to the text does.
    @Test
    fun theFoldPlaceholderCanBeLeftUnclickable() {
        prepareForTouch(blocks)
        onMainThread {
            editor.setFoldingRangeProvider(IndentationFoldingProvider())
            editor.fold(FoldingRange(1, 3))
            editor.setFoldPlaceholderClickable(false)
            // into the placeholder, right of "fun a() {"
            val placeholderX = xOf(1, 10) + editor.getLineHeight() * 0.9f
            val rowMiddle = rowBottomOf(1, 10) - editor.getLineHeight() / 2f

            tap(placeholderX, rowMiddle)
            assertTrue("the tap unfolded", editor.isFolded(1))
            assertEquals(1 to 10, editor.cursorAt())
        }
    }

    @Test
    fun aTapOnTheFoldPlaceholderUnfoldsByDefault() {
        prepareForTouch(blocks)
        onMainThread {
            editor.setFoldingRangeProvider(IndentationFoldingProvider())
            editor.fold(FoldingRange(1, 3))
            assertTrue(editor.isFoldPlaceholderClickable())

            tap(xOf(1, 10) + editor.getLineHeight() * 0.9f, rowBottomOf(1, 10) - editor.getLineHeight() / 2f)

            assertFalse(editor.isFolded(1))
        }
    }

    // the cursor moved into folded lines: they open right away, not at the next frame
    @Test
    fun aCursorMovedIntoAFoldOpensItAtOnce() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        editor.fold(FoldingRange(1, 3))

        editor.setSelection(Range(2, 3, 2, 3))

        assertFalse(editor.isFolded(1))
        assertEquals(2 to 3, editor.cursorAt())
    }

    @Test
    fun foldsSurviveTheEditorBeingRecreated() {
        onMainThread { editor.id = View.generateViewId() }
        offMainThread { editor.setBuffer(bufferOf(blocks)) }
        val recreated = onMainThread {
            editor.setFoldingRangeProvider(IndentationFoldingProvider())
            editor.fold(FoldingRange(1, 3))
            recreate(editor).also { it.setFoldingRangeProvider(IndentationFoldingProvider()) }
        }

        offMainThread { recreated.setBuffer(bufferOf(blocks)) }
        onMainThread {
            assertEquals(listOf(FoldingRange(1, 3)), recreated.getFoldedRanges())
            assertTrue(recreated.isLineHidden(2))
        }
    }

    @Test
    fun anEditSessionKeepsTheFoldsOfTheSavedText() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        editor.fold(FoldingRange(1, 3))
        val session = editor.saveSession("hash")!!
        assertEquals(listOf(1, 3), session.foldedLines)

        editor.setText(blocks)
        assertTrue(editor.getFoldedRanges().isEmpty())
        assertEquals(EditSession.RestoreResult.RESTORED, editor.restoreSession(session, "hash"))
        assertEquals(listOf(FoldingRange(1, 3)), editor.getFoldedRanges())
    }

    @Test
    fun aFoldMarkerThatIsNotTurningPointsWhereItsRangeIs() = onMainThread {
        val feedback = FoldMarkerFeedback(editor, addOverlay = {}, fallbackColor = { Color.GRAY })

        assertEquals(1f, feedback.foldedFraction(5, folded = true))
        assertEquals(0f, feedback.foldedFraction(5, folded = false))
        // a tap shows a ripple; nothing to see here, but nothing may fail either
        feedback.press(100f, 100f, 40)
        feedback.cancel()
    }

    private val blocks = listOf(
        "fun a() {",    // 1
        "    b()",      // 2
        "    c()",      // 3
        "}",            // 4
        "d()"           // 5
    ).joinToString("\n")

    @Test
    fun foldingHidesTheLinesAndUnfoldingBringsThemBack() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())

        assertTrue(editor.toggleFold(1))
        assertTrue(editor.isFolded(1))
        assertTrue(editor.isLineHidden(2))
        assertTrue(editor.isLineHidden(3))
        assertFalse("the indentation fold ends before the closing line", editor.isLineHidden(4))
        assertEquals(3, editor.textLayout.count())
        assertEquals(4, editor.textLayout.getBufferLine(2))

        assertTrue(editor.toggleFold(1))
        assertFalse(editor.isFolded(1))
        assertEquals(5, editor.textLayout.count())
    }

    @Test
    fun aCursorInTheFoldedLinesMovesToTheStartLine() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        editor.setSelection(Range(3, 3, 3, 3))

        editor.fold(FoldingRange(1, 3))

        assertEquals("the end of \"fun a() {\"", 1 to 10, editor.cursorAt())
    }

    @Test
    fun anEditIntoAFoldOpensItAndOneAboveMovesIt() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        editor.fold(FoldingRange(1, 3))

        // a new first line: the fold moves down
        editor.insert("x\n", Range(1, 1, 1, 1))
        assertEquals(listOf(FoldingRange(2, 4)), editor.getFoldedRanges())

        // an edit in the folded lines (as an undo or a replace-all can make)
        editor.insert("e", Range(4, 5, 4, 5))
        assertTrue(editor.getFoldedRanges().isEmpty())
    }

    @Test
    fun loadingAnotherTextOrRemovingTheProviderUnfoldsEverything() = onMainThread {
        editor.setText(blocks)
        editor.setFoldingRangeProvider(IndentationFoldingProvider())
        editor.foldAll()
        assertTrue(editor.isFolded(1))

        editor.setText(blocks)
        assertTrue(editor.getFoldedRanges().isEmpty())

        editor.foldAll()
        editor.setFoldingRangeProvider(null)
        assertTrue(editor.getFoldedRanges().isEmpty())
        assertEquals(5, editor.textLayout.count())
    }

    // #endregion

    // #region modified, edit sessions

    @Test
    fun undoingBackToTheLoadedTextMakesItUnmodified() = onMainThread {
        val heard = mutableListOf<Boolean>()
        editor.setText("abc")
        editor.setOnModifiedChangeListener { heard.add(it) }
        assertFalse(editor.isModified())

        editor.insert("d", Range(1, 4, 1, 4))
        assertTrue(editor.isModified())
        editor.undo()

        assertFalse(editor.isModified())
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun aVersionMarkedSavedLateStillMeansTheTextThatWasWritten() = onMainThread {
        editor.setText("abc")
        editor.insert("d", Range(1, 4, 1, 4))
        val version = editor.getTextVersion()
        // typed on while the file was being written
        editor.insert("e", Range(1, 5, 1, 5))

        editor.markSaved(version)

        assertTrue(editor.isModified())
        editor.undo()
        assertFalse("\"abcd\" is what was written", editor.isModified())
    }

    @Test
    fun unsavedEditsComeBackWithRedoAfterASessionIsRestored() = onMainThread {
        editor.setText("abc")
        editor.insert("d", Range(1, 4, 1, 4))

        val session = editor.saveSession("hash")!!
        assertEquals("the session belongs to the saved text", 3, session.textLength)
        assertTrue(session.undoStack.isEmpty())
        assertEquals(1, session.redoStack.size)

        // the file opened again, without the unsaved edit
        editor.setText("abc")
        assertEquals(EditSession.RestoreResult.RESTORED, editor.restoreSession(session, "hash"))
        assertTrue(editor.canRedo())
        editor.redo()

        assertEquals("abcd", editor.getText())
    }

    @Test
    fun aSessionIsNotPutOnAnotherText() = onMainThread {
        editor.setText("abc")
        val session = editor.saveSession("hash")!!

        editor.setText("abcd")

        assertEquals(EditSession.RestoreResult.CONTENT_MISMATCH, editor.restoreSession(session, "hash"))
    }

    // #endregion

    @Test
    fun editCallbacksHearEveryStageOfAnEdit() = onMainThread {
        val heard = mutableListOf<String>()
        val listener = object : EditorDocument.Callback {
            override fun beforeTextChanged(edits: List<SliceEdit>) {
                heard.add("before")
            }
            override fun onTextChanged(change: EditorDocument.AppliedChange) {
                heard.add("changed +${change.insertedTextLength}")
            }
            override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, caretLineNumber: Int, caretColumn: Int) {
                heard.add("after $caretLineNumber:$caretColumn")
            }
        }
        editor.setText("abc")
        editor.addEditCallback(listener)

        editor.insert("de", Range(1, 4, 1, 4))
        assertEquals(listOf("before", "changed +2", "after 1:6"), heard)

        editor.removeEditCallback(listener)
        editor.insert("f", Range(1, 6, 1, 6))
        assertEquals("a removed listener hears nothing", 3, heard.size)
    }

    // #endregion

    // #region touch: taps and dragging handles, with real motion events

    private var gestureDownTime = 0L
    private var gestureEventTime = 0L

    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) {
            gestureDownTime = SystemClock.uptimeMillis()
            gestureEventTime = gestureDownTime
        } else {
            gestureEventTime += 8
        }
        val event = MotionEvent.obtain(gestureDownTime, gestureEventTime, action, x, y, 0)
        editor.onTouchEvent(event)
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    // a finger going down, sliding sideways in a straight line, and lifting
    private fun drag(fromX: Float, toX: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, fromX, y)
        val steps = 10
        for (step in 1..steps) {
            touch(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / steps, y)
        }
        touch(MotionEvent.ACTION_UP, toX, y)
    }

    // where a position is in the view: x, and the y of the bottom of its row
    private fun xOf(line: Int, column: Int) = editor.getLocationForPosition(line, column)[0].toFloat()
    private fun rowBottomOf(line: Int, column: Int) = editor.getLocationForPosition(line, column)[1].toFloat()

    // On the test thread, before the test goes to the main thread: see offMainThread.
    private fun prepareForTouch(text: String) {
        offMainThread { editor.setTextSize(48f) }
        onMainThread {
            editor.setText(text)
            layOut(editor, width = 1200, height = 600)
        }
    }

    @Test
    fun aTapPutsTheCursorWhereItLands() {
        prepareForTouch("abcdefghij\nabcdefghij")
        onMainThread {

            // in the middle of the second row, right at column 4
            tap(xOf(2, 4), rowBottomOf(2, 4) - editor.getLineHeight() / 2f)
            editor.insert("X")

            assertEquals("abcXdefghij", editor.getLine(2))
        }
    }

    // The insertion handle hangs below the cursor. Taking it by any point and sliding
    // sideways moves the cursor by as much as the finger moved.
    @Test
    fun draggingTheInsertionHandleMovesTheCursorWithTheFinger() {
        prepareForTouch("abcdefghij\nabcdefghij")
        onMainThread {
            tap(xOf(1, 3), rowBottomOf(1, 3) - editor.getLineHeight() / 2f)

            // the handle is just below the row; take it a little off its middle
            val handleY = rowBottomOf(1, 3) + 10f
            val grabOffset = 6f
            drag(xOf(1, 3) + grabOffset, xOf(1, 8) + grabOffset, handleY)
            editor.insert("X")

            assertEquals("abcdefgXhij", editor.getLine(1))
            assertEquals("abcdefghij", editor.getLine(2))
        }
    }

    // A tap right after a tab lands after it: hit testing knows the tab stops too.
    @Test
    fun aTapAfterATabLandsWhereItIsDrawn() {
        prepareForTouch("a\tb")
        onMainThread {
            // a little into "b", which starts at the tab stop
            tap(xOf(1, 3) + 2f, rowBottomOf(1, 3) - editor.getLineHeight() / 2f)
            editor.insert("X")

            assertEquals("a\tXb", editor.getLine(1))
        }
    }

    // The handle goes wherever the finger goes, also where there is no text. The
    // cursor stays on the text, at the position nearest to it.
    @Test
    fun aHandleDraggedPastTheEndOfTheTextLeavesTheCursorAtItsEnd() {
        prepareForTouch("abcdefghij\nabcdefghij")
        onMainThread {
            tap(xOf(1, 3), rowBottomOf(1, 3) - editor.getLineHeight() / 2f)

            // far to the right of the last character of the row
            val handleY = rowBottomOf(1, 3) + 10f
            drag(xOf(1, 3), xOf(1, 11) + 400f, handleY)
            editor.insert("X")

            assertEquals("abcdefghijX", editor.getLine(1))
        }
    }

    // A selection handle follows the finger character by character.
    @Test
    fun draggingASelectionHandleMovesThatEndOfTheSelection() {
        prepareForTouch("hello world again")
        onMainThread {
            editor.setSelection(Range(1, 7, 1, 12))
            assertEquals("world", editor.getSelectedText())

            // the knob of the end handle hangs below the end of the selection
            val handleY = rowBottomOf(1, 12) + 10f
            drag(xOf(1, 12) + 5f, xOf(1, 16) + 5f, handleY)

            assertEquals("world aga", editor.getSelectedText())
        }
    }

    // The two handles never mark the same position: one character stays selected.
    @Test
    fun aSelectionHandleBroughtOntoTheOtherOneStopsOneCharacterShort() {
        prepareForTouch("hello world again")
        onMainThread {
            editor.setSelection(Range(1, 7, 1, 12))

            // the end handle, pulled back exactly to where the selection starts
            val handleY = rowBottomOf(1, 12) + 10f
            drag(xOf(1, 12) + 5f, xOf(1, 7) + 5f, handleY)

            assertEquals("w", editor.getSelectedText())
        }
    }

    // Dragged past the other one, a handle becomes the other end of the selection.
    @Test
    fun aSelectionHandleDraggedPastTheOtherOneChangesPlacesWithIt() {
        prepareForTouch("hello world again")
        onMainThread {
            editor.setSelection(Range(1, 7, 1, 12))

            // the end handle, pulled all the way to the left of the start handle: what
            // was the start of the selection is its end now
            val handleY = rowBottomOf(1, 12) + 10f
            drag(xOf(1, 12) + 5f, xOf(1, 2) + 5f, handleY)

            assertEquals("ello ", editor.getSelectedText())

            // and back again to the right, past the other end once more
            editor.setSelection(Range(1, 7, 1, 12))
            // (the start handle of the first row has its knob below the row)
            drag(xOf(1, 7) - 5f, xOf(1, 15) - 5f, handleY)

            // from where "world" ended to where the finger stopped
            assertEquals(" ag", editor.getSelectedText())
        }
    }

    // #endregion

    // #region instance state

    private fun bufferOf(text: String) = PieceTreeTextBufferBuilder().apply { acceptChunk(text) }.build()

    // what the system does around a rotation: save the old view, restore into a new one
    private fun recreate(old: TestEditor): TestEditor {
        val saved = SparseArray<Parcelable>()
        old.saveHierarchyState(saved)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return TestEditor(context).also {
            it.id = old.id
            it.restoreHierarchyState(saved)
        }
    }

    // setBuffer and setTextSize on the test thread, like an app loading a file in the
    // background: see offMainThread
    @Test
    fun aRecreatedEditorKeepsItsTextSizeAndItsPlaceInTheText() {
        onMainThread { editor.id = View.generateViewId() }
        offMainThread {
            editor.setBuffer(bufferOf("abc\ndef\nghi"))
            editor.setTextSize(41f)
        }
        val recreated = onMainThread {
            editor.setSelection(Range(2, 2, 2, 2))
            val lineHeight = editor.getLineHeight()
            recreate(editor).also {
                assertEquals("the text size", lineHeight, it.getLineHeight())
            }
        }

        // the app loads the same file again: the cursor is back where it was
        offMainThread { recreated.setBuffer(bufferOf("abc\ndef\nghi")) }
        onMainThread {
            recreated.insert("X")
            assertEquals("dXef", recreated.getLine(2))
        }
    }

    @Test
    fun aSavedPlaceIsNotAppliedToAnotherText() {
        onMainThread { editor.id = View.generateViewId() }
        offMainThread { editor.setBuffer(bufferOf("abc\ndef\nghi")) }
        val recreated = onMainThread {
            editor.setSelection(Range(2, 2, 2, 2))
            recreate(editor)
        }

        offMainThread { recreated.setBuffer(bufferOf("a different text")) }
        onMainThread {
            recreated.insert("X")
            assertEquals("the cursor of a newly loaded text is at its start", "Xa different text", recreated.getText())
        }
    }

    // #endregion

    // #region accessibility

    @Test
    fun aScreenReaderSeesATextFieldWithTheTextOnScreenAndTheCursor() = onMainThread {
        editor.setText("abc\ndef")
        editor.setSelection(Range(2, 2, 2, 2))
        layOut(editor)

        @Suppress("DEPRECATION")
        val info = AccessibilityNodeInfo.obtain()
        editor.onInitializeAccessibilityNodeInfo(info)

        assertEquals("android.widget.EditText", info.className.toString())
        assertTrue(info.isEditable)
        assertTrue(info.isMultiLine)
        assertEquals("abc\ndef", info.text.toString())
        // "abc\n" is four chars, the cursor is one further
        assertEquals(5, info.textSelectionStart)
        assertEquals(5, info.textSelectionEnd)
    }

    @Test
    fun aScreenReaderCanMoveTheCursor() = onMainThread {
        editor.setText("abc\ndef")
        layOut(editor)
        @Suppress("DEPRECATION")
        editor.onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo.obtain())

        val arguments = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 1)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 1)
        }
        assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments))

        editor.insert("X")
        assertEquals("aXbc", editor.getLine(1))
    }

    // #endregion

    @Test
    fun aReadOnlyEditorCannotBeChangedByUndoEither() = onMainThread {
        editor.setText("abc")
        editor.insert("d", Range(1, 4, 1, 4))
        editor.setEditable(false)

        editor.undo()
        press(KeyEvent.KEYCODE_Z, ctrl)
        assertEquals("abcd", editor.getText())

        editor.setEditable(true)
        editor.undo()
        assertEquals("abc", editor.getText())
    }

    // #region search matches

    private fun describe(range: Range?) =
        range?.let { "${it.startLine}:${it.startColumn}-${it.endLine}:${it.endColumn}" }

    private fun threeMatches() = mutableListOf(Range(1, 1, 1, 3), Range(1, 4, 1, 6), Range(1, 7, 1, 9))

    @Test
    fun searchMatchesFollowTheTextWhenItIsEdited() = onMainThread {
        editor.setText("ab ab ab")
        val given = threeMatches()
        editor.setSearchResults(given)

        // the caller's list is its own again
        given.clear()
        assertEquals(3, editor.getSearchResults().size)

        editor.insert("xx", Range(1, 1, 1, 1))
        assertEquals(
            listOf("1:3-1:5", "1:6-1:8", "1:9-1:11"),
            editor.getSearchResults().map { describe(it) }
        )

        // typing inside the second match: it is no match any more
        editor.insert("!", Range(1, 7, 1, 7))
        assertEquals(
            listOf("1:3-1:5", "1:10-1:12"),
            editor.getSearchResults().map { describe(it) }
        )
    }

    @Test
    fun replaceAllUsesTheMatchesAndLeavesNoneBehind() = onMainThread {
        editor.setText("ab ab ab")
        editor.setSearchResults(threeMatches())

        editor.replaceAll("c", editor.getSearchResults())

        assertEquals("c c c", editor.getText())
        assertTrue(editor.getSearchResults().isEmpty())
    }

    @Test
    fun steppingThroughTheMatchesWrapsAround() = onMainThread {
        editor.setText("ab ab ab")
        editor.setSearchResults(threeMatches())
        // the cursor is at the end of the text, behind every match

        assertEquals("from the end, on to the first", "1:1-1:3", describe(editor.gotoNextMatch()))
        assertEquals("1:4-1:6", describe(editor.gotoNextMatch()))
        assertEquals("1:1-1:3", describe(editor.gotoPreviousMatch()))
        assertEquals("from the first, back to the last", "1:7-1:9", describe(editor.gotoPreviousMatch()))
        assertEquals("1:7-1:9", describe(editor.getMatchResult()))
    }

    @Test
    fun anotherTextHasNoMatchesOfTheOldOne() = onMainThread {
        editor.setText("ab ab ab")
        editor.setSearchResults(threeMatches())
        editor.setMatchResult(Range(1, 1, 1, 3))

        editor.setText("something else")

        assertTrue(editor.getSearchResults().isEmpty())
        assertEquals(null, editor.getMatchResult())
    }

    // #endregion

    @Test
    fun undoWithNothingToUndoIsHarmless() = onMainThread {
        editor.setText("abc")

        assertFalse(editor.undo())
        assertFalse(editor.redo())
        assertEquals("abc", editor.getText())
    }

    @Test
    fun aReadOnlyEditorRefusesUserEdits() = onMainThread {
        editor.setText("abc")
        editor.setEditable(false)

        editor.insert("X", Range(1, 1, 1, 1))
        editor.delete(Range(1, 1, 1, 2))
        editor.insert("Y")
        inputConnection().commitText("Z", 1)

        assertEquals("abc", editor.getText())
    }

    @Test
    fun theEditorIsLockedDuringAZoom() = onMainThread {
        editor.setText("abc")

        editor.beginZoom()

        assertTrue(editor.isZoomInProgress())
        assertTrue(editor.isLayoutBusy())
        assertFalse(editor.isEditable())
        assertFalse("setText must be refused", editor.setText("other"))
        editor.insert("X", Range(1, 1, 1, 1))
        assertEquals("abc", editor.getText())

        // a pinch that did not change the text size is over at once
        editor.endZoom()

        assertFalse(editor.isZoomInProgress())
        assertTrue(editor.isEditable())
        assertTrue(editor.setText("other"))
    }

    @Test
    fun cutWithoutASelectionRemovesNothing() = onMainThread {
        editor.setText("abc")

        assertFalse(editor.cut())

        assertEquals("abc", editor.getText())
        assertTrue(editor.lastCopyResult is EditorClipboard.CopyResult.NothingSelected)
    }

    @Test
    fun cutRemovesTheSelection() = onMainThread {
        editor.setText("abcdef")
        editor.setSelection(Range(1, 2, 1, 5))

        assertTrue(editor.cut())

        assertEquals("aef", editor.getText())
        assertTrue(editor.lastCopyResult is EditorClipboard.CopyResult.Copied)
    }

    @Test
    fun aSelectionTooLargeForTheClipboardIsNotCopiedAndNotCut() = onMainThread {
        val line = "x".repeat(1000)
        val text = (0..EditorClipboard.MAX_TEXT_LENGTH / 1000).joinToString("\n") { line }
        editor.setText(text)
        editor.selectAll()

        assertFalse(editor.cut())

        assertEquals(text.length, editor.getText().length)
        assertTrue(editor.lastCopyResult is EditorClipboard.CopyResult.TooLarge)
    }

    @Test
    fun aSavedSessionIsASnapshot() = onMainThread {
        editor.setText("abc")
        editor.insert("d", Range(1, 4, 1, 4))
        // "abcd" is the saved text: the step that typed "d" is behind it, on the undo stack
        editor.markSaved()

        val saved = editor.saveSession("hash")!!
        assertEquals("what was just typed is in the saved undo stack", 1, saved.undoStack.size)

        editor.insert("e", Range(1, 5, 1, 5))
        editor.undo()
        editor.undo()

        assertEquals("later edits and undos must not change it", 1, saved.undoStack.size)
        assertTrue(saved.redoStack.isEmpty())
    }
}
