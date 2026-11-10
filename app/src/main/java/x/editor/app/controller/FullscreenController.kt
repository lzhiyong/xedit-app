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

package x.editor.app.controller

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.ImageButton

import androidx.core.graphics.ColorUtils
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible

import com.google.android.material.color.MaterialColors

import x.editor.app.R
import x.editor.app.databinding.ViewFullscreenBarBinding

import com.google.android.material.R as MaterialR

/**
 * Full screen: on a phone, with the keyboard up, the toolbar and the row of tabs leave
 * the editor a strip of text. In full screen they give way to one slim bar ([bar], as
 * high as the tabs alone) and the status bar is hidden (a swipe from the top edge shows
 * it for a moment), so the editor gets the room back.
 *
 * The slim bar keeps what is needed while typing: which document is shown (its name, its
 * place among the open ones, a dot for unsaved changes, a spinner while it is busy; a tap
 * lists the open files), undo, redo, save, the menu of the toolbar, and a way out.
 *
 * When it is on ([isActive]) follows the setting ([Mode]): never by itself, while the
 * keyboard is up, or always; the menu, the shortcut and the way out of the bar turn it
 * on or off over that ([toggle], [exit]). Back (the button or the gesture) leaves it as
 * it is. Turned off while the keyboard is up (in [Mode.KEYBOARD]), it comes back with the
 * keyboard the next time.
 *
 * The editors slide with it: the editor content moves to its new place and its text
 * glides there ([slidingContent] translated from where it was), the bars fade.
 */
internal class FullscreenController(
    private val window: Window,
    private val toolbar: View,
    private val tabs: EditorTabsController,
    private val tabLayout: View,
    private val bar: ViewFullscreenBarBinding,
    // the part under the app bar, whose top moves
    private val content: View,
    // what glides from the old top to the new one: the editors (not the row of extra keys,
    // which keeps to the keyboard)
    private val slidingContent: View,
    private val actions: Actions
) {

    /** What the slim bar does, carried out by the activity. */
    interface Actions {
        fun undo()
        fun redo()
        fun save()
        fun showMenu(anchor: View)
        fun showOpenFiles(anchor: View, textStart: Int)
        /** Full screen came on or went off: the menu says so. */
        fun onActiveChanged(active: Boolean)
    }

    /** When full screen comes on by itself (the setting pref_editor_fullscreen). */
    enum class Mode(val key: String) {
        OFF("off"),
        KEYBOARD("keyboard"),
        ALWAYS("always");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: OFF
        }
    }

    /** What the slim bar shows of the document shown, and what can be done with it. */
    data class State(
        val name: String?,
        val position: Int,
        val count: Int,
        val modified: Boolean,
        val busy: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean,
        val canSave: Boolean
    )

    var mode: Mode = Mode.OFF
        private set

    /** Whether the slim bar is shown in place of the toolbar and the tabs. */
    var isActive = false
        private set

    // the user's say over the mode: on (the menu, the shortcut) or off (the way out of the
    // bar, the menu, the shortcut); null: the mode decides
    private var override: Boolean? = null

    private var imeVisible = false

    // Whether the editor's window has the focus. While a popup or a dialog has it (the list
    // of open files, the menu, a sheet) the keyboard may go down for it: full screen stays as
    // it is until the focus is back, so the bar does not vanish under the popup it opened.
    private var windowFocused = true

    // the keyboard went down: off a moment later, unless it is back by then (switching tabs)
    private val keyboardDown = Runnable { update(animate = true) }

    // the colors of the text and icons: null, the app theme's (see setColors)
    private var foreground: Int? = null
    private var muted: Int? = null
    private var accent: Int? = null

    private var state: State? = null

    init {
        bar.fullscreenUndo.setOnClickListener { actions.undo() }
        bar.fullscreenRedo.setOnClickListener { actions.redo() }
        bar.fullscreenSave.setOnClickListener { actions.save() }
        bar.fullscreenMore.setOnClickListener { actions.showMenu(it) }
        bar.fullscreenExit.setOnClickListener { exit() }
        bar.fullscreenFile.setOnClickListener {
            // the names of the list under the name on the chip
            val barAt = IntArray(2).also { bar.root.getLocationInWindow(it) }
            val nameAt = IntArray(2).also { bar.fullscreenName.getLocationInWindow(it) }
            actions.showOpenFiles(bar.root, nameAt[0] - barAt[0])
        }
        // long presses say what a button is, as the toolbar's do
        listOf(bar.fullscreenUndo, bar.fullscreenRedo, bar.fullscreenSave, bar.fullscreenMore, bar.fullscreenExit)
            .forEach { it.tooltipText = it.contentDescription }
        applyColors()
    }

    // #region on and off

    /** The setting changed (or was read at start): full screen as it says, unless the user said. */
    fun setMode(mode: Mode) {
        if (this.mode == mode) return
        this.mode = mode
        override = null
        update(animate = true)
    }

    /** The keyboard came up or went down. */
    fun onImeVisibilityChanged(visible: Boolean) {
        if (visible == imeVisible) return
        imeVisible = visible
        if (!windowFocused) return
        // turned off while typing: back with the keyboard the next time
        if (mode == Mode.KEYBOARD && override == false) override = null
        settle()
    }

    /** The editor's window got the focus back, or lost it to a popup or a dialog. */
    fun onWindowFocusChanged(focused: Boolean) {
        windowFocused = focused
        if (focused) settle() else bar.root.removeCallbacks(keyboardDown)
    }

    // full screen as it should be now; going off because the keyboard went down, a moment later
    private fun settle() {
        bar.root.removeCallbacks(keyboardDown)
        if (isActive && !wanted() && mode == Mode.KEYBOARD && override == null) {
            bar.root.postDelayed(keyboardDown, KEYBOARD_DOWN_DELAY_MS)
        } else {
            update(animate = true)
        }
    }

    /** The menu or the shortcut: on if it is off, off if it is on. */
    fun toggle() {
        override = !isActive
        update(animate = true)
    }

    /** The way out of the slim bar. */
    fun exit() {
        if (!isActive) return
        override = false
        update(animate = true)
    }

    private fun wanted(): Boolean = override ?: when (mode) {
        Mode.OFF -> false
        Mode.KEYBOARD -> imeVisible
        Mode.ALWAYS -> true
    }

    private fun update(animate: Boolean) {
        bar.root.removeCallbacks(keyboardDown)
        val wanted = wanted()
        if (wanted == isActive) return
        isActive = wanted
        apply(animate)
        actions.onActiveChanged(wanted)
    }

    // The toolbar and the tabs, or the slim bar; the status bar hidden or back. The editors
    // glide from where their top was to where it is now, the bars fade in.
    private fun apply(animate: Boolean) {
        val oldTop = content.top
        // an animation still going (on, then off at once) gives way to this one
        slidingContent.animate().cancel()
        listOf(toolbar, tabLayout, bar.root).forEach { it.animate().cancel(); it.alpha = 1f }

        toolbar.isVisible = !isActive
        tabs.setHidden(isActive)
        bar.root.isVisible = isActive

        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (isActive) {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
        }

        if (!animate || !content.isLaidOut) {
            slidingContent.translationY = 0f
            return
        }
        val appearing = if (isActive) listOf(bar.root) else listOf(toolbar, tabLayout).filter { it.isVisible }
        appearing.forEach { view ->
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EMPHASIZED).start()
        }
        // once laid out anew: where the top went, and the text from there
        OneShotPreDrawListener.add(content) {
            val shift = (oldTop - content.top).toFloat()
            if (shift != 0f) {
                slidingContent.translationY = shift
                slidingContent.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(EMPHASIZED).start()
            }
        }
    }

    // #endregion

    // #region the slim bar

    /** What the slim bar shows; called whenever the document shown or its state changes. */
    fun bind(state: State) {
        if (state == this.state) return
        this.state = state
        with(bar) {
            fullscreenName.text = state.name ?: root.context.getString(R.string.fullscreen_no_file)
            fullscreenPosition.isVisible = state.count > 1 && state.position > 0
            fullscreenPosition.text = root.context.getString(R.string.fullscreen_position, state.position, state.count)
            fullscreenModified.isVisible = state.modified && !state.busy
            fullscreenProgress.isVisible = state.busy
            fullscreenFile.contentDescription = if (state.name != null) {
                root.context.getString(R.string.fullscreen_file_description, state.name, state.position, state.count)
            } else {
                root.context.getString(R.string.fullscreen_no_file)
            }
            setEnabled(fullscreenUndo, state.canUndo)
            setEnabled(fullscreenRedo, state.canRedo)
            setEnabled(fullscreenSave, state.canSave)
        }
    }

    /**
     * The colors of the editor's theme for the text and icons of the bar (its background is
     * the app bar's, colored with the rest); nulls: the app theme's.
     */
    fun setColors(foreground: Int?, muted: Int?, accent: Int?) {
        this.foreground = foreground
        this.muted = muted
        this.accent = accent
        applyColors()
    }

    private fun applyColors() {
        val root = bar.root
        val text = foreground ?: MaterialColors.getColor(root, MaterialR.attr.colorOnSurface)
        val dim = muted ?: MaterialColors.getColor(root, MaterialR.attr.colorOnSurfaceVariant)
        val highlight = accent ?: MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        with(bar) {
            fullscreenName.setTextColor(text)
            fullscreenPosition.setTextColor(dim)
            fullscreenExpand.imageTintList = ColorStateList.valueOf(dim)
            fullscreenModified.imageTintList = ColorStateList.valueOf(highlight)
            fullscreenProgress.setIndicatorColor(highlight)
            // a hairline between the bar and the text, as faint as a divider
            fullscreenDivider.setBackgroundColor(ColorUtils.setAlphaComponent(text, DIVIDER_ALPHA))
            listOf(fullscreenUndo, fullscreenRedo, fullscreenSave, fullscreenMore, fullscreenExit).forEach {
                it.imageTintList = ColorStateList.valueOf(text)
            }
        }
    }

    // a button that cannot be used: faint, as the toolbar's are
    private fun setEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.imageAlpha = if (enabled) 255 else DISABLED_ALPHA
    }

    // #endregion

    // #region saved state

    fun saveState(outState: Bundle) {
        outState.putInt(STATE_OVERRIDE, when (override) { true -> 1; false -> 0; null -> -1 })
    }

    /** As before a rotation: the user's say, and full screen on or off at once (no animation). */
    fun restoreState(savedState: Bundle?) {
        override = when (savedState?.getInt(STATE_OVERRIDE, -1)) { 1 -> true; 0 -> false; else -> null }
        val wanted = wanted()
        if (wanted != isActive) {
            isActive = wanted
            apply(animate = false)
            actions.onActiveChanged(wanted)
        }
    }

    // #endregion

    private companion object {
        const val STATE_OVERRIDE = "fullscreen_override"

        // how long the keyboard may be down before full screen goes off with it
        const val KEYBOARD_DOWN_DELAY_MS = 150L

        const val FADE_MS = 180L
        const val SLIDE_MS = 260L

        // Material's emphasized decelerate: quick out, a soft landing
        val EMPHASIZED = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        // about 12% for the hairline, 38% for a button that is off
        const val DIVIDER_ALPHA = 31
        const val DISABLED_ALPHA = 97
    }
}
