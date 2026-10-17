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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A card of text at the cursor: hover information, the signature of the call being typed,
 * the diagnostics at the cursor (see EditorView.showInfo). Above the cursor when there is
 * room (so that the line typed stays in sight), under it otherwise; the text scrolls when
 * it is long. In the window's decor view, like CompletionWindow; a touch outside it goes
 * on to what is below.
 */
@SuppressLint("ViewConstructor")
class InfoWindow(context: Context) : FrameLayout(context) {

    val isShowing: Boolean get() = parent != null && visibility == VISIBLE

    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2D30.toInt())
    private val textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val margin = dp(8)
    private val radius = dp(12).toFloat()
    private val enter = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private val card = FrameLayout(context)
    private val scroll = ScrollView(context)
    private val text = TextView(context)

    init {
        visibility = GONE
        card.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(surface)
        }
        card.elevation = dp(6).toFloat()
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        card.clipToOutline = true
        text.setTextColor(textColor)
        text.textSize = 13f
        // code comes in monospace by its spans (hover, signatures): the rest reads better so
        text.typeface = Typeface.DEFAULT
        text.setPadding(dp(12), dp(10), dp(12), dp(10))
        text.setTextIsSelectable(false)
        scroll.isVerticalScrollBarEnabled = true
        scroll.addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(0, 0))
    }

    /** Shows [content] at [anchor] (the row of the cursor, in [host]). */
    fun show(host: ViewGroup, anchor: Rect, content: CharSequence) {
        text.text = content
        scroll.scrollTo(0, 0)
        val appearing = !isShowing
        if (parent !== host) {
            (parent as? ViewGroup)?.removeView(this)
            host.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        place(host, anchor)
        animate().cancel()
        visibility = VISIBLE
        if (appearing) {
            alpha = 0f
            animate().alpha(1f).setDuration(140L).setInterpolator(enter).start()
        } else {
            alpha = 1f
        }
    }

    /** Follows the cursor to [anchor] (the text scrolled). */
    fun moveTo(host: ViewGroup, anchor: Rect) {
        if (isShowing) place(host, anchor)
    }

    fun dismiss() {
        if (!isShowing) return
        animate().cancel()
        animate().alpha(0f).setDuration(90L).withEndAction {
            visibility = GONE
            (parent as? ViewGroup)?.removeView(this)
        }.start()
    }

    private fun place(host: ViewGroup, anchor: Rect) {
        val insets = host.systemInsets()
        val width = min(dp(380), host.width - insets.left - insets.right - margin * 2)
        val top = insets.top + margin
        val bottom = host.height - insets.bottom - margin
        val maxHeight = min(dp(260), (bottom - top) / 2)
        text.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = min(text.measuredHeight, maxHeight)
        val above = anchor.top - dp(4) - height
        val y = if (above >= top) above else min(anchor.bottom + dp(4), max(top, bottom - height))
        val x = (anchor.left - dp(24)).coerceIn(insets.left + margin, max(insets.left + margin, host.width - insets.right - margin - width))
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!Rect(card.left, card.top, card.right, card.bottom).contains(ev.x.toInt(), ev.y.toInt())) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun View.systemInsets(): Rect {
        val insets = rootWindowInsets ?: return Rect()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            Rect(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
    }
}
