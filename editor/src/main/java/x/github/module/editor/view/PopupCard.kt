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

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * The look of what floats at the cursor (CompletionWindow, InfoWindow), as the app's small
 * windows by a row have it (EntryProblemPopup, a MaterialCardView): a rounded card of the
 * raised surface (colorSurfaceContainerHigh), a hairline border (colorOutlineVariant) and a
 * shadow under it, so that it stands off the text it is over, in a light theme and a dark.
 *
 * The border is the card's foreground: drawn over what is in it, so a row that is tinted
 * (the selected completion) does not cover it at the edges. The card clips what is in it
 * to its rounded outline, which also casts the shadow.
 */
internal object PopupCard {

    /** The corners of the card, in dp (as EntryProblemPopup's). */
    const val RADIUS_DP = 14

    /** How high the card floats, in dp: its shadow. */
    const val ELEVATION_DP = 8

    /** The border, in dp. */
    const val STROKE_DP = 1

    /** Gives [card] the look; [radius] in px. */
    fun style(card: View, radius: Float = dp(card, RADIUS_DP).toFloat()) {
        val context = card.context
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2D30.toInt())
        val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFF44474E.toInt())
        card.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(surface)
        }
        card.foreground = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(card, STROKE_DP).coerceAtLeast(1), outline)
        }
        card.elevation = dp(card, ELEVATION_DP).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // a shadow that still shows on a dark surface (the theme's alphas apply over these)
            card.outlineAmbientShadowColor = Color.BLACK
            card.outlineSpotShadowColor = Color.BLACK
        }
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        card.clipToOutline = true
    }

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).roundToInt()
}
