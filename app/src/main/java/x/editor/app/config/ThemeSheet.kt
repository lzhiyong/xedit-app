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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.edit
import androidx.lifecycle.LifecycleCoroutineScope

import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.completion.SheetCompletion
import x.editor.app.completion.SheetSchemas
import x.editor.app.highlight.EditorColorScheme
import x.editor.app.highlight.HighlightTheme
import x.editor.app.highlight.HighlightThemeFiles
import x.editor.app.highlight.HighlightThemes
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.PieceTreeTextBuffer

import com.google.android.material.R as MaterialR

/**
 * The user's own highlight themes (menu → Developer tools → Create theme), on one sheet as
 * the formatting rules are (FormattingSheet):
 *
 *  - a theme, edited here in place: a new one (New asks for its name: one no theme has,
 *    downloaded or the user's), or one of the list. An editor of the app with the
 *    highlighting of JSON and its hex colors in their own color, without line numbers or
 *    indent guides, its lines not wrapped.
 *    **Default** puts the theme to start from in (the template of the assets,
 *    theme_template.jsonc, with its name; an edit: it can be undone); **Preview**
 *    shows the document below with the theme as it is typed, saved or not (while it is
 *    rendered, the title of the preview says so, and a window over the button, then that
 *    it is done: PreviewBusy); **Save** checks it
 *    (HighlightThemeFiles.validate) and writes it to home/local/themes: a new theme only
 *    if no theme has its name meanwhile (else nothing is written, and the user is told),
 *    one of the list in place of its file. `meta.colorscheme` changed, the file goes by the
 *    new name, and the theme in use stays in use under it (renameTarget); a name no file can
 *    have, or that another theme has, is said on the check line, to be changed, and Save is
 *    off until it is. Save is off while the text is as the file is;
 *    Undo / Redo by the title;
 *  - the preview: the document shown in the editor ([preview]), whole, in an editor that cannot be edited, highlighted with the theme of the
 *    editor and in its colors; with no document shown, it says to open one. The app ships
 *    no samples of code for it. As the formatting sheet's preview is: not a copy of the
 *    text, but a fork of the document's piece tree (PieceTreeTextBuffer.fork), which shares
 *    the file's chunks and copies only what was typed, put under the preview with
 *    setBuffer and laid out in the background; an empty file says so instead;
 *  - the user's themes (home/local/themes: imported in the settings, or made here), in a
 *    list of a height of its own, each by the name it says it has (`meta.colorscheme`:
 *    HighlightThemeFiles.titleOf), its file by that name when the two differ (a theme
 *    imported as it was): a tap opens one in the editor (unsaved changes there are
 *    asked about first; the one in the editor cannot be tapped); a long press offers to
 *    use it for the highlighting (the setting of the editors, which the settings then
 *    show chosen) or to remove it (asked first; the one in use leaves the default in use). The downloaded themes (home/treesitter/config/themes) are not listed:
 *    they cannot be edited nor removed.
 *
 * A theme used, saved or removed goes to the editors at once ([onThemesChanged]): the
 * one in use, saved, is read again; removed, the default is used. The editor shows the
 * hex colors of the theme in their own color.
 *
 * One thing at a time (ConfigListSheet.gate): while a save, a delete or a Preview goes on the
 * theme cannot be changed (the editor locked: EditorLockView), nor can anything else be begun.
 *
 * @param preview the document shown, for the preview; null: none (or not read yet)
 * @param loadingName the name of the document shown while it is still being loaded (a large
 *   file): the preview says so, and shows it when it is there ([setPreview])
 * @param styleEditor gives an editor of the sheet the look of the editors (font, size, colors) and their floating bar
 * @param highlight sets the highlighting of an editor of the sheet up, for its text as a file of the name given
 * @param onThemesChanged a theme of the user's was saved, made the one in use, or removed: the editors take the settings again
 * @param onClosed the sheet was closed (it is shown again after a recreation of the screen from [saveState])
 */
internal class ThemeSheet(
    activity: Activity,
    scope: LifecycleCoroutineScope,
    private var preview: PreviewFile?,
    private val styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
    private val onThemesChanged: () -> Unit,
    private val onClosed: (ThemeSheet) -> Unit = {},
    private var loadingName: String? = null,
) : ConfigListSheet(activity, scope) {

    /** A theme of the user's as read: how dark it is and how many captures, or what is wrong with it. */
    private class ThemeInfo(
        val name: String,
        /** the name it says it has (meta.colorscheme), else [name] (of its file): what the list shows */
        val title: String,
        val dark: Boolean,
        val captures: Int,
        val problem: String?,
        /** its background, text and keyword colors (null: the theme does not say) */
        val background: Int? = null,
        val foreground: Int? = null,
        val keyword: Int? = null,
        /** a few of its colors: the background, then keywords, strings, functions, types, numbers */
        val swatches: IntArray = IntArray(0),
    )

    private val themeView: View = LayoutInflater.from(activity).inflate(R.layout.view_theme_editor, null)
    private val title = themeView.findViewById<TextView>(R.id.theme_title)
    private val fileLine = themeView.findViewById<TextView>(R.id.theme_file)
    private val editor = themeView.findViewById<CodeEditorView>(R.id.theme_editor)
    private val empty = themeView.findViewById<TextView>(R.id.theme_empty)
    private val status = themeView.findViewById<TextView>(R.id.theme_status)
    private val defaultButton = themeView.findViewById<MaterialButton>(R.id.theme_default)
    private val previewButton = themeView.findViewById<MaterialButton>(R.id.theme_preview)
    private val saveButton = themeView.findViewById<MaterialButton>(R.id.theme_save)
    private val undoButton = themeView.findViewById<MaterialButton>(R.id.theme_undo)
    private val redoButton = themeView.findViewById<MaterialButton>(R.id.theme_redo)
    private val collapseButton = themeView.findViewById<MaterialButton>(R.id.theme_collapse)
    private val editorLock = EditorLockView(themeView, themeView.findViewById(R.id.theme_title_icon), R.drawable.ic_palette)
    private val previewTitle = themeView.findViewById<TextView>(R.id.theme_preview_title)
    private val previewEditor = themeView.findViewById<CodeEditorView>(R.id.theme_preview_editor)
    private val previewEmpty = themeView.findViewById<TextView>(R.id.theme_preview_empty)
    private val previewProgress = themeView.findViewById<View>(R.id.theme_preview_progress)
    private val previewNote = themeView.findViewById<TextView>(R.id.theme_preview_note)

    // the title of the preview and the window, from a tap on Preview to its end
    private val previewBusy = PreviewBusy(
        activity, scope, popup, previewButton, previewTitle, activity.getString(R.string.format_rules_preview),
    )

    // what the file may say where the cursor is, as it is typed (SheetCompletion)
    private val completion = SheetCompletion(activity, editor) { SheetSchemas.theme(activity) }

    // the user's themes as read last; null: not yet
    private var themes: List<ThemeInfo>? = null

    // the theme in the editor; null: none
    private var editingName: String? = null
    // it has no file yet (New): saved, only if no theme has its name
    private var isNew = false
    // its file as it is on disk; null: not read (yet), or a new theme
    private var savedText: String? = null
    // the text put in the editor when it was opened; null: not yet
    private var loadedText: String? = null
    // the theme to start from, as Default put it in: said so while the text is still it
    private var defaultsText: String? = null
    // the theme is read and highlighted: it can be edited, unless something goes on
    private var themeLoaded = false
    // Preview going on, till the theme is on the document
    private var renderTicket: OperationGate.Ticket? = null
    // one reading of the themes at a time, in the order asked for: an older one ending last left what it read
    private val readLock = Mutex()
    // whether the text was right when it was checked last (Save is on only then)
    private var checkedRight = false
    // whether the editor and the preview are shown (Edit; Hide hides them)
    private var editorShown = false
    // what a tap on each theme shows (render), by name
    private var infos: Map<String, EntryInfo> = emptyMap()

    private var checkJob: Job? = null
    private var loadJob: Job? = null
    private var previewJob: Job? = null

    // one at a time: putting a text in an editor and parsing it for the highlighting
    private val textLock = Mutex()
    private val previewLock = Mutex()

    // the document is in the preview and parsed: a theme can be shown on it
    private var previewReady = false
    // the theme to show on the preview once it is ready
    private var pendingPreview: HighlightTheme? = null
    // Preview was tapped: told when the theme is on the document, when it was tapped
    private var renderAskedAt: Long? = null
    private var renderJob: Job? = null

    /**
     * Shows the sheet; [restore]: as it was when [saveState] was called, before the screen
     * was recreated (a theme made the highlight theme turned it light or dark): the theme
     * in the editor again, with what was typed in it.
     */
    fun show(restore: Bundle? = null) {
        showSheet(activity.getString(R.string.theme_sheet_title))
        setHelp("theme", activity.getString(R.string.theme_help_what), activity.getString(R.string.help_how_common)) {
            HighlightThemeFiles.customDirectory(activity).path
        }
        // (dragged by the handle only, as tall as its content, open all the way: set for
        // every panel in showSheet, before the sheet is shown)
        setListTitle(activity.getString(R.string.theme_sheet_list), R.drawable.ic_palette)
        setListMaxHeight((LIST_MAX_HEIGHT_DP * activity.resources.displayMetrics.density).toInt())
        setNewAction { askNew() }
        setFooter(HighlightThemeFiles.customDirectory(activity).path)
        setStatus(StateLevel.QUIET, activity.getString(R.string.theme_sheet_title), activity.getString(R.string.config_reading))
        setUpEditor()
        setUpPreview()
        setExtra(themeView)
        // hidden until a theme is edited (as every panel's editor)
        setExtraVisible(false)
        dialog.setOnDismissListener {
            popup.dismiss()
            release()
            onClosed(this)
        }
        load()
        restore?.getString(STATE_NAME)?.let { name ->
            showEditor()
            open(name, restore.getBoolean(STATE_NEW), restore.getString(STATE_TEXT))
        }
    }

    // the theme with changes not saved (a new one always has), as the user knows it; closing asks first
    override fun unsavedFileName(): String? = editingName?.takeIf { hasUnsavedChanges() }?.let(::fileNameOf)

    // the editor and the preview shown
    private fun showEditor() {
        popup.dismiss()
        editorShown = true
        setExtraVisible(true)
        themes?.let { render() }
    }

    // Hide: the editor and the preview folded away (closing, fading); what is typed stays
    private fun hideEditor() {
        completion.dismiss()
        editorShown = false
        editor.dismissSelectionMenu()
        if (editor.hasFocus()) {
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
        }
        setExtraVisible(false, animate = true)
        themes?.let { render() }
    }

    fun isShowing(): Boolean = dialog.isShowing

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    /**
     * What is needed to show the sheet again as it is ([show] with it): the theme in the
     * editor, and what is typed in it (not when it is very long: then the theme as its file
     * is). The preview is of the document shown then.
     */
    fun saveState(): Bundle = Bundle().apply {
        val name = editingName ?: return@apply
        putString(STATE_NAME, name)
        putBoolean(STATE_NEW, isNew)
        if (loadedText != null) {
            val text = editor.getText()
            if (text.length <= MAX_STATE_TEXT) putString(STATE_TEXT, text)
        }
    }

    // #region the list

    // the user's themes read (again), and the list with them
    private fun load() {
        scope.launch {
            readLock.withLock {
                val read = withContext(Dispatchers.IO) { readThemes() }
                if (!dialog.isShowing) return@launch
                themes = read
                render()
            }
        }
    }

    // each theme of home/local/themes, read and checked. On a worker thread.
    private fun readThemes(): List<ThemeInfo> = HighlightThemeFiles.importedThemes(activity).map { name ->
        var title = name
        try {
            val text = HighlightThemeFiles.readCustom(activity, name).orEmpty()
            title = HighlightThemeFiles.titleOf(text, name)
            val theme = HighlightThemeFiles.validate(activity, name, text)
            val colors = LinkedHashSet<Int>()
            theme.editorColors?.background?.let { colors += it }
            for (capture in SWATCH_CAPTURES) {
                if (colors.size >= MAX_SWATCHES) break
                theme.styleFor(capture, null)?.foreground?.let { colors += it }
            }
            ThemeInfo(
                name, title, HighlightThemes.isDark(theme), theme.captureCount, null,
                background = theme.editorColors?.background,
                foreground = theme.editorColors?.foreground,
                keyword = theme.styleFor("keyword", null)?.foreground,
                swatches = colors.toIntArray(),
            )
        } catch (e: Exception) {
            ThemeInfo(name, title, true, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    // the card and the list
    private fun render() {
        val themes = themes ?: return
        val chosen = AppSettings.getHighlightTheme(activity)
        val inUse = themes.firstOrNull { it.name == chosen }
        setStatus(
            if (themes.any { it.problem != null }) StateLevel.BAD else if (themes.isEmpty()) StateLevel.QUIET else StateLevel.GOOD,
            activity.getString(R.string.theme_sheet_status_title, themes.size),
            if (inUse != null) activity.getString(R.string.theme_status_in_use, inUse.title)
            else activity.getString(R.string.theme_status_builtin),
            if (themes.isEmpty() && !isNew) PopupAction(activity.getString(R.string.theme_new_title)) { askNew() } else null,
        )
        val infos = HashMap<String, EntryInfo>()
        val entries = ArrayList<Entry>()
        // a new theme, not saved yet: its row at the top
        val draft = editingName?.takeIf { isNew && themes.none { t -> t.name == it } }
        if (draft != null) {
            infos[draft] = EntryInfo(
                fileNameOf(draft), activity.getString(R.string.config_state_draft), StateLevel.WARN,
                now = activity.getString(R.string.config_draft_now),
                steps = listOf(activity.getString(R.string.config_draft_step)),
                action = PopupAction(activity.getString(R.string.config_edit)) { showEditor() },
            )
            entries += Entry(
                draft, draft, activity.getString(R.string.config_state_draft), StateLevel.WARN,
                detail = activity.getString(R.string.config_draft_detail),
                selected = editorShown,
                onMore = { showDraftActions(draft) },
            ) { infos[draft]?.let { showInfo(draft, it) } }
        }
        themes.forEach { entries += entryOf(it, chosen, infos) }
        this.infos = infos
        setEntries(
            entries,
            Empty(R.drawable.ic_palette, activity.getString(R.string.theme_empty_title),
                PopupAction(activity.getString(R.string.theme_new_title)) { askNew() }),
        ) { key -> this.infos[key] }
    }

    // whether [name] is the theme in the editor (shown), saved before (not a new one)
    private fun isEditing(name: String): Boolean = editorShown && name == editingName && !isNew

    // A theme: a tap shows its info (dark or light, how many captures, its colours, or what is
    // wrong); ⋮ / a long press: Edit, Use for highlighting, Delete
    private fun entryOf(info: ThemeInfo, chosen: String, infos: MutableMap<String, EntryInfo>): Entry {
        val editing = isEditing(info.name)
        val (state, level) = stateOf(info, chosen)
        val detail = info.problem?.let { "" } ?: activity.getString(
            if (info.dark) R.string.theme_detail_dark else R.string.theme_detail_light,
            info.captures
        )
        val edit = PopupAction(activity.getString(R.string.config_edit)) { request(info.name, new = false) }
        // named otherwise than its file: which file it is, too
        val renamed = info.title != info.name
        val shownName = if (renamed) info.title else fileNameOf(info.name)
        val fileDetail = if (renamed) activity.getString(R.string.theme_info_file, fileNameOf(info.name)) else null
        infos[info.name] = if (info.problem != null) {
            EntryInfo(
                shownName, state, StateLevel.BAD,
                now = activity.getString(R.string.theme_info_invalid_now),
                steps = listOf(activity.getString(R.string.lsp_info_invalid_step)),
                details = listOfNotNull(fileDetail),
                errors = listOf(info.problem),
                action = edit,
            )
        } else {
            EntryInfo(
                shownName, state, level,
                now = activity.getString(
                    if (info.name == chosen) R.string.theme_info_in_use_now else R.string.theme_info_now
                ),
                details = listOfNotNull(
                    fileDetail,
                    activity.getString(if (info.dark) R.string.theme_detail_dark else R.string.theme_detail_light, info.captures),
                    info.swatches.takeIf { it.isNotEmpty() }?.joinToString("  ") { String.format("#%06X", it and 0xFFFFFF) }
                        ?.let { activity.getString(R.string.theme_info_colors, it) },
                ),
                action = edit,
            )
        }
        return Entry(
            info.name, info.title, state, level,
            if (renamed && detail.isNotEmpty()) activity.getString(R.string.lsp_popup_title, detail, fileNameOf(info.name)) else detail,
            problem = info.problem.orEmpty(),
            selected = editing,
            onMore = { showActions(info, chosen) },
        ) { infos[info.name]?.let { showInfo(info.name, it) } }
    }

    // a theme in a word of its level: wrong, in the editor, in use, dark or light
    private fun stateOf(info: ThemeInfo, chosen: String): Pair<String, StateLevel> = when {
        info.problem != null -> activity.getString(R.string.config_state_invalid) to StateLevel.BAD
        isEditing(info.name) -> activity.getString(R.string.config_state_editing) to StateLevel.ACTIVE
        info.name == chosen -> activity.getString(R.string.theme_state_in_use) to StateLevel.ACTIVE
        else -> activity.getString(if (info.dark) R.string.theme_state_dark else R.string.theme_state_light) to StateLevel.GOOD
    }

    // New: a name no theme has, then the theme to start from in the editor (a draft, not saved yet)
    private fun askNew() {
        askNewName(
            activity,
            activity.getString(R.string.theme_new_title),
            activity.getString(R.string.theme_new_hint),
            problemOf = { name ->
                when {
                    name.isEmpty() -> activity.getString(R.string.config_new_empty)
                    !HighlightThemeFiles.isValidName(name) -> activity.getString(R.string.theme_new_unsafe)
                    // a look at two files: quick enough for the main thread
                    HighlightThemeFiles.exists(activity, name) || (isNew && name == editingName) ->
                        activity.getString(R.string.theme_new_exists, name)
                    else -> null
                }
            },
            noteOf = { name -> if (name.isEmpty()) null else activity.getString(R.string.theme_new_note) },
        ) { name -> request(name, new = true) }
    }

    // the draft's actions: Edit, Discard (red)
    private fun showDraftActions(name: String) {
        actionsDialog(name, name, activity.getString(R.string.config_state_draft), StateLevel.WARN)
            .action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (editorShown) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !editorShown,
            ) { showEditor() }
            .action(R.drawable.ic_delete, R.string.config_discard_confirm, activity.getString(R.string.config_draft_discard_summary), danger = true) {
                if (!refusedWhileBusy()) closeEditor()
            }
            .show()
    }

    // ⋮ / a long press, in the order of every panel: Edit, Use for highlighting (if it is
    // right and not in use already), Delete (red; not while it has changes not saved)
    private fun showActions(info: ThemeInfo, chosen: String) {
        val inUse = info.name == chosen
        val editing = isEditing(info.name)
        val (state, level) = stateOf(info, chosen)
        val actions = EntryActionsDialog(activity, info.title)
        // the theme at a glance: "Aa" in its keyword colour on its background ("!" if it is not right)
        if (info.problem != null) {
            actions.tile("!", actions.errorColor, actions.errorContainerColor)
        } else {
            actions.tile(
                "Aa",
                info.keyword ?: info.foreground ?: MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, muted),
                info.background ?: MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainerHighest, muted),
            )
        }
        val stateColor = level.color(activity)
        actions.badge(state, stateColor, withAlpha(stateColor, ConfigListSheet.BADGE_ALPHA))
        val unsaved = info.name == editingName && !isNew && hasUnsavedChanges()
        actions.swatches(info.swatches)
            .action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (editing) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !editing,
            ) { request(info.name, new = false) }
            .action(
                R.drawable.ic_check_circle, R.string.theme_action_use,
                activity.getString(
                    when {
                        info.problem != null -> R.string.theme_action_use_invalid
                        inUse -> R.string.theme_action_use_in_use
                        else -> R.string.theme_action_use_summary
                    }
                ),
                enabled = info.problem == null && !inUse,
            ) { useTheme(info.name, info.title) }
            .action(
                R.drawable.ic_delete, R.string.config_delete,
                when {
                    unsaved -> activity.getString(R.string.config_action_unsaved)
                    inUse -> activity.getString(R.string.theme_action_delete_in_use)
                    else -> activity.getString(R.string.config_action_delete_summary, fileNameOf(info.name))
                },
                enabled = !unsaved,
                danger = true,
            ) { askDelete(info.name) }
            .show()
    }

    // The theme used for the highlighting from now on: the setting of the editors (the
    // settings show it chosen), which they take at once
    // (light where the one in use is dark, or the other way round, recreates the screen: what
    // the toolchain installs goes on, ToolchainJobs, so nothing is asked)
    private fun useTheme(name: String, title: String) {
        if (refusedWhileBusy()) return
        AppSettings.preferences(activity).edit {
            putString(Constants.PREF_EDITOR_HIGHLIGHT_THEME, name)
        }
        onThemesChanged()
        if (!dialog.isShowing) return
        Toast.makeText(activity, activity.getString(R.string.theme_used, title), Toast.LENGTH_SHORT).show()
        render()
    }

    // The theme deleted, once asked; the one in use leaves the default in use
    private fun askDelete(name: String) {
        val inUse = AppSettings.getHighlightTheme(activity) == name
        val message = activity.getString(
            if (inUse) R.string.theme_delete_what_in_use else R.string.theme_delete_what,
            "home/local/themes/" + fileNameOf(name)
        )
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.config_delete_title, fileNameOf(name)),
            message,
            activity.getString(R.string.config_delete),
        ) { delete(name) }
    }

    private fun delete(name: String) {
        val ticket = beginOrRefuse(PanelOp.DELETE, fileNameOf(name), name) ?: return
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) { HighlightThemeFiles.delete(activity, name) }
                if (!deleted) {
                    Toast.makeText(activity, activity.getString(R.string.theme_delete_failed, name), Toast.LENGTH_LONG).show()
                    return@launch
                }
                // the theme in use is gone: the default again, as the settings do
                if (AppSettings.getHighlightTheme(activity) == name) {
                    AppSettings.preferences(activity).edit {
                        putString(Constants.PREF_EDITOR_HIGHLIGHT_THEME, Constants.HIGHLIGHT_THEME_AUTO)
                    }
                }
                onThemesChanged()
                if (!dialog.isShowing) return@launch
                Toast.makeText(activity, activity.getString(R.string.theme_deleted, name), Toast.LENGTH_SHORT).show()
                // it was in the editor: nothing is, now
                if (editingName == name && !isNew) closeEditor()
                load()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #endregion

    // #region the editor

    private fun setUpEditor() {
        styleEditor(editor)
        // the colors of the theme shown as they are: "#61afef" on a background of that color
        // (whatever the setting of the editors says: here it is what the text is about)
        editor.setColorPreviewEnabled(true)
        // a JSON file in a narrow box: no line numbers, no guides; lines are not wrapped (as
        // in every editor of a sheet): a long one scrolls sideways
        editor.setLineNumbersVisible(false)
        editor.setIndentGuidesEnabled(false)
        editor.setWordwrap(false)
        editor.setKeepsTouches(true)
        editor.setEditable(false)
        // Save on once the text differs from the file; checked once the typing pauses
        editor.onTextChangesListener = {
            updateButtons()
            checkJob?.cancel()
            checkJob = scope.launch {
                delay(CHECK_DELAY_MILLIS)
                checkTheme()
                // what was typed is one edit of the history by now: Redo is off after it
                updateButtons()
            }
        }
        defaultButton.setOnClickListener { putDefaults() }
        previewButton.setOnClickListener { previewTheme() }
        saveButton.setOnClickListener { save() }
        undoButton.setOnClickListener {
            if (isReady()) editor.undo()
            updateButtons()
        }
        redoButton.setOnClickListener {
            if (isReady()) editor.redo()
            updateButtons()
        }
        collapseButton.setOnClickListener { hideEditor() }
        showNone()
    }

    // no theme in the editor: how to get one
    // nothing to edit any more (its theme deleted, its draft let go of): nothing in the editor,
    // and the editor folded away, as it is before anything is edited, not left open and empty
    private fun closeEditor() {
        showNone()
        if (editorShown) hideEditor()
    }

    // what goes on: the theme not to be changed meanwhile, and the buttons as it allows
    override fun onBusyChanged(busy: CharSequence?) {
        editorLock.set(busy)
        if (busy != null) {
            completion.dismiss()
            editor.dismissSelectionMenu()
            editor.setEditable(false)
        } else if (themeLoaded) {
            editor.setEditable(true)
        }
        updateButtons()
    }

    private fun showNone() {
        loadJob?.cancel()
        checkJob?.cancel()
        checkedRight = false
        themeLoaded = false
        editingName = null
        isNew = false
        savedText = null
        loadedText = null
        defaultsText = null
        title.text = activity.getString(R.string.theme_editor_title_none)
        fileLine.visibility = View.GONE
        editor.setEditable(false)
        editor.dismissSelectionMenu()
        editor.visibility = View.INVISIBLE
        empty.text = activity.getString(R.string.theme_editor_none)
        empty.visibility = View.VISIBLE
        status.text = ""
        updateButtons()
        themes?.let { render() }
    }

    // the editor can be typed in: a theme is in it, read and highlighted
    private fun isReady(): Boolean = editingName != null && themeLoaded && !gate.isBusy && editor.isEditable()

    // A theme asked for (a tap on the list, or New): in the editor, once the changes to the
    // one there are let go of (asked, if there are any)
    private fun request(name: String, new: Boolean) {
        if (refusedWhileBusy()) return
        val current = editingName
        if (current == null || current == name && !new || !hasUnsavedChanges()) {
            showEditor()
            if (current != name || new || isNew) open(name, new)
            return
        }
        ConfigDialogs.confirmDiscard(activity, activity.getString(R.string.config_discard_switch, fileNameOf(current), fileNameOf(name))) {
            if (!dialog.isShowing || refusedWhileBusy()) return@confirmDiscard
            showEditor()
            open(name, new)
        }
    }

    // whether there is something in the editor not saved: a new theme always is
    private fun hasUnsavedChanges(): Boolean {
        if (editingName == null || loadedText == null) return false
        return isNew || editor.getText() != savedText
    }

    private fun fileNameOf(name: String) = "$name.json"

    /**
     * Puts the theme [name] in the editor: its file, or for a [new] one the theme to start
     * from; highlighted, then editable, checked and shown on the preview.
     */
    private fun open(name: String, new: Boolean, restoredText: String? = null) {
        loadJob?.cancel()
        checkJob?.cancel()
        editingName = name
        isNew = new
        checkedRight = false
        themeLoaded = false
        savedText = null
        loadedText = null
        defaultsText = null
        title.text = activity.getString(
            if (new) R.string.theme_editor_title_new else R.string.theme_editor_title,
            fileNameOf(name)
        )
        fileLine.text = activity.getString(R.string.theme_file_new)
        fileLine.visibility = if (new) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.setEditable(false)
        editor.dismissSelectionMenu()
        status.text = ""
        updateButtons()
        themes?.let { render() }
        loadJob = scope.launch {
            // the file (none for a new theme), and what is shown: what was typed before the
            // screen was recreated, else the file, or the template for a new theme
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val file = if (new) null else HighlightThemeFiles.readCustom(activity, name)
                    val shown = restoredText ?: if (new) HighlightThemeFiles.template(activity, name) else file
                    shown.orEmpty() to file
                }
            }
            if (!dialog.isShowing || editingName != name) return@launch
            // Not read: said, and not to be edited. Taken for an empty text it could be saved
            // over a theme that is there, and only could not be read now.
            val (text, file) = read.getOrElse { e ->
                editor.visibility = View.INVISIBLE
                empty.text = activity.getString(R.string.config_read_failed, fileNameOf(name), e.message ?: e.javaClass.simpleName)
                empty.visibility = View.VISIBLE
                return@launch
            }
            textLock.withLock {
                try {
                    // the tree of the text before goes with it
                    editor.syntaxEngine.release()
                    editor.refreshHighlighting()
                    // refused while the editor is laid out again
                    completion.dismiss()
                    while (!editor.setText(text)) delay(BUSY_RETRY_MILLIS)
                    if (!new) savedText = file
                    loadedText = text
                    // the text must not change while it is parsed: editable after
                    highlight(editor, fileNameOf(name))
                } finally {
                    if (!dialog.isShowing) editor.syntaxEngine.release()
                }
            }
            if (!dialog.isShowing || editingName != name) return@launch
            themeLoaded = true
            editor.setEditable(!gate.isBusy)
            checkTheme()?.let(::showOnPreview)
            updateButtons()
        }
    }

    // Default: the theme to start from (the template of the assets, with its name) in
    // place of the whole text, as an edit (undoable)
    private fun putDefaults() {
        val name = editingName ?: return
        if (!isReady()) return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { HighlightThemeFiles.template(activity, name) }.getOrNull()
            } ?: return@launch
            if (!dialog.isShowing || editingName != name || !isReady()) return@launch
            val lines = editor.getLineCount()
            editor.insert(text, x.github.module.piecetable.common.Range(1, 1, lines, editor.getLineEndColumn(lines)))
            defaultsText = text
            checkTheme()
        }
    }

    /**
     * The theme as it is typed, checked: what is wrong, or that it is right and whether it
     * is saved (or the theme to start from, as Default put it in). Returns it if it is right
     * as a theme, even if its new name will not do for a file ([checkedRight] false: not to
     * be saved as it is).
     */
    private fun checkTheme(): HighlightTheme? {
        checkJob?.cancel()
        val name = editingName ?: return null
        if (loadedText == null) return null
        val text = editor.getText()
        val theme = try {
            HighlightThemeFiles.validate(activity, name, text)
        } catch (e: HighlightThemeFiles.ImportException) {
            status.setTextColor(bad)
            status.text = activity.getString(R.string.config_check_error, e.message.orEmpty())
            checkedRight = false
            updateButtons()
            return null
        }
        // the name changed: the file goes by it, so it must be one it can have
        val target = renameTarget(name, text)
        val nameProblem = when {
            target == null -> null
            !HighlightThemeFiles.isValidName(target) -> activity.getString(R.string.theme_rename_invalid, target)
            HighlightThemeFiles.exists(activity, target) -> activity.getString(R.string.theme_rename_exists, target)
            else -> null
        }
        if (nameProblem != null) {
            status.setTextColor(bad)
            status.text = activity.getString(R.string.config_check_error, nameProblem)
            checkedRight = false
            updateButtons()
            // (it can still be previewed)
            return theme
        }
        status.setTextColor(good)
        val saved = !isNew && text == savedText
        val head = activity.getString(
            when {
                saved -> R.string.theme_check_ok
                text == defaultsText -> R.string.theme_check_defaults
                isNew -> R.string.theme_check_draft
                else -> R.string.theme_check_ok_unsaved
            },
            theme.captureCount
        )
        // what saving will do: the editors take it at once (in use), or it can be used then
        val inUse = !isNew && AppSettings.getHighlightTheme(activity) == name
        val after = when {
            target != null -> activity.getString(
                if (inUse) R.string.theme_after_save_rename_in_use else R.string.theme_after_save_rename,
                fileNameOf(target),
            )
            else -> activity.getString(if (inUse) R.string.theme_after_save_in_use else R.string.theme_after_save)
        }
        status.text = if (saved) head else activity.getString(R.string.config_check_after, head, after)
        checkedRight = true
        updateButtons()
        return theme
    }

    // The name the file of the theme [name] is to go by when [text] is saved: what
    // `meta.colorscheme` says, if that was changed in the editor (a theme imported under
    // another name than it says stays as it is while its name is left alone); null: its own
    private fun renameTarget(name: String, text: String): String? =
        HighlightThemeFiles.renameOf(name, savedText, text)

    // Save on only when there is something to write and it is right: a theme read and
    // editable, new, or not as its file is; no save going on; right as checked last. Undo /
    // Redo on when there is an edit to undo or redo; Restore template and Preview once a
    // theme is in the editor. The title with ● while something is not saved.
    private fun updateButtons() {
        val ready = isReady()
        val changed = editingName != null && themeLoaded && (isNew || editor.getText() != savedText)
        saveButton.isEnabled = ready && changed && checkedRight
        undoButton.isEnabled = ready && editor.canUndo()
        redoButton.isEnabled = ready && editor.canRedo()
        defaultButton.isEnabled = ready
        // nothing to show it on without a document, or with an empty one, or till it is there
        previewButton.isEnabled = ready && previewReady && preview?.let { it.buffer.length > 0 } == true
        editingName?.let { name ->
            title.text = activity.getString(
                if (isNew) R.string.theme_editor_title_new else R.string.theme_editor_title,
                fileNameOf(name)
            ) + if (changed || isNew) " ●" else ""
        }
    }

    // Save: the theme written if it is right; a new one only if no theme has its name; one
    // renamed under its new name, in place of its file, the setting of the theme in use
    // following it (the editors then take it, by the name they are told)
    private fun save() {
        val name = editingName ?: return
        val theme = checkTheme()
        if (theme == null || !checkedRight) {
            Toast.makeText(activity, R.string.theme_save_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val text = editor.getText()
        val new = isNew
        val target = renameTarget(name, text)
        val savedName = target ?: name
        val ticket = beginOrRefuse(PanelOp.SAVE, fileNameOf(savedName), name) ?: return
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            new -> HighlightThemeFiles.create(activity, savedName, text)
                            target != null -> HighlightThemeFiles.rename(activity, name, target, text)
                            else -> {
                                HighlightThemeFiles.write(activity, name, text)
                                true
                            }
                        }
                    }
                }
                result.onFailure { e ->
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.format_save_failed, fileNameOf(savedName), e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                if (result.getOrDefault(false) == false) {
                    // a theme of that name came meanwhile (imported, downloaded): not saved
                    Toast.makeText(activity, activity.getString(R.string.theme_exists, savedName), Toast.LENGTH_LONG).show()
                    return@launch
                }
                // the theme in use renamed: still the one in use, under its new name
                if (target != null && !new && AppSettings.getHighlightTheme(activity) == name) {
                    AppSettings.preferences(activity).edit {
                        putString(Constants.PREF_EDITOR_HIGHLIGHT_THEME, target)
                    }
                }
                // (nothing else can be opened meanwhile: locked)
                editingName = savedName
                onThemesChanged()
                if (!dialog.isShowing) return@launch
                Toast.makeText(activity, activity.getString(R.string.theme_saved, savedName), Toast.LENGTH_SHORT).show()
                isNew = false
                savedText = text
                title.text = activity.getString(R.string.theme_editor_title, fileNameOf(savedName))
                fileLine.visibility = View.GONE
                checkTheme()
                load()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #endregion

    // #region the preview

    private fun setUpPreview() {
        styleEditor(previewEditor)
        // Only to look at: no typing, no keyboard, no cursor, no line of the cursor; nothing
        // selected and no floating bar; not focused (a tap leaves the focus where it is)
        previewEditor.setSelectionMenu(null)
        previewEditor.setTextSelectable(false)
        previewEditor.isFocusable = false
        previewEditor.isFocusableInTouchMode = false
        previewEditor.setEditable(false)
        previewEditor.setLineNumbersVisible(true)
        // not wrapped, as every editor of a sheet
        previewEditor.setWordwrap(false)
        previewEditor.setCursorDrawable(ColorDrawable(Color.TRANSPARENT))
        previewEditor.setCurrentLineColor(Color.TRANSPARENT)
        // a drag on it scrolls its text, not the sheet
        previewEditor.setKeepsTouches(true)
        fillPreview()
    }

    /**
     * The document that was being loaded when the sheet opened is there ([file]; null: it
     * could not be read): shown, with the theme in the editor if it is right.
     */
    fun setPreview(file: PreviewFile?) {
        if (!dialog.isShowing) return
        preview = file
        loadingName = null
        previewProgress.visibility = View.GONE
        fillPreview()
        updateButtons()
        if (isReady()) checkTheme()?.let(::showOnPreview)
    }

    // The document in the preview, or why there is none
    private fun fillPreview() {
        val loading = loadingName
        if (loading != null) {
            previewTitle.text = activity.getString(R.string.theme_preview_title, loading)
            previewEditor.visibility = View.INVISIBLE
            previewEmpty.text = activity.getString(R.string.preview_document_loading, loading)
            previewEmpty.visibility = View.VISIBLE
            previewNote.text = ""
            previewProgress.visibility = View.VISIBLE
            return
        }
        // no document shown: nothing to preview on, said in its place
        val file = preview
        if (file == null) {
            previewTitle.text = activity.getString(R.string.theme_preview_title_none)
            previewEditor.visibility = View.INVISIBLE
            previewEmpty.text = activity.getString(R.string.theme_preview_none)
            previewEmpty.visibility = View.VISIBLE
            previewNote.text = ""
            return
        }
        previewTitle.text = activity.getString(R.string.theme_preview_title, file.name)
        if (file.buffer.length == 0) {
            previewEditor.visibility = View.INVISIBLE
            previewEmpty.text = activity.getString(R.string.format_preview_empty_file)
            previewEmpty.visibility = View.VISIBLE
            previewNote.text = ""
            return
        }
        previewEmpty.visibility = View.GONE
        previewEditor.visibility = View.VISIBLE
        previewNote.text = activity.getString(R.string.theme_preview_note_none)
        // tabs as wide as in the document's editor
        val tabSize = file.tabSize.coerceIn(1, 16)
        if (previewEditor.getTabSize() != tabSize) previewEditor.setTabSize(tabSize)
        previewProgress.visibility = View.VISIBLE
        previewJob = scope.launch {
            previewLock.withLock {
                try {
                    if (!dialog.isShowing) return@withLock
                    // the fork under the preview, laid out in the background (refused while
                    // the preview is being pinch-zoomed: again in a moment)
                    val laidOut = CompletableDeferred<Unit>()
                    while (!previewEditor.setBuffer(file.buffer) { laidOut.complete(Unit) }) delay(BUSY_RETRY_MILLIS)
                    previewEditor.refreshHighlighting()
                    laidOut.await()
                    if (!dialog.isShowing) return@withLock
                    previewProgress.visibility = View.GONE
                    // highlighted as the document is: by its name
                    highlight(previewEditor, file.name)
                } finally {
                    if (!dialog.isShowing) previewEditor.syntaxEngine.release()
                }
            }
            if (!dialog.isShowing) return@launch
            previewReady = true
            pendingPreview?.let(::showOnPreview)
            updateButtons()
        }
    }

    // Preview: the theme as it is typed on the document, if it is right; how it goes by the
    // title of the preview and in the window (PreviewBusy)
    private fun previewTheme() {
        previewBusy.keepWindow()
        val theme = checkTheme()
        if (theme == null) {
            previewNote.setTextColor(bad)
            previewNote.text = activity.getString(R.string.theme_preview_invalid)
            previewBusy.say(activity.getString(R.string.format_rules_preview), previewNote.text, bad)
            return
        }
        val file = preview ?: return
        renderTicket = beginOrRefuse(PanelOp.PREVIEW, file.name) ?: return
        renderJob?.cancel()
        renderAskedAt = System.currentTimeMillis()
        previewBusy.begin(
            activity.getString(R.string.theme_preview_title_busy, file.name),
            activity.getString(R.string.theme_preview_rendering),
        )
        showOnPreview(theme)
    }

    // [theme] on the document: its captures and the colors of its editor (once it is ready)
    private fun showOnPreview(theme: HighlightTheme) {
        if (!previewReady) {
            pendingPreview = theme
            return
        }
        pendingPreview = null
        previewEditor.syntaxEngine.useTheme(theme)
        previewEditor.refreshHighlighting()
        EditorColorScheme.applyTo(previewEditor, theme.editorColors, HighlightThemes.isDark(theme))
        // not a place to type: no line of the cursor
        previewEditor.setCurrentLineColor(Color.TRANSPARENT)
        previewNote.setTextColor(muted)
        previewNote.text = activity.getString(R.string.theme_preview_note, theme.name)
        renderAskedAt?.let(::endRender)
    }

    // The theme asked for at [askedAt] is on the document: said to be done once it is drawn,
    // and not so soon that "rendering" could not be read
    private fun endRender(askedAt: Long) {
        renderAskedAt = null
        val file = preview
        if (file == null) {
            endPreview()
            return
        }
        renderJob = scope.launch {
            try {
                delay((MIN_RENDER_MILLIS - (System.currentTimeMillis() - askedAt)).coerceAtLeast(FRAME_MILLIS))
                if (!dialog.isShowing) return@launch
                previewBusy.finish(
                    activity.getString(R.string.theme_preview_title, file.name),
                    activity.getString(R.string.theme_preview_done),
                )
            } finally {
                endPreview()
            }
        }
    }

    // Preview is over: the theme can be changed again
    private fun endPreview() {
        renderTicket?.let(gate::end)
        renderTicket = null
    }

    // #endregion

    // the sheet is gone: what the editors hold
    private fun release() {
        themeLoaded = false
        previewJob?.cancel()
        renderJob?.cancel()
        renderAskedAt = null
        endPreview()
        previewBusy.stop()
        checkJob?.cancel()
        loadJob?.cancel()
        completion.release()
        editor.onTextChangesListener = null
        editor.dismissSelectionMenu()
        editor.recycleRenderNodes()
        previewEditor.recycleRenderNodes()
        editor.syntaxEngine.release()
        previewEditor.syntaxEngine.release()
    }

    /**
     * The document the preview shows: its [name] (its highlighting goes by it), its text as
     * [buffer], a fork of its piece tree (PieceTreeTextBuffer.fork: the file's chunks shared,
     * not copied), and the width of its tabs ([tabSize]).
     */
    class PreviewFile(val name: String, val buffer: PieceTreeTextBuffer, val tabSize: Int)

    companion object {
        private const val STATE_NAME = "name"
        private const val STATE_NEW = "new"
        private const val STATE_TEXT = "text"

        // what is typed is kept over a recreation up to this long (a Bundle is not for megabytes)
        private const val MAX_STATE_TEXT = 100_000

        // the theme is checked this long after the typing stops
        private const val CHECK_DELAY_MILLIS = 300L

        // the editor refuses a text while it is being zoomed or laid out again: tried again after this
        private const val BUSY_RETRY_MILLIS = 50L

        // Preview says it is rendering at least this long, and waits at least a frame
        private const val MIN_RENDER_MILLIS = 300L
        private const val FRAME_MILLIS = 32L

        // the colors of a theme shown in its dialog: the background, then these captures
        private val SWATCH_CAPTURES = listOf("keyword", "string", "function", "type", "number", "comment", "constant")
        private const val MAX_SWATCHES = 6

        // the list of themes no taller than this (about four rows), scrolling beyond
        private const val LIST_MAX_HEIGHT_DP = 260
    }
}
