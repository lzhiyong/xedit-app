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

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TabStopSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView

import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat

import com.google.android.material.color.MaterialColors

import x.editor.app.R

import com.google.android.material.R as MaterialR

/**
 * The shortcut of a menu item after its title, as in the menus of a desktop editor:
 * "Search        Ctrl+F", the shortcuts of a menu in a column of their own, smaller and
 * in a quieter color.
 *
 * Android's own menu shortcuts (MenuItem.setAlphabeticShortcut) are no use for that:
 * they are only shown with a hardware keyboard, and are one char with modifiers, where
 * the keymap has function keys and sequences (`<C-k>c`). So the title carries it: the
 * title, a tab, the shortcut, and a [TabStopSpan] that puts the shortcut at the column.
 * A single-line TextView lays out a text with a tab with a StaticLayout, which keeps tab
 * stops, and the menu measures its width with them; so the menu is as wide as it shows.
 *
 * The menu must not grow wide for it:
 *  - the column starts after the longest title that has a shortcut, but never further
 *    right than R.dimen.menu_shortcut_max_column; an item whose title goes past that
 *    shows no shortcut rather than pushing the column out;
 *  - a shortcut longer than R.dimen.menu_shortcut_max_width (a long sequence) is cut
 *    with an ellipsis;
 *  - a command with several shortcuts shows the shortest one.
 *
 * Only for items in a list (the overflow menu, a popup menu): the title of a button on
 * the toolbar is its tooltip, which should not have a tab in it.
 */
object MenuShortcuts {

    /** The items of the toolbar's overflow menu (R.menu.editor_toolbar) and what they run. */
    val OVERFLOW: Map<Int, ShortcutCommand> = mapOf(
        R.id.action_open_file to ShortcutCommand.OPEN_FILE,
        R.id.action_edit_mode to ShortcutCommand.READ_ONLY,
        R.id.action_search to ShortcutCommand.SEARCH,
        R.id.action_goto_line to ShortcutCommand.GOTO_LINE,
        R.id.action_wordwrap to ShortcutCommand.WORDWRAP,
        R.id.action_fullscreen to ShortcutCommand.FULLSCREEN,
        R.id.action_console to ShortcutCommand.CONSOLE,
    )

    /**
     * The items that are buttons on the toolbar, and what they run: in a menu of their
     * own when the toolbar is away (full screen's menu) they are in the list too.
     */
    val TOOLBAR_BUTTONS: Map<Int, ShortcutCommand> = mapOf(
        R.id.action_undo to ShortcutCommand.UNDO,
        R.id.action_redo to ShortcutCommand.REDO,
        R.id.action_save_file to ShortcutCommand.SAVE,
        R.id.action_run to ShortcutCommand.RUN,
    )

    // the shortcut against the title
    private const val SHORTCUT_SCALE = 0.8f

    // a disabled item: its shortcut as faint as its title (Material's disabled opacity)
    private const val DISABLED_ALPHA = 0.38f

    /**
     * Puts the shortcut of each item of [commands] that is in [menu] after its title, by
     * [keymap]; an item without a shortcut (or with no room for it) gets its plain title
     * back. Call it after the titles and the enabled state are set (onPrepareOptionsMenu),
     * and again whenever they change: it can be called any number of times.
     *
     * @param context the menu's (its theme gives the text appearance of the items)
     */
    fun apply(context: Context, menu: Menu, keymap: Keymap, commands: Map<Int, ShortcutCommand>) {
        val resources = context.resources
        val titlePaint = titlePaint(context)
        val shortcutPaint = TextPaint(titlePaint).apply { textSize *= SHORTCUT_SCALE }
        val gap = resources.getDimensionPixelSize(R.dimen.menu_shortcut_gap)
        val maxShortcutWidth = resources.getDimensionPixelSize(R.dimen.menu_shortcut_max_width).toFloat()
        val maxColumn = resources.getDimensionPixelSize(R.dimen.menu_shortcut_max_column)
        val muted = MaterialColors.getColor(context, MaterialR.attr.colorOnSurfaceVariant, Color.GRAY)

        class Entry(val item: MenuItem, val title: CharSequence, val titleWidth: Int, val shortcut: String?)

        val entries = commands.mapNotNull { (id, command) ->
            val item = menu.findItem(id) ?: return@mapNotNull null
            val title = plainTitle(item.title) ?: return@mapNotNull null
            val width = Math.ceil(titlePaint.measureText(title, 0, title.length).toDouble()).toInt()
            Entry(item, title, width, label(keymap, command))
        }
        // the column: after the longest title that has a shortcut, at most at maxColumn
        val widest = entries.filter { it.shortcut != null }.maxOfOrNull { it.titleWidth } ?: return run {
            entries.forEach { it.item.title = it.title }
        }
        val column = minOf(widest + gap, maxColumn)

        for (entry in entries) {
            val shortcut = entry.shortcut
            if (shortcut == null || entry.titleWidth + gap > column) {
                entry.item.title = entry.title
                continue
            }
            val shown = TextUtils.ellipsize(shortcut, shortcutPaint, maxShortcutWidth, TextUtils.TruncateAt.END)
            val color = if (entry.item.isEnabled) muted
                else ColorUtils.setAlphaComponent(muted, (Color.alpha(muted) * DISABLED_ALPHA).toInt())
            entry.item.title = SpannableStringBuilder(entry.title).apply {
                append('\t')
                val start = length
                append(shown)
                setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(SHORTCUT_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // a paragraph style: over the whole (one-line) paragraph
                setSpan(TabStopSpan.Standard(column), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
    }

    /**
     * The shortcut of [command] in [keymap] as it is written in a menu ("Ctrl+F",
     * "Ctrl+K C"); the shortest if it has several (fewest keys, then fewest chars),
     * null if it has none.
     */
    fun label(keymap: Keymap, command: ShortcutCommand): String? =
        keymap.mappings.asSequence()
            .filter { (it.action as? ShortcutAction.Run)?.command == command }
            .map { it.keys }
            .minWithOrNull(compareBy<List<KeyStroke>> { it.size }.thenBy { format(it).length })
            ?.let(::format)

    /** [keys] as written in a menu: the keys of a stroke joined by "+", the strokes by a space. */
    fun format(keys: List<KeyStroke>): String =
        keys.joinToString(" ") { stroke -> stroke.capLabels.joinToString("+") }

    // the title without a shortcut put after it before (everything from the tab on)
    private fun plainTitle(title: CharSequence?): CharSequence? {
        if (title == null) return null
        val tab = TextUtils.indexOf(title, '\t')
        return if (tab < 0) title else title.subSequence(0, tab).toString()
    }

    // How the items' titles are drawn: AppCompat's popup menu item takes the text
    // appearance textAppearanceLargePopupMenu (Material 3: Body Large).
    private fun titlePaint(context: Context): TextPaint {
        val view = TextView(context)
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(
            androidx.appcompat.R.attr.textAppearanceLargePopupMenu, value, true
        ) && value.resourceId != 0
        if (resolved) {
            TextViewCompat.setTextAppearance(view, value.resourceId)
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        return TextPaint(view.paint)
    }
}
