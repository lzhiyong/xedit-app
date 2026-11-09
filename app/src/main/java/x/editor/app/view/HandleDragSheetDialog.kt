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

package x.editor.app.view

import android.content.Context
import android.view.MotionEvent
import android.view.View

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A bottom sheet that can be dragged (down, or closed) by its drag handle only
 * (setDraggedByHandleOnly), the sheets of the developer tools: ConfigListSheet (language
 * servers, formatting, snippets), ToolchainSheet, ShortcutsSheet. Their layouts keep the
 * handle above what scrolls, so that it is always at the top of the sheet.
 *
 * Each touch, before the sheet's behavior sees it: whether the sheet may be dragged by it is
 * decided where it starts. A scroll of the content (a fling back to its top too) never moves
 * the sheet then.
 */
open class HandleDragSheetDialog(context: Context) : BottomSheetDialog(context) {

    private var dragHandle: View? = null
    private var draggedByHandleOnly = false
    private val handleLocation = IntArray(2)

    /**
     * Asked before the user closes the sheet (dragged down, Back, a tap outside: cancel), not
     * when the code dismisses it: true holds the sheet (it springs back up), and the asker
     * calls the function it is given to close it after all (the panels of the developer
     * tools: "Discard unsaved changes?" first). Null: it closes.
     */
    var onCloseRequest: ((close: () -> Unit) -> Boolean)? = null

    override fun cancel() {
        val request = onCloseRequest
        if (request != null && isShowing && request(::closeNow)) {
            // dragged away: back up, as it was
            if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) behavior.state = BottomSheetBehavior.STATE_EXPANDED
            return
        }
        super.cancel()
    }

    // closed after all (the user let go of what held it)
    private fun closeNow() {
        if (isShowing) super.cancel()
    }

    /** The drag handle of the content, which stays at its top (above what scrolls). */
    fun setDragHandle(handle: View) {
        dragHandle = handle
    }

    /**
     * Whether the sheet is dragged only by a touch that starts on its drag handle. Off: by a
     * drag anywhere on it, as a bottom sheet is, which also takes the content's scroll over
     * once it is back at its top.
     *
     * On, the sheet opens all the way and is never left part way (skipCollapsed): a scroll
     * no longer pulls a sheet up, so content below a lower state could not be reached.
     * Dragged down by the handle, it closes (or springs back if not dragged far).
     */
    fun setDraggedByHandleOnly(handleOnly: Boolean) {
        draggedByHandleOnly = handleOnly
        if (handleOnly) {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        } else {
            behavior.isDraggable = true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (draggedByHandleOnly && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            behavior.isDraggable = isOnHandle(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    // [ev] (in the dialog's window) is on the drag handle
    private fun isOnHandle(ev: MotionEvent): Boolean {
        val handle = dragHandle ?: return false
        if (!handle.isShown) return false
        handle.getLocationInWindow(handleLocation)
        val x = ev.x - handleLocation[0]
        val y = ev.y - handleLocation[1]
        return x >= 0 && y >= 0 && x < handle.width && y < handle.height
    }
}
