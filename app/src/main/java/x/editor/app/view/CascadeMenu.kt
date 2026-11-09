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

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat

import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * A popup menu whose sub-menus open in the menu itself, as in saket/cascade: a tap on an
 * item with a sub-menu slides its items in from the right in place of the menu's, under a
 * header with ← and the sub-menu's title, the menu's height going with them; the header
 * (or Back) slides them out again. No item to go back with, no second popup.
 *
 * The menu is as wide as its widest page (the root and every sub-menu, measured before it
 * is shown), so only its height changes; a page higher than there is room for scrolls.
 * Items as a menu shows them: the icon (a column for the icons if any item has one), the
 * title (with a shortcut after a tab: MenuShortcuts), a check box for a checkable item, ▸
 * for a sub-menu; a disabled item faint and not tappable; hidden ones left out.
 *
 * The popup does not take the focus: the view that has it (the editor) keeps it, and the
 * keyboard stays up under the menu. A touch outside closes it (and goes on to what is
 * there); Back, taken from the activity while the menu is open (its OnBackPressedDispatcher,
 * the system's back gesture too), goes out of a sub-menu, or closes the menu.
 *
 * @param context themed as the menus (its text appearance for the titles, the check box);
 *   a context of the activity, for Back
 * @param onItemClick an item without a sub-menu tapped (the menu closes first)
 * @param onDismiss the menu closed, whatever closed it
 */
class CascadeMenu(
    private val context: Context,
    private val menu: Menu,
    private val onItemClick: (MenuItem) -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private val density = context.resources.displayMetrics.density
    private val onSurface = MaterialColors.getColor(context, MaterialR.attr.colorOnSurface, Color.BLACK)
    private val onSurfaceVariant = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

    // A page: the items of a menu (and the header of a sub-menu), the view showing them, and
    // the height it takes in the popup (as high as its items, at most the room there is),
    // kept on the view too (its tag) for the frame to lay it out at, sliding out as well.
    private class Page(val view: View, height: Int) {
        val height: Int = height.also { view.tag = it }
    }

    private val frame = PageFrame(context)
    private val popup = PopupWindow(context)
    private val pages = ArrayList<Page>()
    // how wide the menu is (the widest of its pages); not `width`: in PageFrame that would
    // be the frame's own (View.getWidth), 0 until it is laid out
    private var menuWidth = 0
    private var maxHeight = 0
    private var animator: ValueAnimator? = null

    // Back while the menu is open: out of the sub-menu shown, or the menu closed
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = back()
    }

    /** Whether the menu is open. */
    val isShowing: Boolean get() = popup.isShowing

    init {
        popup.contentView = frame
        popup.width = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        // (not focusable: the editor keeps the focus and its keyboard; a touch outside still
        // closes the menu, as it is watched for)
        popup.isFocusable = false
        popup.isOutsideTouchable = true
        popup.elevation = ELEVATION_DP * density
        popup.setBackgroundDrawable(
            MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(CORNER_DP * density).build()).apply {
                fillColor = ColorStateList.valueOf(
                    MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainer, Color.WHITE)
                )
                initializeElevationOverlay(context)
            }
        )
        popup.setOnDismissListener {
            animator?.cancel()
            backCallback.remove()
            onDismiss()
        }
    }

    /** Shown at the end of [anchor], over it (as the overflow menu of a toolbar). */
    fun show(anchor: View) {
        val root = buildPage(menu, header = null)
        // as wide as the widest page: the root and every sub-menu in it (measured once)
        menuWidth = measureWidest(menu, root).coerceIn(
            (MIN_WIDTH_DP * density).toInt(),
            context.resources.displayMetrics.widthPixels - (2 * EDGE_DP * density).toInt(),
        )
        popup.overlapAnchor = true
        maxHeight = popup.getMaxAvailableHeight(anchor) - (EDGE_DP * density).toInt()
        val page = Page(root, heightOf(root))
        pages += page
        frame.addView(root)
        frame.shownHeight = page.height
        // The popup's width, as it is (not WRAP_CONTENT): the popup is put at the end of the
        // anchor by it, which with no width given put it past the anchor's end, off the
        // screen. Its height follows the pages (the window resizes as they slide).
        popup.width = menuWidth
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
        // (the pages clipped to the menu's rounded outline as they slide)
        (frame.parent as? View)?.clipToOutline = true
        activityOf(context)?.onBackPressedDispatcher?.addCallback(backCallback)
    }

    fun dismiss() = popup.dismiss()

    // #region pages

    // the page of [items]; with [header] (a sub-menu's item), ← and its title above them
    private fun buildPage(items: Menu, header: MenuItem?): View {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (PAGE_PADDING_DP * density).toInt()
            setPadding(0, if (header == null) pad else 0, 0, pad)
        }
        if (header != null) {
            list.addView(headerRow(header))
            list.addView(View(context).apply {
                setBackgroundColor(MaterialColors.getColor(context, MaterialR.attr.colorOutlineVariant, Color.LTGRAY))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt().coerceAtLeast(1)).apply {
                    bottomMargin = (PAGE_PADDING_DP * density).toInt()
                }
            })
        }
        val shown = (0 until items.size()).map { items.getItem(it) }.filter { it.isVisible }
        val icons = shown.any { it.icon != null }
        for (item in shown) list.addView(itemRow(item, icons))
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ← and the title of the sub-menu of [item], in the accent colour: back to the menu above
    private fun headerRow(item: MenuItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (ROW_HEIGHT_DP * density).toInt()
        setPaddingRelative((ROW_PADDING_DP * density).toInt(), 0, (ROW_PADDING_DP * density).toInt(), 0)
        background = selectableBackground()
        isClickable = true
        contentDescription = plainTitle(item.title)
        setOnClickListener { back() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(primary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(px(HEADER_ICON_DP), px(HEADER_ICON_DP)).apply {
                marginEnd = px(ICON_GAP_DP)
            }
        })
        addView(TextView(context).apply {
            text = plainTitle(item.title)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setTextAppearance(this, resolveStyle(MaterialR.attr.textAppearanceTitleSmall))
            setTextColor(primary)
        })
    }

    private fun itemRow(item: MenuItem, icons: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (ROW_HEIGHT_DP * density).toInt()
        setPaddingRelative(px(ROW_PADDING_DP), 0, px(ROW_PADDING_DP), 0)
        background = selectableBackground()
        isEnabled = item.isEnabled
        contentDescription = plainTitle(item.title)
        val faint = { color: Int ->
            if (item.isEnabled) color else ColorUtils.setAlphaComponent(color, (Color.alpha(color) * DISABLED_ALPHA).toInt())
        }
        if (icons) {
            addView(ImageView(context).apply {
                item.icon?.constantState?.newDrawable(context.resources)?.mutate()?.let(::setImageDrawable)
                    ?: setImageDrawable(item.icon)
                imageTintList = ColorStateList.valueOf(faint(onSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(px(ICON_DP), px(ICON_DP)).apply { marginEnd = px(ICON_GAP_DP) }
            })
        }
        addView(TextView(context).apply {
            // (a tab and the shortcut after the title, at its tab stop: one line keeps them)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            TextViewCompat.setTextAppearance(this, resolveStyle(androidx.appcompat.R.attr.textAppearanceLargePopupMenu))
            setTextColor(faint(onSurface))
            text = item.title
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        when {
            item.hasSubMenu() -> addView(ImageView(context).apply {
                // (the triangle of AppCompat's menus, not a chevron)
                setImageResource(R.drawable.ic_arrow_drop_right)
                imageTintList = ColorStateList.valueOf(faint(onSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(px(TRAILING_DP), px(TRAILING_DP)).apply { marginStart = px(ICON_GAP_DP) }
            })
            item.isCheckable -> addView(MaterialCheckBox(context).apply {
                isChecked = item.isChecked
                isEnabled = item.isEnabled
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = px(ICON_GAP_DP)
                }
            })
        }
        if (item.isEnabled) {
            setOnClickListener {
                if (animator?.isRunning == true) return@setOnClickListener
                val sub = item.subMenu
                if (item.hasSubMenu() && sub != null) {
                    open(sub, item)
                } else {
                    popup.dismiss()
                    onItemClick(item)
                }
            }
        }
    }

    // the widest of [root] and the pages of all the sub-menus under [items]
    private fun measureWidest(items: Menu, root: View): Int {
        var widest = naturalWidth(root)
        for (i in 0 until items.size()) {
            val item = items.getItem(i)
            val sub = item.subMenu
            if (item.isVisible && item.hasSubMenu() && sub != null) {
                widest = maxOf(widest, measureWidest(sub, buildPage(sub, item)))
            }
        }
        return widest
    }

    private fun naturalWidth(page: View): Int {
        val list = (page as ViewGroup).getChildAt(0)
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return list.measuredWidth
    }

    // the height of [page] at the menu's width: its items, at most the room there is
    private fun heightOf(page: View): Int {
        val list = (page as ViewGroup).getChildAt(0)
        list.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        return list.measuredHeight.coerceAtMost(maxHeight.coerceAtLeast((ROW_HEIGHT_DP * density).toInt()))
    }

    // #endregion

    // #region going in and out

    // the sub-menu [sub] of [item] slid in from the right, over the menu it is in
    private fun open(sub: Menu, item: MenuItem) {
        val from = pages.last()
        val view = buildPage(sub, item)
        val page = Page(view, heightOf(view))
        pages += page
        frame.addView(page.view)
        slide(from, page, forward = true)
    }

    // the sub-menu shown slid out to the right, the menu it is in back (the root: closed)
    private fun back() {
        if (animator?.isRunning == true) return
        if (pages.size <= 1) {
            popup.dismiss()
            return
        }
        val from = pages.removeAt(pages.lastIndex)
        val to = pages.last()
        frame.addView(to.view, 0)
        slide(from, to, forward = false)
    }

    // [from] out and [to] in, side by side, the menu's height going from one's to the other's
    private fun slide(from: Page, to: Page, forward: Boolean) {
        val distance = menuWidth.toFloat()
        val outTo = if (forward) -distance else distance
        val inFrom = if (forward) distance else -distance
        to.view.translationX = inFrom
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                from.view.translationX = outTo * f
                to.view.translationX = inFrom * (1 - f)
                from.view.alpha = 1 - f
                to.view.alpha = f
                frame.shownHeight = (from.height + (to.height - from.height) * f).toInt()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    frame.removeView(from.view)
                    from.view.translationX = 0f
                    from.view.alpha = 1f
                    to.view.translationX = 0f
                    to.view.alpha = 1f
                    frame.shownHeight = to.height
                }
            })
            start()
        }
    }

    // the activity [context] is of (through the theme wrappers around it), for Back
    private fun activityOf(context: Context): ComponentActivity? {
        var at: Context? = context
        while (at is ContextWrapper) {
            if (at is ComponentActivity) return at
            at = at.baseContext
        }
        return null
    }

    // #endregion

    // the pages, one over the other while they slide; as wide as the menu, as high as
    // [shownHeight] (each page as high as its own: they do not stretch as the menu grows)
    private inner class PageFrame(context: Context) : FrameLayout(context) {
        var shownHeight = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val height = child.tag as? Int ?: shownHeight
                child.measure(
                    MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(menuWidth, shownHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
        }
    }

    private fun selectableBackground() = TypedValue().let { value ->
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) && value.resourceId != 0) {
            context.getDrawable(value.resourceId)
        } else {
            null
        }
    }

    private fun resolveStyle(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true) && value.resourceId != 0) {
            value.resourceId
        } else {
            MaterialR.style.TextAppearance_Material3_BodyLarge
        }
    }

    private fun px(dp: Float) = (dp * density).toInt()

    // a title without the shortcut put after it (everything from the tab on)
    private fun plainTitle(title: CharSequence?): CharSequence? {
        if (title == null) return null
        val tab = TextUtils.indexOf(title, '\t')
        return if (tab < 0) title else title.subSequence(0, tab).toString()
    }

    private companion object {
        const val MIN_WIDTH_DP = 200f
        const val EDGE_DP = 16f
        const val CORNER_DP = 12f
        const val ELEVATION_DP = 8f
        const val PAGE_PADDING_DP = 8f
        const val ROW_HEIGHT_DP = 48f
        const val ROW_PADDING_DP = 16f
        const val ICON_DP = 24f
        const val HEADER_ICON_DP = 20f
        const val ICON_GAP_DP = 12f
        const val TRAILING_DP = 24f
        const val DISABLED_ALPHA = 0.38f
        const val DURATION_MS = 280L
    }
}
