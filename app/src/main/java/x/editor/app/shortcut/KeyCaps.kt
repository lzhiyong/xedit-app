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

package x.editor.app.shortcut

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.res.ResourcesCompat

import com.google.android.material.color.MaterialColors

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * Draws keys as keys of a keyboard: a cap for each (Ctrl, Shift, F), a rounded tile of
 * one color a step above the row it is on. The keys of a sequence (`<C-k>c`) are set
 * apart by a "›".
 */
object KeyCaps {

    private const val TEXT_SP = 12f
    private const val PADDING_H_DP = 7f
    private const val PADDING_V_DP = 4f
    private const val RADIUS_DP = 6f
    private const val MIN_WIDTH_DP = 26f
    private const val GAP_DP = 4f
    private const val SEQUENCE_GAP_DP = 6f

    /** Fills [container] with [keys]; with [placeholder] (muted) if there are none. */
    fun render(container: LinearLayout, keys: List<KeyStroke>, placeholder: CharSequence? = null) {
        container.removeAllViews()
        val muted = MaterialColors.getColor(container, MaterialR.attr.colorOnSurfaceVariant)
        if (keys.isEmpty()) {
            if (placeholder != null) {
                container.addView(TextView(container.context).apply {
                    text = placeholder
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(muted)
                })
            }
            return
        }
        keys.forEachIndexed { index, stroke ->
            if (index > 0) {
                container.addView(TextView(container.context).apply {
                    text = "›"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(muted)
                }, params(container, SEQUENCE_GAP_DP).apply { marginEnd = dp(container, SEQUENCE_GAP_DP - GAP_DP) })
            }
            stroke.capLabels.forEachIndexed { i, label ->
                container.addView(cap(container, label), params(container, if (index == 0 && i == 0) 0f else GAP_DP))
            }
        }
    }

    private fun params(container: LinearLayout, start: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(container, start) }

    private fun cap(container: LinearLayout, text: String): TextView {
        val context = container.context
        // one color only, a step above the rows (colorSurfaceContainer) it is drawn on
        val background = GradientDrawable().apply {
            cornerRadius = dp(container, RADIUS_DP).toFloat()
            setColor(MaterialColors.getColor(container, MaterialR.attr.colorSurfaceContainerHighest))
        }
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setTextColor(MaterialColors.getColor(container, MaterialR.attr.colorOnSurface))
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
            minWidth = dp(container, MIN_WIDTH_DP)
            val h = dp(container, PADDING_H_DP)
            val v = dp(container, PADDING_V_DP)
            setPadding(h, v, h, v)
            this.background = background
        }
    }

    private fun dp(container: LinearLayout, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, container.resources.displayMetrics).toInt()
}
