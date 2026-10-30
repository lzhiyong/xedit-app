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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager

import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.adapter.GenericListAdapter
import x.editor.app.databinding.ActivityEditorBinding
import x.editor.app.view.CodeEditorView
import x.github.module.editor.input.CaretMove

import com.google.android.material.R as MaterialR

/**
 * The row of extra keys over the keyboard: what a soft keyboard does not have, or not
 * at hand, for code.
 *
 *  | key        | what it does                                          | while the completion list is shown |
 *  |------------|-------------------------------------------------------|------------------------------------|
 *  | Tab        | next placeholder in a snippet, else indents           | takes the selected item            |
 *  | Tab (held) | placeholder before in a snippet, else outdents        | takes the selected item            |
 *  | symbols    | typed as the keyboard types them (pairs may close)    | the same                           |
 *  | ← →        | move the cursor; held, they repeat                    | the list goes, the cursor moves    |
 *  | ↑ ↓        | move the cursor; held, they repeat                    | move through the list              |
 *  | ⤢ / »      | shows or folds away the arrow keys (remembered)       | the same                           |
 *  | Ctrl, Alt  | tap: held for the next key; long press: until tapped   | the same                           |
 *
 * Ctrl and Alt are for the shortcuts of the keymap (keymap.json): latched, the next
 * char of the soft keyboard, or symbol of the row, is a key of a shortcut (Ctrl+S), not
 * typed. The app takes it ([onSymbol], and the editor's textInterceptor) and calls
 * [consumeModifiers] after it. Latched, a key shows the accent; held, underlined too.
 *
 * Folded away, the arrow keys leave their room to the symbols. They come out by
 * themselves while the completion list is shown (↑ ↓ move through it), and go again a
 * moment after it went: not at once, so that they do not come and go at every key
 * while typing. See [setCompletionActive].
 *
 * While the list is shown, Tab, ↑ and ↓ take the accent color (and Tab the icon of
 * Enter), so it can be seen that they act on the list: see [setCompletionActive].
 *
 * The symbols come from the settings (EditorSettings.extraKeys), the colors from the
 * editor's theme or the app theme ([setColors]).
 */
class ExtraKeysController(
    private val binding: ActivityEditorBinding,
    private val activeEditor: () -> CodeEditorView?,
    private val haptic: () -> Unit,
    /** A symbol of the row tapped: true if the app took it (a key of a shortcut), else typed. */
    private val onSymbol: (String) -> Boolean = { false },
) {

    /** Ctrl or Alt of the row: off, held for the next key, or held until tapped again. */
    enum class Latch { OFF, ONCE, LOCKED }

    /**
     * The colors of the row: [panel] behind the keys, [key] of a key, [content] of its
     * label or icon, [accent] / [onAccent] of the keys that act on the completion list
     * while it is shown, [divider] of the line along the top.
     */
    data class Colors(
        val panel: Int,
        val key: Int,
        val content: Int,
        val accent: Int,
        val onAccent: Int,
        val divider: Int,
    ) {
        companion object {
            /**
             * The colors of an editor theme: keys a little off its [background], so
             * that they are seen, and its [accent] (the cursor's color) for the keys of
             * the completion list.
             */
            fun of(background: Int, foreground: Int, accent: Int): Colors {
                val accentFill = ColorUtils.blendARGB(background, accent, 0.32f)
                return Colors(
                    panel = ColorUtils.blendARGB(background, foreground, 0.04f),
                    key = ColorUtils.blendARGB(background, foreground, 0.11f),
                    content = foreground,
                    accent = accentFill,
                    onAccent = readableOn(accentFill, foreground),
                    divider = ColorUtils.blendARGB(background, foreground, 0.12f),
                )
            }

            // the foreground, or black or white if it is not readable on [color]
            private fun readableOn(color: Int, foreground: Int): Int {
                val opaque = ColorUtils.setAlphaComponent(color, 0xFF)
                return if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(foreground, 0xFF), opaque) >= 3.0) {
                    foreground
                } else if (ColorUtils.calculateLuminance(opaque) > 0.5) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            }
        }
    }

    private val panel = binding.extraKeysPanel
    private val tabKey: MaterialButton = binding.extraKeyTab
    private val leftKey: MaterialButton = binding.extraKeyLeft
    private val upKey: MaterialButton = binding.extraKeyUp
    private val downKey: MaterialButton = binding.extraKeyDown
    private val rightKey: MaterialButton = binding.extraKeyRight
    private val arrowGroup: MaterialCardView = binding.extraKeysArrows
    private val arrowsToggle: MaterialButton = binding.extraKeyArrowsToggle
    private val ctrlKey: MaterialButton = binding.extraKeyCtrl
    private val altKey: MaterialButton = binding.extraKeyAlt

    private var ctrlLatch = Latch.OFF
    private var altLatch = Latch.OFF

    // the arrow keys: shown as the user left them (remembered), or out for the
    // completion list while it is shown
    private val preferences = AppSettings.preferences(panel.context)
    private var arrowsWanted = preferences.getBoolean(Constants.PREF_EXTRA_KEYS_ARROWS_EXPANDED, false)
    private var arrowsForCompletion = false
    private val arrowKeys = ArrayList<RepeatingKey>()
    private val foldArrowsLater = Runnable {
        arrowsForCompletion = false
        showArrows(animate = true)
    }

    // the colors set, or the app theme's
    private var customColors: Colors? = null
    private var colors: Colors = themeColors()

    // 0: the keys as usual, 1: Tab, up and down act on the completion list; in between
    // while it changes
    private var completionFraction = 0f
    private var completionActive = false
    private var completionAnimator: ValueAnimator? = null

    private val symbolAdapter = GenericListAdapter<String>(
        layoutId = R.layout.item_extra_key,
        idSelector = { it },
        contentComparator = { old, new -> old == new }
    )

    init {
        setUpSymbols()
        setUpKeys()
        showArrows(animate = false)
        applyColors()
    }

    // #region API

    /** Whether Ctrl of the row is latched (for the next key, or held). */
    val ctrl: Boolean get() = ctrlLatch != Latch.OFF

    /** Whether Alt of the row is latched. */
    val alt: Boolean get() = altLatch != Latch.OFF

    /** A key was made with them: those latched for one key are let go. */
    fun consumeModifiers() {
        if (ctrlLatch == Latch.ONCE) ctrlLatch = Latch.OFF
        if (altLatch == Latch.ONCE) altLatch = Latch.OFF
        applyModifierColors()
    }

    /** The symbols of the row, in their order. */
    fun setKeys(keys: List<String>) {
        symbolAdapter.submitList(keys.toList())
    }

    /** The colors of the row; null for the app theme's. */
    fun setColors(colors: Colors?) {
        customColors = colors
        this.colors = colors ?: themeColors()
        applyColors()
        @Suppress("NotifyDataSetChanged")
        symbolAdapter.notifyDataSetChanged()
    }

    /**
     * Whether the completion list of the editor shown is shown: then Tab takes its
     * selected item and up and down move through it, and they look it.
     */
    fun setCompletionActive(active: Boolean) {
        if (active == completionActive) return
        completionActive = active
        // folded away: out for the list at once, and away again a moment after it went
        panel.removeCallbacks(foldArrowsLater)
        if (active) {
            if (!arrowsWanted && !arrowsForCompletion) {
                arrowsForCompletion = true
                showArrows(animate = true)
            }
        } else if (arrowsForCompletion) {
            panel.postDelayed(foldArrowsLater, FOLD_AFTER_COMPLETION_MILLIS)
        }
        tabKey.setIconResource(if (active) R.drawable.ic_key_accept else R.drawable.ic_keyboard_tab)
        tabKey.contentDescription = panel.context.getString(
            if (active) R.string.extra_key_accept_completion else R.string.extra_key_tab
        )
        completionAnimator?.cancel()
        completionAnimator = ValueAnimator.ofFloat(completionFraction, if (active) 1f else 0f).apply {
            duration = if (active) ACCENT_IN_MILLIS else ACCENT_OUT_MILLIS
            interpolator = EMPHASIZED
            addUpdateListener {
                completionFraction = it.animatedValue as Float
                applyKeyColors()
            }
            start()
        }
    }

    // #endregion

    // #region keys

    private fun setUpSymbols() {
        symbolAdapter.onBindView = { holder, symbol ->
            val label = holder.getView<TextView>(R.id.tv_name)
            label.text = symbol
            label.setTextColor(colors.content)
            (holder.itemView as? MaterialCardView)?.apply {
                setCardBackgroundColor(colors.key)
                rippleColor = ColorStateList.valueOf(rippleOf(colors.content))
                contentDescription = symbol
            }
        }
        // typed as the keyboard types them: a bracket or a quote may close itself
        symbolAdapter.onItemClick = { _, symbol ->
            haptic()
            // with Ctrl or Alt latched (or in a sequence): a key of a shortcut
            if (!onSymbol(symbol)) activeEditor()?.typeText(symbol)
        }
        symbolAdapter.onItemLongClick = { _, symbol ->
            activeEditor()?.typeText(symbol)
        }
        binding.extraKeysList.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = symbolAdapter
            // the keys are all alike: no animation when the list is replaced
            itemAnimator = null
            // A swipe through the symbols that starts at the left or right edge of the
            // screen is theirs, not the system's back gesture. (The bottom edge cannot be
            // taken from the system: the row is kept above it, see ImeAnimationInsetsHandler.)
            addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                ViewCompat.setSystemGestureExclusionRects(view, listOf(Rect(0, 0, right - left, bottom - top)))
            }
        }
    }

    private fun setUpKeys() {
        // Tab, or the completion; held: one level of indentation less
        tabKey.setOnClickListener {
            haptic()
            val editor = activeEditor() ?: return@setOnClickListener
            if (!editor.acceptCompletion()) editor.pressTab()
        }
        tabKey.setOnLongClickListener {
            haptic()
            val editor = activeEditor() ?: return@setOnLongClickListener true
            // in a snippet: back to the placeholder before (Shift+Tab), else outdent
            if (!editor.acceptCompletion() && !editor.previousSnippetStop()) editor.outdentLines()
            true
        }

        // Ctrl and Alt: a tap latches for one key (or lets go), a long press until tapped
        fun modifier(key: MaterialButton, get: () -> Latch, set: (Latch) -> Unit) {
            key.setOnClickListener {
                haptic()
                set(if (get() == Latch.OFF) Latch.ONCE else Latch.OFF)
                applyModifierColors()
            }
            key.setOnLongClickListener {
                haptic()
                set(Latch.LOCKED)
                applyModifierColors()
                true
            }
        }
        modifier(ctrlKey, { ctrlLatch }) { ctrlLatch = it }
        modifier(altKey, { altLatch }) { altLatch = it }

        // the arrow keys out or away; remembered for the next time
        arrowsToggle.setOnClickListener {
            haptic()
            panel.removeCallbacks(foldArrowsLater)
            arrowsWanted = !arrowsVisible
            arrowsForCompletion = false
            preferences.edit { putBoolean(Constants.PREF_EXTRA_KEYS_ARROWS_EXPANDED, arrowsWanted) }
            showArrows(animate = true)
        }

        // left and right: away from the word being completed, so the list goes
        RepeatingKey { editor ->
            editor.dismissCompletions()
            editor.moveCaret(CaretMove.LEFT)
        }.attachTo(leftKey)
        RepeatingKey { editor ->
            editor.dismissCompletions()
            editor.moveCaret(CaretMove.RIGHT)
        }.attachTo(rightKey)

        // up and down: through the completion list while it is shown, otherwise by lines
        RepeatingKey { editor ->
            if (!editor.moveCompletionSelection(-1)) editor.moveCaret(CaretMove.UP)
        }.attachTo(upKey)
        RepeatingKey { editor ->
            if (!editor.moveCompletionSelection(1)) editor.moveCaret(CaretMove.DOWN)
        }.attachTo(downKey)
    }

    /**
     * A key that acts at once when it is touched, and again and again while it is held
     * (after [REPEAT_DELAY_MILLIS], every [REPEAT_INTERVAL_MILLIS]). A click that did not
     * come from a touch (accessibility, a hardware keyboard) acts once.
     */
    private inner class RepeatingKey(private val action: (CodeEditorView) -> Unit) :
        View.OnTouchListener, View.OnClickListener {

        private var key: View? = null
        private var lastTouchUp = 0L

        private val repeat = object : Runnable {
            override fun run() {
                if (!fire()) return
                key?.postDelayed(this, REPEAT_INTERVAL_MILLIS)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        fun attachTo(view: View) {
            key = view
            view.setOnTouchListener(this)
            view.setOnClickListener(this)
            arrowKeys += this
        }

        /** No more repeats: the key went away while it was held. */
        fun stop() {
            key?.removeCallbacks(repeat)
        }

        // false: the key still shows itself pressed (its ripple) and gives a click,
        // which is left out, see onClick
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(repeat)
                    haptic()
                    fire()
                    view.postDelayed(repeat, REPEAT_DELAY_MILLIS)
                }
                MotionEvent.ACTION_MOVE -> {
                    // slid off the key: no more repeats
                    if (event.x < 0 || event.y < 0 || event.x > view.width || event.y > view.height) {
                        view.removeCallbacks(repeat)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(repeat)
                    lastTouchUp = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(repeat)
            }
            return false
        }

        override fun onClick(view: View) {
            // the click of a touch: done already, when it began
            if (SystemClock.uptimeMillis() - lastTouchUp < CLICK_AFTER_TOUCH_MILLIS) return
            haptic()
            fire()
        }

        private fun fire(): Boolean {
            val editor = activeEditor() ?: return false
            action(editor)
            return true
        }
    }

    // #region arrow keys folding

    private val arrowsVisible: Boolean
        get() = arrowGroup.isVisible

    // The arrow keys shown or not, as wanted or for the completion list; the symbols
    // take the room they leave, with the group sliding out from (or into) the toggle.
    private fun showArrows(animate: Boolean) {
        val show = arrowsWanted || arrowsForCompletion
        if (show != arrowsVisible) {
            if (animate && panel.isLaidOut) {
                TransitionManager.beginDelayedTransition(
                    binding.extraKeysRow,
                    AutoTransition().setDuration(ARROWS_MILLIS).setInterpolator(EMPHASIZED)
                )
            }
            if (!show) arrowKeys.forEach { it.stop() }
            arrowGroup.isVisible = show
        }
        arrowsToggle.setIconResource(if (show) R.drawable.ic_key_collapse else R.drawable.ic_key_arrows)
        arrowsToggle.contentDescription = panel.context.getString(
            if (show) R.string.extra_key_arrows_collapse else R.string.extra_key_arrows_expand
        )
    }

    // #endregion

    // #region colors

    private fun themeColors(): Colors {
        fun color(attr: Int) = MaterialColors.getColor(panel, attr)
        return Colors(
            panel = color(MaterialR.attr.colorSurfaceContainer),
            key = color(MaterialR.attr.colorSurfaceContainerHighest),
            content = color(MaterialR.attr.colorOnSurface),
            accent = color(MaterialR.attr.colorPrimaryContainer),
            onAccent = color(MaterialR.attr.colorOnPrimaryContainer),
            divider = color(MaterialR.attr.colorOutlineVariant),
        )
    }

    private fun applyColors() {
        panel.setBackgroundColor(colors.panel)
        binding.extraKeysDivider.dividerColor = colors.divider
        arrowGroup.setCardBackgroundColor(colors.key)
        val ripple = ColorStateList.valueOf(rippleOf(colors.content))
        for (key in listOf(tabKey, leftKey, upKey, downKey, rightKey, arrowsToggle, ctrlKey, altKey)) key.rippleColor = ripple
        // the toggle quieter than the keys: it does not type anything
        arrowsToggle.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        arrowsToggle.iconTint = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors.content, 0xB3))
        applyKeyColors()
        applyModifierColors()
    }

    // Ctrl and Alt: the key color when off, the accent when latched; underlined when held
    private fun applyModifierColors() {
        for ((key, latch) in listOf(ctrlKey to ctrlLatch, altKey to altLatch)) {
            val on = latch != Latch.OFF
            key.backgroundTintList = ColorStateList.valueOf(if (on) colors.accent else colors.key)
            key.setTextColor(if (on) colors.onAccent else colors.content)
            key.paintFlags = if (latch == Latch.LOCKED) {
                key.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            } else {
                key.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
            key.isSelected = on
        }
    }

    // Tab, up and down: the key color, turning into the accent as the completion list
    // appears ([completionFraction]); left and right: as the group they are in
    private fun applyKeyColors() {
        val f = completionFraction
        val content = ColorStateList.valueOf(colors.content)
        val accentContent = ColorStateList.valueOf(ColorUtils.blendARGB(colors.content, colors.onAccent, f))

        tabKey.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(colors.key, colors.accent, f))
        tabKey.iconTint = accentContent

        // the arrows lie on the group's color: transparent, filled with the accent
        val arrowFill = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.accent, (Color.alpha(colors.accent) * f).toInt())
        )
        upKey.backgroundTintList = arrowFill
        downKey.backgroundTintList = arrowFill
        upKey.iconTint = accentContent
        downKey.iconTint = accentContent

        val clear = ColorStateList.valueOf(Color.TRANSPARENT)
        leftKey.backgroundTintList = clear
        rightKey.backgroundTintList = clear
        leftKey.iconTint = content
        rightKey.iconTint = content
    }

    private fun rippleOf(content: Int): Int = ColorUtils.setAlphaComponent(content, 0x2E)

    // #endregion

    private companion object {
        const val REPEAT_DELAY_MILLIS = 400L
        const val REPEAT_INTERVAL_MILLIS = 50L
        // a click this soon after a touch ended is the click of that touch
        const val CLICK_AFTER_TOUCH_MILLIS = 500L
        const val ACCENT_IN_MILLIS = 220L
        const val ACCENT_OUT_MILLIS = 160L
        const val ARROWS_MILLIS = 220L
        // the arrow keys that came out for the completion list go this long after it
        const val FOLD_AFTER_COMPLETION_MILLIS = 1500L
        // Material 3's emphasized easing
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
