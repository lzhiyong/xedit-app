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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] that stays where it is when a child inside it takes the focus
 * (the sheets of ConfigListSheet: the editors of FormattingSheet).
 *
 * A NestedScrollView scrolls a child that takes the focus fully into view. An editor
 * takes the focus on the touch that starts on it (EditorView: onDown → requestFocus), so
 * a tap on an editor that was partly scrolled off moved the whole sheet under the
 * finger: down a little for the editor at the top, up a little for the one at the bottom.
 * Here a change of focus scrolls nothing ([scrollsToFocusedChild] off, the default).
 *
 * What still scrolls: the soft keyboard coming up (the view made shorter) keeps the
 * focused editor in view, as a NestedScrollView does (onSizeChanged); and a child that
 * asks for a rectangle of it to be shown (requestChildRectangleOnScreen).
 */
class FocusStableScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** Whether a child that takes the focus is scrolled into view (a NestedScrollView's way). */
    var scrollsToFocusedChild = false

    // the focus is moving: the scroll that goes with it is not done
    private var holdingStill = false
    // the focus moved while a layout was due: NestedScrollView puts the scroll off to it
    private var holdAtLayout = false

    override fun requestChildFocus(child: View?, focused: View?) {
        if (scrollsToFocusedChild) {
            super.requestChildFocus(child, focused)
            return
        }
        holdingStill = true
        try {
            super.requestChildFocus(child, focused)
        } finally {
            holdingStill = false
        }
        if (isLayoutRequested) holdAtLayout = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!holdAtLayout) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        holdingStill = true
        try {
            super.onLayout(changed, l, t, r, b)
        } finally {
            holdingStill = false
            holdAtLayout = false
        }
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (holdingStill) 0 else super.computeScrollDeltaToGetChildRectOnScreen(rect)
}
