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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

import com.google.android.material.color.MaterialColors

import com.google.android.material.R as MaterialR

/**
 * A row of overlapping color dots, each ringed in the surface color so they stand
 * apart: the colors of a highlight theme at a glance.
 */
class SwatchesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val diameter = 18 * density
    private val overlap = 5 * density
    private val ring = 2 * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SwatchesView, MaterialR.attr.colorSurfaceContainerHigh)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = MaterialColors.getColor(this@SwatchesView, MaterialR.attr.colorOutlineVariant)
    }

    var colors: IntArray = IntArray(0)
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = colors.size
        val width = if (count == 0) 0f else diameter + (count - 1) * (diameter - overlap) + ring * 2
        setMeasuredDimension(
            resolveSize(width.toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize((diameter + ring * 2).toInt() + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = diameter / 2
        val cy = paddingTop + ring + radius
        // the first dot on top: drawn last
        for (index in colors.indices.reversed()) {
            val cx = paddingLeft + ring + radius + index * (diameter - overlap)
            canvas.drawCircle(cx, cy, radius + ring, ringPaint)
            fill.color = colors[index]
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius - density / 2, outline)
        }
    }
}
