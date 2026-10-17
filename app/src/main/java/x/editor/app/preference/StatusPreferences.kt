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
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

import com.google.android.material.color.MaterialColors

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * A preference with a layout of its own, which CardPreferenceStyler leaves as it
 * is (and which stands apart from the groups of cards around it).
 */
interface KeepsLayout

/** How something stands: well, waiting (for the next start), or with a problem. */
enum class Status(
    @DrawableRes val icon: Int,
    @AttrRes val container: Int,
    @AttrRes val onContainer: Int,
    @AttrRes val accent: Int
) {
    OK(
        R.drawable.ic_check_circle,
        MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer,
        androidx.appcompat.R.attr.colorPrimary
    ),
    PENDING(
        R.drawable.ic_schedule,
        MaterialR.attr.colorTertiaryContainer, MaterialR.attr.colorOnTertiaryContainer,
        MaterialR.attr.colorTertiary
    ),
    PROBLEM(
        R.drawable.ic_error_outline,
        MaterialR.attr.colorErrorContainer, MaterialR.attr.colorOnErrorContainer,
        androidx.appcompat.R.attr.colorError
    )
}

/** A row of a card, with its [status] as an icon at its end, in its color. */
class StatusPreference(context: Context) : Preference(context) {

    var status: Status = Status.OK
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        widgetLayoutResource = R.layout.preference_widget_status
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val icon = holder.findViewById(R.id.status_icon) as? ImageView ?: return
        icon.setImageResource(status.icon)
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, status.accent))
    }
}

/**
 * The top of the resources status: a card in the color of the [status], with an
 * icon (or a spinner while [loading]), a [title], a [subtitle], and four numbers
 * below ([stats]: file types, themes, grammars, queries; hidden while null).
 */
class StatusHeaderPreference(context: Context) : Preference(context), KeepsLayout {

    var status: Status = Status.OK
    var loading: Boolean = false
    var headline: CharSequence = ""
    var subtitle: CharSequence = ""
    var stats: IntArray? = null

    init {
        layoutResource = R.layout.preference_status_header
        isSelectable = false
        isPersistent = false
    }

    /** Shows what is set now. */
    fun refresh() = notifyChanged()

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val card = holder.itemView
        val container = MaterialColors.getColor(card, status.container)
        val onContainer = MaterialColors.getColor(card, status.onContainer)

        card.backgroundTintList = ColorStateList.valueOf(container)
        holder.findViewById(R.id.status_badge)?.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.getColor(card, MaterialR.attr.colorSurface))

        (holder.findViewById(R.id.status_icon) as? ImageView)?.apply {
            isVisible = !loading
            setImageResource(status.icon)
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, status.accent))
        }
        holder.findViewById(R.id.status_progress)?.isVisible = loading

        (holder.findViewById(R.id.status_title) as? TextView)?.apply {
            text = headline
            setTextColor(onContainer)
        }
        (holder.findViewById(R.id.status_subtitle) as? TextView)?.apply {
            text = subtitle
            setTextColor(onContainer)
        }

        val values = stats
        holder.findViewById(R.id.status_stats)?.visibility = if (values != null) View.VISIBLE else View.GONE
        val tiles = listOf(
            R.id.stat_file_types_value to R.id.stat_file_types_label,
            R.id.stat_themes_value to R.id.stat_themes_label,
            R.id.stat_libraries_value to R.id.stat_libraries_label,
            R.id.stat_queries_value to R.id.stat_queries_label
        )
        tiles.forEachIndexed { index, (valueId, labelId) ->
            (holder.findViewById(valueId) as? TextView)?.apply {
                text = values?.getOrNull(index)?.toString() ?: "–"
                setTextColor(onContainer)
            }
            (holder.findViewById(labelId) as? TextView)?.setTextColor(onContainer)
        }
    }
}
