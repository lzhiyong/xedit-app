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

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator

import x.editor.app.R
import x.editor.app.view.CollapseAnimator
import x.editor.app.view.HandleDragSheetDialog
import x.editor.app.view.showSettled
import x.editor.app.view.MaxHeightRecyclerView

/**
 * The skeleton every panel of the developer tools that lists files is built on
 * (sheet_config_list): LanguageServersSheet, SnippetsSheet (through ConfigFilesSheet),
 * FormattingSheet, ThemeSheet. ToolchainSheet and ShortcutsSheet, whose entries are in one
 * file, have the same parts and gestures in layouts of their own.
 *
 * The parts, always in this order: the drag handle (the only thing that drags the sheet:
 * setDraggedByHandleOnly), the title with ⓘ ([setHelp]: PanelHelp, shown by itself the
 * first time), the status card ([setStatus]: a sentence, at most one next step), what the
 * panel has of its own and its editor ([setExtra], hidden at first), the title of the list
 * with New ([setNewAction]), the list ([setEntries]; empty: an icon, a sentence and the
 * button that makes one), and where the files are ([setFooter]: a tap copies it).
 *
 * One set of gestures, the same on every panel:
 *  - a tap on a row: how it is doing, in the window by it ([popup], [showInfo]: EntryInfo);
 *  - ⋮ on a row, or a long press: what can be done with it ([Entry.onMore]: an
 *    EntryActionsDialog, Edit first, Delete last and red: [actionsDialog]);
 *  - New by the list: a name, then a draft in the editor (written only when it is saved).
 * The row tinted is the one in the editor, and only that; a tap does not change it.
 *
 * One window at a time ([popup]): the info of a row, the help, what is wrong with a text.
 * Closing the sheet with changes not saved asks first ([unsavedFileName]).
 *
 * One thing at a time ([gate]): while a save, a delete, a preview goes on, New, the next step of
 * the card and ⋮ of the rows are off, its row has a ring and says what goes on, the panel's
 * editor is locked ([onBusyChanged]), and a write is not closed on. A tap on a row still shows
 * its info: that changes nothing.
 */
internal abstract class ConfigListSheet(protected val activity: Activity, protected val scope: LifecycleCoroutineScope) {

    protected val view: View = LayoutInflater.from(activity).inflate(R.layout.sheet_config_list, null)

    // dragged by its handle only (setDraggedByHandleOnly), which stays at the top
    protected val dialog: HandleDragSheetDialog = HandleDragSheetDialog(activity).apply {
        setDragHandle(view.findViewById(R.id.config_drag_handle))
    }

    /** The window of the panel, one at a time: the info of a row, the help, what is wrong. */
    internal val popup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)

    private val list = view.findViewById<MaxHeightRecyclerView>(R.id.config_list).apply {
        layoutManager = LinearLayoutManager(activity)
        // the rows change as a whole (setEntries), there is nothing to animate
        itemAnimator = null
    }
    // stable ids (Entry.key): a row stays the same view while its entry is there, however
    // often the list is shown again (a window by it stays by it: rowOf)
    private val adapter = EntryAdapter().apply { setHasStableIds(true) }.also { list.adapter = it }
    private val empty = view.findViewById<View>(R.id.config_empty)

    // whether the list has been given its entries yet
    private var entriesShown = false

    // the colours of the levels (StateLevel)
    protected val good = StateLevel.GOOD.color(activity)
    protected val warn = StateLevel.WARN.color(activity)
    protected val bad = StateLevel.BAD.color(activity)
    protected val muted = StateLevel.QUIET.color(activity)
    protected val primary = StateLevel.ACTIVE.color(activity)

    // the key of the row whose info is shown (showInfo), to bring it up to date in place
    private var infoKey: String? = null

    /** What goes on on the panel: one thing at a time. */
    protected val gate = OperationGate().also { gate ->
        gate.onChanged = { busyChanged() }
    }

    /** What goes on, in a few words; null: nothing. */
    protected fun busyText(): String? = gate.current?.describe(activity)

    /** Whether something goes on, and then what was asked for is not done: says so. */
    protected fun refusedWhileBusy(): Boolean {
        toastBusy(activity, busyText() ?: return false)
        return true
    }

    /** [op] on [subject] ([key]: the entry it is on) begun; something else going on: said, and null. */
    protected fun beginOrRefuse(op: PanelOp, subject: String, key: String? = null): OperationGate.Ticket? {
        val ticket = gate.begin(op, subject, key)
        if (ticket == null) refusedWhileBusy()
        return ticket
    }

    /** What goes on changed ([busy]: in a few words; null: nothing does): the panel locks its editor, or unlocks it. */
    protected open fun onBusyChanged(busy: CharSequence?) {}

    // what can start something is off while something goes on, and the rows show it
    @SuppressLint("NotifyDataSetChanged")
    private fun busyChanged() {
        val busy = busyText()
        val idle = busy == null
        view.findViewById<View>(R.id.config_list_new).isEnabled = idle
        view.findViewById<View>(R.id.config_empty_action).isEnabled = idle
        view.findViewById<View>(R.id.config_status_action).isEnabled = idle
        adapter.notifyDataSetChanged()
        onBusyChanged(busy)
    }

    /** Shows the sheet, with [title]; the panel fills it in after. */
    protected fun showSheet(title: CharSequence) {
        view.findViewById<TextView>(R.id.config_title).text = title
        dialog.onCloseRequest = { close ->
            val name = unsavedFileName()
            val running = gate.current
            if (running != null && !running.op.closable) {
                // a write going on: not to be closed on, nor asked about ("discard?" over what is being saved)
                refusedWhileBusy()
                true
            } else if (name == null) {
                false
            } else {
                ConfigDialogs.confirmDiscard(activity, activity.getString(R.string.config_discard_close, name)) { close() }
                true
            }
        }
        dialog.setContentView(view)
        // Set before the sheet is shown, not after it (where each panel had it): as tall as
        // its content, open all the way, no state part way — dragged down by the handle it
        // closes. Every panel of the developer tools opens the same way.
        dialog.setDraggedByHandleOnly(true)
        dialog.behavior.apply {
            isFitToContents = true
            peekHeight = 0
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // not shown before the list has what is read from disk (setEntries): it starts empty,
        // and the sheet would grow with it (or with why it is empty, or a longer card)
        dialog.showSettled { entriesShown }
    }

    /**
     * The file whose changes in the editor are not saved (a draft too), named as the user
     * knows it; null: none. Closing the sheet asks first while there is one.
     */
    protected open fun unsavedFileName(): String? = null

    /**
     * ⓘ: what the panel is for ([what]), how it is used ([how]), where its files are
     * ([where]); shown by itself the first time the panel [id] is opened.
     */
    protected fun setHelp(id: String, what: CharSequence, how: CharSequence, where: () -> String) {
        val title = view.findViewById<TextView>(R.id.config_title).text
        PanelHelp(activity, scope, popup, id, title, what, how, where)
            .attach(view.findViewById(R.id.config_help), view.findViewById(R.id.config_title_row)) { dialog.isShowing }
    }

    /** The status card: a dot of [level], [title], a sentence ([text]); [action]: the next step, if any. */
    protected fun setStatus(level: StateLevel, title: CharSequence, text: CharSequence, action: PopupAction? = null) {
        view.findViewById<View>(R.id.config_status_dot).background = dot(level.color(activity))
        view.findViewById<TextView>(R.id.config_status_title).text = title
        view.findViewById<TextView>(R.id.config_status_text).apply {
            this.text = text
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<MaterialButton>(R.id.config_status_action).apply {
            if (action == null) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                this.text = action.label
                visibility = View.VISIBLE
                isEnabled = !gate.isBusy
                setOnClickListener { action.run() }
            }
        }
    }

    /** [content] between the card and the list: what a panel shows of its own, its editor. */
    protected fun setExtra(content: View) {
        view.findViewById<FrameLayout>(R.id.config_extra).apply {
            removeAllViews()
            addView(content)
            visibility = View.VISIBLE
        }
    }

    // what a panel shows of its own (setExtra), and its closing (setExtraVisible, animate)
    private val extraCollapse by lazy { CollapseAnimator(view.findViewById<FrameLayout>(R.id.config_extra)) }

    /**
     * What a panel shows of its own (setExtra: its editor) shown, or hidden. [animate],
     * hidden: it closes upwards and fades (the sheet shrinking with it), rather than going at
     * once (CollapseAnimator). Shown again while it closes: it stops closing, and is there.
     */
    protected fun setExtraVisible(visible: Boolean, animate: Boolean = false) {
        extraCollapse.setVisible(visible, animate)
    }

    /** The title of the list, with [icon] before it; none: the title alone. */
    protected fun setListTitle(title: CharSequence, @DrawableRes icon: Int? = null) {
        view.findViewById<TextView>(R.id.config_list_title).text = title
        view.findViewById<View>(R.id.config_list_icon_frame).visibility = if (icon == null) View.GONE else View.VISIBLE
        if (icon != null) view.findViewById<ImageView>(R.id.config_list_icon).setImageResource(icon)
    }

    /** New, by the title of the list; [onNew] when it is tapped (the sheet stays). */
    protected fun setNewAction(onNew: () -> Unit) {
        view.findViewById<MaterialButton>(R.id.config_list_new).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                popup.dismiss()
                onNew()
            }
        }
    }

    /** Where the files are ([path]); a tap on it copies it. */
    protected fun setFooter(path: String) {
        view.findViewById<TextView>(R.id.config_footer).apply {
            text = activity.getString(R.string.config_footer_location, path)
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(path, path))
                Toast.makeText(activity, R.string.config_footer_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** No entries: an [icon], a sentence in large letters ([title]), and the button that makes one ([action]). */
    protected class Empty(@DrawableRes val icon: Int, val title: CharSequence, val action: PopupAction? = null)

    /** The entries again; [whenEmpty] if there are none. The info shown is brought up to date by [infoOf]. */
    @SuppressLint("NotifyDataSetChanged")
    protected fun setEntries(entries: List<Entry>, whenEmpty: Empty, infoOf: ((String) -> EntryInfo?)? = null) {
        entriesShown = true
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) {
            view.findViewById<ImageView>(R.id.config_empty_icon).setImageResource(whenEmpty.icon)
            view.findViewById<TextView>(R.id.config_empty_title).text = whenEmpty.title
            view.findViewById<MaterialButton>(R.id.config_empty_action).apply {
                val action = whenEmpty.action
                visibility = if (action == null) View.GONE else View.VISIBLE
                text = action?.label
                isEnabled = !gate.isBusy
                setOnClickListener { action?.run() }
            }
        }
        adapter.setEntries(entries)
        adapter.notifyDataSetChanged()
        if (infoOf != null) list.post { refreshInfo(infoOf) }
    }

    /**
     * The row shown now of the entry of [key]; null: none (not in the list, or scrolled out
     * of it). The same view as long as the entry is in the list (stable ids).
     */
    protected fun rowOf(key: String): View? =
        adapter.idOf(key)?.let { list.findViewHolderForItemId(it) }?.itemView

    /** [info] in the window by the row of [key] (the row tapped, if it cannot be found). */
    protected fun showInfo(key: String, info: EntryInfo) {
        val row = rowOf(key)?.takeIf { it.isAttachedToWindow } ?: tappedRow?.takeIf { it.isAttachedToWindow }
        if (row == null) {
            popup.dismiss()
            return
        }
        infoKey = key
        popup.show(row, info)
    }

    /** The info shown by a row brought up to date ([infoOf] its key; null: it is gone, and so is the window). */
    protected fun refreshInfo(infoOf: (String) -> EntryInfo?) {
        val key = infoKey ?: return
        if (!popup.isShowing) {
            infoKey = null
            return
        }
        val row = rowOf(key)
        if (row == null || !popup.isShowingBy(row)) return
        val info = infoOf(key)
        if (info == null) {
            popup.dismiss()
            infoKey = null
        } else {
            popup.show(row, info)
        }
    }

    /**
     * The list no taller than [maxHeight] (pixels), its rows scrolling inside it, so that
     * many entries do not make the sheet long; 0: as tall as all of them.
     */
    protected fun setListMaxHeight(maxHeight: Int) {
        list.setMaxHeight(maxHeight)
    }

    /**
     * An entry: [name], [state] in the colour of [level] (no state: no dot), [detail] (a
     * line, grey), [problem] (red, only when there is one). [badge]: its letters and colour
     * (null: of its key, badgeOf). [dimmed]: turned off. [selected]: it is in the editor (the
     * row tinted). [current]: it is about the file in the editor (〔current file〕). [busy]:
     * something goes on (a ring for the dot). [onClick]: a tap (its info); [onMore]: ⋮ and a
     * long press (its actions).
     */
    protected class Entry(
        val key: String,
        val name: CharSequence,
        val state: CharSequence,
        val level: StateLevel,
        val detail: CharSequence = "",
        val problem: CharSequence = "",
        val badge: Pair<String, Int>? = null,
        val dimmed: Boolean = false,
        val selected: Boolean = false,
        val current: Boolean = false,
        val busy: Boolean = false,
        val onMore: (() -> Unit)? = null,
        val onClick: () -> Unit,
    )

    /** The row of the entry tapped last (what a window is shown by when its own cannot be found). */
    protected var tappedRow: View? = null
        private set

    private class EntryHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {
        var entries: List<Entry> = emptyList()
            private set

        // the id of each key ever shown (the same key, the same id), and those of the entries
        private val ids = HashMap<String, Long>()
        private var itemIds: List<String> = emptyList()

        fun setEntries(entries: List<Entry>) {
            this.entries = entries
            val seen = HashSet<String>()
            itemIds = entries.mapIndexed { index, entry -> if (seen.add(entry.key)) entry.key else "${entry.key}#$index" }
        }

        fun idOf(key: String): Long? = if (key in itemIds) idOfKey(key) else null

        private fun idOfKey(key: String): Long = ids.getOrPut(key) { ids.size.toLong() }

        override fun getItemId(position: Int): Long = idOfKey(itemIds[position])

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EntryHolder(LayoutInflater.from(activity).inflate(R.layout.item_config_entry, parent, false))

        override fun onBindViewHolder(holder: EntryHolder, position: Int) = bind(holder.itemView, entries[position])
    }

    private fun bind(row: View, entry: Entry) {
        val density = row.resources.displayMetrics.density
        // what goes on is on this entry: its ring, and what it is in its state's place
        val operation = gate.current
        val working = operation != null && operation.key == entry.key
        val (letters, color) = entry.badge ?: badgeOf(entry.key)
        row.findViewById<TextView>(R.id.entry_badge).apply {
            text = letters
            setTextColor(color)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(ColorUtils.setAlphaComponent(color, 0x33))
            }
        }
        row.findViewById<TextView>(R.id.entry_name).text = entry.name
        row.findViewById<TextView>(R.id.entry_current).apply {
            visibility = if (entry.current) View.VISIBLE else View.GONE
            setTextColor(primary)
            background = GradientDrawable().apply {
                cornerRadius = 100 * density
                setColor(ColorUtils.setAlphaComponent(primary, 0x24))
            }
        }
        val stateColor = entry.level.color(activity)
        row.findViewById<View>(R.id.entry_dot).apply {
            background = dot(stateColor)
            visibility = if (entry.state.isEmpty() || entry.busy || working) View.GONE else View.VISIBLE
        }
        row.findViewById<CircularProgressIndicator>(R.id.entry_progress).apply {
            setIndicatorColor(stateColor)
            visibility = if (entry.busy || working) View.VISIBLE else View.GONE
        }
        row.findViewById<TextView>(R.id.entry_state).apply {
            text = if (working) operation?.describe(activity) else entry.state
            setTextColor(stateColor)
        }
        row.findViewById<TextView>(R.id.entry_detail).apply {
            text = entry.detail
            visibility = if (entry.detail.isEmpty()) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.entry_problem).apply {
            text = entry.problem
            setTextColor(bad)
            visibility = if (entry.problem.isEmpty()) View.GONE else View.VISIBLE
        }
        // off (a server not enabled): there, but quieter
        row.alpha = if (entry.dimmed) 0.6f else 1f
        // in the editor: tinted, in rounded corners (the ripple is in the foreground)
        row.background = if (entry.selected) GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(ColorUtils.setAlphaComponent(primary, SELECTED_ALPHA))
        } else null
        row.setOnClickListener {
            tappedRow = row
            entry.onClick()
        }
        val more = entry.onMore
        val moreButton = row.findViewById<MaterialButton>(R.id.entry_more)
        moreButton.visibility = if (more == null) View.INVISIBLE else View.VISIBLE
        // nothing can be done with an entry while something goes on: dimmed, a long press says why
        moreButton.isEnabled = operation == null
        moreButton.alpha = if (operation == null) 1f else BUSY_ALPHA
        moreButton.setOnClickListener {
            tappedRow = row
            popup.dismiss()
            more?.invoke()
        }
        if (more != null) {
            row.setOnLongClickListener {
                tappedRow = row
                popup.dismiss()
                if (!refusedWhileBusy()) more()
                true
            }
        } else {
            row.setOnLongClickListener(null)
            row.isLongClickable = false
        }
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * The dialog of an entry's actions (⋮, a long press), its head filled in: the tile of
     * its badge, [name], its [state] in the colour of [level]. The panel adds the actions in
     * the order of every panel: Edit first (dimmed, \"Editing\", when it is in the editor),
     * then its own, then Delete (red, last).
     */
    internal fun actionsDialog(key: String, name: CharSequence, state: CharSequence, level: StateLevel,
                               badge: Pair<String, Int>? = null): EntryActionsDialog {
        val (letters, color) = badge ?: badgeOf(key)
        val dialog = EntryActionsDialog(activity, name).tile(letters, color, withAlpha(color, TILE_ALPHA))
        if (state.isNotEmpty()) {
            val stateColor = level.color(activity)
            dialog.badge(state, stateColor, withAlpha(stateColor, BADGE_ALPHA))
        }
        return dialog
    }

    /** The tint of a button (an icon of a row) in [color]. */
    protected fun tintOf(color: Int): ColorStateList = ColorStateList.valueOf(color)

    /** [color] with [alpha] (0..255) in place of its own (a tile, a badge of a dialog: faint). */
    protected fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** A badge of a name: its first letters (two at most), the colour of its hash. */
    protected fun badgeOf(name: String): Pair<String, Int> {
        val letters = name.filter { it.isLetterOrDigit() }.take(2).ifEmpty { "?" }
        return letters to BADGE_COLORS[Math.floorMod(name.hashCode(), BADGE_COLORS.size)]
    }

    /** The list scrolled so that the entry at [index] is in it. */
    protected fun scrollToEntry(index: Int) {
        if (index < 0) return
        list.post { if (index < adapter.itemCount) list.scrollToPosition(index) }
    }

    companion object {
        // the tint of the row in the editor
        private const val SELECTED_ALPHA = 0x29

        // ⋮ of a row while something goes on
        private const val BUSY_ALPHA = 0.38f

        // the tile of an entry's dialog, and the badge of its state: their colour, faint
        const val TILE_ALPHA = 0x2E
        const val BADGE_ALPHA = 0x26

        // the list no taller than this (about four rows), scrolling beyond
        const val LIST_MAX_HEIGHT_DP = 260

        private val BADGE_COLORS = intArrayOf(
            0xFF61AFEF.toInt(), 0xFFE5C07B.toInt(), 0xFF98C379.toInt(),
            0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFD19A66.toInt()
        )
    }
}
