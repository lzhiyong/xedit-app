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
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A handle that looks like a drawable: an image of the app's, or the ones of the
 * theme (android:textSelectHandleLeft / Right / textSelectHandle), as Android's own
 * text fields use them.
 *
 * Such images follow Android's convention, and so does this class: **all three hang
 * below the row** they stand on, with the top edge of the image on the bottom of
 * the row. What keeps the two ends of a short selection from covering each other is
 * not the side of the row but the direction: the start handle reaches out to the
 * left of its tip, the end handle to the right. The tip is where the image points,
 * a fraction of its width from its left edge ([hotspotFraction]): by the convention,
 * three quarters for the start handle, a quarter for the end handle, the middle for
 * the insertion handle.
 *
 * It is drawn as it is; the handle color does not tint it. An image that should
 * take the theme's colors does so itself (as the theme's handles do, through
 * colorControlActivated).
 */
class ResourceTextHandle(
    kind: Kind,
    source: Drawable,
    density: Float,
    /** Where the tip is across the image, from 0 (its left edge) to 1 (its right edge). */
    private val hotspotFraction: Float = defaultHotspotFraction(kind)
) : TextHandleDrawable(kind, density) {

    companion object {
        /** Where Android's own handle images point, for each kind. */
        fun defaultHotspotFraction(kind: Kind): Float = when (kind) {
            Kind.START -> 0.75f
            Kind.END -> 0.25f
            Kind.INSERTION -> 0.5f
        }

        // for an image that does not say how large it is
        private const val FALLBACK_SIZE_DP = 22f
    }

    // An image of its own: the one handed in may come from the resources' cache and
    // be shared, and its bounds and alpha are about to be set.
    private val image: Drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()

    private val imageWidth = image.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: FALLBACK_SIZE_DP * density
    private val imageHeight = image.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: FALLBACK_SIZE_DP * density

    // it never stands above its row
    override val heightAboveRow: Float = 0f

    override fun layoutBody(body: RectF) {
        val left = tipX - imageWidth * hotspotFraction
        body.set(left, rowBottom, left + imageWidth, rowBottom + imageHeight)
    }

    override fun draw(canvas: Canvas) {
        val scale = drawScale()
        if (scale <= 0f) {
            return
        }
        image.setBounds(body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt())
        image.alpha = drawAlpha()
        // growing and shrinking around the tip, where it points
        val saved = canvas.save()
        canvas.scale(scale, scale, tipX, rowBottom)
        image.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        image.colorFilter = colorFilter
        invalidateSelf()
    }
}
