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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The list of completions under the cursor (above it when there is no room below): a
 * kind badge, the label (what was typed of it in bold), and a detail on the right. The
 * label comes first: it is shown whole as far as the row allows, and the detail (a
 * type, a signature) only in the room that is left, if that is enough to be read. One
 * item is selected: a hardware keyboard moves through them and takes one (see
 * EditorView.showCompletions); a tap takes one.
 *
 * Like FloatingBar, it lives in the window's decor view, over everything. It knows
 * nothing of where the items come from.
 */
@SuppressLint("ViewConstructor")
class CompletionWindow(context: Context) : FrameLayout(context) {

    /**
     * One completion: its [label], an optional [detail] (a type, a signature), a
     * [kind] badge (a letter or two) in [kindColor], and how much of the label matches
     * what was typed ([matchLength], shown in bold). [tag]: whatever the caller needs back.
     */
    data class Item(
        val label: String,
        val detail: String? = null,
        val kind: String = "",
        val kindColor: Int = 0,
        val matchLength: Int = 0,
        val tag: Any? = null,
        /** The chars of the label that match what was typed (a fuzzy match), shown in bold; instead of [matchLength]. */
        val matchIndices: IntArray? = null,
    )

    /** Called when an item is taken (tapped, or with the keyboard). */
    var onPick: ((Item) -> Unit)? = null

    /**
     * Called with true when the window appears and with false when it starts to go (not
     * when its fade-out ends): a row of keys of the app can follow it (see
     * EditorView.onCompletionsShownChanged).
     */
    var onShownChanged: ((Boolean) -> Unit)? = null

    // shown, and not on its way out: the fade-out of dismiss leaves the view in place
    // for a moment, and a key in that moment must not take an item of a list that went
    private var shown = false

    val isShowing: Boolean get() = shown && parent != null && visibility == VISIBLE

    // #region look

    private val surface = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2D30.toInt())
    private val textColor = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val detailColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFA0A0A0.toInt())
    private val selectedColor = themeColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFF3D4A5C.toInt())
    // what of a label matches what was typed, as blink.cmp shows it: in the accent color, bold
    // (colorPrimary is AppCompat's attribute)
    private val matchColor = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())

    private val rowHeight = dp(34)
    // as wide as the items need, from this (not narrower: a short list would look lost) to
    // the width it always had
    private val minWidth = dp(200)
    private val maxWidth = dp(340)
    // the detail (a type, a signature) asks for at most this part of the width when the
    // list is sized; in a row it gets what the label leaves (see Row.bind)
    private val detailShare = 0.45f
    // a detail narrower than this is not worth showing: left out
    private val minDetailWidth = dp(48)
    // the width of a row, as the list was last sized (see place)
    private var rowWidth = 0
    // the sizes of the text of a row, in sp
    private val labelTextSize = 13f
    private val detailTextSize = 11f
    private val badgeTextSize = 10f
    // to measure what the rows show, as they show it
    private val labelPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(labelTextSize)
        typeface = Typeface.MONOSPACE
    }
    private val detailPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(detailTextSize) }
    private val badgePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(badgeTextSize)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val maxRows = 6
    private val margin = dp(8)
    private val radius = dp(12).toFloat()
    private val enterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    // #endregion

    private val card = FrameLayout(context)
    private val list = ListView(context)
    private var items: List<Item> = emptyList()
    private var selected = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row(context)
            row.bind(items[position], position == selected)
            return row
        }
    }

    init {
        visibility = GONE
        clipChildren = false
        card.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(surface)
        }
        card.elevation = dp(6).toFloat()
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        card.clipToOutline = true
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.selector = GradientDrawable()
        list.isVerticalScrollBarEnabled = true
        list.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { onPick?.invoke(it) }
        }
        card.addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(0, 0))
    }

    // #region API

    /**
     * Shows [items] at the cursor, [anchor] being its row in [host] (the decor view):
     * under it, or above it when there is no room below (the keyboard, and a bar on top of
     * it whose top in [host] is [bottomLimit]: the list keeps clear of both); with fewer
     * rows where neither has room for all, never over the row (see PopupPlacement). The
     * first item is selected.
     */
    fun show(host: ViewGroup, anchor: Rect, items: List<Item>, bottomLimit: Int = Int.MAX_VALUE) {
        this.items = items
        selected = 0
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        val appearing = !isShowing
        if (parent !== host) {
            (parent as? ViewGroup)?.removeView(this)
            host.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        place(host, anchor, bottomLimit)
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            shown = true
            onShownChanged?.invoke(true)
        }
        if (appearing) {
            alpha = 0f
            card.translationY = -dp(4).toFloat()
            animate().alpha(1f).setDuration(140L).setInterpolator(enterInterpolator).start()
            card.animate().translationY(0f).setDuration(180L).setInterpolator(enterInterpolator).start()
        } else {
            alpha = 1f
        }
    }

    /** Follows the cursor to [anchor] (the text scrolled), keeping the items. */
    fun moveTo(host: ViewGroup, anchor: Rect, bottomLimit: Int = Int.MAX_VALUE) {
        if (isShowing) place(host, anchor, bottomLimit)
    }

    /** Takes the window away. */
    fun dismiss() {
        if (!isShowing) return
        shown = false
        onShownChanged?.invoke(false)
        animate().cancel()
        animate().alpha(0f).setDuration(90L).withEndAction {
            visibility = GONE
            (parent as? ViewGroup)?.removeView(this)
        }.start()
    }

    /** Moves the selection by [delta] items, wrapping around. */
    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        selected = Math.floorMod(selected + delta, items.size)
        adapter.notifyDataSetChanged()
        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition
        if (selected < first || selected > last) list.setSelection(selected)
    }

    /** Takes the selected item. Returns whether there was one. */
    fun pickSelected(): Boolean {
        val item = items.getOrNull(selected) ?: return false
        onPick?.invoke(item)
        return true
    }

    // #endregion

    private fun place(host: ViewGroup, anchor: Rect, bottomLimit: Int) {
        val insets = host.systemInsets()
        val widest = min(maxWidth, host.width - insets.left - insets.right - margin * 2)
        val width = contentWidth(widest).coerceIn(min(minWidth, widest), widest)
        rowWidth = width
        val wanted = min(items.size, maxRows) * rowHeight
        val top = insets.top + margin
        val bottom = min(host.height - insets.bottom, bottomLimit) - margin
        // under the cursor, else above it, else where there is more room with fewer rows
        // (they scroll): never over the line being typed
        val (y, height) = PopupPlacement.vertical(
            anchor.top, anchor.bottom, wanted, top, bottom,
            gapAbove = dp(4), gapBelow = dp(4), preferAbove = false, step = rowHeight,
        )
        // the labels start about where the word being typed does
        val x = (anchor.left - dp(40)).coerceIn(insets.left + margin, max(insets.left + margin, host.width - insets.right - margin - width))
        (card.layoutParams as LayoutParams).apply {
            this.width = width
            this.height = height
            leftMargin = x
            topMargin = y
            gravity = Gravity.TOP or Gravity.START
        }
        card.requestLayout()
        card.invalidateOutline()
    }

    // How wide the rows want to be: the widest of badge, label and detail (the detail no
    // more than its share of [widest]), with the paddings and margins of a row.
    private fun contentWidth(widest: Int): Int {
        var most = 0
        val detailCap = widest * detailShare
        for (item in items) {
            val badge = if (item.kind.isEmpty()) dp(22).toFloat() else max(dp(22).toFloat(), badgePaint.measureText(item.kind) + dp(8))
            val label = labelPaint.measureText(item.label)
            val detail = item.detail?.takeIf { it.isNotEmpty() }?.let { min(detailPaint.measureText(it), detailCap) + dp(8) } ?: 0f
            val row = dp(10) + badge + dp(10) + label + detail + dp(12)
            if (row > most) most = kotlin.math.ceil(row).toInt()
        }
        return most
    }

    // a touch outside the list goes on to what is below (the editor)
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = Rect(card.left, card.top, card.right, card.bottom)
            if (!r.contains(ev.x.toInt(), ev.y.toInt())) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    // One row: the badge, the label, the detail.
    private inner class Row(context: Context) : LinearLayout(context) {
        private val badge = TextView(context)
        private val label = TextView(context)
        private val detail = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListLayoutParams(rowHeight)
            setPadding(dp(10), 0, dp(12), 0)
            badge.gravity = Gravity.CENTER
            badge.textSize = badgeTextSize
            badge.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            badge.minWidth = dp(22)
            badge.setPadding(dp(4), dp(1), dp(4), dp(1))
            label.textSize = labelTextSize
            label.typeface = Typeface.MONOSPACE
            label.setTextColor(textColor)
            label.isSingleLine = true
            label.ellipsize = TextUtils.TruncateAt.END
            detail.textSize = detailTextSize
            detail.setTextColor(detailColor)
            detail.isSingleLine = true
            detail.ellipsize = TextUtils.TruncateAt.END
            detail.gravity = Gravity.END
            addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // the label takes what the detail leaves; the detail its own width, up to its share
            addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            addView(detail, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        }

        fun bind(item: Item, isSelected: Boolean) {
            setBackgroundColor(if (isSelected) selectedColor else 0)
            badge.text = item.kind
            badge.visibility = if (item.kind.isEmpty()) INVISIBLE else VISIBLE
            badge.setTextColor(item.kindColor)
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor((item.kindColor and 0x00FFFFFF) or 0x33000000)
            }
            val text = SpannableString(item.label)
            val indices = item.matchIndices
                ?: IntArray(item.matchLength.coerceIn(0, item.label.length)) { it }
            highlight(text, indices)
            label.text = text
            // The label first: the detail only gets what it leaves of the row, and is left
            // out when that is too little to be read (the label is then ellipsized only if
            // it does not fit the row by itself).
            val room = detailRoom(item)
            detail.text = item.detail.orEmpty()
            if (room >= minDetailWidth) {
                detail.maxWidth = room
                detail.visibility = VISIBLE
            } else {
                detail.visibility = GONE
            }
        }

        // What the row leaves for the detail of [item] once its badge and its whole label
        // are in; 0 without a detail.
        private fun detailRoom(item: Item): Int {
            if (item.detail.isNullOrEmpty() || rowWidth <= 0) return 0
            val badgeWidth = if (item.kind.isEmpty()) dp(22).toFloat()
                else max(dp(22).toFloat(), badgePaint.measureText(item.kind) + dp(8))
            // a little more for the label: what matches is drawn bold
            val labelWidth = labelPaint.measureText(item.label) + dp(4)
            val used = paddingLeft + paddingRight + badgeWidth + dp(10) + labelWidth + dp(8)
            return (rowWidth - used).toInt().coerceAtLeast(0)
        }
    }

    // the matched chars in the accent color and bold, a run of them as one span
    private fun highlight(text: SpannableString, indices: IntArray) {
        var i = 0
        while (i < indices.size) {
            val start = indices[i]
            var end = start + 1
            while (i + 1 < indices.size && indices[i + 1] == end) {
                end++
                i++
            }
            if (start >= 0 && end <= text.length) {
                text.setSpan(ForegroundColorSpan(matchColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i++
        }
    }

    @Suppress("FunctionName")
    private fun AbsListLayoutParams(height: Int) =
        android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private fun themeColor(attr: Int, fallback: Int): Int = MaterialColors.getColor(context, attr, fallback)

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
}
