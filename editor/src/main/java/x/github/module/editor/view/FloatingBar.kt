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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import x.github.module.editor.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A floating bar over the selection, as the system's floating text toolbar: the main
 * items in a pill, the others in an overflow menu that the pill turns into.
 *
 * Structure:
 *
 *     this (transparent, a margin around for the shadow)
 *      └─ panel (background, elevation, clipped to its outline, which the animation changes)
 *           ├─ barPanel        the main items, in a row
 *           ├─ listPanel       the overflow menu: a scroll view of rows
 *           └─ overflowButton  ⋮ / ← (moves, turns, fades from one to the other)
 *
 * The panel is as large as the pill and the menu together; what is seen of it is its
 * outline, and the animation only changes that rectangle (its bottom or top right corner
 * stays): nothing is laid out again while it runs.
 *
 * The overflow menu is as high as the system's: at most [maxOverflowRows] rows, and half
 * a row more when there are more (what scrolls shows that it does), never more than the
 * room above or below the bar; the other rows scroll.
 */
@SuppressLint("ViewConstructor")
class FloatingBar(context: Context) : FrameLayout(context) {

    /** An item of the bar. */
    data class Item(
        val id: Int,
        val title: CharSequence,
        val icon: Drawable? = null,
        /** In the overflow menu rather than in the bar. */
        val overflow: Boolean = false,
        /** Tinted with the color of the bar's icons; false for colored icons (of apps). */
        val tintIcon: Boolean = true,
    )

    /** Called when an item is tapped. */
    var onItemClick: ((Item) -> Unit)? = null

    /** The rows the overflow menu shows at most before it scrolls (the system shows 4). */
    var maxOverflowRows: Int = MAX_OVERFLOW_ROWS

    val isShowing: Boolean get() = parent != null && visibility == VISIBLE

    // #region look

    private val bgColor = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF3D3D3F.toInt())
    private val iconColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFE3E3E3.toInt())
    private val textColor = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF0F0F0.toInt())
    private val rippleColor = ColorStateList.valueOf((textColor and 0x00FFFFFF) or 0x33000000)

    private val shadowMargin = dp(16)
    private val barHeight = dp(52)
    private val barItemWidth = dp(52)
    private val barPaddingH = dp(6)
    private val rowHeight = dp(52)
    private val listPaddingV = dp(8)
    private val listMinWidth = dp(176)
    private val listMaxWidth = dp(280)
    private val listRadius = dp(26).toFloat()

    private val morphInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // #endregion

    // #region views

    private val panel = FrameLayout(context)
    private val barPanel = LinearLayout(context)
    private val listPanel = FrameLayout(context)
    private val listScroll = ScrollView(context)
    private val listRows = LinearLayout(context)
    private val overflowButton = FrameLayout(context)
    private val moreIcon = ImageView(context)
    private val backIcon = ImageView(context)

    // #endregion

    // #region sizes and state

    private var barW = 0
    private var listW = 0
    // the overflow menu: all its rows, what of them is shown, the whole of it
    private var rowsH = 0
    private var listViewportH = 0
    private var listH = 0
    private var unionW = 0
    private var unionH = 0
    private var hasOverflow = false
    private var expandUp = true
    // 0: the pill, 1: the overflow menu
    private var progress = 0f
    private var overflowOpen = false
    private var morphAnimator: ValueAnimator? = null
    private val outlineRect = Rect()
    private var outlineRadius = 0f

    // #endregion

    init {
        clipChildren = false
        clipToPadding = false
        visibility = GONE

        panel.setBackgroundColor(bgColor)
        panel.elevation = dp(8).toFloat()
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(outlineRect, outlineRadius)
            }
        }
        panel.clipToOutline = true
        // takes the taps between the items, which would go to the editor below
        panel.isClickable = true
        addView(panel, LayoutParams(0, 0).apply {
            setMargins(shadowMargin, shadowMargin, shadowMargin, shadowMargin)
        })

        barPanel.orientation = LinearLayout.HORIZONTAL
        barPanel.gravity = Gravity.CENTER_VERTICAL

        listRows.orientation = LinearLayout.VERTICAL
        listScroll.isVerticalScrollBarEnabled = true
        listScroll.isVerticalFadingEdgeEnabled = true
        listScroll.setFadingEdgeLength(dp(16))
        listScroll.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        listScroll.addView(listRows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listPanel.addView(listScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel.addView(barPanel)
        panel.addView(listPanel)
        panel.addView(overflowButton)

        moreIcon.setImageResource(R.drawable.ic_floating_bar_more)
        backIcon.setImageResource(R.drawable.ic_floating_bar_back)
        moreIcon.imageTintList = ColorStateList.valueOf(iconColor)
        backIcon.imageTintList = ColorStateList.valueOf(iconColor)
        overflowButton.addView(moreIcon, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        overflowButton.addView(backIcon, LayoutParams(dp(24), dp(24), Gravity.CENTER))
        overflowButton.background = borderlessRipple()
        overflowButton.contentDescription = context.getString(R.string.floating_bar_more_options)
        overflowButton.setOnClickListener { setOverflowOpen(!overflowOpen) }
    }

    // #region API

    /** The items, those of the bar and those of the overflow menu, in their order. */
    fun setItems(items: List<Item>) {
        morphAnimator?.cancel()
        barPanel.removeAllViews()
        listRows.removeAllViews()

        val (overflowItems, primary) = items.partition { it.overflow }
        hasOverflow = overflowItems.isNotEmpty()
        for (item in primary) {
            val w = if (item.icon != null) barItemWidth else ViewGroup.LayoutParams.WRAP_CONTENT
            barPanel.addView(createBarItem(item), LinearLayout.LayoutParams(w, barHeight))
        }
        for (item in overflowItems) {
            listRows.addView(createRow(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        }
        overflowButton.visibility = if (hasOverflow) VISIBLE else GONE
        measureSizes()
    }

    /**
     * Shows the bar over [anchor] (the selection, in the coordinates of [host], usually
     * the decor view), or under it if there is no room above.
     *
     * [gapAbove] and [gapBelow]: the room kept clear between the anchor and the bar, over
     * it and under it. The editor gives one line height, as its magnifier has, and more
     * where a selection handle reaches further out of its row (the knob of the start
     * handle above the row, the end and insertion handles below it): the bar never
     * covers them. By default 8dp above and 28dp below (room for handles under the row).
     */
    fun show(host: ViewGroup, anchor: Rect, gapAbove: Int = dp(8), gapBelow: Int = dp(28)) {
        if (barPanel.childCount == 0 && !hasOverflow) return
        if (parent !== host) {
            (parent as? ViewGroup)?.removeView(this)
            host.addView(this, FrameLayout.LayoutParams(0, 0))
        }

        val insets = host.systemInsets()
        val side = dp(8)
        val topLimit = insets.top + side
        val bottomLimit = host.height - insets.bottom - side

        // vertically: over the selection if there is room, under it otherwise
        val barTop = barTop(anchor.top, anchor.bottom, barHeight, topLimit, bottomLimit, gapAbove, gapBelow)
        // the menu opens up or down from the bar, where there is room for it: as many
        // rows as fit there (the system's rule), the others scroll
        val roomUp = barTop + barHeight - topLimit
        val roomDown = bottomLimit - barTop
        val up = fitOverflow(roomUp, roomDown)

        // horizontally: centered on the selection, inside the screen; the menu is
        // aligned to the right of the pill
        val minRight = insets.left + side + unionW
        val maxRight = host.width - insets.right - side
        val barRight = (anchor.centerX() + barW / 2).coerceAtMost(maxRight).coerceAtLeast(min(minRight, maxRight))

        applyDirection(up)
        overflowOpen = false
        listScroll.scrollTo(0, 0)
        applyProgress(0f)
        settleVisibility()

        val unionTop = if (up) barTop + barHeight - unionH else barTop
        (layoutParams as FrameLayout.LayoutParams).apply {
            width = unionW + shadowMargin * 2
            height = unionH + shadowMargin * 2
            gravity = Gravity.TOP or Gravity.START
        }
        translationX = (barRight - unionW - shadowMargin).toFloat()
        translationY = (unionTop - shadowMargin).toFloat()
        requestLayout()

        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
        }
        animate().alpha(1f).setDuration(150).start()
    }

    /** Fades the bar out; it stays in its host, to be shown again. */
    fun hide() {
        morphAnimator?.cancel()
        if (visibility != VISIBLE) return
        animate().cancel()
        animate().alpha(0f).setDuration(100).withEndAction { visibility = GONE }.start()
    }

    /** Takes the bar away from its host at once. */
    fun dismiss() {
        morphAnimator?.cancel()
        animate().cancel()
        visibility = GONE
        (parent as? ViewGroup)?.removeView(this)
    }

    /** Opens the overflow menu, or closes it back into the pill. */
    fun setOverflowOpen(open: Boolean) {
        if (!hasOverflow || open == overflowOpen) return
        overflowOpen = open
        overflowButton.contentDescription = context.getString(
            if (open) R.string.floating_bar_back else R.string.floating_bar_more_options
        )
        morphAnimator?.cancel()
        if (open) listScroll.scrollTo(0, 0)

        // both are shown while it runs, the alpha and the outline decide what is seen
        barPanel.visibility = VISIBLE
        listPanel.visibility = VISIBLE

        val from = progress
        val to = if (open) 1f else 0f
        val fullDuration = if (open) 320L else 260L
        morphAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = (fullDuration * abs(to - from)).toLong().coerceAtLeast(1L)
            interpolator = morphInterpolator
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) settleVisibility()
                    // what scrolls shows it for a moment, as a list does
                    if (!canceled && overflowOpen && rowsH > listViewportH) listScroll.awakenScrollBarsCompat()
                }
            })
            start()
        }
    }

    // #endregion

    // #region animation

    // f: 0 the pill, 1 the overflow menu
    private fun applyProgress(f: Float) {
        progress = f

        // the height leads the width a little: it grows up, then to the right
        val hf = clamp(f / 0.85f)
        val wf = clamp((f - 0.1f) / 0.9f)
        val w = lerp(barW, listW, wf)
        val h = lerp(barHeight, listH, hf)
        val left = unionW - w
        if (expandUp) outlineRect.set(left, unionH - h, unionW, unionH)
        else outlineRect.set(left, 0, unionW, h)
        outlineRadius = lerpF(barHeight / 2f, listRadius, f)
        panel.invalidateOutline()

        // the items of the bar fade out first, the menu fades in after (and moves a little)
        barPanel.alpha = 1f - clamp(f / 0.45f)
        val listAlpha = clamp((f - 0.35f) / 0.65f)
        listPanel.alpha = listAlpha
        listPanel.translationY = (if (expandUp) 1 else -1) * dp(10) * (1f - listAlpha)

        // ⋮ moves from the right of the pill to the bottom (or top) left of the menu,
        // and turns into ←
        overflowButton.translationX = -(listW - barItemWidth) * f
        moreIcon.alpha = 1f - clamp(f / 0.5f)
        moreIcon.rotation = 90f * f
        backIcon.alpha = clamp((f - 0.5f) / 0.5f)
        backIcon.rotation = -90f * (1f - f)
    }

    private fun settleVisibility() {
        barPanel.visibility = if (overflowOpen) INVISIBLE else VISIBLE
        listPanel.visibility = if (overflowOpen && hasOverflow) VISIBLE else INVISIBLE
    }

    // #endregion

    // #region measure and layout

    private fun measureSizes() {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        val barHSpec = MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
        var itemsW = 0
        for (i in 0 until barPanel.childCount) {
            val child = barPanel.getChildAt(i)
            val lpW = child.layoutParams.width
            val wSpec = if (lpW > 0) MeasureSpec.makeMeasureSpec(lpW, MeasureSpec.EXACTLY) else unspecified
            child.measure(wSpec, barHSpec)
            itemsW += child.measuredWidth
        }
        val buttonW = if (hasOverflow) barItemWidth else 0
        barPanel.setPadding(barPaddingH, 0, barPaddingH + buttonW, 0)
        barW = barPaddingH * 2 + itemsW + buttonW

        if (hasOverflow) {
            val rowHSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
            val rowWSpec = MeasureSpec.makeMeasureSpec(listMaxWidth, MeasureSpec.AT_MOST)
            var widest = 0
            for (i in 0 until listRows.childCount) {
                val child = listRows.getChildAt(i)
                child.measure(rowWSpec, rowHSpec)
                widest = max(widest, child.measuredWidth)
            }
            listW = widest.coerceIn(listMinWidth, listMaxWidth)
            rowsH = listRows.childCount * rowHeight
        } else {
            listW = barW
            rowsH = 0
        }
        // until show() knows the room: as high as the rule allows
        setListViewport(visibleRowsHeight(Int.MAX_VALUE))
    }

    /**
     * How high the shown part of the rows is when there is room for [maxRows] of them: as
     * the system's floating toolbar has it, at most [maxOverflowRows] rows, at least two
     * (if there are), and half a row more when not all of them are shown, so that it is
     * seen that the menu scrolls. Never more than the rows themselves.
     */
    private fun visibleRowsHeight(maxRows: Int): Int =
        visibleRowsHeight(listRows.childCount, rowHeight, maxRows, maxOverflowRows)

    // The overflow menu for the room up and down from the bar: it opens up if it has all
    // the room it wants there, else down if it has it there, else where there is more;
    // its shown rows are cut to what fits. Returns whether it opens up.
    private fun fitOverflow(roomUp: Int, roomDown: Int): Boolean {
        if (!hasOverflow) {
            setListViewport(0)
            return true
        }
        // the room for rows: without the bar's row (the ← button) and the padding
        val fit = fitOverflow(listRows.childCount, rowHeight, maxOverflowRows, roomUp, roomDown, barHeight + listPaddingV)
        setListViewport(fit.viewport)
        return fit.up
    }

    private fun setListViewport(viewport: Int) {
        listViewportH = viewport
        listH = if (hasOverflow) listViewportH + listPaddingV + barHeight else barHeight
        unionW = max(barW, listW)
        unionH = max(barHeight, listH)
        (panel.layoutParams as LayoutParams).apply { width = unionW; height = unionH }
        panel.requestLayout()
    }

    private fun applyDirection(up: Boolean) {
        expandUp = up
        val v = if (up) Gravity.BOTTOM else Gravity.TOP
        barPanel.layoutParams = LayoutParams(barW, barHeight, v or Gravity.END)
        listPanel.layoutParams = LayoutParams(listW, listH, v or Gravity.END)
        overflowButton.layoutParams = LayoutParams(barItemWidth, barHeight, v or Gravity.END)
        // the row of the ← button is below the rows when the menu opens up, above otherwise
        listPanel.setPadding(
            0, if (up) listPaddingV else barHeight,
            0, if (up) barHeight else listPaddingV
        )
    }

    // #endregion

    // #region touch

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = (ev.x - panel.left).toInt()
            val y = (ev.y - panel.top).toInt()
            // outside what is seen: the touch goes on to what is below (the editor)
            if (visibility != VISIBLE || !outlineRect.contains(x, y)) return false
        }
        // no taps while it changes shape
        if (morphAnimator?.isRunning == true) return true
        return super.dispatchTouchEvent(ev)
    }

    // #endregion

    // #region items

    private fun createBarItem(item: Item): View {
        val view: View = if (item.icon != null) {
            ImageView(context).apply {
                setImageDrawable(item.icon.mutate().also { if (item.tintIcon) it.setTint(iconColor) })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = (barItemWidth - dp(24)) / 2
                setPadding(p, (barHeight - dp(24)) / 2, p, (barHeight - dp(24)) / 2)
                contentDescription = item.title
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = item.title
            }
        } else {
            TextView(context).apply {
                text = item.title
                setTextColor(textColor)
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = barItemWidth
                setPadding(dp(12), 0, dp(12), 0)
            }
        }
        view.background = borderlessRipple()
        view.setOnClickListener { onItemClick?.invoke(item) }
        return view
    }

    private fun createRow(item: Item) = TextView(context).apply {
        text = item.title
        setTextColor(textColor)
        textSize = 15f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(20), 0, dp(24), 0)
        item.icon?.let { drawable ->
            val icon = drawable.mutate()
            if (item.tintIcon) icon.setTint(iconColor)
            val size = dp(22)
            icon.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(16)
        }
        background = RippleDrawable(rippleColor, null, ColorDrawable(Color.WHITE))
        setOnClickListener { onItemClick?.invoke(item) }
    }

    private fun borderlessRipple() = RippleDrawable(rippleColor, null, null).apply { radius = dp(22) }

    // #endregion

    // #region helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun clamp(v: Float) = v.coerceIn(0f, 1f)
    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).roundToInt()
    private fun lerpF(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun themeColor(attr: Int, fallback: Int): Int = MaterialColors.getColor(context, attr, fallback)

    // the scroll bar of the menu shows for a moment (awakenScrollBars is protected)
    private fun ScrollView.awakenScrollBarsCompat() {
        val y = scrollY
        scrollTo(0, y + 1)
        scrollTo(0, y)
    }

    private fun View.systemInsets(): Rect {
        val insets = rootWindowInsets ?: return Rect()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
            )
            Rect(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
    }

    // #endregion

    /** Where the overflow menu opens ([up] from the bar or down), and how high its rows show. */
    internal data class OverflowFit(val up: Boolean, val viewport: Int)

    companion object {
        // as the system's floating toolbar
        const val MAX_OVERFLOW_ROWS = 4
        const val MIN_OVERFLOW_ROWS = 2

        /**
         * The top of a bar [barHeight] high by an anchor from [anchorTop] to
         * [anchorBottom], in the room from [topLimit] to [bottomLimit]: [gapAbove] over
         * the anchor if it fits there, else [gapBelow] under it, kept inside the room
         * (where there is room on neither side, it may then come over the anchor).
         */
        internal fun barTop(
            anchorTop: Int, anchorBottom: Int, barHeight: Int,
            topLimit: Int, bottomLimit: Int, gapAbove: Int, gapBelow: Int
        ): Int {
            val above = anchorTop - gapAbove - barHeight
            if (above >= topLimit) return above
            return min(anchorBottom + gapBelow, bottomLimit - barHeight).coerceAtLeast(topLimit)
        }

        /**
         * How high [count] rows of [rowHeight] show when there is room for [maxRows] of
         * them: at most [maxOverflowRows], at least [MIN_OVERFLOW_ROWS] (if there are),
         * and half a row more when not all of them show (what scrolls shows that it does).
         */
        internal fun visibleRowsHeight(count: Int, rowHeight: Int, maxRows: Int, maxOverflowRows: Int): Int {
            if (count == 0) return 0
            val shown = min(maxOverflowRows, min(max(MIN_OVERFLOW_ROWS, maxRows), count))
            val peek = if (shown < count) rowHeight / 2 else 0
            return shown * rowHeight + peek
        }

        /**
         * The overflow menu of [count] rows for the room [roomUp] and [roomDown] from the
         * bar ([chrome]: what of it is not rows, the ← row and the padding): it opens up if
         * it has all the room it wants there, else down if it has it there, else where
         * there is more; its rows are cut to what fits (one at least).
         */
        internal fun fitOverflow(
            count: Int, rowHeight: Int, maxOverflowRows: Int, roomUp: Int, roomDown: Int, chrome: Int
        ): OverflowFit {
            val wanted = visibleRowsHeight(count, rowHeight, Int.MAX_VALUE, maxOverflowRows)
            val up = when {
                roomUp - chrome >= wanted -> true
                roomDown - chrome >= wanted -> false
                else -> roomUp >= roomDown
            }
            val room = (if (up) roomUp else roomDown) - chrome
            val fits = visibleRowsHeight(count, rowHeight, max(1, room / rowHeight), maxOverflowRows)
            return OverflowFit(up, min(fits, max(rowHeight, room)))
        }
    }
}
