/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.util

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

object AppUtils {

    /**
     * The resource a theme attribute points to, or 0 if the attribute is not set or
     * holds a plain value (e.g. `#ff0000`) instead of a reference.
     */
    fun resolveAttrResourceId(context: Context, @AttrRes id: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(id, typedValue, true)
        return typedValue.resourceId
    }

    /**
     * The color of a theme attribute, whether the theme gives it as a reference
     * (`@color/...`, a color state list included: its default color) or as a plain
     * value. [fallback] if the attribute is not set or is not a color.
     */
    @ColorInt
    fun resolveColor(
        context: Context,
        @AttrRes id: Int,
        @ColorInt fallback: Int = Color.TRANSPARENT
    ): Int {
        val typedValue = TypedValue()
        if (!context.theme.resolveAttribute(id, typedValue, true)) {
            return fallback
        }
        return when {
            typedValue.resourceId != 0 -> ContextCompat.getColor(context, typedValue.resourceId)
            typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
                typedValue.data
            else -> fallback
        }
    }

    /** The height of the status bar as the platform declares it, 0 if it does not. */
    fun getStatusBarHeight(context: Context): Int = context.resources.run {
        val id = getIdentifier("status_bar_height", "dimen", "android")
        if (id != 0) getDimensionPixelSize(id) else 0
    }

    fun getActionBarHeight(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, context.resources.displayMetrics)
        } else {
            0
        }
    }
}
