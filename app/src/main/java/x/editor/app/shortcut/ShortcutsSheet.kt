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

import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.completion.SheetSchemas
import x.editor.app.config.ConfigDialogs
import x.editor.app.config.EntryActionsDialog
import x.editor.app.config.EntryInfo
import x.editor.app.config.EntryProblemPopup
import x.editor.app.config.OperationGate
import x.editor.app.config.PanelHelp
import x.editor.app.config.PanelOp
import x.editor.app.config.PopupAction
import x.editor.app.config.SheetFileEditor
import x.editor.app.config.StateLevel
import x.editor.app.config.askNewName
import x.editor.app.config.describe
import x.editor.app.config.toastBusy
import x.editor.app.view.CodeEditorView
import x.editor.app.view.CollapseAnimator
import x.editor.app.view.HandleDragSheetDialog
import x.editor.app.view.showSettled
import x.editor.app.view.MaxHeightRecyclerView
import x.github.module.editor.view.EditorView

/**
 * The shortcuts (menu → Developer tools → Shortcuts), a panel of the developer tools with
 * the parts and gestures of every one (ConfigListSheet), its entries the mappings of one
 * file, keymap.json:
 *
 *  - the status card: how many mappings, how many keymap.json left out (and a step to see
 *    them: every one, by its line, in the window, Edit at its foot); <leader> and timeoutlen;
 *  - the tabs: one for each group of commands (Edit, Code, File and view), the mappings
 *    that insert text, the user's own (`"groups"`, or the `"group"` of a mapping), and +,
 *    which makes a tab (added to `"groups"`, through the editor when it has changes);
 *  - the rows of the tab shown, each as in the settings, the keys drawn as caps: a row for
 *    each mapping of a command, one \"Not set\" for a command without any. A tap: its info
 *    (keys, action, what it is for, its tab); ⋮ or a long press: Edit (keymap.json in the
 *    editor, at that mapping: selected, its line lit up; Not set: a new mapping of it),
 *    Delete (that mapping out of keymap.json, asked first; off while the editor has changes);
 *  - New by the list: a mapping put at the end of `"keymaps"` in the editor, from the
 *    template (its action and tab, when the tab says them), the cursor where its keys go;
 *    written when it is saved.
 *
 * keymap.json is edited on the panel (SheetFileEditor), hidden at first; saved, it is read
 * again ([onSaved]). Closing the panel with changes not saved asks first.
 *
 * A tab of the user's own is deleted by the button by the title of the list, or a long press
 * on it: out of `"groups"`, and its mappings back to the tab of their command, not deleted.
 *
 * One thing at a time ([gate]): while keymap.json is written (a save, a mapping deleted, a tab
 * added or deleted) and read again, the editor is locked, nothing else can be begun and the
 * panel is not closed on.
 *
 * @param keymap the keymap as it is now (it changes when the file is saved)
 * @param problems what was left out of the file when it was read last
 * @param onSaved keymap.json was written: it is read again (and the user told)
 */
internal class ShortcutsSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val keymap: () -> Keymap,
    private val problems: () -> List<String>,
    styleEditor: (EditorView) -> Unit,
    highlight: suspend (CodeEditorView, String) -> Unit,
    private val onSaved: suspend () -> Unit,
) {

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_shortcuts, null)
    // dragged by its handle only, as every panel of the developer tools
    private val dialog = HandleDragSheetDialog(activity).apply {
        setDragHandle(view.findViewById(R.id.shortcuts_drag_handle))
    }
    private val popup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)
    private val editorFrame = view.findViewById<FrameLayout>(R.id.shortcuts_editor)
    private val editorCollapse = CollapseAnimator(editorFrame)
    private val tabs = view.findViewById<TabLayout>(R.id.shortcuts_tabs)
    private val list = view.findViewById<MaxHeightRecyclerView>(R.id.shortcuts_list)
    private val empty = view.findViewById<View>(R.id.shortcuts_empty)
    private val adapter = RowAdapter()

    // the icons as in the settings: on a round container of the secondary color
    private val iconTint: ColorStateList? = ContextCompat.getColorStateList(activity, R.color.pref_icon_tint)

    // the tabs the last save renamed, old name → new
    private var renamedTabs: Map<String, String> = emptyMap()

    // what goes on on the panel: one thing at a time
    private val gate = OperationGate().also { gate -> gate.onChanged = { busyChanged() } }

    private val fileEditor = SheetFileEditor(
        activity, scope, gate, { dialog.isShowing }, styleEditor, highlight, popup,
        check = { _, text -> problemsOf(KeymapFile.check(text)) },
        afterSave = { _, text ->
            (KeymapFile.check(text) as? KeymapFile.Check.Right)?.let { activity.getString(R.string.shortcut_after_save, it.count) }
        },
        defaultOf = { KeymapFile.template(activity) },
        // a tab renamed in "groups": its mappings go with it, or its old name would stay a tab
        write = { _, text ->
            val before = runCatching { KeymapFile.open(activity).readText() }.getOrNull()
            val renames = before?.let { KeymapFile.renamedGroups(it, text) }.orEmpty()
            KeymapFile.write(activity, KeymapFile.withGroupsRenamed(text, renames))
            renamedTabs = renames
        },
        onSaved = { _, _ ->
            Toast.makeText(activity, activity.getString(R.string.config_saved_then, KeymapFile.FILE,
                activity.getString(R.string.shortcut_saved_then)), Toast.LENGTH_SHORT).show()
            val renames = renamedTabs
            renamedTabs = emptyMap()
            // what was written is not what was typed: the file in the editor, as it is now
            if (renames.isNotEmpty()) openFile()
            readAgain((chosen as? Tab.Own)?.let { renames[it.name] })
        },
        // the actions, the groups, what a mapping may say, as it is typed
        schemaOf = { SheetSchemas.keymap(activity) },
    )

    // whether the editor is shown
    private var editorShown = false

    // the tabs as shown, and the one chosen
    private var tabList: List<Tab> = emptyList()
    private var chosen: Tab? = null
    // the tabs being put in again (render): what they say of being chosen is not a choice
    private var settingTabs = false

    /** A tab: a group of commands, the mappings that insert text, or one of the user's own. */
    private sealed class Tab {
        data class Group(val group: ShortcutGroup) : Tab()
        object Insert : Tab()
        data class Own(val name: String) : Tab()
    }

    // a row: what it does, its name, a line under it, its keys; the mapping (null: a command
    // not set), and the command
    private class Row(
        val icon: Int,
        val title: String,
        val detail: String,
        val keys: List<KeyStroke>,
        val mapping: KeyMapping?,
        val command: ShortcutCommand?,
    )

    // Whether something goes on, and then what was asked for is not done: says so
    private fun refusedWhileBusy(): Boolean {
        val running = gate.current ?: return false
        toastBusy(activity, running.describe(activity))
        return true
    }

    // the editor locked while something goes on, and what can start something off
    private fun busyChanged() {
        val busy = gate.current?.describe(activity)
        fileEditor.setLocked(busy)
        val idle = busy == null
        view.findViewById<View>(R.id.shortcuts_new).isEnabled = idle
        view.findViewById<View>(R.id.shortcuts_empty_action).isEnabled = idle
        view.findViewById<View>(R.id.shortcuts_tab_delete).isEnabled = idle
    }

    fun show() {
        view.findViewById<View>(R.id.shortcuts_new).setOnClickListener { newMapping(null) }
        view.findViewById<View>(R.id.shortcuts_tab_delete).setOnClickListener {
            (chosen as? Tab.Own)?.let { askDeleteTab(it.name) }
        }
        view.findViewById<View>(R.id.shortcuts_empty_action).setOnClickListener { newMapping(null) }
        fileEditor.setHelp(activity.getString(R.string.shortcut_help))
        fileEditor.setOnCollapse { hideEditor() }
        fileEditor.showNone(activity.getString(R.string.shortcut_editor_title, KeymapFile.FILE), "")
        editorFrame.addView(fileEditor.view)
        editorCollapse.setVisible(false)
        list.layoutManager = LinearLayoutManager(activity)
        // the rows change as a whole, there is nothing to animate
        list.itemAnimator = null
        list.adapter = adapter
        list.setMaxHeight((LIST_MAX_HEIGHT_DP * activity.resources.displayMetrics.density).toInt())
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (settingTabs) return
                val chosenTab = tab.tag as? Tab
                if (chosenTab == null) {
                    // +: a tab of the user's own; the one chosen stays chosen
                    tabs.getTabAt(tabList.indexOf(chosen).coerceAtLeast(0))?.select()
                    askNewTab()
                    return
                }
                chosen = chosenTab
                showRows()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.tag == null && !settingTabs) askNewTab()
            }
        })
        view.findViewById<TextView>(R.id.shortcuts_footer).apply {
            val path = KeymapFile.file(activity).path
            text = activity.getString(R.string.config_footer_location, path)
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(path, path))
                Toast.makeText(activity, R.string.config_footer_copied, Toast.LENGTH_SHORT).show()
            }
        }
        render()
        dialog.setContentView(view)
        // opens at its full height, and is closed by one drag down (by the handle)
        dialog.setDraggedByHandleOnly(true)
        dialog.onCloseRequest = { close ->
            val running = gate.current
            if (running != null && !running.op.closable) {
                // a write going on: not to be closed on, nor asked about
                toastBusy(activity, running.describe(activity))
                true
            } else if (!fileEditor.hasUnsavedChanges()) {
                false
            } else {
                ConfigDialogs.confirmDiscard(activity, activity.getString(R.string.config_discard_close, KeymapFile.FILE)) { close() }
                true
            }
        }
        dialog.setOnDismissListener {
            popup.dismiss()
            fileEditor.release()
        }
        dialog.showSettled()
        PanelHelp(
            activity, scope, popup, "shortcuts",
            activity.getString(R.string.action_shortcuts),
            activity.getString(R.string.shortcut_help_what),
            activity.getString(R.string.shortcut_help_how),
        ) { KeymapFile.file(activity).path }.attach(
            view.findViewById(R.id.shortcuts_help), view.findViewById(R.id.shortcuts_title_row)
        ) { dialog.isShowing }
    }

    // #region the editor

    // keymap.json in the editor (read the first time; as it was left after)
    private fun showEditor() {
        popup.dismiss()
        editorShown = true
        editorCollapse.setVisible(true)
        if (fileEditor.key == null || fileEditor.loadFailed) openFile()
    }

    private fun openFile() {
        fileEditor.open(KEY, KeymapFile.FILE, activity.getString(R.string.shortcut_editor_title, KeymapFile.FILE)) {
            KeymapFile.open(activity).readText()
        }
    }

    // the editor hidden (what is in it stays), and the keyboard with it; it closes upwards
    private fun hideEditor() {
        editorShown = false
        fileEditor.hideKeyboard()
        editorCollapse.setVisible(false, animate = true)
    }

    // what is wrong with a text of the keymap, a line each; empty: nothing
    private fun problemsOf(check: KeymapFile.Check): List<String> = when (check) {
        is KeymapFile.Check.Right -> emptyList()
        is KeymapFile.Check.Syntax -> listOf(
            if (check.line > 0) activity.getString(R.string.config_error_line, check.line, check.message) else check.message
        )
        is KeymapFile.Check.LeftOut -> check.problems
    }

    // keymap.json written: read again, and the tabs with it ([select]: the tab to show then)
    private suspend fun readAgain(select: String?) {
        onSaved()
        if (!dialog.isShowing) return
        if (select != null) chosen = Tab.Own(select)
        render()
    }

    // Edit of [mapping]: keymap.json in the editor, at that mapping (selected, its line lit up)
    private fun editMapping(mapping: KeyMapping) {
        if (refusedWhileBusy()) return
        showEditor()
        fileEditor.whenReady {
            val span = KeymapFile.spanOf(fileEditor.text(), mapping.keys, keymap().leader)
            if (span == null) {
                Toast.makeText(activity, R.string.shortcut_not_found, Toast.LENGTH_SHORT).show()
            } else {
                fileEditor.select(span.start, span.end)
            }
        }
    }

    // New (a mapping): put at the end of \"keymaps\" in the editor, from the template; [command]:
    // its action (a command not set), else the tab's when it says one; the cursor where its
    // keys go. Written when it is saved.
    private fun newMapping(command: ShortcutCommand?) {
        if (refusedWhileBusy()) return
        showEditor()
        val tab = chosen
        val group = when (tab) {
            is Tab.Own -> tab.name
            Tab.Insert -> ShortcutGroup.INSERT_ID
            else -> null
        }
        fileEditor.whenReady {
            val made = runCatching {
                KeymapFile.withNewMapping(activity, fileEditor.text(), command?.id.orEmpty(), group)
            }
            made.onSuccess { (text, caret) -> fileEditor.replaceText(text, caret) }
                .onFailure { Toast.makeText(activity, R.string.shortcut_not_added, Toast.LENGTH_LONG).show() }
        }
    }

    // #endregion

    // #region a mapping's actions

    // ⋮ / a long press: Edit (Not set: a new mapping of it), Delete (red, last; off while the
    // editor has changes not saved, or for a command not set)
    private fun showActions(row: Row) {
        popup.dismiss()
        if (refusedWhileBusy()) return
        val mapping = row.mapping
        val (state, level) = stateOf(row)
        val color = level.color(activity)
        val dialog = EntryActionsDialog(activity, row.title)
            .tile(keysText(row).ifEmpty { "—" }.take(TILE_CHARS), color, (color and 0x00FFFFFF) or (0x2E shl 24))
            .badge(state, color, (color and 0x00FFFFFF) or (0x26 shl 24))
        if (mapping != null) {
            dialog.action(R.drawable.ic_edit, R.string.config_edit, activity.getString(R.string.shortcut_action_edit_summary)) {
                editMapping(mapping)
            }
        } else {
            dialog.action(R.drawable.ic_add, R.string.shortcut_new_mapping, activity.getString(R.string.shortcut_action_set_summary)) {
                newMapping(row.command)
            }
        }
        val unsaved = fileEditor.hasUnsavedChanges()
        dialog.action(
            R.drawable.ic_delete, R.string.shortcut_action_delete,
            when {
                mapping == null -> activity.getString(R.string.shortcut_action_delete_none)
                unsaved -> activity.getString(R.string.config_action_unsaved)
                else -> activity.getString(R.string.shortcut_action_delete_summary)
            },
            enabled = mapping != null && !unsaved, danger = true,
        ) { mapping?.let { askDelete(row, it) } }
        dialog.show()
    }

    // Delete: the mapping out of keymap.json, once asked; read again then
    private fun askDelete(row: Row, mapping: KeyMapping) {
        val keys = keysText(row)
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.shortcut_delete_title, keys),
            activity.getString(R.string.shortcut_delete_message, keys, row.detail, KeymapFile.FILE),
            activity.getString(R.string.config_delete),
        ) {
            val ticket = gate.begin(PanelOp.DELETE, keys)
            if (ticket == null) {
                refusedWhileBusy()
                return@confirmDelete
            }
            val leader = keymap().leader
            scope.launch {
                try {
                    val done = withContext(Dispatchers.IO) {
                        runCatching {
                            val text = KeymapFile.open(activity).readText()
                            val edited = KeymapFile.withoutMapping(text, mapping.keys, leader) ?: return@runCatching false
                            if (KeymapFile.check(edited) is KeymapFile.Check.Syntax) return@runCatching false
                            KeymapFile.write(activity, edited)
                            true
                        }.getOrDefault(false)
                    }
                    if (!done) {
                        Toast.makeText(activity, R.string.shortcut_delete_failed, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    if (dialog.isShowing) {
                        Toast.makeText(activity, activity.getString(R.string.config_deleted, keys), Toast.LENGTH_SHORT).show()
                        // the file in the editor, as it is now (it had no changes: Delete is off then)
                        if (fileEditor.key != null) openFile()
                    }
                    // (the sheet closed meanwhile too: the file is written, the keys go by it)
                    readAgain(null)
                } finally {
                    gate.end(ticket)
                }
            }
        }
    }

    // #endregion

    // #region New tab

    // +: a name, then the tab added to \"groups\" of keymap.json
    private fun askNewTab() {
        if (refusedWhileBusy()) return
        val builtIn = ShortcutGroup.entries.flatMap { listOf(it.id, activity.getString(it.title)) } +
            listOf(ShortcutGroup.INSERT_ID, activity.getString(R.string.shortcut_group_insert))
        askNewName(
            activity,
            activity.getString(R.string.shortcut_new_tab_title),
            activity.getString(R.string.shortcut_new_tab_hint),
            problemOf = { name ->
                when {
                    name.isEmpty() -> activity.getString(R.string.config_new_empty)
                    name in builtIn || tabList.any { it is Tab.Own && it.name == name } ->
                        activity.getString(R.string.shortcut_tab_exists, name)
                    else -> null
                }
            },
        ) { name -> addTab(name) }
    }

    // With changes in the editor not saved: the tab put in the editor, with them. Else it is
    // put in the file and saved at once (if the file is right), and shown.
    private fun addTab(name: String) {
        if (refusedWhileBusy()) return
        if (fileEditor.key != null && fileEditor.hasUnsavedChanges()) {
            showEditor()
            fileEditor.replaceText(KeymapFile.withGroup(fileEditor.text(), name))
            Toast.makeText(activity, activity.getString(R.string.shortcut_tab_added_unsaved, name), Toast.LENGTH_LONG).show()
            return
        }
        val ticket = gate.begin(PanelOp.SAVE, KeymapFile.FILE) ?: return
        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = KeymapFile.withGroup(KeymapFile.open(activity).readText(), name)
                        if (KeymapFile.check(text) is KeymapFile.Check.Syntax) return@runCatching false
                        KeymapFile.write(activity, text)
                        true
                    }.getOrDefault(false)
                }
                if (!written) {
                    Toast.makeText(activity, R.string.shortcut_tab_not_added, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (dialog.isShowing) {
                    Toast.makeText(activity, activity.getString(R.string.shortcut_tab_added, name), Toast.LENGTH_SHORT).show()
                    if (fileEditor.key != null) openFile()
                }
                readAgain(name)
            } finally {
                gate.end(ticket)
            }
        }
    }

    // Delete of a tab of the user's own (the button by the title of the list, a long press on
    // it): once asked; off while the editor has changes (what is written is the file)
    private fun askDeleteTab(name: String) {
        if (refusedWhileBusy()) return
        if (fileEditor.hasUnsavedChanges()) {
            Toast.makeText(activity, R.string.config_action_unsaved, Toast.LENGTH_SHORT).show()
            return
        }
        val tab = Tab.Own(name)
        val count = keymap().mappings.count { tabOf(it) == tab }
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.shortcut_delete_tab_title, name),
            activity.getString(R.string.shortcut_delete_tab_message, name, KeymapFile.FILE, count),
            activity.getString(R.string.config_delete),
        ) { deleteTab(name) }
    }

    // The tab out of keymap.json, its mappings back to the tab of their command; read again then
    private fun deleteTab(name: String) {
        val ticket = gate.begin(PanelOp.DELETE, name)
        if (ticket == null) {
            refusedWhileBusy()
            return
        }
        scope.launch {
            try {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = KeymapFile.withoutGroup(KeymapFile.open(activity).readText(), name)
                        if (KeymapFile.check(text) is KeymapFile.Check.Syntax) return@runCatching false
                        KeymapFile.write(activity, text)
                        true
                    }.getOrDefault(false)
                }
                if (!written) {
                    Toast.makeText(activity, activity.getString(R.string.shortcut_delete_tab_failed, name), Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (dialog.isShowing) {
                    Toast.makeText(activity, activity.getString(R.string.config_deleted, name), Toast.LENGTH_SHORT).show()
                    if (fileEditor.key != null) openFile()
                }
                readAgain(null)
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #endregion

    // #region the tabs

    // the status card, the tabs (the one chosen kept; + last) and its rows
    private fun render() {
        val keymap = keymap()
        renderStatus(keymap)
        val inserts = keymap.mappings.any { it.action is ShortcutAction.Insert && tabOf(it) == Tab.Insert }
        val own = (keymap.groups + keymap.mappings.mapNotNull { (tabOf(it) as? Tab.Own)?.name }).distinct()
        tabList = ShortcutGroup.entries.map { Tab.Group(it) } +
            (if (inserts) listOf(Tab.Insert) else emptyList()) +
            own.map { Tab.Own(it) }
        val keep = chosen?.takeIf { it in tabList } ?: tabList.first()
        chosen = keep
        settingTabs = true
        tabs.removeAllTabs()
        for (tab in tabList) {
            val item = tabs.newTab().setText(titleOf(tab)).setTag(tab)
            tabs.addTab(item, false)
            // a tab of the user's own: a long press deletes it
            if (tab is Tab.Own) item.view.setOnLongClickListener {
                askDeleteTab(tab.name)
                true
            }
        }
        // the last: + (a tab of the user's own), its tag none
        tabs.addTab(tabs.newTab().setIcon(R.drawable.ic_add).setContentDescription(R.string.shortcut_new_tab_title), false)
        tabs.getTabAt(tabList.indexOf(keep))?.select()
        settingTabs = false
        showRows()
    }

    // How many mappings, how many left out (red: they do nothing), <leader> and timeoutlen;
    // the next step: see what was left out
    private fun renderStatus(keymap: Keymap) {
        val problems = problems()
        val level = if (problems.isEmpty()) StateLevel.GOOD else StateLevel.BAD
        view.findViewById<View>(R.id.shortcuts_status_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(level.color(activity))
        }
        view.findViewById<TextView>(R.id.shortcuts_status_title).text =
            if (problems.isEmpty()) activity.getString(R.string.shortcut_status_title, keymap.mappings.size)
            else activity.getString(R.string.shortcut_status_title_ignored, keymap.mappings.size, problems.size)
        view.findViewById<TextView>(R.id.shortcuts_subtitle).text = activity.getString(
            R.string.shortcut_sheet_subtitle,
            keymap.leader.joinToString(" ") { it.capLabels.joinToString("+") },
            keymap.timeoutMillis
        )
        view.findViewById<MaterialButton>(R.id.shortcuts_status_action).apply {
            visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener {
                popup.show(
                    view.findViewById<View>(R.id.shortcuts_status_card),
                    EntryInfo(
                        KeymapFile.FILE, activity.getString(R.string.shortcut_state_ignored_count, problems.size), StateLevel.BAD,
                        now = activity.getString(R.string.shortcut_ignored_now, problems.size),
                        errors = problems,
                        action = PopupAction(activity.getString(R.string.config_edit)) { showEditor() },
                    ),
                )
            }
        }
    }

    private fun titleOf(tab: Tab): String = when (tab) {
        is Tab.Group -> activity.getString(tab.group.title)
        Tab.Insert -> activity.getString(R.string.shortcut_group_insert)
        is Tab.Own -> tab.name
    }

    // the tab a mapping is listed in: its \"group\", else its command's (text: insert)
    private fun tabOf(mapping: KeyMapping): Tab {
        mapping.group?.let { group ->
            ShortcutGroup.byId(group)?.let { return Tab.Group(it) }
            return if (group == ShortcutGroup.INSERT_ID) Tab.Insert else Tab.Own(group)
        }
        return when (val action = mapping.action) {
            is ShortcutAction.Run -> Tab.Group(action.command.group)
            is ShortcutAction.Insert -> Tab.Insert
        }
    }

    // the rows of the tab chosen, and the title of the list; none: why, and New
    private fun showRows() {
        val tab = chosen ?: return
        val rows = rowsOf(tab)
        adapter.setRows(rows)
        list.scrollToPosition(0)
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.shortcuts_list_title).text =
            activity.getString(R.string.shortcut_list_title, titleOf(tab), rows.count { it.mapping != null })
        // only a tab of the user's own can be deleted
        view.findViewById<View>(R.id.shortcuts_tab_delete).visibility = if (tab is Tab.Own) View.VISIBLE else View.GONE
    }

    private fun rowsOf(tab: Tab): List<Row> {
        val mappings = keymap().mappings
        val here = mappings.filter { tabOf(it) == tab }
        val rows = ArrayList<Row>()
        if (tab is Tab.Group) {
            // its commands in their order, each with its mappings (or \"Not set\" if it has none anywhere)
            val mapped = mappings.mapNotNull { (it.action as? ShortcutAction.Run)?.command }.toSet()
            for (command in ShortcutCommand.entries.filter { it.group == tab.group }) {
                here.filter { (it.action as? ShortcutAction.Run)?.command == command }
                    .sortedBy { it.keys.size }
                    .forEach { rows += rowOf(it) }
                if (command !in mapped) rows += Row(command.icon, titleOf(command), command.id, emptyList(), null, command)
            }
            // what was put here by its \"group\": another group's commands, text
            here.filter { (it.action as? ShortcutAction.Run)?.command?.group != tab.group }.forEach { rows += rowOf(it) }
        } else {
            here.forEach { rows += rowOf(it) }
        }
        return rows
    }

    private fun rowOf(mapping: KeyMapping): Row = when (val action = mapping.action) {
        is ShortcutAction.Run ->
            Row(action.command.icon, mapping.desc.ifEmpty { titleOf(action.command) }, action.command.id, mapping.keys, mapping, action.command)
        is ShortcutAction.Insert ->
            Row(R.drawable.ic_snippets, mapping.desc.ifEmpty { activity.getString(R.string.shortcut_insert_text) }, oneLine(action.body), mapping.keys, mapping, null)
    }

    private fun titleOf(command: ShortcutCommand): String =
        activity.getString(command.title).replaceFirstChar { it.titlecase() }

    // the first line of a text, short
    private fun oneLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > 40) line.take(40) + "…" else line
    }

    // the keys of a row as written (<C-s>), as caps say them
    private fun keysText(row: Row): String = row.keys.joinToString(" ") { it.capLabels.joinToString("+") }

    // a row in a word of its level: set (green), not set (grey)
    private fun stateOf(row: Row): Pair<String, StateLevel> =
        if (row.mapping != null) activity.getString(R.string.shortcut_state_set) to StateLevel.GOOD
        else activity.getString(R.string.shortcut_not_set) to StateLevel.QUIET

    // What a tap on a row shows: its keys, its action, what it is for, its tab; Edit (or New)
    private fun infoOf(row: Row): EntryInfo {
        val (state, level) = stateOf(row)
        val mapping = row.mapping
        val details = ArrayList<CharSequence>()
        if (mapping != null) details += activity.getString(R.string.shortcut_info_keys, keysText(row))
        details += activity.getString(R.string.shortcut_info_action, row.detail)
        mapping?.desc?.takeIf { it.isNotEmpty() }?.let { details += activity.getString(R.string.shortcut_info_desc, it) }
        mapping?.let { details += activity.getString(R.string.shortcut_info_tab, titleOf(tabOf(it))) }
        return EntryInfo(
            row.title, state, level,
            now = activity.getString(if (mapping != null) R.string.shortcut_info_set_now else R.string.shortcut_info_unset_now),
            steps = if (mapping == null) listOf(activity.getString(R.string.shortcut_info_unset_step)) else emptyList(),
            details = details,
            action = if (mapping != null) PopupAction(activity.getString(R.string.config_edit)) { editMapping(mapping) }
                else PopupAction(activity.getString(R.string.shortcut_new_mapping)) { newMapping(row.command) },
        )
    }

    // #endregion

    // #region the list

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    // The rows of a tab, as one card of the settings: a thin gap between two rows, the
    // outer corners large
    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun setRows(rows: List<Row>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutInflater.from(activity).inflate(R.layout.item_shortcut, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = holder.itemView
            bind(row, rows[position])
            (row.layoutParams as? RecyclerView.LayoutParams)?.let {
                it.topMargin = if (position > 0) activity.resources.getDimensionPixelSize(R.dimen.pref_card_gap) else 0
                row.layoutParams = it
            }
            row.setBackgroundResource(
                when {
                    rows.size == 1 -> R.drawable.bg_pref_card_single
                    position == 0 -> R.drawable.bg_pref_card_top
                    position == rows.size - 1 -> R.drawable.bg_pref_card_bottom
                    else -> R.drawable.bg_pref_card_middle
                }
            )
        }
    }

    private fun bind(row: View, data: Row) {
        row.findViewById<ImageView>(R.id.shortcut_icon).apply {
            setImageResource(data.icon)
            imageTintList = iconTint
        }
        row.findViewById<TextView>(R.id.shortcut_title).text = data.title
        row.findViewById<TextView>(R.id.shortcut_detail).apply {
            text = data.detail
            visibility = if (data.detail.isEmpty()) View.GONE else View.VISIBLE
        }
        val notSet = activity.getString(R.string.shortcut_not_set)
        KeyCaps.render(row.findViewById(R.id.shortcut_keys), data.keys, notSet)
        row.contentDescription = "${data.title}, " + (if (data.keys.isEmpty()) notSet else keysText(data))
        // a tap: its info by it; ⋮ or a long press: its actions
        row.setOnClickListener { popup.show(row, infoOf(data)) }
        row.setOnLongClickListener {
            showActions(data)
            true
        }
        row.findViewById<View>(R.id.shortcut_more).setOnClickListener { showActions(data) }
    }

    // #endregion

    private companion object {
        const val KEY = "keymap"

        // the list of a tab no taller than this (about five rows), scrolling beyond
        const val LIST_MAX_HEIGHT_DP = 360

        // the keys in the tile of a mapping's actions, at most
        const val TILE_CHARS = 6
    }
}
