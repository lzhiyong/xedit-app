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
package x.editor.app.config

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import x.editor.app.R
import x.editor.app.preference.SwatchesView

import com.google.android.material.R as MaterialR

/**
 * What can be done with an entry of a sheet of the developer tools, on a long press on it
 * (dialog_entry_actions): a theme of ThemeSheet, a server of LanguageServersSheet.
 *
 * At its head the entry at a glance: a tile ([tile]), its name, a line on it ([detail]; none
 * unless set: a server's dialog has none), a badge of what it is now ([badge]: "In use",
 * "Running"), and colors as swatches ([swatches]). What went wrong with an entry is not
 * shown here: LanguageServersSheet shows it in a popup by its row (EntryProblemPopup), on a
 * tap. Then the actions ([action]), a row each with an icon on a rounded tonal
 * square, what it is and a line on what it does; one that cannot be done now is dimmed and
 * its line says why; a dangerous one (removing) is in the error color. A tap on an action
 * closes the dialog and does it. Cancel closes it.
 */
internal class EntryActionsDialog(private val activity: Activity, name: CharSequence) {

    private val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_entry_actions, null)
    private val list = view.findViewById<LinearLayout>(R.id.entry_actions_list)
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setView(view)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    /**
     * The colors of the dialog: errors, and their container. Not named `error`: inside
     * `textView.apply { }` that would be TextView.getError() (a CharSequence), not this.
     */
    val errorColor: Int = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorError, 0xFFE06C75.toInt())
    val errorContainerColor: Int = MaterialColors.getColor(activity, MaterialR.attr.colorErrorContainer, 0x33E06C75)

    init {
        view.findViewById<TextView>(R.id.entry_actions_name).text = name
        view.findViewById<View>(R.id.entry_actions_swatches).visibility = View.GONE
        view.findViewById<View>(R.id.entry_actions_detail).visibility = View.GONE
    }

    /** The tile: [text] in [textColor] on [background] (a theme: "Aa"; a server: its badge). */
    fun tile(text: CharSequence, textColor: Int, background: Int): EntryActionsDialog = apply {
        view.findViewById<MaterialCardView>(R.id.entry_actions_tile).setCardBackgroundColor(background)
        view.findViewById<TextView>(R.id.entry_actions_tile_text).apply {
            this.text = text
            setTextColor(textColor)
        }
    }

    /** The line under the name; [color]: the error color for what is wrong, else the usual one. */
    fun detail(text: CharSequence, color: Int? = null): EntryActionsDialog = apply {
        view.findViewById<TextView>(R.id.entry_actions_detail).apply {
            this.text = text
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            if (color != null) setTextColor(color)
        }
    }

    /** A badge by the name ("In use", "Running"), in [textColor] on [background]. */
    fun badge(text: CharSequence, textColor: Int, background: Int): EntryActionsDialog = apply {
        view.findViewById<TextView>(R.id.entry_actions_badge).apply {
            this.text = text
            visibility = View.VISIBLE
            setTextColor(textColor)
            this.background = GradientDrawable().apply {
                cornerRadius = 100 * resources.displayMetrics.density
                setColor(background)
            }
        }
    }

    /** Colors as swatches under the head (a theme's); none: no swatches. */
    fun swatches(colors: IntArray): EntryActionsDialog = apply {
        view.findViewById<SwatchesView>(R.id.entry_actions_swatches).apply {
            this.colors = colors
            visibility = if (colors.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * An action: [icon], [title], what it does ([summary]). Not [enabled]: dimmed, and
     * [summary] says why. [danger]: in the error color (removing). Tapped: the dialog
     * closes, then [run].
     */
    fun action(
        @DrawableRes icon: Int,
        @StringRes title: Int,
        summary: CharSequence,
        enabled: Boolean = true,
        danger: Boolean = false,
        run: () -> Unit,
    ): EntryActionsDialog = apply {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_entry_action, list, false)
        row.findViewById<ImageView>(R.id.entry_action_icon).apply {
            setImageResource(icon)
            if (danger) imageTintList = ColorStateList.valueOf(errorColor)
        }
        if (danger) row.findViewById<View>(R.id.entry_action_icon_box).backgroundTintList = ColorStateList.valueOf(errorContainerColor)
        row.findViewById<TextView>(R.id.entry_action_title).apply {
            setText(title)
            if (danger) setTextColor(errorColor)
        }
        row.findViewById<TextView>(R.id.entry_action_summary).text = summary
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else DISABLED_ALPHA
        if (enabled) {
            row.setOnClickListener {
                dialog.dismiss()
                run()
            }
        }
        list.addView(row)
    }

    fun show() {
        dialog.show()
    }

    private companion object {
        // an action that cannot be done now
        const val DISABLED_ALPHA = 0.45f
    }
}
