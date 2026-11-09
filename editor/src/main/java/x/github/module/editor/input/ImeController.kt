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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The soft keyboard as seen from [view]: showing it, hiding it, how much of the view
 * it currently covers, and keeping it informed about the selection.
 */
class ImeController(
    private val view: View,
    // More or less of the bottom of the view is covered than before (by the keyboard,
    // by the bottom overlay). `grew` is true when it is more, false when less.
    private val onOverlapChanged: (grew: Boolean) -> Unit = {}
) {

    /** The space the soft keyboard takes up in the window right now; [Insets.NONE] while hidden. */
    var insets: Insets = Insets.NONE
        private set

    /**
     * How many pixels at the bottom of the view are covered: by the soft keyboard,
     * and by the [bottomOverlay] if there is one.
     *
     * This is not the height of the keyboard. In a window that is resized for the
     * keyboard, or whose layout makes room for it, the view ends above the keyboard
     * and nothing is covered. In an edge-to-edge window nothing is resized, the
     * keyboard simply lies on top of the bottom of the view, and this says how much
     * of it. A view that does not reach the bottom of the window is covered by less
     * than the keyboard is high.
     */
    var overlap: Int = 0
        private set

    /**
     * A view that lies on top of the bottom of the editor: typically a bar of extra
     * keys (symbols, tab, arrows) that sits at the bottom edge and rides up on top
     * of the soft keyboard. What it covers counts as covered, just like what the
     * keyboard covers, so the cursor is kept above it and not merely above the
     * keyboard.
     *
     * Where the bar is in the layout does not matter: only where it is on screen
     * is looked at, so a bar that is moved with translationY, or that lives in
     * another container or window, is handled the same way. A bar that does not
     * actually reach over the editor (because the layout puts it below) simply
     * covers nothing.
     */
    var bottomOverlay: View? = null
        set(value) {
            field?.removeOnLayoutChangeListener(overlayLayoutListener)
            field = value
            value?.addOnLayoutChangeListener(overlayLayoutListener)
            updateOverlap()
        }

    // the bar was resized, shown or hidden
    private val overlayLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateOverlap()
    }

    private val locationOnScreen = IntArray(2)

    init {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            updateOverlap()
            windowInsets  // Do not consume, continue dispatch to other child views
        }
        // A second way to hear of the keyboard: an ancestor that consumes the window
        // insets (many layouts do, after padding themselves for the system bars)
        // keeps the listener above from ever being called.
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    windowInsets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat = windowInsets

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    updateOverlap()
                    // And once more when everybody else has reacted to the end of
                    // the animation too: a bar that follows the keyboard is usually
                    // moved to its final place by a callback just like this one,
                    // which may run after it.
                    view.post { updateOverlap() }
                }
            }
        )
    }

    /**
     * Works out [overlap] again and reports a change. Called by itself when the
     * keyboard comes or goes and when the [bottomOverlay] is laid out; call it as
     * well when the view was moved or resized, or the overlay was moved by other
     * means than layout (translation).
     */
    fun updateOverlap() {
        // what the window says, whether or not anybody passed it down to this view
        val rootInsets = ViewCompat.getRootWindowInsets(view)
        insets = rootInsets?.getInsets(WindowInsetsCompat.Type.ime()) ?: Insets.NONE

        // Everything in screen coordinates, so that it does not matter which
        // container or window the overlay is in.
        view.getLocationOnScreen(locationOnScreen)
        val viewBottom = locationOnScreen[1] + view.height

        // the y from which on the view is covered
        var coveredFrom = viewBottom

        if (insets.bottom > 0) {
            val root = view.rootView
            root.getLocationOnScreen(locationOnScreen)
            val keyboardTop = locationOnScreen[1] + root.height - insets.bottom
            coveredFrom = Math.min(coveredFrom, keyboardTop)
        }

        val overlay = bottomOverlay
        if (overlay != null && overlay.isShown && overlay.height > 0) {
            overlay.getLocationOnScreen(locationOnScreen)
            val overlayTop = locationOnScreen[1]
            val overlayBottom = overlayTop + overlay.height
            // It only counts if it sits on what is covered already (on the keyboard,
            // or on the bottom edge of the view) and reaches up from there. A bar
            // somewhere else on screen has nothing to do with the bottom of the view.
            if (overlayBottom >= coveredFrom && overlayTop < coveredFrom) {
                coveredFrom = overlayTop
            }
        }

        val covered = (viewBottom - coveredFrom).coerceIn(0, view.height)
        if (covered != overlap) {
            val grew = covered > overlap
            overlap = covered
            onOverlapChanged(grew)
        }
    }

    private val inputMethodManager =
        view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    // what the input method was last told: selection start/end, composing start/end
    private var reportedSelectionStart = UNKNOWN
    private var reportedSelectionEnd = UNKNOWN
    private var reportedComposingStart = UNKNOWN
    private var reportedComposingEnd = UNKNOWN

    /**
     * Tells the input method where the selection and the composing region are, in
     * document offsets (-1 for "no composing region"). Input methods depend on this:
     * it is how they notice that the user moved the cursor away from the word they
     * are composing. Cheap to call often, only a change is passed on.
     *
     * @return whether anything had changed since the last report
     */
    fun reportSelection(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int): Boolean {
        if (selectionStart == reportedSelectionStart && selectionEnd == reportedSelectionEnd &&
            composingStart == reportedComposingStart && composingEnd == reportedComposingEnd
        ) {
            return false
        }
        reportedSelectionStart = selectionStart
        reportedSelectionEnd = selectionEnd
        reportedComposingStart = composingStart
        reportedComposingEnd = composingEnd
        inputMethodManager?.updateSelection(view, selectionStart, selectionEnd, composingStart, composingEnd)
        return true
    }

    /**
     * Makes the input method start over with this view: it drops whatever it was
     * composing and asks for a fresh connection. For when the text changed in a way
     * it cannot follow (another file was loaded, an edit was refused).
     */
    fun restartInput() {
        reportedSelectionStart = UNKNOWN
        inputMethodManager?.restartInput(view)
    }

    fun show() {
        val window = view.context.findActivity()?.window ?: return
        WindowCompat.getInsetsController(window, view)?.show(WindowInsetsCompat.Type.ime())
    }

    fun hide() {
        val window = view.context.findActivity()?.window ?: return
        WindowCompat.getInsetsController(window, view)?.hide(WindowInsetsCompat.Type.ime())
    }

    /**
     * Hides the soft keyboard, but only if the input method is serving [view] right
     * now: a keyboard that belongs to another input field (a search box on another
     * screen) is left alone.
     *
     * For when the view is leaving the screen. It goes by the window token instead of
     * the insets controller, which is not reliable for a window that is on its way
     * to the background.
     */
    fun hideIfActive() {
        val imm = inputMethodManager ?: return
        val token = view.windowToken ?: return
        if (imm.isActive(view)) {
            imm.hideSoftInputFromWindow(token, 0)
        }
    }

    private companion object {
        // no real offset
        const val UNKNOWN = Int.MIN_VALUE
    }

    // A view's context is often not the Activity itself but a wrapper around it (a
    // themed context, a dialog, a DI wrapper), so unwrap until one turns up.
    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
