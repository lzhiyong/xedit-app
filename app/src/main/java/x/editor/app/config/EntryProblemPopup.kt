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
package x.editor.app.config

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher

import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator

import x.editor.app.R
import x.editor.app.view.MaxHeightScrollView

/**
 * What an entry of a sheet is about, in a small window by its row (popup_entry_problem):
 * LanguageServersSheet shows it on a tap on any server, with how it is doing and what may
 * be done (running: how; not running: what starts it; not installed: where to install it;
 * off: "enabled"; failed: why), and whose port another server has too, whose extensions go
 * to an earlier server, or whose file is not right; FormattingSheet on a tap on a language
 * whose rules file is not right (only then), and by its buttons on Preview (formatting, then
 * done; or that it cannot be done with the rules of another language, or of one that no
 * server formats); ThemeSheet by its buttons on Preview (rendering, then done);
 * ToolchainSheet on a tap on a toolchain (what it says of its version, a ring before the text
 * while it is asked; how far it is, a bar over the text, while it is being installed), by the
 * list on Update tapped again and by Save once a save installs something (how far all of it
 * is, a bar), when an install or update fails (why, in the same window), by the footer's ⋯
 * (all of it) and by the icon of the empty list (why it is empty). A title with a dot of
 * [color], Copy and Close (top right), the text (it scrolls within a height of its own, and
 * can be selected), and before it, on its first line, a small ring of that colour while what
 * it says is being found out (how far it is, only when given), or over it a bar of that
 * colour ([show]: bar).
 *
 * It is kept small, so as not to be in the way: no wider than [MAX_WIDTH_DP] (nor the row;
 * shown centred under a small anchor, an icon, it is [MAX_WIDTH_DP] wide),
 * and its text gets at most [MAX_TEXT_HEIGHT_DP] (and a third of the screen), less if the
 * larger of the room under the row and over it is less, so the whole window is on the
 * screen and a long text scrolls to its end. Where there is no room under the row, it goes
 * over it; shown above ([show]: above: what is under the anchor is what the user is to see,
 * Preview and its preview), over it, unless it fits only under it. It does not take the focus: a tap outside closes it and still goes where it was
 * made (another row, the list scrolled, a long press), Back ([back], the sheet's) closes it
 * alone, and so does Close. Shown sticky, a tap outside does not close it (it still goes on
 * to what is there): only Close or Back do, the user's own doing (an install going on, which
 * the user is to see to its end). A tap on the row it is by does not close it: [show] by the row it is shown by
 * changes what it says in place (it is not closed and opened again). One at a time ([show]
 * by another row closes the one shown).
 *
 * [compact]: smaller still (the editor's window of the toolchain, over the code): narrower,
 * its text smaller and a few lines high, no Copy. [draggable]: not dropped down from its
 * anchor but laid over the screen, at [position] if it was dragged there (else under the
 * anchor, at its end), and moved by its head (the grip shown before its dot); a double tap
 * on the head puts it back. It stays inside what is visible of the window (the keyboard
 * shown, above it, as it is shown or changed next).
 */
internal class EntryProblemPopup(
    private val activity: Activity,
    back: OnBackPressedDispatcher? = null,
    private val compact: Boolean = false,
    private val draggable: Boolean = false,
) {

    /** Where it was dragged to ([draggable]): its top left, on the window; null: by its anchor. */
    var position: Point? = null

    /** Told where it was dragged to (null: put back by a double tap), as the drag ends. */
    var onMoved: ((Point?) -> Unit)? = null

    // where a draggable one is now (its top left, on the window)
    private var placedX = 0
    private var placedY = 0

    private var window: PopupWindow? = null

    // the row it is by, and what it says (Copy copies it), while it is shown
    private var anchor: View? = null
    private var title: CharSequence = ""
    private var text: CharSequence = ""
    private var color: Int = 0
    private var progress: Int = NO_PROGRESS
    // [progress] as a bar over the text, not a ring before it
    private var bar = false
    // the text follows its end as it grows (output coming in), as the console does
    private var follow = false
    private var action: PopupAction? = null
    // shown under the middle of its anchor (an icon), not at its end
    private var centered = false
    // over its anchor rather than under it: asked for, and where it is now (fit)
    private var above = false
    private var placedAbove = false

    // Back while it is shown: it closes, not the sheet under it
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = dismiss()
    }

    init {
        back?.addCallback(backCallback)
    }

    /**
     * Shows [text] under [title] by [anchor] (a row of the list). Shown by it already (a tap
     * on the row it is by, or what it says changed): what it says is changed in place, the
     * window not closed and opened again; by another row, that one's replaces it.
     *
     * [progress]: how far what it is about is, 0 to 100, as a small ring before the text
     * ([INDETERMINATE]: turning, not known; [NO_PROGRESS]: no ring). [sticky]: a tap outside does not
     * close it, only Close or Back do (kept from when it is opened). [centered]: under the
     * middle of [anchor] (an icon), as wide as it may be, not at its end as wide as it.
     * [bar]: [progress] as a bar over the text, not a ring before it. [above]: over [anchor],
     * not under it (kept from when it is opened). [follow]: the text goes down with what is
     * added at its end (output coming in), as the console does: scrolled up by hand it
     * stays, scrolled back to the end it follows again.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(
        anchor: View,
        title: CharSequence,
        text: CharSequence,
        color: Int,
        progress: Int = NO_PROGRESS,
        sticky: Boolean = false,
        centered: Boolean = false,
        action: PopupAction? = null,
        bar: Boolean = false,
        above: Boolean = false,
        follow: Boolean = false,
    ) {
        val shown = window
        if (shown != null && shown.isShowing && anchor === this.anchor && anchor.isAttachedToWindow) {
            update(shown, anchor, title, text, color, progress, bar, action, follow)
            return
        }
        dismiss()
        if (!anchor.isAttachedToWindow) return
        this.action = action
        val density = activity.resources.displayMetrics.density
        // in a frame, so that the card's margin holds (room for its shadow)
        val content = FrameLayout(activity)
        LayoutInflater.from(activity).inflate(R.layout.popup_entry_problem, content, true)
        content.findViewById<View>(R.id.problem_copy).setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(this.title, this.text.toString()))
            Toast.makeText(activity, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
        }
        // closed by the user (the only way, but Back, when it is sticky)
        content.findViewById<View>(R.id.problem_close).setOnClickListener { dismiss() }
        if (compact) compactify(content)
        if (draggable) attachDrag(content)
        fill(content, title, text, color, progress, bar, follow)
        this.centered = centered
        this.above = above
        val width = widthBy(anchor)
        val height = if (draggable) fitFloating(anchor, content, width) else fit(anchor, content, width)
        this.anchor = anchor
        // not focusable: the sheet under it stays usable, a tap outside closes it and goes
        // on to what is there; Back is the sheet's, which closes it first (backCallback)
        window = PopupWindow(content, width, height, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 0f
            if (sticky) {
                // a tap outside goes on to what is there, and the window stays: Close, Back
                isOutsideTouchable = false
            } else {
                isOutsideTouchable = true
                // a tap on the row it is by does not close it (the tap goes on to the row,
                // which shows it again: in place); any other outside it does
                setTouchInterceptor { view, event -> isOnAnchor(view, event) }
            }
            setOnDismissListener {
                if (window === this) {
                    window = null
                    this@EntryProblemPopup.anchor = null
                    backCallback.isEnabled = false
                }
            }
            if (draggable) {
                // laid over the screen where it was dragged to, else under its anchor at its end
                val at = floatingPlace(anchor, width, height)
                placedX = at.x
                placedY = at.y
                showAtLocation(anchor, Gravity.TOP or Gravity.START, at.x, at.y)
            } else {
                // under the row, at its end (above it, where there is no room under it); the
                // card's own margin keeps it off the row
                showAsDropDown(anchor, xOffset(anchor, width), yOffset(anchor, height))
            }
        }
        backCallback.isEnabled = true
    }

    /** Shows [info] by [anchor]: its title, its four parts, the colour of its level, its button. */
    fun show(anchor: View, info: EntryInfo, sticky: Boolean = false, centered: Boolean = false) {
        show(anchor, info.title(), info.text(activity), info.level.color(activity), info.progress, sticky, centered, info.action)
    }

    // What it says changed, shown in the same window (no closing and opening): its height
    // and place by [anchor] worked out again. Its button is what it is now (the same label,
    // perhaps another thing to do).
    private fun update(
        window: PopupWindow, anchor: View, title: CharSequence, text: CharSequence, color: Int, progress: Int,
        bar: Boolean, action: PopupAction?, follow: Boolean,
    ) {
        val sameAction = action?.label?.toString() == this.action?.label?.toString()
        this.action = action
        bindAction(window.contentView)
        if (title.toString() == this.title.toString() && text.toString() == this.text.toString() &&
            color == this.color && progress == this.progress && bar == this.bar && follow == this.follow && sameAction
        ) return
        val content = window.contentView
        fill(content, title, text, color, progress, bar, follow)
        val width = widthBy(anchor)
        if (draggable) {
            // where it is (dragged there or not): only its size changes, kept on the screen
            val height = fitFloating(anchor, content, width)
            val at = clamp(anchor, placedX, placedY, width, height)
            placedX = at.x
            placedY = at.y
            window.update(at.x, at.y, width, height)
            return
        }
        val height = fit(anchor, content, width)
        window.update(anchor, xOffset(anchor, width), yOffset(anchor, height), width, height)
    }

    // its dot, title and text, and the ring before the text (while it is being found out)
    // or the bar over it ([bar]); the text following its end ([follow])
    private fun fill(
        content: View, title: CharSequence, text: CharSequence, color: Int, progress: Int, bar: Boolean, follow: Boolean,
    ) {
        this.title = title
        this.text = text
        this.color = color
        this.progress = progress
        this.bar = bar
        this.follow = follow
        content.findViewById<MaxHeightScrollView>(R.id.problem_scroll).setFollowsEnd(follow)
        val ring = content.findViewById<CircularProgressIndicator>(R.id.problem_progress)
        val line = content.findViewById<LinearProgressIndicator>(R.id.problem_bar)
        ring.setIndicatorColor(color)
        line.setIndicatorColor(color)
        showProgress(ring, if (bar) NO_PROGRESS else progress)
        showProgress(line, if (bar) progress else NO_PROGRESS)
        content.findViewById<View>(R.id.problem_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        content.findViewById<TextView>(R.id.problem_title).text = title
        content.findViewById<TextView>(R.id.problem_text).text = text
        bindAction(content)
    }

    // [indicator] as [progress] says: gone, turning, or how far
    private fun showProgress(indicator: BaseProgressIndicator<*>, progress: Int) {
        indicator.apply {
            when {
                progress == NO_PROGRESS -> visibility = View.GONE
                progress == INDETERMINATE -> if (!isIndeterminate || visibility != View.VISIBLE) {
                    // (not switched while it is shown)
                    visibility = View.INVISIBLE
                    isIndeterminate = true
                    visibility = View.VISIBLE
                }
                else -> {
                    if (isIndeterminate) {
                        // (not switched while it is shown)
                        visibility = View.INVISIBLE
                        isIndeterminate = false
                    }
                    visibility = View.VISIBLE
                    setProgressCompat(progress.coerceIn(0, 100), true)
                }
            }
        }
    }

    // the button at its foot: what [action] says, closing the window then doing it
    private fun bindAction(content: View) {
        val button = content.findViewById<MaterialButton>(R.id.problem_action)
        val action = action
        if (action == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        button.text = action.label
        button.visibility = View.VISIBLE
        // the colour of errors for what stops or drops something (Stop all); the button's own
        // colour (kept the first time) for the rest
        val own = button.getTag(R.id.problem_action) as? ColorStateList
            ?: button.textColors.also { button.setTag(R.id.problem_action, it) }
        button.setTextColor(
            if (action.danger) ColorStateList.valueOf(MaterialColors.getColor(button, androidx.appcompat.R.attr.colorError))
            else own
        )
        button.setOnClickListener {
            if (action.dismisses) dismiss()
            action.run()
        }
    }

    // [content] made smaller ([compact]): its text and title, its margins, its buttons; no Copy
    private fun compactify(content: View) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        content.findViewById<View>(R.id.problem_copy).visibility = View.GONE
        content.findViewById<TextView>(R.id.problem_title).setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_TITLE_SP)
        content.findViewById<TextView>(R.id.problem_text).setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_TEXT_SP)
        content.findViewById<View>(R.id.problem_body).setPadding(dp(10), dp(2), dp(4), dp(8))
        (content.findViewById<View>(R.id.problem_bar).layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin = dp(4)
        content.findViewById<MaterialButton>(R.id.problem_close).apply {
            minWidth = dp(32)
            minHeight = dp(32)
            minimumWidth = dp(32)
            minimumHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            iconSize = dp(16)
        }
        content.findViewById<MaterialButton>(R.id.problem_action).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_TITLE_SP)
            minHeight = dp(32)
            minimumHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin = dp(4)
        }
    }

    // [content]'s head drags the window ([draggable]): moved with the finger once it went
    // further than a touch slop, kept inside what is visible; where it ends is [position]
    // (and told, [onMoved]). A double tap on the head puts it back by its anchor.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(content: View) {
        content.findViewById<View>(R.id.problem_grip).visibility = View.VISIBLE
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var lastTap = 0L
        // (Copy and Close take their own touches: the rest of the head is what is held)
        content.findViewById<View>(R.id.problem_header).setOnTouchListener { _, event ->
            val shown = window ?: return@setOnTouchListener false
            val anchor = anchor ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = placedX
                    startY = placedY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        val at = clamp(anchor, startX + dx.toInt(), startY + dy.toInt(), shown.width, shown.height)
                        placedX = at.x
                        placedY = at.y
                        shown.update(at.x, at.y, -1, -1)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val at = Point(placedX, placedY)
                        position = at
                        onMoved?.invoke(at)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap <= ViewConfiguration.getDoubleTapTimeout()) {
                            // back to its own place
                            position = null
                            onMoved?.invoke(null)
                            val at = floatingPlace(anchor, shown.width, shown.height)
                            placedX = at.x
                            placedY = at.y
                            shown.update(at.x, at.y, -1, -1)
                            lastTap = 0L
                        } else {
                            lastTap = now
                        }
                    }
                    dragging = false
                }
            }
            true
        }
    }

    // What is visible of [anchor]'s window (not under the status bar, nor the keyboard), on the window
    private fun visibleBounds(anchor: View): Rect {
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val root = IntArray(2).also(anchor.rootView::getLocationOnScreen)
        frame.offset(-root[0], -root[1])
        return frame
    }

    // [x], [y] for a window [width] by [height], moved so that all of it is visible (as much
    // as can be, when it is larger)
    private fun clamp(anchor: View, x: Int, y: Int, width: Int, height: Int): Point {
        val bounds = visibleBounds(anchor)
        return Point(
            x.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left)),
            y.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top)),
        )
    }

    // Where a draggable one goes: where it was dragged to, else under [anchor] at its end
    private fun floatingPlace(anchor: View, width: Int, height: Int): Point {
        val wanted = position ?: run {
            val at = IntArray(2).also(anchor::getLocationInWindow)
            val overlap = (OVERLAP_DP * activity.resources.displayMetrics.density).toInt()
            Point(at[0] + anchor.width - width, at[1] + anchor.height - overlap)
        }
        return clamp(anchor, wanted.x, wanted.y, width, height)
    }

    // The height of a draggable one: its text no taller than its limit (a quarter of what is
    // visible at most), wherever it is
    private fun fitFloating(anchor: View, content: View, width: Int): Int {
        val density = activity.resources.displayMetrics.density
        val bounds = visibleBounds(anchor)
        val minText = ((if (compact) COMPACT_MIN_TEXT_HEIGHT_DP else MIN_TEXT_HEIGHT_DP) * density).toInt()
        // compact: so many whole lines of its text (by its line height: the font size the
        // user chose counts too), not a height in dp
        val maxText = if (compact) {
            content.findViewById<TextView>(R.id.problem_text).lineHeight * COMPACT_TEXT_LINES
        } else {
            (MAX_TEXT_HEIGHT_DP * density).toInt()
        }
        content.findViewById<MaxHeightScrollView>(R.id.problem_scroll)
            .setMaxHeight(minOf(maxText, bounds.height() / 4).coerceAtLeast(minText))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height().coerceAtLeast(1), View.MeasureSpec.AT_MOST),
        )
        return content.measuredHeight
    }

    // At the end of [anchor] (a row), or under its middle ([centered], an icon)
    private fun xOffset(anchor: View, width: Int): Int =
        if (centered) (anchor.width - width) / 2 else anchor.width - width

    // From the bottom of [anchor]: a little over it, or all of it and the window's [height]
    private fun yOffset(anchor: View, height: Int): Int {
        val overlap = (OVERLAP_DP * activity.resources.displayMetrics.density).toInt()
        return if (placedAbove) overlap - anchor.height - height else -overlap
    }

    // as wide as the row, but no wider than MAX_WIDTH_DP (nor narrower than MIN_WIDTH_DP);
    // centred under an icon, MAX_WIDTH_DP (no wider than the screen)
    private fun widthBy(anchor: View): Int {
        val density = activity.resources.displayMetrics.density
        val max = ((if (compact) COMPACT_MAX_WIDTH_DP else MAX_WIDTH_DP) * density).toInt()
        val min = ((if (compact) COMPACT_MIN_WIDTH_DP else MIN_WIDTH_DP) * density).toInt()
        if (centered) {
            val screen = activity.resources.displayMetrics.widthPixels
            return max.coerceAtMost(screen)
        }
        return anchor.width.coerceAtMost(max).coerceAtLeast(min)
    }

    // Whether [event], a touch outside the window ([view], its root), is on the row it is
    // by: then it is let through to the row, the window kept (true)
    private fun isOnAnchor(view: View, event: MotionEvent): Boolean {
        val outside = event.action == MotionEvent.ACTION_OUTSIDE ||
            (event.action == MotionEvent.ACTION_DOWN &&
                (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height))
        if (!outside) return false
        // a touch on another app's window (no place given): not the row's
        if (event.action == MotionEvent.ACTION_OUTSIDE && event.x == 0f && event.y == 0f) return false
        val anchor = anchor?.takeIf { it.isAttachedToWindow } ?: return false
        // the event is where it is to the window: on the screen, then
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val x = origin[0] + event.x
        val y = origin[1] + event.y
        val at = IntArray(2).also(anchor::getLocationOnScreen)
        return x >= at[0] && x < at[0] + anchor.width && y >= at[1] && y < at[1] + anchor.height
    }

    // The height of the window, its text's limit set so that it fits on the screen, by
    // [anchor]: under it or over it, where there is more room. Asked to be over it ([above]):
    // there if it fits, or if there is no less room there than under it ([placedAbove]).
    private fun fit(anchor: View, content: View, width: Int): Int {
        val density = activity.resources.displayMetrics.density
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val overlap = (OVERLAP_DP * density).toInt()
        val below = frame.bottom - (location[1] + anchor.height - overlap)
        val above = location[1] - frame.top
        var room = maxOf(below, above, (MIN_TEXT_HEIGHT_DP * density).toInt())
        val scroll = content.findViewById<MaxHeightScrollView>(R.id.problem_scroll)
        var limit = minOf((MAX_TEXT_HEIGHT_DP * density).toInt(), frame.height() / 3)
        fun measure(): Int {
            scroll.setMaxHeight(limit)
            content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(room, View.MeasureSpec.AT_MOST),
            )
            return content.measuredHeight
        }
        var height = measure()
        placedAbove = this.above && (height <= above || above >= below)
        if (placedAbove) room = above.coerceAtLeast((MIN_TEXT_HEIGHT_DP * density).toInt())
        // too tall with its head and margins: the text gets that much less
        if (height >= room) {
            val head = height - scroll.measuredHeight
            limit = (room - head).coerceAtLeast((MIN_TEXT_HEIGHT_DP * density).toInt())
            height = measure()
        }
        return height.coerceAtMost(room)
    }

    /** Whether it is shown now. */
    val isShowing: Boolean get() = window?.isShowing == true

    /** Whether it is shown now by [anchor]. */
    fun isShowingBy(anchor: View): Boolean = isShowing && this.anchor === anchor

    fun dismiss() {
        window?.dismiss()
        window = null
        anchor = null
        action = null
        backCallback.isEnabled = false
    }

    /** Closed for good: its Back is taken away (the window it was for goes, or keeps no popup). */
    fun release() {
        dismiss()
        backCallback.remove()
    }

    companion object {
        /** No ring before the text ([show]). */
        const val NO_PROGRESS = -2

        /** A ring that turns, not saying how far ([show]). */
        const val INDETERMINATE = -1

        // the text scrolls beyond this (or a third of the screen, or what the room by the row allows)
        private const val MAX_TEXT_HEIGHT_DP = 150
        // the text is never given less than this, however little room there is
        private const val MIN_TEXT_HEIGHT_DP = 64
        // no wider than this, however wide the row (a tablet, landscape)
        private const val MAX_WIDTH_DP = 260
        // no narrower than this, whatever the row
        private const val MIN_WIDTH_DP = 200
        // drawn this much over the bottom of the row: the card's margin is room enough
        private const val OVERLAP_DP = 4

        // [compact] (the editor's window, over the code): five lines of text, narrower,
        // smaller type
        private const val COMPACT_TEXT_LINES = 5
        private const val COMPACT_MIN_TEXT_HEIGHT_DP = 32
        private const val COMPACT_MAX_WIDTH_DP = 220
        private const val COMPACT_MIN_WIDTH_DP = 180
        private const val COMPACT_TITLE_SP = 12f
        private const val COMPACT_TEXT_SP = 11f
    }
}
