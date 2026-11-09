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

package x.github.module.editor.input

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF

/**
 * The handle the editor draws by itself, the default: a thin stem that stands on
 * the text where the selection starts or ends, and a round knob to take it by.
 *
 *  - the start handle has its knob *above* the row, the end handle *below* it, so
 *    the two never sit on top of each other however short the selection is;
 *  - the insertion handle is a knob below the cursor (the cursor bar is its stem).
 *
 * Its colors follow the handle color ([applyColor]).
 */
class DrawnTextHandle(
    kind: Kind,
    density: Float
) : TextHandleDrawable(kind, density) {

    /** The radius of the knob. */
    val knobRadius: Float = 7.5f * density

    private val ringWidth = 2f * density
    private val stemWidth = 2f * density

    /** The color of the stem and of the ring around the knob. */
    var color: Int = 0xFF5C7CFA.toInt()
        set(value) {
            field = value
            invalidateSelf()
        }

    /** The color of the inside of the knob. */
    var knobColor: Int = 0xFFE4E9FF.toInt()
        set(value) {
            field = value
            invalidateSelf()
        }

    // only the start handle stands above its row: the whole knob
    override val heightAboveRow: Float = if (kind == Kind.START) 2 * knobRadius else 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun layoutBody(body: RectF) {
        val centerY = if (isAboveRow) rowTop - knobRadius else rowBottom + knobRadius
        body.set(tipX - knobRadius, centerY - knobRadius, tipX + knobRadius, centerY + knobRadius)
    }

    /** The ring and the stem take [color]; the inside of the knob is a very light tint of it. */
    override fun applyColor(color: Int) {
        // nine parts white, one part of the color
        knobColor = (0xFF shl 24) or
            ((((color shr 16) and 0xFF) + 9 * 255) / 10 shl 16) or
            ((((color shr 8) and 0xFF) + 9 * 255) / 10 shl 8) or
            (((color and 0xFF) + 9 * 255) / 10)
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val visible = presence.coerceAtLeast(0f)
        if (visible <= 0f) {
            return
        }
        val alpha = drawAlpha()

        // the stem; the insertion handle has none, the cursor bar is there already
        if (kind != Kind.INSERTION) {
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = alpha
            canvas.drawRect(tipX - stemWidth / 2, rowTop, tipX + stemWidth / 2, rowBottom, paint)
        }

        // the knob: a light disc with a ring
        val radius = knobRadius * drawScale()
        val centerX = body.centerX()
        val centerY = body.centerY()
        paint.style = Paint.Style.FILL
        paint.color = knobColor
        paint.alpha = alpha
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.color = color
        paint.alpha = alpha
        canvas.drawCircle(centerX, centerY, radius - ringWidth / 2, paint)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
}
