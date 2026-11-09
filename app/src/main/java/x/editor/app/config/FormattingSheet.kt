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
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast

import androidx.lifecycle.LifecycleCoroutineScope

import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

import java.io.File

import x.editor.app.R
import x.editor.app.completion.SheetCompletion
import x.editor.app.completion.SheetSchemas
import x.editor.app.controller.LspController
import x.editor.app.format.FormattedText
import x.editor.app.format.SnapshotBuffer
import x.editor.app.lsp.FormatterServers
import x.editor.app.lsp.FormattingRules
import x.editor.app.lsp.LanguageServerSpec
import x.editor.app.lsp.LanguageServers
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView
import x.github.module.lsp.LspFormattingOptions
import x.github.module.piecetable.PieceTreeSnapshot
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.common.Range

/**
 * The formatting rules (menu → Developer tools → Formatting rules), on one sheet as the
 * toolchain is:
 *
 *  - how the document shown is formatted: its language, its server (and whether that
 *    formats), and where its rules come from (the defaults, its language's rules file, the
 *    project's .editorconfig or .clang-format);
 *  - a rules file (`formatting/<language>.json`), edited here in place: hidden at first,
 *    with the preview, as the editor of the language servers' sheet is. Edit of a
 *    language's dialog (a long press on it in the list) shows both, with its file; Hide,
 *    after Undo / Redo in the editor's title, folds both away (closing upwards and fading,
 *    setExtraVisible animate; what is typed stays). The preview is filled in the first
 *    time they are shown, not before. An
 *    editor of the app ([CodeEditorView]) with the highlighting of JSON, without line
 *    numbers or indent guides, and the floating bar of the editors (cut, copy, paste,
 *    select all), shows the file as it is written. **Default** puts the language's
 *    template in (assets/formatting, filled in from the editor: [newRules]; an edit: it
 *    can be undone); **Save**
 *    checks them (FormattingRules.validate: JSON, an object, each rule of the right kind)
 *    and writes the file only if they are right; **Preview** shows the document formatted
 *    with them, saved or not (while it is formatted, the title of the preview says so, and
 *    a popup over the button, then that it is done: PreviewBusy): only
 *    with the rules of the document's language (with others,
 *    a popup by the buttons says so: open a file of that language to preview it;
 *    EntryProblemPopup), and only by its server: if none formats it (none installed or
 *    on, it does not format, it does not run now), nothing is previewed and the popup says
 *    why, with the servers to install as a tap on the language in the list shows them;
 *  - the preview: the document shown, in an editor of the app that cannot be edited
 *    (nor selected, nor focused), highlighted as the document is. Not a TextView, which
 *    would build the whole of a large file into one layout: the editor lays it out in the
 *    background and draws what is on screen. And not a copy of the text: the document is a fork of its piece tree
 *    (PieceTreeTextBuffer.fork), which shares the file's chunks and copies only what was
 *    typed, and which the highlighting parses as it parses any document. Formatted, it is a
 *    new text built from a snapshot of the document ([SnapshotBuffer]) and the server's
 *    edits, or what the rules alone make of it if the server failed or did not answer in
 *    time ([FormattedText]). An empty file says so instead;
 *  - the languages with rules files, in a list of their own height that scrolls, each with
 *    its rules in a line (summaryOf: "indent spaces · 4  trim trailing spaces · yes  …",
 *    a rule its file leaves out as the template has it, a server's own keys as written);
 *    a file that is not right says so instead, its first problem under its name. Which
 *    server formats a language is not said there: the card above says it of the
 *    document's, and Preview of any other. New
 *    above it (a language id: its file made from its template, [newRules], and shown in the
 *    editor). The row tinted is the language whose rules file is being edited (the editor
 *    shown, its file in it: [isEditing]); none when the sheet opens, none while the editor
 *    is hidden. A tap does not change it: it reads the files again, and only if its file is
 *    not right does a popup by its row say what is wrong, all of it (EntryProblemPopup):
 *    nothing else is said on a tap. A long press opens its dialog (EntryActionsDialog): Edit (its
 *    file in the editor above; unsaved changes there are asked about first) and Remove
 *    (its file, asked first; the language goes back to the defaults). A language without
 *    a file is not listed, the document's neither: no file is made but by New.
 *
 * Both editors and the list are inside the sheet, which scrolls and is dragged down by the
 * same gesture: they keep the touches that start on them (EditorView.setKeepsTouches), so
 * a drag on them scrolls their text, not the sheet. A tap on the rules editor focuses it
 * without moving the sheet (FocusStableScrollView); the preview takes no focus at all, has
 * no floating bar and selects nothing. The sheet opens all the way, and is dragged by its
 * handle only (a scroll never closes it): down to its peek height, half the window's, and
 * from there closed.
 *
 * The document shown may still be loading when the sheet opens ([documentLoading]: a large
 * file): the card and the preview say so, and it all comes when it is there ([documentLoaded]).
 *
 * A document too long to ask its server about (LspController.PREVIEW_MAX_CHARS, a formatter
 * may take gigabytes over it, and the system kill it and Termux) is previewed with the rules
 * alone ([FormattedText.simulated]), the note and the popup saying so.
 *
 * One thing at a time (ConfigListSheet.gate): while a save, a delete or a Preview goes on the
 * rules cannot be changed (the editor locked: EditorLockView), nor can anything else be begun;
 * what a preview shows is the rules as they were when it was asked for. A preview may take long
 * (its server is asked): Preview is Stop meanwhile.
 *
 * @param info how the document shown is formatted (LspController.formattingInfo); null: no document (yet)
 * @param source the editor of the document shown (its text is what the preview shows); null: none (yet)
 * @param fileName the name of the document shown
 * @param documentLoading it is still being loaded: [documentLoaded] comes when it is
 * @param newRules the text of a new rules file of a language (New, Default): its template in the assets, filled in from the editor's indentation (FormattingRules.newFileText)
 * @param formatPreview what its server would do with the rules given (LspController.formatPreview)
 * @param serverStates how the language servers are doing, by id (LspController.serverStates), as they are now
 * @param formattingOf whether a server that runs formats, by its id (LspController.formattingOf); null: not known now
 * @param styleEditor gives an editor of the sheet the look of the editors (font, size, colors) and their floating bar
 * @param highlight sets the highlighting of an editor of the sheet up, for its text as a file of the name given
 */
internal class FormattingSheet(
    activity: Activity,
    scope: LifecycleCoroutineScope,
    private var info: LspController.FormattingInfo?,
    private var source: EditorView?,
    private val fileName: String?,
    private val newRules: (String) -> String,
    private val formatPreview: suspend (JsonObject, Int) -> LspController.FormatPreview,
    private val serverStates: () -> Map<String, LspController.ServerState>,
    private val formattingOf: (String) -> LspController.ServerFormatting?,
    private val styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
    private var documentLoading: Boolean = false,
) : ConfigListSheet(activity, scope) {

    private val rulesView: View = LayoutInflater.from(activity).inflate(R.layout.view_formatting_rules, null)
    private val rulesTitle = rulesView.findViewById<TextView>(R.id.rules_title)
    private val rulesEditor = rulesView.findViewById<CodeEditorView>(R.id.rules_editor)
    private val rulesEmpty = rulesView.findViewById<TextView>(R.id.rules_empty)
    private val status = rulesView.findViewById<TextView>(R.id.rules_status)
    private val fileLine = rulesView.findViewById<TextView>(R.id.rules_file)
    private val defaultButton = rulesView.findViewById<MaterialButton>(R.id.rules_default)
    private val previewButton = rulesView.findViewById<MaterialButton>(R.id.rules_preview)
    private val saveButton = rulesView.findViewById<MaterialButton>(R.id.rules_save)
    private val undoButton = rulesView.findViewById<MaterialButton>(R.id.rules_undo)
    private val redoButton = rulesView.findViewById<MaterialButton>(R.id.rules_redo)
    private val preview = rulesView.findViewById<CodeEditorView>(R.id.preview_editor)
    private val previewTitle = rulesView.findViewById<TextView>(R.id.preview_title)
    private val previewEmpty = rulesView.findViewById<TextView>(R.id.preview_empty)
    private val previewNote = rulesView.findViewById<TextView>(R.id.preview_note)
    private val previewProgress = rulesView.findViewById<View>(R.id.preview_progress)
    private val originalButton = rulesView.findViewById<MaterialButton>(R.id.preview_original)

    // the title of the preview and the popup, from a tap on Preview to its end
    private val previewBusy = PreviewBusy(
        activity, scope, popup, previewButton, previewTitle, activity.getString(R.string.format_rules_preview),
    )
    // which run of Preview is the latest (a run cancelled does not end a newer one's dots)
    private var previewRun = 0
    private val collapseButton = rulesView.findViewById<MaterialButton>(R.id.rules_collapse)
    private val editorLock = EditorLockView(rulesView, rulesView.findViewById(R.id.rules_title_icon), R.drawable.ic_edit)

    // what the file may say where the cursor is, as it is typed (SheetCompletion)
    private val completion = SheetCompletion(activity, rulesEditor) { SheetSchemas.formatting(activity) }

    // the language of the document shown, whose rules the preview goes by; null: no document
    private var documentLanguage: String? = info?.languageId
    private val name: String = fileName?.substringAfterLast('/').orEmpty()

    // whether the document's language has a rules file
    private var fromFile = info?.fromFile == true

    // the language whose rules file is in the editor; null: none yet (no document, none picked)
    private var editingId: String? = null
    // that file as it is on disk; null: there is none (the generated rules apply)
    private var savedText: String? = null
    // what the editor was given when the file was opened (the file, or the generated rules)
    private var loadedText: String? = null
    // the generated rules as Restore template put them in: said so while the text is still them
    private var defaultsText: String? = null
    // the language of the draft (New): its file is not on disk until it is saved; null: none
    private var draftId: String? = null
    // whether the text was right when it was checked last (Save is on only then)
    private var checkedRight = false
    // what was wrong with it then, a line each (all of them, in the window, on a tap)
    private var problems: List<String> = emptyList()

    // the rules files, as read last; the list waits for the first reading
    private var files: List<FileEntry> = emptyList()
    // the language servers, as read with them (registered first, as LanguageServers.all)
    private var specs: List<LanguageServerSpec> = emptyList()
    private var filesRead = false

    // the document as a snapshot, for the formatting (made once, reused while it does not change)
    private var original: SnapshotBuffer? = null
    // the document as a piece tree of the preview's own (fork), with the version it is of
    private var originalTree: Pair<Int, PieceTreeTextBuffer>? = null

    private var previewJob: Job? = null
    private var checkJob: Job? = null
    private var loadJob: Job? = null
    // which text of the preview is the latest (a newer one makes the work for an older one moot)
    private var shown = 0

    // one at a time on each editor: putting a text in and parsing it for the highlighting
    // (a parse is not stopped halfway, and must not read a text that is being replaced)
    private val rulesLock = Mutex()
    private val previewLock = Mutex()

    // the rules are read and highlighted: they can be edited, unless something goes on
    private var rulesLoaded = false
    // the file of the rules could not be read: nothing to do with it
    private var rulesLoadFailed = false

    // whether the editor and the preview are shown (Edit of a language's dialog; Hide hides them)
    private var editorShown = false
    // whether the preview has been given the document (the first time they were shown)
    private var previewStarted = false

    // what a tap on each language shows (renderFiles), by id
    private var infos: Map<String, EntryInfo> = emptyMap()

    fun show() {
        showSheet(activity.getString(R.string.format_sheet_title))
        setHelp("formatting", activity.getString(R.string.format_help_what), activity.getString(R.string.format_help_how)) {
            FormattingRules.directory(activity).path
        }
        // (dragged by the handle only, a scroll of the sheet never moving it; as tall as
        // its content, open all the way: set for every panel in showSheet, before the sheet
        // is shown)
        setListTitle(activity.getString(R.string.format_sheet_languages), R.drawable.ic_data_object)
        // many languages do not make the sheet long: the list scrolls within this
        setListMaxHeight((LIST_MAX_HEIGHT_DP * activity.resources.displayMetrics.density).toInt())
        setNewAction { askNew() }
        setFooter(FormattingRules.directory(activity).path)
        showDocument()
        setExtra(rulesView)
        // hidden until a language is edited
        setExtraVisible(false)
        setUpRules()
        setUpPreview()
        dialog.setOnDismissListener {
            popup.dismiss()
            release()
        }
        load()
    }

    // the rules file with changes not saved (a draft always has), as the user knows it; closing asks first
    override fun unsavedFileName(): String? = editingId?.takeIf { hasUnsavedChanges() }?.let(FormattingRules::fileNameOf)

    // #region the document shown

    /**
     * The document that was loading when the sheet opened is there ([source]; null: it could
     * not be read), or more is known of it ([info], its server attached since): the card, the
     * list, and the preview, if it is shown already.
     */
    fun documentLoaded(info: LspController.FormattingInfo?, source: EditorView?) {
        if (!dialog.isShowing) return
        val first = documentLoading
        documentLoading = false
        this.info = info
        this.source = source
        documentLanguage = info?.languageId
        fromFile = info?.fromFile == true
        showDocument()
        renderFiles()
        if (!first) return
        busy(false)
        updateButtons()
        when {
            source == null -> showEmpty(activity.getString(R.string.format_preview_no_document))
            previewStarted -> showOriginal()
        }
    }

    private fun showDocument() {
        val info = info ?: run {
            if (documentLoading) {
                setStatus(StateLevel.ACTIVE, activity.getString(R.string.preview_document_loading, name), activity.getString(R.string.format_sheet_loading_hint))
            } else {
                setStatus(StateLevel.QUIET, activity.getString(R.string.format_sheet_no_document), activity.getString(R.string.format_sheet_no_document_hint))
            }
            return
        }
        val origin = buildList {
            add(
                if (fromFile) activity.getString(R.string.format_source_file, FormattingRules.fileNameOf(info.languageId))
                else activity.getString(R.string.format_source_generated)
            )
            info.projectFiles?.let { add(activity.getString(R.string.format_source_project, it)) }
        }.joinToString(" + ")
        val server = when {
            info.serverId == null -> activity.getString(R.string.format_server_none)
            info.formats -> activity.getString(
                if (info.formatsRanges) R.string.format_server_formats_ranges else R.string.format_server_formats,
                info.serverId
            )
            else -> activity.getString(R.string.format_server_cannot, info.serverId)
        }
        val language = info.languageId
        // the next step: edit the language's file, or make one
        val action = if (fromFile) {
            PopupAction(activity.getString(R.string.format_status_edit, FormattingRules.fileNameOf(language))) { showEditor(language) }
        } else {
            PopupAction(activity.getString(R.string.format_status_new, language)) { newDraft(language) }
        }
        setStatus(
            if (info.formats) StateLevel.GOOD else StateLevel.WARN,
            activity.getString(R.string.format_sheet_current, language),
            "$server\n" + activity.getString(R.string.format_sheet_source, origin),
            action,
        )
    }

    // #endregion

    // #region the rules

    private fun setUpRules() {
        styleEditor(rulesEditor)
        // a JSON file of a few lines in a narrow box: no line numbers, no guides; lines are
        // not wrapped (as in every editor of a sheet): a long one scrolls sideways
        rulesEditor.setLineNumbersVisible(false)
        rulesEditor.setIndentGuidesEnabled(false)
        rulesEditor.setWordwrap(false)
        rulesEditor.setKeepsTouches(true)
        // nothing to edit until a file is read and parsed for the highlighting
        rulesEditor.setEditable(false)
        // Save on once the text differs from the file; checked once the typing pauses
        rulesEditor.onTextChangesListener = {
            updateButtons()
            checkJob?.cancel()
            checkJob = scope.launch {
                delay(CHECK_DELAY_MILLIS)
                checkRules()
                // what was typed is one edit of the history by now: Redo is off after it
                updateButtons()
            }
        }
        defaultButton.setOnClickListener { editingId?.let(::putDefaults) }
        saveButton.setOnClickListener { editingId?.let(::save) }
        undoButton.setOnClickListener {
            if (editingId != null && rulesEditor.isEditable()) rulesEditor.undo()
            updateButtons()
        }
        redoButton.setOnClickListener {
            if (editingId != null && rulesEditor.isEditable()) rulesEditor.redo()
            updateButtons()
        }
        previewButton.setOnClickListener { formatAndShow() }
        collapseButton.setOnClickListener { hideEditor() }
        // more than one problem: all of them, in the window of the panel
        status.setOnClickListener {
            val id = editingId ?: return@setOnClickListener
            if (problems.size > 1) {
                popup.show(
                    status,
                    activity.getString(R.string.config_problems_title, FormattingRules.fileNameOf(id), problems.size),
                    problems.joinToString("\n"),
                    bad,
                )
            }
        }
        showNoRules()
    }

    // Save on only when there is something to write and it is right: the rules read and
    // editable, a draft or a text not as the file is (with no file, the template: saving
    // makes it), no save going on, the text right as it was checked last. Undo / Redo on when
    // the editor has an edit to undo or redo. The title with ● while something is not saved.
    private fun updateButtons() {
        val ready = rulesLoaded && !gate.isBusy && rulesEditor.isEditable()
        val saved = savedText
        val changed = rulesLoaded && (saved == null || rulesEditor.getText() != saved)
        saveButton.isEnabled = ready && changed && checkedRight
        defaultButton.isEnabled = editingId != null && !rulesLoadFailed && !gate.isBusy
        // going on: it stops it; else on with something to preview
        val previewing = gate.current?.op == PanelOp.PREVIEW
        previewButton.isEnabled = previewing || (editingId != null && source != null && !rulesLoadFailed && !gate.isBusy)
        previewButton.setText(if (previewing) R.string.format_preview_stop else R.string.format_rules_preview)
        undoButton.isEnabled = ready && rulesEditor.canUndo()
        redoButton.isEnabled = ready && rulesEditor.canRedo()
        editingId?.let { id ->
            rulesTitle.text = activity.getString(R.string.format_rules_title, FormattingRules.fileNameOf(id)) +
                if (changed || id == draftId) " ●" else ""
        }
    }

    // no file in the editor yet: why, and nothing to do with it
    private fun showNoRules() {
        rulesTitle.text = activity.getString(R.string.format_rules_title_none)
        rulesEditor.setEditable(false)
        rulesEditor.visibility = View.INVISIBLE
        rulesEmpty.text = activity.getString(R.string.format_rules_no_document)
        rulesEmpty.visibility = View.VISIBLE
        fileLine.visibility = View.GONE
        updateButtons()
    }

    // what goes on: the rules not to be changed meanwhile, and the buttons as it allows
    override fun onBusyChanged(busy: CharSequence?) {
        editorLock.set(busy)
        if (busy != null) {
            completion.dismiss()
            rulesEditor.dismissSelectionMenu()
            rulesEditor.setEditable(false)
        } else if (rulesLoaded) {
            rulesEditor.setEditable(true)
        }
        updateButtons()
    }

    // the rules files read (again), and the list with them; then [then]. Nothing is put in
    // the editor: that waits for Edit (showEditor).
    private fun load(then: (() -> Unit)? = null) {
        scope.launch {
            filesLock.withLock {
                val (read, servers) = withContext(Dispatchers.IO) {
                    // the servers (what formats each language), and the servers to suggest (read once)
                    FormatterServers.catalog(activity)
                    inspectFiles() to runCatching { LanguageServers.all(activity) }.getOrDefault(emptyList())
                }
                if (!dialog.isShowing) return@launch
                files = read
                specs = servers
                filesRead = true
                renderFiles()
            }
            then?.invoke()
        }
    }

    // one reading of the files at a time, in the order asked for: an older one ending last left what it read
    private val filesLock = Mutex()

    // Edit of a language's dialog: the editor and the preview shown (the preview given the
    // document the first time), with the rules of [id] (changes to others asked about first).
    // Its row is tinted in the list once its rules are in the editor (openRules; here, if they
    // are already): not before, so that a change let go of is not asked about in vain.
    private fun showEditor(id: String) {
        popup.dismiss()
        editorShown = true
        setExtraVisible(true)
        if (!previewStarted) {
            previewStarted = true
            if (source != null) showOriginal()
        }
        if (id != editingId) requestRules(id) else renderFiles()
    }

    // New (or the next step of the status card): a draft of [id]'s rules in the editor, from
    // its template; nothing on disk until it is saved
    private fun newDraft(id: String) {
        letGo(id) {
            draftId = id
            popup.dismiss()
            editorShown = true
            setExtraVisible(true)
            if (!previewStarted) {
                previewStarted = true
                if (source != null) showOriginal()
            }
            openRules(id)
            scrollToEntry(0)
        }
    }

    // Hide: the editor and the preview folded away (closing, fading); what is typed stays
    private fun hideEditor() {
        completion.dismiss()
        editorShown = false
        popup.dismiss()
        rulesEditor.dismissSelectionMenu()
        if (rulesEditor.hasFocus()) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(rulesEditor.windowToken, 0)
            rulesEditor.clearFocus()
        }
        setExtraVisible(false, animate = true)
        renderFiles()
    }

    /**
     * Puts the rules file of [id] in the editor (the generated rules if there is none), and
     * has it highlighted; editable once that is done. The list says which one it is.
     */
    private fun openRules(id: String) {
        loadJob?.cancel()
        checkJob?.cancel()
        // the language in the editor: its row tinted in the list (isEditing)
        editingId = id
        rulesLoaded = false
        rulesLoadFailed = false
        savedText = null
        loadedText = null
        defaultsText = null
        rulesTitle.text = activity.getString(R.string.format_rules_title, FormattingRules.fileNameOf(id))
        rulesEmpty.visibility = View.GONE
        rulesEditor.visibility = View.VISIBLE
        rulesEditor.setEditable(false)
        rulesEditor.dismissSelectionMenu()
        status.text = ""
        fileLine.visibility = View.GONE
        updateButtons()
        renderFiles()
        val draft = id == draftId
        loadJob = scope.launch {
            // a draft: not read (its file is not made until it is saved)
            val read: Result<String?> = if (draft) Result.success(null)
                else withContext(Dispatchers.IO) { runCatching { FormattingRules.read(activity, id) } }
            if (!dialog.isShowing || editingId != id) return@launch
            // Not read: said, and not to be edited. Taken for no file, its template could be
            // saved over a file that is there, and only could not be read now.
            read.exceptionOrNull()?.let { e ->
                rulesEditor.visibility = View.INVISIBLE
                rulesEmpty.text = activity.getString(R.string.config_read_failed, FormattingRules.fileNameOf(id), e.message ?: e.javaClass.simpleName)
                rulesEmpty.visibility = View.VISIBLE
                rulesLoadFailed = true
                updateButtons()
                return@launch
            }
            val text = read.getOrNull()
            // (removed meanwhile: the template, to be saved again)
            val shownText = text ?: newRules(id)
            rulesLock.withLock {
                try {
                    // the tree of the text before goes with it
                    rulesEditor.syntaxEngine.release()
                    rulesEditor.refreshHighlighting()
                    // refused while the editor is laid out again (its font and size were just set)
                    completion.dismiss()
                    while (!rulesEditor.setText(shownText)) delay(BUSY_RETRY_MILLIS)
                    savedText = text
                    loadedText = shownText
                    showFileLine(id)
                    checkRules()
                    // the text must not change while it is parsed: editable after
                    highlight(rulesEditor, FormattingRules.fileNameOf(id))
                } finally {
                    // the sheet gone meanwhile: what the parse made goes too
                    if (!dialog.isShowing) rulesEditor.syntaxEngine.release()
                }
            }
            if (dialog.isShowing && editingId == id) {
                rulesLoaded = true
                rulesEditor.setEditable(!gate.isBusy)
                updateButtons()
            }
        }
    }

    // A language to edit: its rules in the editor, once the changes to the ones there are let
    // go of (asked, if there are any)
    private fun requestRules(id: String) {
        if (id == editingId) return
        letGo(id) { openRules(id) }
    }

    // [then] once the changes in the editor (to another language than [id]) are let go of:
    // asked, if there are any; a draft let go of is gone
    private fun letGo(id: String, then: () -> Unit) {
        if (refusedWhileBusy()) return
        val current = editingId
        if (current == null || current == id || !hasUnsavedChanges()) {
            if (current != null && current != id && current == draftId) draftId = null
            then()
            return
        }
        val message = activity.getString(R.string.config_discard_switch, FormattingRules.fileNameOf(current), FormattingRules.fileNameOf(id))
        ConfigDialogs.confirmDiscard(activity, message) {
            if (!dialog.isShowing || refusedWhileBusy()) return@confirmDiscard
            if (current == draftId) draftId = null
            then()
        }
    }

    // whether there is something in the editor not saved: a draft always; else the text
    // differs from the file (or, with none, from what was put in)
    private fun hasUnsavedChanges(): Boolean {
        val id = editingId ?: return false
        if (id == draftId) return true
        val reference = savedText ?: loadedText ?: return false
        return rulesEditor.getText() != reference
    }

    // Default: the language's template in place of the whole text, as an edit (undoable)
    private fun putDefaults(id: String) {
        if (!rulesEditor.isEditable()) return
        val text = newRules(id)
        val lines = rulesEditor.getLineCount()
        rulesEditor.insert(text, Range(1, 1, lines, rulesEditor.getLineEndColumn(lines)))
        defaultsText = text
        checkRules()
    }

    // that the file is not there yet (the title names it)
    private fun showFileLine(id: String) {
        if (savedText != null) {
            fileLine.visibility = View.GONE
            return
        }
        fileLine.text = activity.getString(
            if (id == draftId) R.string.format_rules_file_draft else R.string.format_rules_file_generated,
            FormattingRules.fileNameOf(id)
        )
        fileLine.visibility = View.VISIBLE
    }

    /**
     * The rules as they are typed, checked, and the check line (as every panel's editor has
     * it): right (saved, or not and what saving will do), or the first thing wrong and how
     * many more (all of them, in the window, on a tap). Returns the rules if they are right.
     */
    private fun checkRules(): JsonObject? {
        checkJob?.cancel()
        val id = editingId ?: return null
        val text = rulesEditor.getText()
        val result = when (val validation = FormattingRules.validate(text)) {
            is FormattingRules.Validation.Valid -> {
                problems = emptyList()
                status.setTextColor(good)
                val draft = id == draftId
                val head = activity.getString(
                    when {
                        !draft && text == savedText -> R.string.config_check_ok
                        text == defaultsText -> R.string.config_check_defaults
                        draft -> R.string.config_check_draft
                        else -> R.string.config_check_ok_unsaved
                    }
                )
                status.text = if (!draft && text == savedText) head
                    else activity.getString(R.string.config_check_after, head, activity.getString(R.string.format_after_save, id))
                validation.rules
            }
            is FormattingRules.Validation.Invalid -> {
                problems = validation.problems.map { FormattingRules.describe(activity, it) }
                status.setTextColor(bad)
                val first = activity.getString(R.string.config_check_error, problems.firstOrNull().orEmpty())
                status.text = if (problems.size > 1) first + "\n" + activity.getString(R.string.config_check_more, problems.size - 1) else first
                null
            }
        }
        checkedRight = result != null
        status.isClickable = problems.size > 1
        updateButtons()
        return result
    }

    // Save: the rules written to the language's file if they are right; else what is wrong
    private fun save(id: String) {
        if (checkRules() == null) {
            Toast.makeText(activity, R.string.format_save_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val text = rulesEditor.getText()
        val draft = id == draftId
        val file = FormattingRules.fileNameOf(id)
        val ticket = beginOrRefuse(PanelOp.SAVE, file, id) ?: return
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        // a draft is made, never written over a file that came meanwhile
                        check(!draft || !FormattingRules.fileOf(activity, id).exists()) {
                            activity.getString(R.string.config_new_exists, file)
                        }
                        FormattingRules.write(activity, id, text) to inspectFiles()
                    }
                }
                result.onFailure { e ->
                    Toast.makeText(activity, activity.getString(R.string.format_save_failed, file, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
                val (validation, entries) = result.getOrNull() ?: return@launch
                if (validation !is FormattingRules.Validation.Valid) {
                    // checked again as written: not saved
                    checkRules()
                    return@launch
                }
                if (id == documentLanguage) fromFile = true
                if (id == draftId) draftId = null
                if (!dialog.isShowing) return@launch
                Toast.makeText(
                    activity,
                    activity.getString(R.string.config_saved_then, file, activity.getString(R.string.format_saved_then, id)),
                    Toast.LENGTH_SHORT
                ).show()
                files = entries
                renderFiles()
                showDocument()
                savedText = text
                loadedText = text
                showFileLine(id)
                checkRules()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #endregion

    // #region the preview

    private fun setUpPreview() {
        styleEditor(preview)
        // Only to look at: no typing, no keyboard, no cursor, no line of the cursor; nothing
        // selected by a long press and no floating bar (copy, select all). And not focused:
        // a tap on it leaves the focus (and the keyboard) where they are, and the sheet
        // does not move to show it.
        preview.setSelectionMenu(null)
        preview.setTextSelectable(false)
        preview.isFocusable = false
        preview.isFocusableInTouchMode = false
        preview.setEditable(false)
        // not wrapped, as every editor of a sheet
        preview.setWordwrap(false)
        preview.setCursorDrawable(ColorDrawable(Color.TRANSPARENT))
        preview.setCurrentLineColor(Color.TRANSPARENT)
        // a drag on it scrolls its text, not the sheet
        preview.setKeepsTouches(true)
        originalButton.setOnClickListener { showOriginal() }
        if (documentLoading) {
            previewTitle.text = activity.getString(R.string.format_preview_title, name)
            showEmpty(activity.getString(R.string.preview_document_loading, name))
            busy(true)
            return
        }
        if (source == null) {
            previewTitle.text = activity.getString(R.string.format_rules_preview)
            showEmpty(activity.getString(R.string.format_preview_no_document))
            return
        }
        // the document goes in the first time the preview is shown (showEditor), not before
        previewTitle.text = activity.getString(R.string.format_preview_title, name)
    }

    // the document as it is
    private fun showOriginal() {
        val editor = source ?: return
        previewJob?.cancel()
        previewRun++
        previewBusy.stop()
        originalButton.visibility = View.GONE
        previewTitle.text = activity.getString(R.string.format_preview_title, name)
        val tree = originalTreeOf(editor)
        if (tree.length == 0) {
            showEmpty(activity.getString(R.string.format_preview_empty_file))
            previewNote.text = ""
            return
        }
        previewJob = scope.launch {
            busy(true)
            previewNote.text = activity.getString(R.string.format_preview_note_original, tree.getLineCount())
            show(tree, editor.getTabSize())
        }
    }

    // The document as a piece tree of the preview's own: the one made before if the document
    // has not changed since. A fork: the file's chunks shared, only what was typed copied.
    private fun originalTreeOf(editor: EditorView): PieceTreeTextBuffer {
        val buffer = editor.getTextBuffer()
        val version = buffer.getVersionId()
        originalTree?.takeIf { it.first == version }?.let { return it.second }
        return buffer.fork().also { originalTree = version to it }
    }

    // the document at [snapshot] as lines, for the formatting: the one made before if it has not changed since
    private suspend fun originalOf(snapshot: PieceTreeSnapshot): SnapshotBuffer {
        original?.takeIf { it.snapshot.versionId == snapshot.versionId }?.let { return it }
        return withContext(Dispatchers.Default) { SnapshotBuffer.of(snapshot) }.also { original = it }
    }

    // Preview: the document formatted with the rules as they are typed (saved or not), if
    // they are the rules of its language
    private fun formatAndShow() {
        // going on: Preview is Stop
        if (gate.current?.op == PanelOp.PREVIEW) {
            stopPreview()
            return
        }
        val editor = source ?: return
        val document = documentLanguage ?: return
        val editing = editingId ?: return
        previewBusy.keepWindow()
        if (editing != document) {
            showMismatch(editing, document)
            return
        }
        // no server formats the document's language (none installed or on, or its server
        // does not format): nothing is previewed, a popup says why
        if (showCannotPreview(document, afterAsking = false)) return
        val rules = checkRules() ?: run {
            Toast.makeText(activity, R.string.format_preview_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = editor.getTextBuffer().createSnapshot()
        if (snapshot.length == 0) {
            showEmpty(activity.getString(R.string.format_preview_empty_file))
            return
        }
        val tabWidth = editor.getTabSize()
        val ticket = beginOrRefuse(PanelOp.PREVIEW, name) ?: return
        previewJob?.cancel()
        val run = ++previewRun
        previewBusy.begin(
            activity.getString(R.string.format_preview_title_busy, name),
            activity.getString(R.string.format_preview_formatting),
        )
        previewJob = scope.launch {
            busy(true)
            try {
                val result = formatPreview(rules, snapshot.versionId)
                if (!dialog.isShowing) return@launch
                // no server formatted it (none, it does not format, or does not run now): not
                // laid out with the rules alone, a popup says why
                if (result is LspController.FormatPreview.RulesOnly) {
                    busy(false)
                    previewBusy.stop()
                    showCannotPreview(document, afterAsking = true)
                    return@launch
                }
                val base = originalOf(snapshot)
                val options = result.options
                val (formatted, note) = when (result) {
                    is LspController.FormatPreview.Edits -> {
                        if (result.edits.isEmpty()) {
                            null to activity.getString(R.string.format_preview_note_unchanged, result.serverId)
                        } else {
                            val text = withContext(Dispatchers.Default) { FormattedText.withEdits(base, result.edits) }
                            text to activity.getString(R.string.format_preview_note_server, result.serverId, text.changes)
                        }
                    }
                    // (said by a popup above)
                    is LspController.FormatPreview.RulesOnly -> return@launch
                    is LspController.FormatPreview.Failed -> {
                        val text = withContext(Dispatchers.Default) { FormattedText.simulated(base, options, tabWidth) }
                        text to activity.getString(R.string.format_preview_note_failed, result.serverId, result.message, text.changes)
                    }
                    is LspController.FormatPreview.TooLarge -> {
                        val text = withContext(Dispatchers.Default) { FormattedText.simulated(base, options, tabWidth) }
                        text to activity.getString(R.string.format_preview_note_too_large, snapshot.length / 1_000_000f, text.changes)
                    }
                    is LspController.FormatPreview.TextChanged -> {
                        busy(false)
                        previewNote.text = activity.getString(R.string.format_preview_note_changed)
                        previewBusy.finish(null, previewNote.text, warn)
                        return@launch
                    }
                }
                if (!dialog.isShowing) return@launch
                originalButton.visibility = View.VISIBLE
                previewNote.text = listOfNotNull(
                    note,
                    activity.getString(R.string.format_preview_rules, describe(options)),
                    info?.projectFiles?.let { activity.getString(R.string.format_preview_note_project, it) },
                ).joinToString("\n")
                // the rules can be edited again while the text is laid out and highlighted (a
                // large file takes long; what is shown is decided)
                gate.end(ticket)
                when {
                    formatted == null -> show(originalTreeOf(editor), options.tabSize)
                    formatted.buffer.length == 0 -> showEmpty(activity.getString(R.string.format_preview_empty_result))
                    else -> show(formatted.buffer, options.tabSize)
                }
                // laid out and highlighted: it can be read now
                previewBusy.finish(
                    activity.getString(R.string.format_preview_title_formatted, name),
                    activity.getString(R.string.format_preview_done) + "\n" + note,
                    if (result is LspController.FormatPreview.Edits) StateLevel.GOOD_COLOR else warn,
                )
            } finally {
                gate.end(ticket)
                // cancelled, or gone: as it was (not over a newer run)
                if (run == previewRun) previewBusy.stop()
            }
        }
    }

    // Stop, of a preview going on: the server's answer not waited for; what the preview had stays
    private fun stopPreview() {
        previewJob?.cancel()
        busy(false)
        previewNote.text = activity.getString(R.string.format_preview_stopped)
    }

    // Preview with the rules of another language than the document's: not done, and why, in
    // the popup of the sheet (EntryProblemPopup) over the button: a file of that
    // language is to be opened in the editor to preview them
    private fun showMismatch(editing: String, document: String) {
        val editingFile = FormattingRules.fileNameOf(editing)
        previewBusy.say(
            activity.getString(R.string.lsp_popup_title, editingFile, activity.getString(R.string.format_mismatch_title)),
            activity.getString(R.string.format_mismatch_message, name, document, editingFile, editing),
            warn,
        )
    }

    /**
     * Preview of [document]'s language, which no server formats: a popup over the button
     * (EntryProblemPopup) says why (the servers to install for it, how to add one;
     * the only place that says it: the list does not), and nothing is previewed. Returns whether
     * it was shown. Before the server is asked ([afterAsking] false), only when that is
     * known (no server, or one that runs and does not format); after ([afterAsking] true:
     * it did not format the document), whatever the reason: its server does not run now
     * (its state, and to start it), or the document is not with it.
     */
    private fun showCannotPreview(document: String, afterAsking: Boolean): Boolean {
        val provider = providerOf(document)
        val (paragraphs, color) = cannotFormat(document, provider)?.let { it to bad } ?: run {
            if (!afterAsking) return false
            val why = when (provider) {
                is Provider.NotRunning -> activity.getString(
                    R.string.format_preview_not_running, provider.id, activity.getString(stateTextOf(provider.state)),
                )
                is Provider.Formats -> activity.getString(R.string.format_preview_not_attached, name, provider.id)
                // (cannotFormat took these)
                else -> return false
            }
            listOf<CharSequence>(why) to warn
        }
        previewBusy.say(
            activity.getString(R.string.lsp_popup_title, name, activity.getString(R.string.format_preview_cannot)),
            paragraphsOf(paragraphs),
            color,
        )
        return true
    }

    /**
     * [buffer] in the preview, tabs [tabSize] wide (the rules' own, once formatted), then
     * highlighted as the document is: shown plain while it is parsed. A newer text makes
     * what is left to do for this one moot.
     */
    private suspend fun show(buffer: PieceTreeTextBuffer, tabSize: Int) {
        previewEmpty.visibility = View.GONE
        preview.visibility = View.VISIBLE
        if (preview.getTabSize() != tabSize.coerceIn(1, 16)) preview.setTabSize(tabSize.coerceIn(1, 16))
        val token = ++shown
        previewLock.withLock {
            if (token != shown) return
            try {
                // the tree of the text before goes with it
                preview.syntaxEngine.release()
                val laidOut = CompletableDeferred<Unit>()
                // refused while the preview is being pinch-zoomed: again in a moment
                while (!preview.setBuffer(buffer) { laidOut.complete(Unit) }) delay(BUSY_RETRY_MILLIS)
                preview.refreshHighlighting()
                laidOut.await()
                if (token != shown || !dialog.isShowing) return
                busy(false)
                highlight(preview, name)
            } finally {
                // the sheet gone meanwhile: what the parse made goes too
                if (!dialog.isShowing) preview.syntaxEngine.release()
            }
        }
    }

    // nothing to show, and why
    private fun showEmpty(text: String) {
        shown++
        busy(false)
        preview.visibility = View.INVISIBLE
        previewEmpty.text = text
        previewEmpty.visibility = View.VISIBLE
    }

    private fun busy(busy: Boolean) {
        previewProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // the sheet is gone: what the editors hold (parses still running let go of theirs when done)
    private fun release() {
        rulesLoaded = false
        previewJob?.cancel()
        previewRun++
        previewBusy.stop()
        popup.dismiss()
        checkJob?.cancel()
        loadJob?.cancel()
        completion.release()
        rulesEditor.onTextChangesListener = null
        rulesEditor.dismissSelectionMenu()
        preview.dismissSelectionMenu()
        preview.recycleRenderNodes()
        rulesEditor.recycleRenderNodes()
        rulesEditor.syntaxEngine.release()
        preview.syntaxEngine.release()
        original = null
        originalTree = null
    }

    // #endregion

    // #region the languages with rules files

    private class FileEntry(
        val languageId: String,
        val file: File,
        val rules: JsonObject?,
        // what its documents are formatted with: its rules over its template's (null: not right)
        val options: LspFormattingOptions? = null,
        val problems: List<FormattingRules.Problem> = emptyList(),
    ) {
        val problem: FormattingRules.Problem? get() = problems.firstOrNull()
    }

    // Every rules file, read and checked, with the rules it makes (its own over its
    // language's template, as filled in for a new file: [newRules], the editor's
    // indentation in it, as LspController's defaults are). On a worker thread.
    private fun inspectFiles(): List<FileEntry> = FormattingRules.languages(activity).mapNotNull { id ->
        val file = FormattingRules.fileOf(activity, id)
        val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
        when (val validation = FormattingRules.validate(text)) {
            is FormattingRules.Validation.Valid -> {
                // the placeholders filled in: the indentation given here is not used
                val generated = runCatching { FormattingRules.defaults(newRules(id), "    ", 4) }
                    .getOrDefault(LspFormattingOptions())
                FileEntry(id, file, validation.rules, FormattingRules.merge(generated, validation.rules))
            }
            is FormattingRules.Validation.Invalid -> FileEntry(id, file, null, problems = validation.problems)
        }
    }

    // The list: the draft (New) first, then the languages with a rules file only (none is
    // made but by Save), the document's first if it has one, tagged. The one being edited is
    // tinted (isEditing); a tap does not change that, it shows its info. ⋮: Edit, Delete.
    private fun renderFiles() {
        if (!filesRead) return
        val document = documentLanguage
        val infos = HashMap<String, EntryInfo>()
        val entries = files
            .sortedBy { if (it.languageId == document) 0 else 1 }
            .map { entryOf(it, infos) }
            .toMutableList()
        draftId?.takeIf { id -> files.none { it.languageId == id } }?.let { id ->
            infos[id] = draftInfo(id)
            entries.add(0, Entry(
                id, id, activity.getString(R.string.config_state_draft), StateLevel.WARN,
                detail = activity.getString(R.string.config_draft_detail),
                selected = isEditing(id),
                current = id == document,
                onMore = { showDraftActions(id) },
            ) { showInfo(id, draftInfo(id)) })
        }
        this.infos = infos
        val empty = document?.let { language ->
            Empty(R.drawable.ic_data_object, activity.getString(R.string.format_empty_title),
                PopupAction(activity.getString(R.string.format_status_new, language)) { newDraft(language) })
        } ?: Empty(R.drawable.ic_data_object, activity.getString(R.string.format_empty_title),
            PopupAction(activity.getString(R.string.config_new)) { askNew() })
        setEntries(entries, empty) { key -> this.infos[key] }
    }

    private fun entryOf(entry: FileEntry, infos: MutableMap<String, EntryInfo>): Entry {
        val id = entry.languageId
        val (state, level) = stateOf(id, entry)
        infos[id] = infoOf(entry)
        return Entry(
            id, id, state, level,
            // its rules in a line; not right: none (the problem says why)
            entry.options?.let(::summaryOf).orEmpty(),
            problem = problemLineOf(entry),
            // tinted: its rules file is the one being edited, not the one tapped
            selected = isEditing(id),
            current = id == documentLanguage,
            onMore = { showActions(id, entry) },
        ) {
            showInfo(id, infoOf(entry))
            load()
        }
    }

    // What a tap on a language shows: the rules its files are formatted with, or every
    // problem of its file; Edit at its foot
    private fun infoOf(entry: FileEntry): EntryInfo {
        val id = entry.languageId
        val file = FormattingRules.fileNameOf(id)
        val edit = PopupAction(activity.getString(R.string.config_edit)) { showEditor(id) }
        val options = entry.options
        if (entry.problems.isNotEmpty() || options == null) {
            return EntryInfo(
                file, activity.getString(R.string.config_state_invalid), StateLevel.BAD,
                now = activity.getString(R.string.format_info_invalid_now, id),
                steps = listOf(activity.getString(R.string.lsp_info_invalid_step)),
                errors = entry.problems.map { FormattingRules.describe(activity, it) },
                action = edit,
            )
        }
        return EntryInfo(
            file, activity.getString(R.string.format_state_right), StateLevel.GOOD,
            now = activity.getString(R.string.format_info_now, id),
            details = describe(options).split(" · "),
            action = edit,
        )
    }

    private fun draftInfo(id: String): EntryInfo = EntryInfo(
        FormattingRules.fileNameOf(id), activity.getString(R.string.config_state_draft), StateLevel.WARN,
        now = activity.getString(R.string.config_draft_now),
        steps = listOf(activity.getString(R.string.config_draft_step)),
        action = PopupAction(activity.getString(R.string.config_edit)) { showEditor(id) },
    )

    // the draft's actions: Edit, Discard (red)
    private fun showDraftActions(id: String) {
        actionsDialog(id, id, activity.getString(R.string.config_state_draft), StateLevel.WARN)
            .action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (isEditing(id)) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !isEditing(id),
            ) { showEditor(id) }
            .action(R.drawable.ic_delete, R.string.config_discard_confirm, activity.getString(R.string.config_draft_discard_summary), danger = true) {
                discardDraft()
            }
            .show()
    }

    // the draft let go of: gone from the editor and the list
    private fun discardDraft() {
        if (refusedWhileBusy()) return
        val id = draftId ?: return
        draftId = null
        if (editingId == id) closeEditor()
        renderFiles()
    }

    // nothing to edit any more (its file deleted, its draft let go of): nothing in the editor,
    // and the editor folded away, as it is before anything is edited, not left open and empty
    private fun closeEditor() {
        clearEditor()
        if (editorShown) hideEditor()
    }

    private fun clearEditor() {
        loadJob?.cancel()
        checkJob?.cancel()
        editingId = null
        rulesLoaded = false
        rulesLoadFailed = false
        savedText = null
        loadedText = null
        defaultsText = null
        checkedRight = false
        problems = emptyList()
        status.text = ""
        showNoRules()
    }

    /**
     * The rules of a row, as the first sheet had them: "indent spaces · 4  trim trailing
     * spaces · yes  …", each rule its name and value (kept together, on one line if it can),
     * the next after two spaces; then a server's own keys as they are written.
     */
    private fun summaryOf(options: LspFormattingOptions): String {
        fun value(rule: Boolean?): String = activity.getString(
            when (rule) {
                null -> R.string.format_rule_server
                true -> R.string.format_rule_yes
                false -> R.string.format_rule_no
            }
        )
        val parts = ArrayList<String>()
        parts += if (options.insertSpaces) activity.getString(R.string.format_summary_indent_spaces, options.tabSize)
            else activity.getString(R.string.format_summary_indent_tabs, options.tabSize)
        parts += activity.getString(R.string.format_summary_trim_trailing, value(options.trimTrailingWhitespace))
        parts += activity.getString(R.string.format_summary_final_newline, value(options.insertFinalNewline))
        parts += activity.getString(R.string.format_summary_trim_final, value(options.trimFinalNewlines))
        for ((key, value) in options.properties) parts += activity.getString(R.string.format_summary_property, key, value.toString())
        // a name and its value not broken apart at the dot
        return parts.joinToString(SUMMARY_SEPARATOR) { it.replace(" · ", "\u00A0·\u00A0") }
    }

    // Under the name of a file that is not right: its first problem, and how many more
    private fun problemLineOf(entry: FileEntry): CharSequence {
        val first = entry.problem ?: return ""
        val line = FormattingRules.describe(activity, first)
        val more = entry.problems.size - 1
        return if (more > 0) activity.getString(R.string.format_sheet_problems_more, line, more) else line
    }

    // #region the server that formats a language

    // What formats a language: its server, which runs and formats (or not), or does not
    // run now (its state said, nothing more); or no server at all
    private sealed class Provider {
        class Formats(val id: String, val ranges: Boolean) : Provider()
        class CannotFormat(val id: String) : Provider()
        class NotRunning(val id: String, val state: LspController.ServerState?) : Provider()
        object None : Provider()
    }

    private fun providerOf(languageId: String): Provider {
        val spec = LanguageServers.specForLanguage(specs, languageId) ?: return Provider.None
        formattingOf(spec.id)?.let { return if (it.formats) Provider.Formats(spec.id, it.ranges) else Provider.CannotFormat(spec.id) }
        return Provider.NotRunning(spec.id, serverStates()[spec.id])
    }

    private fun stateTextOf(state: LspController.ServerState?): Int = when (state) {
        LspController.ServerState.STARTING -> R.string.lsp_state_starting
        LspController.ServerState.FAILED -> R.string.lsp_state_failed
        else -> R.string.lsp_state_idle
    }

    // Why [languageId] cannot be formatted, in a sentence (the popup of Preview: its title
    // says it cannot), then the servers that format it to install and how to add one; null:
    // its server formats, or does not run now (not known)
    private fun cannotFormat(languageId: String, provider: Provider): List<CharSequence>? {
        val why = when (provider) {
            is Provider.CannotFormat -> activity.getString(R.string.format_cannot_server, provider.id)
            Provider.None -> activity.getString(R.string.format_cannot_none, languageId)
            else -> return null
        }
        return listOf(why, suggestionsFor(languageId), activity.getString(R.string.format_suggest_how))
    }

    // paragraphs, a blank line between them
    private fun paragraphsOf(paragraphs: List<CharSequence>): CharSequence = SpannableStringBuilder().apply {
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) append("\n\n")
            append(paragraph)
        }
    }

    // The servers that format [languageId] (assets/formatting/servers.jsonc): each with its
    // project, how to install it in Termux, its command and a word on it; where to find
    // more. None known: where to look.
    private fun suggestionsFor(languageId: String): CharSequence {
        val catalog = FormatterServers.catalog(activity)
        val suggestions = catalog.forLanguage(languageId)
        val more = catalog.directory ?: "https://github.com/search?q=${android.net.Uri.encode(languageId)}+language+server&type=repositories"
        if (suggestions.isEmpty()) return activity.getString(R.string.format_suggest_none, languageId, more)
        val language = activity.resources.configuration.locales[0].language
        val body = suggestions.joinToString("\n\n") { suggestion ->
            buildList {
                add("• " + suggestion.name)
                add("  " + suggestion.url)
                suggestion.install?.let { add("  " + activity.getString(R.string.format_suggest_install, it)) }
                suggestion.command?.let { add("  " + activity.getString(R.string.format_suggest_command, it)) }
                suggestion.note(language)?.let { add("  $it") }
            }.joinToString("\n")
        }
        return SpannableStringBuilder()
            .append(labelled(activity.getString(R.string.format_suggest_label), "\n" + body))
            .append("\n\n")
            .append(activity.getString(R.string.format_suggest_more, more))
    }

    // [text] after [label] in bold
    private fun labelled(label: String, text: CharSequence): CharSequence = SpannableStringBuilder(label).apply {
        setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(text)
    }


    // #endregion

    // whether [id]'s rules file is the one being edited: in the editor, the editor shown
    // (its row tinted, its state Editing)
    private fun isEditing(id: String): Boolean = editorShown && id == editingId

    // How a language is now, in a word of its level: wrong (said over Editing), in the
    // editor, else its rules are right
    private fun stateOf(id: String, entry: FileEntry): Pair<String, StateLevel> = when {
        entry.problem != null -> activity.getString(R.string.config_state_invalid) to StateLevel.BAD
        isEditing(id) -> activity.getString(R.string.config_state_editing) to StateLevel.ACTIVE
        else -> activity.getString(R.string.format_state_right) to StateLevel.GOOD
    }

    // ⋮ / a long press on a language: Edit (its rules in the editor above), Delete (its file;
    // not while it has changes not saved in the editor)
    private fun showActions(id: String, entry: FileEntry) {
        val editing = isEditing(id)
        val (state, level) = stateOf(id, entry)
        val unsaved = editingId == id && hasUnsavedChanges()
        actionsDialog(id, id, state, level)
            .action(
                R.drawable.ic_edit, R.string.config_edit,
                activity.getString(if (editing) R.string.config_action_editing else R.string.config_action_edit_summary),
                enabled = !editing,
            ) { showEditor(id) }
            .action(
                R.drawable.ic_delete, R.string.config_delete,
                if (unsaved) activity.getString(R.string.config_action_unsaved)
                else activity.getString(R.string.config_action_delete_summary, FormattingRules.fileNameOf(id)),
                enabled = !unsaved, danger = true,
            ) { askDelete(id) }
            .show()
    }

    // Delete: the rules file, once asked; the language goes back to the defaults
    private fun askDelete(id: String) {
        val file = FormattingRules.fileNameOf(id)
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.config_delete_title, file),
            activity.getString(R.string.format_delete_what, FormattingRules.DIR + "/" + file, id),
            activity.getString(R.string.config_delete),
        ) {
            val ticket = beginOrRefuse(PanelOp.DELETE, file, id) ?: return@confirmDelete
            scope.launch {
                try {
                    val deleted = withContext(Dispatchers.IO) {
                        // (one gone already is what was asked for)
                        runCatching { FormattingRules.fileOf(activity, id).let { it.delete() || !it.exists() } }.getOrDefault(false)
                    }
                    if (!deleted) {
                        Toast.makeText(activity, activity.getString(R.string.config_delete_failed, file), Toast.LENGTH_LONG).show()
                        if (dialog.isShowing) load()
                        return@launch
                    }
                    if (id == documentLanguage) fromFile = false
                    if (!dialog.isShowing) return@launch
                    Toast.makeText(activity, activity.getString(R.string.config_deleted, file), Toast.LENGTH_SHORT).show()
                    showDocument()
                    // in the editor: nothing is now (no file is made but by Save)
                    if (editingId == id) closeEditor()
                    load()
                } finally {
                    gate.end(ticket)
                }
            }
        }
    }

    // New: a language id, then a draft of its rules in the editor (its template), written by Save
    private fun askNew() {
        val document = documentLanguage
        askNewName(
            activity,
            activity.getString(R.string.format_new_title),
            activity.getString(R.string.format_new_hint),
            initial = document?.takeIf { d -> files.none { it.languageId == d } && d != draftId }.orEmpty(),
            problemOf = { name ->
                when {
                    !filesRead -> activity.getString(R.string.config_reading)
                    name.isBlank() -> activity.getString(R.string.config_new_empty)
                    FormattingRules.fileNameOf(name) != "$name.json" -> activity.getString(R.string.config_new_unsafe)
                    files.any { it.languageId.equals(name, ignoreCase = true) } || name == draftId ->
                        activity.getString(R.string.config_new_exists, FormattingRules.fileNameOf(name))
                    else -> null
                }
            },
            noteOf = { name ->
                when {
                    name.isEmpty() -> null
                    name.lowercase() in Templates.namesIn(activity, Templates.FORMATTING_PRESETS) ->
                        activity.getString(R.string.format_new_note_preset, name)
                    else -> activity.getString(R.string.lsp_new_note_template)
                }
            },
        ) { name -> newDraft(name) }
    }

    // #endregion

    // the rules in words: the indentation, then the whitespace rules and a server's own
    private fun describe(options: LspFormattingOptions): String {
        val parts = ArrayList<String>()
        parts += if (options.insertSpaces) activity.getString(R.string.format_indent_spaces, options.tabSize)
            else activity.getString(R.string.format_indent_tabs) + " (${options.tabSize})"
        fun rule(value: Boolean?, label: Int) {
            parts += activity.getString(label) + ": " + when (value) {
                null -> activity.getString(R.string.format_rule_server)
                true -> activity.getString(R.string.format_rule_yes)
                false -> activity.getString(R.string.format_rule_no)
            }
        }
        rule(options.trimTrailingWhitespace, R.string.format_rule_trim_trailing)
        rule(options.insertFinalNewline, R.string.format_rule_final_newline)
        rule(options.trimFinalNewlines, R.string.format_rule_trim_final)
        for ((key, value) in options.properties) parts += "$key=$value"
        return parts.joinToString(" · ")
    }

    private companion object {
        // the rules are checked this long after the typing stops
        const val CHECK_DELAY_MILLIS = 300L

        // the list of languages no taller than this (about four rows), scrolling beyond
        const val LIST_MAX_HEIGHT_DP = 260

        // between the rules of a row (summaryOf)
        const val SUMMARY_SEPARATOR = "  "

        // the tile of a language's dialog, and the badge of its state: their color, faint
        const val TILE_ALPHA = 0x2E
        const val BADGE_ALPHA = 0x26

        // an editor refuses a text while it is being zoomed or laid out again: tried again after this
        const val BUSY_RETRY_MILLIS = 50L
    }
}
