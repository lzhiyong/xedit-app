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

package x.editor.app.preference

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.transition.Fade
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat

import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

import kotlin.math.ceil
import kotlin.math.roundToInt

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * The drop-down of [SimpleMenuPreference]: a Material 3 menu that opens right over
 * the row of the preference, with the entry that is chosen now lying on the row
 * itself, so the eye does not have to move to find it (the "simple menu" of
 * Material 1, as Shizuku and the Android settings use it).
 *
 *  - It opens from the row: it grows out of the chosen entry and fades in, and
 *    fades out when it closes.
 *  - The chosen entry is marked with the secondary container color.
 *  - Too many entries for the screen: the menu scrolls, starting with the chosen
 *    entry on the row as far as possible.
 *
 * The card is drawn by a view of its own inside a transparent window, not by the
 * window: so its shadow grows along with it while it opens.
 */
class SimpleMenuPopup(private val context: Context) {

    private var popup: PopupWindow? = null

    val isShowing: Boolean
        get() = popup?.isShowing == true

    fun dismiss() {
        popup?.dismiss()
    }

    /**
     * Opens the menu over [anchor] (the row of the preference) with [entries],
     * [selectedIndex] marked (-1 for none). [onItemSelected] is told the position of
     * the entry tapped; the menu closes by itself.
     */
    fun show(
        anchor: View,
        entries: Array<CharSequence>,
        selectedIndex: Int,
        onItemSelected: (Int) -> Unit
    ) {
        if (entries.isEmpty() || !anchor.isAttachedToWindow) return
        dismiss()

        val res = context.resources
        val itemHeight = res.getDimensionPixelSize(R.dimen.simple_menu_item_height)
        val itemPadding = res.getDimensionPixelSize(R.dimen.simple_menu_item_padding_horizontal)
        val listPadding = res.getDimensionPixelSize(R.dimen.simple_menu_padding_vertical)
        val corner = res.getDimension(R.dimen.simple_menu_corner)
        val elevation = res.getDimension(R.dimen.simple_menu_elevation)
        val screenMargin = res.getDimensionPixelSize(R.dimen.simple_menu_screen_margin)
        // room around the card for its shadow, inside the window
        val shadowRoom = (elevation * 2.5f).roundToInt()

        // #region the entries
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, listPadding, 0, listPadding)
        }
        var widestText = 0f
        entries.forEachIndexed { index, text ->
            val item = createItem(text, index == selectedIndex, itemPadding)
            item.setOnClickListener {
                onItemSelected(index)
                dismiss()
            }
            widestText = maxOf(widestText, item.paint.measureText(text, 0, text.length))
            list.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight))
        }
        // #endregion

        // #region where it goes: in the coordinates of the window of the anchor
        val root = anchor.rootView
        val insets = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val boundsLeft = (insets?.left ?: 0) + screenMargin
        val boundsTop = (insets?.top ?: 0) + screenMargin
        val boundsRight = root.width - (insets?.right ?: 0) - screenMargin
        val boundsBottom = root.height - (insets?.bottom ?: 0) - screenMargin

        // as wide as the longest entry, in steps of 8dp, within what Material allows
        val step = 8 * res.displayMetrics.density
        val width = (ceil((widestText + itemPadding * 2) / step) * step).roundToInt()
            .coerceIn(
                res.getDimensionPixelSize(R.dimen.simple_menu_min_width),
                res.getDimensionPixelSize(R.dimen.simple_menu_max_width)
            )
            .coerceAtMost(boundsRight - boundsLeft)

        val contentHeight = listPadding * 2 + entries.size * itemHeight
        val height = contentHeight.coerceAtMost(boundsBottom - boundsTop)

        val anchorLocation = IntArray(2).also { anchor.getLocationInWindow(it) }
        val isRtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // the text of the entries starts where the title of the row does
        val title = anchor.findViewById<View>(android.R.id.title)
        val x = if (title != null && title.isShown) {
            val titleLocation = IntArray(2).also { title.getLocationInWindow(it) }
            if (isRtl) titleLocation[0] + title.width + itemPadding - width
            else titleLocation[0] - itemPadding
        } else {
            if (isRtl) anchorLocation[0] + anchor.width - width else anchorLocation[0]
        }.coerceIn(boundsLeft, maxOf(boundsLeft, boundsRight - width))

        // the chosen entry over the middle of the row
        val anchorCenterY = anchorLocation[1] + anchor.height / 2
        val selectedCenter = listPadding + maxOf(0, selectedIndex) * itemHeight + itemHeight / 2
        val y = (anchorCenterY - selectedCenter)
            .coerceIn(boundsTop, maxOf(boundsTop, boundsBottom - height))
        // scrolls if it does not fit: as far as it can toward the chosen entry on the row
        val scrollY = (selectedCenter - (anchorCenterY - y)).coerceIn(0, contentHeight - height)
        // #endregion

        // #region the card
        val card = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            background = MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(corner).build()
            ).apply {
                fillColor = ColorStateList.valueOf(
                    MaterialColors.getColor(context, MaterialR.attr.colorSurfaceContainer, Color.WHITE)
                )
                initializeElevationOverlay(context)
            }
            // the entries are clipped by the round corners too
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
            clipToOutline = true
            this.elevation = elevation
        }

        val container = FrameLayout(context).apply {
            setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom)
            clipToPadding = false
            clipChildren = false
            addView(card, FrameLayout.LayoutParams(width, height))
        }
        // #endregion

        val window = PopupWindow(
            container,
            width + shadowRoom * 2,
            height + shadowRoom * 2,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = false
            this.elevation = 0f
            animationStyle = 0
            enterTransition = null
            exitTransition = Fade().apply { duration = EXIT_DURATION }
            setOnDismissListener { if (popup === this) popup = null }
        }
        // a tap in the room around the card is a tap outside of the menu
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                (event.x < card.left || event.x > card.right || event.y < card.top || event.y > card.bottom)
            ) {
                window.dismiss()
                true
            } else {
                false
            }
        }
        popup = window

        // grows out of the chosen entry, which stays on the row
        card.pivotX = if (isRtl) width.toFloat() else 0f
        card.pivotY = (anchorCenterY - y).toFloat().coerceIn(0f, height.toFloat())
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.6f
        list.post { card.scrollTo(0, scrollY) }

        window.showAtLocation(anchor, Gravity.LEFT or Gravity.TOP, x - shadowRoom, y - shadowRoom)
        animateIn(card)
    }

    private fun animateIn(card: View) {
        val interpolator = MotionUtils.resolveThemeInterpolator(
            context,
            MaterialR.attr.motionEasingEmphasizedDecelerateInterpolator,
            PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        )
        val duration = MotionUtils.resolveThemeDuration(
            context, MaterialR.attr.motionDurationMedium2, ENTER_DURATION.toInt()
        ).toLong()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.SCALE_X, card.scaleX, 1f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, card.scaleY, 1f),
                ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).apply {
                    this.duration = duration / 2
                }
            )
            this.duration = duration
            this.interpolator = interpolator
            start()
        }
    }

    private fun createItem(text: CharSequence, selected: Boolean, padding: Int): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(padding, 0, padding, 0)
            TextViewCompat.setTextAppearance(
                this,
                resolveResourceId(MaterialR.attr.textAppearanceBodyLarge)
            )
            setTextColor(
                MaterialColors.getColor(
                    context,
                    if (selected) MaterialR.attr.colorOnSecondaryContainer else MaterialR.attr.colorOnSurface,
                    Color.BLACK
                )
            )
            val highlight = MaterialColors.getColor(context, android.R.attr.colorControlHighlight, Color.LTGRAY)
            background = RippleDrawable(
                ColorStateList.valueOf(highlight),
                if (selected) {
                    ColorDrawable(
                        MaterialColors.getColor(context, MaterialR.attr.colorSecondaryContainer, Color.LTGRAY)
                    )
                } else null,
                ColorDrawable(Color.WHITE)
            )
            isSelected = selected
            isFocusable = true
            isClickable = true
        }

    private fun resolveResourceId(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.resourceId
        else android.R.style.TextAppearance_Material_Body1
    }

    private companion object {
        const val ENTER_DURATION = 300L
        const val EXIT_DURATION = 120L
    }
}
