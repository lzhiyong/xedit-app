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

package x.github.module.editor.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * How folding looks: the fold marker next to the number of a line a range starts
 * on, and the placeholder at the end of a folded line that stands in for the hidden
 * lines (EditorView.setFoldStyle).
 *
 * [DefaultFoldStyle] draws them the way VS Code does; [DrawableFoldStyle] draws
 * drawables of the app's. Anything else implements this. Main thread; the canvas is
 * in content coordinates.
 */
interface FoldStyle {

    /**
     * Draws a fold marker into the rectangle. [foldedFraction] is 0 for an open
     * range and 1 for a folded one, and in between while the marker turns after a
     * tap. [color] is the marker color (EditorView.setFoldMarkerColor).
     */
    fun drawMarker(
        canvas: Canvas, foldedFraction: Float,
        left: Float, top: Float, right: Float, bottom: Float, color: Int
    )

    /** How wide the placeholder of folded lines is, on rows [lineHeight] tall. */
    fun placeholderWidth(lineHeight: Int): Int

    /**
     * Draws the placeholder of folded lines into the rectangle, right after the
     * text of the line the fold starts on. [color] is the marker color. A tap on it
     * unfolds the range.
     */
    fun drawPlaceholder(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int)
}

/**
 * Folding the way VS Code shows it: a chevron for the marker (pointing down while
 * open, to the right while folded, turning in between), and three dots on a faint
 * rounded background for the placeholder.
 */
open class DefaultFoldStyle : FoldStyle {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    override fun drawMarker(
        canvas: Canvas, foldedFraction: Float,
        left: Float, top: Float, right: Float, bottom: Float, color: Int
    ) {
        val size = Math.min(right - left, bottom - top) * 0.36f
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        strokePaint.color = color
        strokePaint.strokeWidth = Math.max(1f, size / 4f)
        // pointing down, turned counterclockwise by up to a quarter turn
        path.reset()
        path.moveTo(centerX - size / 2f, centerY - size / 4f)
        path.lineTo(centerX, centerY + size / 4f)
        path.lineTo(centerX + size / 2f, centerY - size / 4f)
        canvas.save()
        canvas.rotate(-90f * foldedFraction.coerceIn(0f, 1f), centerX, centerY)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    // room for three dots and the space around them
    override fun placeholderWidth(lineHeight: Int): Int = lineHeight * 3 / 2

    override fun drawPlaceholder(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        val height = bottom - top
        // the background, a little inside the row
        val inset = Math.max(1f, height / 6f)
        rect.set(left, top + inset, right, bottom - inset)
        val radius = rect.height() / 3f
        fillPaint.color = withAlpha(color, 0x2E)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // three dots in the middle, spaced by twice their size
        val dot = Math.max(1f, height / 14f)
        val gap = dot * 3.2f
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        fillPaint.color = withAlpha(color, 0xD0)
        for (i in -1..1) {
            canvas.drawCircle(centerX + i * gap, centerY, dot, fillPaint)
        }
    }

    protected fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/**
 * Folding with the app's drawables: [expanded] is the marker of an open range,
 * [folded] that of a folded one (while a marker turns after a tap, the two fade into
 * each other), [placeholder] stands in for folded lines (null: the default three dots).
 *
 * With [tint] (the default) the drawables are tinted in the marker color, so that
 * single-color icons follow the theme; without it they keep their own colors. A
 * drawable is drawn into the marker's rectangle, kept square and centered.
 */
class DrawableFoldStyle @JvmOverloads constructor(
    private val expanded: Drawable,
    private val folded: Drawable,
    private val placeholder: Drawable? = null,
    private val tint: Boolean = true
) : FoldStyle {

    // for what is not given as a drawable
    private val fallback = DefaultFoldStyle()

    private fun draw(drawable: Drawable, canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float,
                     color: Int, alpha: Int, square: Boolean) {
        var l = left
        var t = top
        var r = right
        var b = bottom
        if (square) {
            val size = Math.min(right - left, bottom - top)
            l = (left + right - size) / 2f
            t = (top + bottom - size) / 2f
            r = l + size
            b = t + size
        }
        drawable.setBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        drawable.colorFilter = if (tint) PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) else null
        drawable.alpha = alpha
        drawable.draw(canvas)
    }

    override fun drawMarker(
        canvas: Canvas, foldedFraction: Float,
        left: Float, top: Float, right: Float, bottom: Float, color: Int
    ) {
        val fraction = foldedFraction.coerceIn(0f, 1f)
        if (fraction < 1f) {
            draw(expanded, canvas, left, top, right, bottom, color, ((1f - fraction) * 255).toInt(), true)
        }
        if (fraction > 0f) {
            draw(folded, canvas, left, top, right, bottom, color, (fraction * 255).toInt(), true)
        }
    }

    override fun placeholderWidth(lineHeight: Int): Int {
        val drawable = placeholder ?: return fallback.placeholderWidth(lineHeight)
        // as wide as the drawable is at the height of a row
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        return if (width > 0 && height > 0) lineHeight * width / height else lineHeight * 3 / 2
    }

    override fun drawPlaceholder(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        val drawable = placeholder ?: return fallback.drawPlaceholder(canvas, left, top, right, bottom, color)
        draw(drawable, canvas, left, top, right, bottom, color, 255, false)
    }
}
