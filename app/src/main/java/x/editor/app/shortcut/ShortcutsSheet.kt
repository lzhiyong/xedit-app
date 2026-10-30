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

import android.app.Activity
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * The shortcuts as they are (keymap.json over the defaults), on one sheet: the commands
 * in their groups (Edit, Code, File and view), each a card of rows as in the settings,
 * one row for each mapping of a command (one with "Not set" for a command without any),
 * the keys drawn as caps; then the mappings that insert text. Under each name the action
 * as keymap.json names it, to write there. What was left out of the file when it was
 * read last is shown above, in the error color.
 *
 * Nothing is changed here: "Edit" opens keymap.json in the editor ([onEdit]), which is
 * read again when it is saved.
 */
class ShortcutsSheet(
    private val activity: Activity,
    private val keymap: Keymap,
    private val problems: List<String>,
    private val onEdit: () -> Unit
) {

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_shortcuts, null)
    private val dialog = BottomSheetDialog(activity)
    private val sections = view.findViewById<LinearLayout>(R.id.shortcuts_sections)

    // the icons as in the settings: on a round container of the secondary color
    private val iconTint: ColorStateList? = ContextCompat.getColorStateList(activity, R.color.pref_icon_tint)

    fun show() {
        view.findViewById<TextView>(R.id.shortcuts_subtitle).text = activity.getString(
            R.string.shortcut_sheet_subtitle,
            keymap.leader.joinToString(" ") { it.capLabels.joinToString("+") },
            keymap.timeoutMillis
        )
        view.findViewById<View>(R.id.shortcuts_edit).setOnClickListener {
            dialog.dismiss()
            onEdit()
        }
        if (problems.isNotEmpty()) {
            view.findViewById<View>(R.id.shortcuts_problems_card).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.shortcuts_problems).text =
                activity.getString(R.string.shortcut_problems, problems.size) + "\n" +
                    problems.joinToString("\n") { "• $it" }
        }
        render()
        dialog.setContentView(view)
        dialog.behavior.apply {
            // a long list: open at its full height, and closed by one drag down
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun render() {
        val byCommand = keymap.mappings.groupBy { (it.action as? ShortcutAction.Run)?.command }
        ShortcutGroup.entries.forEach { group ->
            val rows = ArrayList<View>()
            ShortcutCommand.entries.filter { it.group == group }.forEach { command ->
                val mappings = byCommand[command].orEmpty().sortedBy { it.keys.size }
                if (mappings.isEmpty()) {
                    rows += row(command.icon, titleOf(command), command.id, emptyList())
                } else {
                    mappings.forEach { mapping ->
                        val title = mapping.desc.ifEmpty { titleOf(command) }
                        rows += row(command.icon, title, command.id, mapping.keys)
                    }
                }
            }
            addGroup(activity.getString(group.title), rows)
        }

        // the mappings that put text in: by their description, or what they insert
        val inserts = keymap.mappings.filter { it.action is ShortcutAction.Insert }
        if (inserts.isNotEmpty()) {
            val rows = inserts.map { mapping ->
                val body = (mapping.action as ShortcutAction.Insert).body
                row(
                    R.drawable.ic_snippets,
                    mapping.desc.ifEmpty { activity.getString(R.string.shortcut_insert_text) },
                    oneLine(body),
                    mapping.keys
                )
            }
            addGroup(activity.getString(R.string.shortcut_group_insert), rows)
        }
    }

    private fun addGroup(title: String, rows: List<View>) {
        sections.addView(TextView(activity).apply {
            text = title
            setTextAppearance(resolve(MaterialR.attr.textAppearanceTitleSmall))
            setTextColor(MaterialColors.getColor(this@ShortcutsSheet.view, androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(4), dp(16), dp(4), dp(8))
        })
        rows.forEachIndexed { index, row ->
            // a thin gap between two rows, the outer corners large: one card, as in the settings
            sections.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = activity.resources.getDimensionPixelSize(R.dimen.pref_card_gap)
            })
            row.setBackgroundResource(
                when {
                    rows.size == 1 -> R.drawable.bg_pref_card_single
                    index == 0 -> R.drawable.bg_pref_card_top
                    index == rows.size - 1 -> R.drawable.bg_pref_card_bottom
                    else -> R.drawable.bg_pref_card_middle
                }
            )
        }
    }

    private fun row(icon: Int, title: String, detail: String, keys: List<KeyStroke>): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_shortcut, sections, false)
        row.findViewById<ImageView>(R.id.shortcut_icon).apply {
            setImageResource(icon)
            imageTintList = iconTint
        }
        row.findViewById<TextView>(R.id.shortcut_title).text = title
        row.findViewById<TextView>(R.id.shortcut_detail).apply {
            text = detail
            visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
        val notSet = activity.getString(R.string.shortcut_not_set)
        KeyCaps.render(row.findViewById(R.id.shortcut_keys), keys, notSet)
        row.contentDescription = "$title, " + (if (keys.isEmpty()) notSet else keys.joinToString(" ") {
            it.capLabels.joinToString("+")
        })
        // only shown: nothing to tap
        row.isClickable = false
        row.isFocusable = false
        return row
    }

    private fun titleOf(command: ShortcutCommand): String =
        activity.getString(command.title).replaceFirstChar { it.titlecase() }

    // the first line of a text, short
    private fun oneLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > 40) line.take(40) + "…" else line
    }

    private fun resolve(attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}
