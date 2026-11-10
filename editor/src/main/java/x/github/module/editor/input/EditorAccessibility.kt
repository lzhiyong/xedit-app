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

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

import x.github.module.editor.model.EditorDocument

/**
 * What a screen reader gets to know about the editor: that it is a multi-line text
 * field, what text is in it, where the cursor is, and when either changes.
 *
 * A screen reader expects a text field to hand over its whole text. That cannot be
 * done for a file of tens of megabytes on every cursor move, so it is given a
 * **window**: the lines that are on screen, up to a few thousand chars. The offsets
 * it is told (selection, where text changed) are relative to that window, and the
 * offsets it sends back (set the selection) are turned into document offsets again.
 *
 * This is basic support: reading what is on screen, following the cursor, hearing
 * what is typed, and copy / cut / paste. Moving through the text by word or line
 * with screen reader gestures is not implemented.
 */
class EditorAccessibility(
    private val view: View,
    private val document: EditorDocument,
    private val bridge: InputMethodBridge,
    // the first and the last buffer line on screen
    private val visibleLines: () -> IntRange
) {

    companion object {
        // the most text handed to the screen reader at once
        private const val MAX_WINDOW_LENGTH = 4000
    }

    private val accessibilityManager =
        view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    /** Whether anybody is listening. Everything here is skipped when nobody is. */
    val isEnabled: Boolean get() = accessibilityManager?.isEnabled == true

    // the document offset at which the last window handed out started
    private var windowStart = 0

    // The text of the lines on screen (cut off after MAX_WINDOW_LENGTH), and as a
    // side effect where in the document it starts.
    private fun windowText(): String {
        val lineCount = document.getLineCount()
        val lines = visibleLines()
        val firstLine = lines.first.coerceIn(1, lineCount)
        val lastLine = lines.last.coerceIn(firstLine, lineCount)

        windowStart = document.getLineStartOffset(firstLine)

        val text = StringBuilder()
        for (line in firstLine..lastLine) {
            if (line > firstLine) text.append('\n')
            text.append(document.getLine(line))
            if (text.length >= MAX_WINDOW_LENGTH) {
                text.setLength(MAX_WINDOW_LENGTH)
                break
            }
        }
        return text.toString()
    }

    /** Call from View.onInitializeAccessibilityNodeInfo, after super. */
    fun populateNodeInfo(info: AccessibilityNodeInfo, editable: Boolean) {
        val text = windowText()

        info.className = "android.widget.EditText"
        info.isEditable = editable
        info.isMultiLine = true
        info.text = text
        info.setTextSelection(
            (bridge.selectionStartOffset - windowStart).coerceIn(0, text.length),
            (bridge.selectionEndOffset - windowStart).coerceIn(0, text.length)
        )

        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
        if (editable) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        }
    }

    /**
     * Carries out ACTION_SET_SELECTION: the offsets in [arguments] are relative to
     * the window that was last handed out.
     *
     * @return whether there was a selection to set
     */
    fun setSelection(arguments: Bundle?): Boolean {
        if (arguments == null) return false
        val start = arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1)
        val end = arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1)
        if (start < 0 || end < 0) return false
        bridge.composer.setSelection(windowStart + start, windowStart + end)
        return true
    }

    /**
     * Tells the screen reader that text changed: [removedLength] chars at document
     * offset [offset] were replaced by [addedLength] chars. This is what makes it
     * speak what is typed.
     */
    fun notifyTextChanged(offset: Int, removedLength: Int, addedLength: Int) {
        if (!isEnabled) return
        val text = windowText()
        send(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) { event ->
            event.fromIndex = (offset - windowStart).coerceIn(0, text.length)
            event.removedCount = removedLength
            event.addedCount = addedLength
            event.text.add(text)
        }
    }

    /** Tells the screen reader that the cursor or the selection moved. */
    fun notifySelectionChanged() {
        if (!isEnabled) return
        val text = windowText()
        send(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) { event ->
            event.fromIndex = (bridge.selectionStartOffset - windowStart).coerceIn(0, text.length)
            event.toIndex = (bridge.selectionEndOffset - windowStart).coerceIn(0, text.length)
            event.itemCount = text.length
            event.text.add(text)
        }
    }

    private inline fun send(type: Int, fill: (AccessibilityEvent) -> Unit) {
        @Suppress("DEPRECATION") // the constructor that replaces obtain() needs API 30
        val event = AccessibilityEvent.obtain(type)
        // source, class name, package
        view.onInitializeAccessibilityEvent(event)
        fill(event)
        view.sendAccessibilityEventUnchecked(event)
    }
}
