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
import android.widget.Toast

import androidx.annotation.DrawableRes
import androidx.lifecycle.LifecycleCoroutineScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.completion.JsonSchema
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView

/**
 * A panel whose entries are files, a file each (LanguageServersSheet: a server each;
 * SnippetsSheet: a language each), edited on the panel itself (SheetFileEditor), hidden at
 * first. The gestures are those of every panel (ConfigListSheet):
 *
 *  - a tap on an entry: its info by its row ([infoOf] of the panel);
 *  - ⋮ / a long press: Edit (its file in the editor, shown; changes to another asked about
 *    first), the panel's own, Delete (asked first; off while its file has changes not
 *    saved in the editor) — [showActions];
 *  - New: a name (what is wrong with it said as it is typed, [newProblem]; the template it
 *    will start from under it, [templateNote]), then a draft in the editor ([templateOf]):
 *    a row of its own at the top of the list, \"New · not saved\", and nothing on disk until
 *    Save writes it. Let go of (another file opened, the panel closed: asked first), it is gone;
 *  - Hide (in the editor's title) folds the editor away; what is typed stays.
 *
 * Saved ([writeFile]), [onFileSaved] does what comes of it, and the files are read again.
 * The panel reads the files as [S] ([read]) and shows them ([render], [showEntries]).
 */
internal abstract class ConfigFilesSheet<S : Any>(
    activity: Activity,
    scope: LifecycleCoroutineScope,
    styleEditor: (EditorView) -> Unit,
    highlight: suspend (CodeEditorView, String) -> Unit,
) : ConfigListSheet(activity, scope) {

    /** The files as read last; null: not yet. */
    protected var state: S? = null
        private set

    /** The key of the draft (New), not on disk until it is saved; null: none. */
    protected var draftKey: String? = null
        private set

    // whether the editor is shown
    private var editorShown = false

    private val fileEditor = SheetFileEditor(
        activity, scope, gate, { dialog.isShowing }, styleEditor, highlight, popup,
        check = { key, text -> checkFile(key, text) },
        afterSave = { key, text -> afterSave(key, text, key == draftKey) },
        defaultOf = { key -> templateOf(key) },
        write = { key, text ->
            // a draft is made, never written over a file that came meanwhile
            if (key == draftKey && exists(key)) {
                throw IllegalStateException(activity.getString(R.string.config_new_exists, fileNameOf(key)))
            }
            writeFile(key, text)
        },
        onSaved = { key, _ ->
            val created = key == draftKey
            if (created) draftKey = null
            onFileSaved(key, created)
            if (dialog.isShowing) reload()
        },
        schemaOf = { key -> schemaOf(key) },
    )

    // #region what a panel says

    /** The files read: on a worker thread. */
    protected abstract fun read(): S

    /** The card and the list, for [state] (showEntries). */
    protected abstract fun render(state: S)

    /** The name of the file of [key] (`clangd.json`). */
    protected abstract fun fileNameOf(key: String): String

    /** The title of the editor with the file of [key] in it: `<what> · <file>`. */
    protected abstract fun headingOf(key: String): CharSequence

    /** The file of [key] as it is written; null: there is none. On a worker thread. */
    protected abstract fun readFile(key: String): String?

    /** Whether the file of [key] is on disk. On a worker thread. */
    protected abstract fun exists(key: String): Boolean

    /** What is wrong with [text] as the file of [key], a line each; empty: nothing. */
    protected abstract fun checkFile(key: String, text: String): List<String>

    /** What saving [text] as the file of [key] will do ([created]: it is new), for the check line; null: nothing to say. */
    protected open fun afterSave(key: String, text: String, created: Boolean): CharSequence? = null

    /** The template of [key] (a draft starts as it; Restore template puts it back); null: none. */
    protected abstract fun templateOf(key: String): String?

    /** Which template a new [key] starts from, under its name as it is typed; null: nothing to say. */
    protected open fun templateNote(key: String): CharSequence? = null

    /** Writes [text] as the file of [key]. On a worker thread. */
    protected abstract fun writeFile(key: String, text: String)

    /** Removes the file of [key]; whether it is gone. On a worker thread. */
    protected abstract fun deleteFile(key: String): Boolean

    /** What deleting [key] does, for the dialog that asks first (what goes, what it touches). */
    protected abstract fun deleteMessage(key: String): CharSequence

    /** The file of [key] was deleted: what comes of it. */
    protected open fun onFileDeleted(key: String) {}

    /** What may be written in the file of [key], for its completion (SheetCompletion); null: its words only. */
    protected open fun schemaOf(key: String): JsonSchema? = null

    /**
     * The file of [key] was saved ([created]: it was a draft): what comes of it (the user told).
     * The save is over when this returns: the panel stays locked till then.
     */
    protected abstract suspend fun onFileSaved(key: String, created: Boolean)

    /** What is wrong with [key] as the name of a new file; null: nothing. */
    protected abstract fun newProblem(key: String, state: S): String?

    /** The sheet was closed: what a panel shows of its own goes. */
    protected open fun onSheetDismissed() {}

    /** What the name of a new file is at first (the document's language). */
    protected open fun newInitial(state: S): String = ""

    // #endregion

    /**
     * Shows the panel: [title], ⓘ ([helpId]: [helpWhat], [helpHow], where the files are
     * ([where])), the list as [listTitle] with [listIcon], New (a name asked for as
     * [newTitle], [newHint]), [help] under the editor; then the files.
     */
    protected fun showFiles(
        title: CharSequence,
        helpId: String,
        helpWhat: CharSequence,
        helpHow: CharSequence,
        where: () -> String,
        listTitle: CharSequence,
        @DrawableRes listIcon: Int,
        newTitle: CharSequence,
        newHint: CharSequence,
        help: CharSequence,
        emptyHeading: CharSequence,
        emptyMessage: CharSequence,
    ) {
        showSheet(title)
        setHelp(helpId, helpWhat, helpHow, where)
        fileEditor.setOnCollapse { hideEditor() }
        // (dragged by the handle only, as every panel: set in showSheet)
        setListTitle(listTitle, listIcon)
        // many files do not make the sheet long: the list scrolls within this
        setListMaxHeight((ConfigListSheet.LIST_MAX_HEIGHT_DP * activity.resources.displayMetrics.density).toInt())
        setNewAction { requestNew() }
        setStatus(StateLevel.QUIET, title, activity.getString(R.string.config_reading))
        fileEditor.setHelp(help)
        fileEditor.showNone(emptyHeading, emptyMessage)
        noneHeading = emptyHeading
        noneMessage = emptyMessage
        this.newTitle = newTitle
        this.newHint = newHint
        setExtra(fileEditor.view)
        // hidden until an entry is edited
        setExtraVisible(false)
        dialog.setOnDismissListener {
            popup.dismiss()
            fileEditor.release()
            onSheetDismissed()
        }
        reload()
    }

    private var noneHeading: CharSequence = ""
    private var noneMessage: CharSequence = ""
    private var newTitle: CharSequence = ""
    private var newHint: CharSequence = ""

    // one reading at a time, in the order asked for: an older one ending last left what it read
    private val readLock = Mutex()

    /** The files read again, and the panel with them; then [then]. */
    protected fun reload(then: (() -> Unit)? = null) {
        scope.launch {
            readLock.withLock {
                val read = withContext(Dispatchers.IO) { read() }
                if (!dialog.isShowing) return@launch
                state = read
                render(read)
            }
            then?.invoke()
        }
    }

    // what goes on on the panel: the editor locked while it does, the list as it is
    override fun onBusyChanged(busy: CharSequence?) {
        fileEditor.setLocked(busy)
    }

    /**
     * The list: [entries], with the draft (if there is one) at the top; [whenEmpty] if there
     * is nothing at all. [infoOf]: the info of a key as it is now (the window shown by a row
     * brought up to date with it).
     */
    protected fun showEntries(entries: List<Entry>, whenEmpty: Empty, infoOf: (String) -> EntryInfo?) {
        val draft = draftKey?.takeIf { key -> entries.none { it.key == key } }?.let(::draftEntry)
        setEntries(listOfNotNull(draft) + entries, whenEmpty) { key -> if (key == draftKey) draftInfo(key) else infoOf(key) }
    }

    // the row of the draft: \"New · not saved\", tinted while it is in the editor
    private fun draftEntry(key: String): Entry = Entry(
        key, fileNameOf(key), activity.getString(R.string.config_state_draft), StateLevel.WARN,
        detail = activity.getString(R.string.config_draft_detail),
        selected = isEditing(key),
        onMore = { showDraftActions(key) },
    ) { showInfo(key, draftInfo(key)) }

    private fun draftInfo(key: String): EntryInfo = EntryInfo(
        fileNameOf(key), activity.getString(R.string.config_state_draft), StateLevel.WARN,
        now = activity.getString(R.string.config_draft_now),
        steps = listOf(activity.getString(R.string.config_draft_step)),
        action = PopupAction(activity.getString(R.string.config_edit)) { edit(key) },
    )

    // the draft's actions: Edit, Discard (red)
    private fun showDraftActions(key: String) {
        actionsDialog(key, fileNameOf(key), activity.getString(R.string.config_state_draft), StateLevel.WARN)
            .action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (isEditing(key)) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !isEditing(key),
            ) { edit(key) }
            .action(R.drawable.ic_delete, R.string.config_discard_confirm, activity.getString(R.string.config_draft_discard_summary), danger = true) {
                discardDraft()
            }
            .show()
    }

    // the draft let go of: gone from the editor and the list
    private fun discardDraft() {
        if (refusedWhileBusy()) return
        val key = draftKey ?: return
        draftKey = null
        if (fileEditor.key == key) closeEditor()
        state?.let(::render)
    }

    /**
     * The actions of the file entry [key] (⋮, a long press), in the order of every panel:
     * Edit (its file in the editor; dimmed while it is there), [extra] (the panel's own),
     * Delete (red; [deletable]: it has a file of the user's; off while that file has changes
     * not saved in the editor).
     */
    protected fun showActions(
        key: String,
        name: CharSequence,
        state: CharSequence,
        level: StateLevel,
        deletable: Boolean = true,
        deleteDisabledSummary: CharSequence? = null,
        extra: (EntryActionsDialog) -> Unit = {},
    ) {
        val editing = isEditing(key)
        val dialog = actionsDialog(key, name, state, level)
        if (deletable) {
            dialog.action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (editing) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !editing,
            ) { edit(key) }
        }
        extra(dialog)
        val unsaved = hasUnsavedChanges(key)
        dialog.action(
            R.drawable.ic_delete, R.string.config_delete,
            when {
                !deletable -> deleteDisabledSummary ?: ""
                unsaved -> activity.getString(R.string.config_action_unsaved)
                else -> activity.getString(R.string.config_action_delete_summary, fileNameOf(key))
            },
            enabled = deletable && !unsaved, danger = true,
        ) { askDelete(key) }
        dialog.show()
    }

    // Delete: the file, once asked
    private fun askDelete(key: String) {
        val file = fileNameOf(key)
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.config_delete_title, file),
            deleteMessage(key),
            activity.getString(R.string.config_delete),
        ) {
            val ticket = beginOrRefuse(PanelOp.DELETE, file, key) ?: return@confirmDelete
            scope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) { runCatching { deleteFile(key) }.getOrDefault(false) }
                    if (!deleted) {
                        Toast.makeText(activity, activity.getString(R.string.config_delete_failed, file), Toast.LENGTH_LONG).show()
                        if (dialog.isShowing) reload()
                        return@launch
                    }
                    onFileDeleted(key)
                    if (!dialog.isShowing) return@launch
                    Toast.makeText(activity, activity.getString(R.string.config_deleted, file), Toast.LENGTH_SHORT).show()
                    forget(key)
                    reload()
                } finally {
                    gate.end(ticket)
                }
            }
        }
    }

    /** The file of [key] is gone (removed): if it is in the editor, nothing is, now. */
    protected fun forget(key: String) {
        if (fileEditor.key == key) closeEditor()
    }

    // nothing to edit any more (its file deleted, its draft let go of): nothing in the editor,
    // and the editor folded away, as it is before anything is edited, not left open and empty
    private fun closeEditor() {
        fileEditor.showNone(noneHeading, noneMessage)
        if (editorShown) hideEditor()
    }

    /** What is in the editor checked again: what it is checked against changed. */
    protected fun recheckEditor() {
        if (fileEditor.key != null) fileEditor.checkNow()
    }

    /** The file of [key] shown in the editor (Edit), changes to another asked about first. */
    protected fun edit(key: String) = requestFile(key)

    /** Whether the file of [key] is in the editor with changes not saved (hidden or not). */
    protected fun hasUnsavedChanges(key: String): Boolean = fileEditor.key == key && fileEditor.hasUnsavedChanges()

    /** Whether the file of [key] is the one shown in the editor (its row tinted). */
    protected fun isEditing(key: String): Boolean = editorShown && fileEditor.key == key

    override fun unsavedFileName(): String? = if (fileEditor.hasUnsavedChanges()) fileEditor.fileName() else null

    /** New, as the button by the list does (the status card's next step, the empty list's). */
    protected fun startNew(initial: String? = null) {
        requestNew(initial)
    }

    /** New was asked for: a name is asked for ([initial] in it). A panel whose names are limited offers them first. */
    protected open fun requestNew(initial: String? = null) {
        askNew(newTitle, newHint, initial)
    }

    // [then] once the changes in the editor (to another file than [key]) are let go of: asked,
    // if there are any; a draft let go of is gone
    private fun afterLettingGo(key: String?, then: () -> Unit) {
        if (refusedWhileBusy()) return
        val current = fileEditor.key
        if (current == null || current == key || !fileEditor.hasUnsavedChanges()) {
            then()
            return
        }
        val message = if (key == null) activity.getString(R.string.config_discard_new, fileNameOf(current))
            else activity.getString(R.string.config_discard_switch, fileNameOf(current), fileNameOf(key))
        ConfigDialogs.confirmDiscard(activity, message) {
            if (!dialog.isShowing || refusedWhileBusy()) return@confirmDiscard
            if (current == draftKey) draftKey = null
            then()
        }
    }

    // An entry to edit: the file of [key] in the editor (shown), once the changes to the one
    // there are let go of (asked, if there are any)
    private fun requestFile(key: String) {
        afterLettingGo(key) { showEditor(key) }
    }

    // The editor shown, with the file of [key] in it (unless it is in it already: as it was
    // left, what was typed too)
    private fun showEditor(key: String) {
        popup.dismiss()
        editorShown = true
        setExtraVisible(true)
        if (key != fileEditor.key || fileEditor.loadFailed) {
            if (key == draftKey) {
                fileEditor.openDraft(key, fileNameOf(key), headingOf(key), templateOf(key).orEmpty())
            } else {
                fileEditor.open(key, fileNameOf(key), headingOf(key)) { readFile(key) }
            }
        }
        state?.let(::render)
    }

    // the editor hidden (what is in it stays), and the keyboard with it; it closes upwards,
    // fading, the sheet shrinking with it
    private fun hideEditor() {
        editorShown = false
        fileEditor.hideKeyboard()
        setExtraVisible(false, animate = true)
        state?.let(::render)
    }

    // New: a name, then a draft of it in the editor (nothing on disk until it is saved)
    private fun askNew(title: CharSequence, hint: CharSequence, initial: String? = null) {
        askNewName(
            activity, title, hint,
            initial = initial ?: state?.let(::newInitial).orEmpty(),
            problemOf = { name ->
                val read = state
                when {
                    read == null -> activity.getString(R.string.config_reading)
                    name == draftKey -> activity.getString(R.string.config_new_exists, fileNameOf(name))
                    else -> newProblem(name, read)
                }
            },
            noteOf = { name -> if (name.isEmpty()) null else templateNote(name) },
        ) { name -> createDraft(name) }
    }

    /** A draft of [name] (a right name for a new file) in the editor; nothing on disk until it is saved. */
    protected fun createDraft(name: String) {
        afterLettingGo(null) {
            // a draft before it, let go of: gone
            draftKey = name
            popup.dismiss()
            editorShown = true
            setExtraVisible(true)
            fileEditor.openDraft(name, fileNameOf(name), headingOf(name), templateOf(name).orEmpty())
            state?.let(::render)
            scrollToEntry(0)
        }
    }
}
