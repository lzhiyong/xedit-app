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
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The cursor the editor draws by itself, the default: a bar of [color] filling its
 * bounds, as high as a row and [width] wide.
 *
 * Its color follows the handle color, so cursor and handles always match. The
 * cursor can also be an image instead (EditorView.setCursorDrawable).
 */
class DrawnCursorDrawable(density: Float) : Drawable() {

    /** How wide the bar is, in pixels. */
    var width: Int = Math.max(1, Math.round(2f * density))

    var color: Int = 0xFF5C7CFA.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidateSelf()
        }

    private val paint = Paint().apply { color = this@DrawnCursorDrawable.color }

    override fun getIntrinsicWidth(): Int = width

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
