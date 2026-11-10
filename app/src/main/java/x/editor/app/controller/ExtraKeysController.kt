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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
 *  | ⌃          | shows or folds away Ctrl, Alt and Shift; held: stay   | the same                           |
 *  |            | (a shortcut made does not fold them)                  |                                    |
 *  | symbols    | typed as the keyboard types them (pairs may close)    | the same                           |
 *  | ← →        | move the cursor; held, they repeat                    | the list goes, the cursor moves    |
 *  | ↑ ↓        | move the cursor; held, they repeat                    | move through the list              |
 *  | ⤢ / »      | shows or folds away the arrow keys (remembered)       | the same                           |
 *  | Ctrl, Alt, Shift | tap: held for the next key; long press: until tapped; lit while held | the same      |
 *
 * Ctrl and Alt are for the shortcuts of the keymap (keymap.json): latched, the next
 * char of the soft keyboard, or symbol of the row, is a key of a shortcut (Ctrl+S), not
 * typed. The app takes it ([onSymbol], and the editor's textInterceptor) and calls
 * [consumeModifiers] after it. Shift goes with them (Ctrl+Shift+F); alone, it makes the next
 * letter a capital, the arrow keys select, and Tab go back as held Tab does.
 *
 * Only Tab stays out at all times: Ctrl, Alt and Shift are folded behind ⌃, so that the
 * symbols keep the row (as the arrow keys are behind their toggle). ⌃ brings them out, and
 * they stay out until ⌃ is tapped again: making a shortcut does not fold them, so that
 * Ctrl+C is followed by Ctrl+V without bringing them out again. Held down, ⌃ keeps them out
 * for good ([modifiersPinned], remembered). Folded with one latched, ⌃ says which, in its
 * letters.
 *
 * Latched (a tap) or held (a long press), the word on the key lights up in the highlight
 * color (the cursor's color of the editor theme, or the app's primary color; another one
 * where that is about the color of the text, as the cursor often is), on a tint of it, so
 * that it is seen at a glance that the next key is a shortcut; held, it is underlined too.
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
     * while it is shown, [divider] of the line along the top, [highlight] of the word
     * on Ctrl or Alt while it is latched (made readable on its key, see
     * [applyModifierColors]).
     */
    data class Colors(
        val panel: Int,
        val key: Int,
        val content: Int,
        val accent: Int,
        val onAccent: Int,
        val divider: Int,
        val highlight: Int,
    ) {
        companion object {
            /**
             * The colors of an editor theme: keys a little off its [background], so
             * that they are seen, and its [accent] (the cursor's color) for the keys of
             * the completion list; [highlight] for the word on Ctrl or Alt while latched
             * (the accent too, unless one is given).
             */
            fun of(background: Int, foreground: Int, accent: Int, highlight: Int = accent): Colors {
                val accentFill = ColorUtils.blendARGB(background, accent, 0.32f)
                return Colors(
                    panel = ColorUtils.blendARGB(background, foreground, 0.04f),
                    key = ColorUtils.blendARGB(background, foreground, 0.11f),
                    content = foreground,
                    accent = accentFill,
                    onAccent = readableOn(accentFill, foreground),
                    divider = ColorUtils.blendARGB(background, foreground, 0.12f),
                    highlight = highlight,
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
    private val shiftKey: MaterialButton = binding.extraKeyShift
    private val modifierGroup: LinearLayout = binding.extraKeysModifiers
    private val modifiersToggle: MaterialButton = binding.extraKeyModifiersToggle

    private var ctrlLatch = Latch.OFF
    private var altLatch = Latch.OFF
    private var shiftLatch = Latch.OFF

    // the arrow keys: shown as the user left them (remembered), or out for the
    // completion list while it is shown
    private val preferences = AppSettings.preferences(panel.context)
    private var arrowsWanted = preferences.getBoolean(Constants.PREF_EXTRA_KEYS_ARROWS_EXPANDED, false)
    private var arrowsForCompletion = false
    private val arrowKeys = ArrayList<RepeatingKey>()

    // Ctrl, Alt and Shift: out for good (⌃ held down, remembered), or for now (⌃ tapped)
    private var modifiersPinned = preferences.getBoolean(Constants.PREF_EXTRA_KEYS_MODIFIERS_PINNED, false)
    private var modifiersOpen = false

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
        showModifiers(animate = false)
        applyColors()
    }

    // #region API

    /** Whether Ctrl of the row is latched (for the next key, or held). */
    val ctrl: Boolean get() = ctrlLatch != Latch.OFF

    /** Whether Alt of the row is latched. */
    val alt: Boolean get() = altLatch != Latch.OFF

    /** Whether Shift of the row is latched. */
    val shift: Boolean get() = shiftLatch != Latch.OFF

    /** A key was made with them: those latched for one key are let go. */
    fun consumeModifiers() {
        if (ctrlLatch == Latch.ONCE) ctrlLatch = Latch.OFF
        if (altLatch == Latch.ONCE) altLatch = Latch.OFF
        if (shiftLatch == Latch.ONCE) shiftLatch = Latch.OFF
        afterModifierUsed()
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
            when {
                shift -> {
                    pressShiftTab(editor)
                    shiftUsed()
                }
                !editor.acceptCompletion() -> editor.pressTab()
            }
        }
        tabKey.setOnLongClickListener {
            haptic()
            activeEditor()?.let { pressShiftTab(it) }
            true
        }

        // ⌃: Ctrl, Alt and Shift out or away; held down, they stay out (or no longer do)
        modifiersToggle.setOnClickListener {
            haptic()
            val fold = modifierGroup.isVisible
            if (fold && modifiersPinned) pinModifiers(false)
            modifiersOpen = !fold
            showModifiers(animate = true)
        }
        modifiersToggle.setOnLongClickListener {
            haptic()
            pinModifiers(!modifiersPinned)
            Toast.makeText(
                panel.context,
                if (modifiersPinned) R.string.extra_key_modifiers_pinned else R.string.extra_key_modifiers_unpinned,
                Toast.LENGTH_SHORT,
            ).show()
            modifiersOpen = false
            showModifiers(animate = true)
            true
        }

        // Ctrl, Alt and Shift: a tap latches for one key (or lets go), a long press until tapped
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
        modifier(shiftKey, { shiftLatch }) { shiftLatch = it }

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
            editor.moveCaret(CaretMove.LEFT, extendSelection = shift)
        }.attachTo(leftKey)
        RepeatingKey { editor ->
            editor.dismissCompletions()
            editor.moveCaret(CaretMove.RIGHT, extendSelection = shift)
        }.attachTo(rightKey)

        // up and down: through the completion list while it is shown, otherwise by lines
        RepeatingKey { editor ->
            if (!editor.moveCompletionSelection(-1)) editor.moveCaret(CaretMove.UP, extendSelection = shift)
        }.attachTo(upKey)
        RepeatingKey { editor ->
            if (!editor.moveCompletionSelection(1)) editor.moveCaret(CaretMove.DOWN, extendSelection = shift)
        }.attachTo(downKey)
    }

    // Shift+Tab: the completion taken, else back to the placeholder before in a snippet, else outdent
    private fun pressShiftTab(editor: CodeEditorView) {
        if (!editor.acceptCompletion() && !editor.previousSnippetStop()) editor.outdentLines()
    }

    // Shift was used with an arrow key or Tab, a key let go: Shift latched for one key lets go
    private fun shiftUsed() {
        if (shiftLatch != Latch.ONCE) return
        shiftLatch = Latch.OFF
        afterModifierUsed()
    }

    private fun pinModifiers(pinned: Boolean) {
        modifiersPinned = pinned
        preferences.edit { putBoolean(Constants.PREF_EXTRA_KEYS_MODIFIERS_PINNED, pinned) }
    }

    // a latch let go: the keys show it. They stay out (until ⌃ is tapped), so that the next
    // shortcut is a tap away
    private fun afterModifierUsed() {
        applyModifierColors()
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
                    shiftUsed()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(repeat)
                    shiftUsed()
                }
            }
            return false
        }

        override fun onClick(view: View) {
            // the click of a touch: done already, when it began
            if (SystemClock.uptimeMillis() - lastTouchUp < CLICK_AFTER_TOUCH_MILLIS) return
            haptic()
            fire()
            shiftUsed()
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

    // #region Ctrl, Alt and Shift folding

    // Ctrl, Alt and Shift shown or not; the symbols take the room they leave
    private fun showModifiers(animate: Boolean) {
        val show = modifiersPinned || modifiersOpen
        if (show != modifierGroup.isVisible) {
            if (animate && panel.isLaidOut) {
                TransitionManager.beginDelayedTransition(
                    binding.extraKeysRow,
                    AutoTransition().setDuration(ARROWS_MILLIS).setInterpolator(EMPHASIZED)
                )
            }
            modifierGroup.isVisible = show
        }
        modifiersToggle.contentDescription = panel.context.getString(
            if (show) R.string.extra_key_modifiers_collapse else R.string.extra_key_modifiers_expand
        )
        applyModifierColors()
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
            highlight = color(androidx.appcompat.R.attr.colorPrimary),
        )
    }

    private fun applyColors() {
        panel.setBackgroundColor(colors.panel)
        binding.extraKeysDivider.dividerColor = colors.divider
        arrowGroup.setCardBackgroundColor(colors.key)
        val ripple = ColorStateList.valueOf(rippleOf(colors.content))
        for (key in listOf(tabKey, leftKey, upKey, downKey, rightKey, arrowsToggle, ctrlKey, altKey, shiftKey, modifiersToggle)) {
            key.rippleColor = ripple
        }
        // the toggle quieter than the keys: it does not type anything
        arrowsToggle.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        arrowsToggle.iconTint = ColorStateList.valueOf(ColorUtils.setAlphaComponent(colors.content, 0xB3))
        applyKeyColors()
        applyModifierColors()
    }

    // Ctrl, Alt and Shift: as the other keys when off; latched, the word in the highlight
    // color on a faint tint of it (the color made darker or lighter where it would not be
    // read well there); underlined too when held. ⌃ as the other keys; folded with some
    // latched, their first letters as they would be.
    private fun applyModifierColors() {
        val highlight = distinctHighlight()
        val latchedFill = ColorUtils.blendARGB(colors.key, highlight, LATCHED_TINT)
        val latchedText = legibleOn(highlight, latchedFill)
        val letters = buildString {
            if (ctrl) append('C')
            if (alt) append('A')
            if (shift) append('S')
        }.takeUnless { modifierGroup.isVisible }.orEmpty()
        modifiersToggle.backgroundTintList = ColorStateList.valueOf(if (letters.isEmpty()) colors.key else latchedFill)
        modifiersToggle.setTextColor(latchedText)
        modifiersToggle.text = letters
        if (letters.isEmpty()) {
            modifiersToggle.setIconResource(R.drawable.ic_key_modifiers)
            modifiersToggle.iconTint = ColorStateList.valueOf(colors.content)
        } else {
            modifiersToggle.icon = null
        }
        for ((key, latch) in listOf(ctrlKey to ctrlLatch, altKey to altLatch, shiftKey to shiftLatch)) {
            val on = latch != Latch.OFF
            key.backgroundTintList = ColorStateList.valueOf(if (on) latchedFill else colors.key)
            key.setTextColor(if (on) latchedText else colors.content)
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

    /**
     * The color a latched Ctrl, Alt or Shift lights up in: [Colors.highlight], unless it is
     * about the color of the other keys' words (the cursor of many themes is the text color),
     * where the word would not be seen to change; then the app theme's primary color, or,
     * if that is no different, a blue made for the background.
     */
    private fun distinctHighlight(): Int {
        val content = colors.content
        if (isDistinct(colors.highlight, content)) return colors.highlight
        val primary = MaterialColors.getColor(panel, androidx.appcompat.R.attr.colorPrimary)
        if (isDistinct(primary, content)) return primary
        val dark = ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(colors.key, 0xFF)) < 0.5
        return if (dark) FALLBACK_HIGHLIGHT_ON_DARK else FALLBACK_HIGHLIGHT_ON_LIGHT
    }

    // whether [a] is told from [b] at a glance: far enough apart in CIELAB
    private fun isDistinct(a: Int, b: Int): Boolean {
        val labA = DoubleArray(3)
        val labB = DoubleArray(3)
        ColorUtils.colorToLAB(ColorUtils.setAlphaComponent(a, 0xFF), labA)
        ColorUtils.colorToLAB(ColorUtils.setAlphaComponent(b, 0xFF), labB)
        return ColorUtils.distanceEuclidean(labA, labB) >= MIN_HIGHLIGHT_DISTANCE
    }

    /**
     * [color], or the nearest shade of it (toward black on a light [background], toward
     * white on a dark one) that has a contrast of [MIN_HIGHLIGHT_CONTRAST] with it.
     */
    private fun legibleOn(color: Int, background: Int): Int {
        val back = ColorUtils.setAlphaComponent(background, 0xFF)
        val front = ColorUtils.setAlphaComponent(color, 0xFF)
        if (ColorUtils.calculateContrast(front, back) >= MIN_HIGHLIGHT_CONTRAST) return front
        val toward = if (ColorUtils.calculateLuminance(back) > 0.5) Color.BLACK else Color.WHITE
        for (step in 1..10) {
            val shade = ColorUtils.blendARGB(front, toward, step / 10f)
            if (ColorUtils.calculateContrast(shade, back) >= MIN_HIGHLIGHT_CONTRAST) return shade
        }
        return toward
    }

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
        // how much of the highlight color is in the key of a latched Ctrl, Alt or Shift
        const val LATCHED_TINT = 0.24f
        // how far (CIELAB) the highlight must be from the words on the keys to be used
        const val MIN_HIGHLIGHT_DISTANCE = 35.0
        // the highlight when neither the theme's nor the app's stands out from the words
        const val FALLBACK_HIGHLIGHT_ON_DARK = 0xFF64B5F6.toInt()
        const val FALLBACK_HIGHLIGHT_ON_LIGHT = 0xFF1565C0.toInt()
        // of the word on a latched Ctrl, Alt or Shift with its key (WCAG: 3 for UI and bold text)
        const val MIN_HIGHLIGHT_CONTRAST = 3.0
        // Material 3's emphasized easing
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
