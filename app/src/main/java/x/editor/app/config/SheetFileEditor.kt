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
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast

import androidx.lifecycle.LifecycleCoroutineScope

import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.completion.JsonSchema
import x.editor.app.completion.SheetCompletion
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView
import x.github.module.piecetable.common.Range

/**
 * The editor of a panel of the developer tools, the same on every one that edits a file
 * whole (the language servers and the snippets: ConfigFilesSheet; packages.json:
 * ToolchainSheet; keymap.json: ShortcutsSheet). FormattingSheet and ThemeSheet, which have
 * a preview under theirs, follow the same rules in their own views.
 *
 *  - Its title: `<what> · <file>`, and ` ●` after it while there is something not saved
 *    (a draft always is). By it, in this order: Undo, Redo, Hide ([setOnCollapse]).
 *  - The file as it is on disk, comments and all (what is seen is what is saved; the line
 *    numbers of what is wrong are the file's); a file whose entries are in one list goes
 *    to the one asked for ([select]).
 *  - The check line, checked once the typing pauses, always one of: `✓ Right`;
 *    `✓ Right · Not saved · <what saving will do>` ([afterSave]); `✗ Line 12: …` with
 *    `N more, tap to see` (each of them, in the window of the panel) when there are more.
 *  - Restore template on the left (what [defaultOf] gives, as an edit: it can be undone,
 *    nothing is written), Save on the right: on only while the text differs from the file
 *    (or is a draft) and is right.
 *  - A draft ([openDraft]: New) is not on disk: Save writes it.
 *
 * As it is typed, what may be written where the cursor is comes up in a list
 * (SheetCompletion, by [schemaOf]). Paste, cut, copy and select all are in the floating bar
 * of the editor (styleEditor). One file at a time, by a key of the panel's own.
 *
 * One thing at a time ([gate], the panel's): a save goes through it, from its write to the end
 * of what comes of it ([onSaved]), and while anything goes on on the panel the editor is locked
 * ([setLocked]): nothing can be typed, undone or saved, and it shows (EditorLockView).
 *
 * @param gate what goes on on the panel: one thing at a time
 * @param isShowing whether the sheet is still shown: work done for one that went is dropped
 * @param popup the window of the panel: every problem of the text, when there is more than one
 * @param check what is wrong with a text of a key, a line each; empty: it is right
 * @param afterSave what saving a right text of a key will do, in a few words (shown on the
 *   check line); null: nothing to say
 * @param defaultOf the template of a key; null: none
 * @param write writes a text of a key (on a worker thread); throws if it cannot
 * @param confirmSave asked before a right text is written: what saving will do, if that wants a
 *   word from the user; then the function given, to write it (not called: nothing is written)
 * @param onSaved a key was written, with the text (on the main thread): the save is over when
 *   this returns (a server restarted, what is installed), the editor locked till then
 * @param schemaOf what may be written in the file of a key, for its completion
 * @param onEdited the text was changed by the user (on the main thread)
 */
internal class SheetFileEditor(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val gate: OperationGate,
    private val isShowing: () -> Boolean,
    private val styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
    private val popup: EntryProblemPopup,
    private val check: (key: String, text: String) -> List<String>,
    private val afterSave: (key: String, text: String) -> CharSequence? = { _, _ -> null },
    private val defaultOf: (key: String) -> String?,
    private val write: (key: String, text: String) -> Unit,
    private val confirmSave: (key: String, text: String, write: () -> Unit) -> Unit = { _, _, write -> write() },
    private val onSaved: suspend (key: String, text: String) -> Unit,
    private val schemaOf: (key: String) -> JsonSchema? = { null },
    private val onEdited: () -> Unit = {},
) {

    val view: View = LayoutInflater.from(activity).inflate(R.layout.view_sheet_file_editor, null)
    private val title = view.findViewById<TextView>(R.id.file_title)
    private val editor = view.findViewById<CodeEditorView>(R.id.file_editor)
    private val empty = view.findViewById<TextView>(R.id.file_empty)
    private val status = view.findViewById<TextView>(R.id.file_status)
    private val help = view.findViewById<TextView>(R.id.file_help)
    private val defaultButton = view.findViewById<MaterialButton>(R.id.file_default)
    private val saveButton = view.findViewById<MaterialButton>(R.id.file_save)
    private val undoButton = view.findViewById<MaterialButton>(R.id.file_undo)
    private val redoButton = view.findViewById<MaterialButton>(R.id.file_redo)
    private val collapseButton = view.findViewById<MaterialButton>(R.id.file_collapse)
    private val lockView = EditorLockView(view, view.findViewById(R.id.file_title_icon), R.drawable.ic_edit)

    /** The key of the file in the editor; null: none yet. */
    var key: String? = null
        private set

    /** Whether the file in the editor is a draft (New): not on disk until it is saved. */
    var isDraft: Boolean = false
        private set

    // the name of that file (its highlighting goes by it), and the title
    private var fileName: String = ""
    private var heading: CharSequence = ""
    // that file as it is on disk; null: not read (yet), or a draft
    private var savedText: String? = null
    // the template, as Restore template put it in: said so while the text is still it
    private var defaultsText: String? = null

    // the file is read and highlighted (what is in the editor can be saved, once it is not locked)
    private var loaded = false
    // the file has a template, and could be read: Restore template can be on
    private var hasDefaults = false
    // why nothing is to be changed now (setLocked); null: it can be
    private var lockMessage: String? = null

    /** Whether the file of [key] could not be read (said in the editor's place): to be opened again. */
    var loadFailed: Boolean = false
        private set

    // what was wrong with the text when it was checked last (empty: it was right)
    private var problems: List<String> = listOf("")

    private var checkJob: Job? = null
    private var loadJob: Job? = null

    // what is to be done once the file is in the editor and editable (whenReady), or only in it (whenOpened)
    private val pending = ArrayList<() -> Unit>()
    private val pendingOpened = ArrayList<() -> Unit>()

    // one at a time: putting a text in and parsing it for the highlighting
    private val lock = Mutex()

    // what may be written where the cursor is, as it is typed (the file of the key shown)
    private val completion = SheetCompletion(activity, editor) { key?.let(schemaOf) }

    init {
        styleEditor(editor)
        // a JSON file in a narrow box: no line numbers, no guides; lines are not wrapped (as
        // in every editor of a panel): a long one scrolls sideways
        editor.setLineNumbersVisible(false)
        editor.setIndentGuidesEnabled(false)
        editor.setWordwrap(false)
        editor.setKeepsTouches(true)
        // nothing to edit until a file is read and parsed for the highlighting
        editor.setEditable(false)
        // checked once the typing pauses
        editor.onTextChangesListener = {
            if (isReady()) onEdited()
            updateButtons()
            checkJob?.cancel()
            checkJob = scope.launch {
                delay(CHECK_DELAY_MILLIS)
                checkText()
                // what was typed is one edit of the history by now: Redo is off after it
                updateButtons()
            }
        }
        defaultButton.setOnClickListener { key?.let(::putDefaults) }
        saveButton.setOnClickListener { key?.let(::save) }
        undoButton.setOnClickListener {
            if (isReady()) editor.undo()
            updateButtons()
        }
        redoButton.setOnClickListener {
            if (isReady()) editor.redo()
            updateButtons()
        }
        // more than one problem: all of them, in the window of the panel
        status.setOnClickListener {
            if (problems.size > 1) {
                popup.show(
                    status,
                    activity.getString(R.string.config_problems_title, fileName, problems.size),
                    problems.joinToString("\n"),
                    StateLevel.BAD_COLOR,
                )
            }
        }
        updateButtons()
    }

    /** Hide, after Undo / Redo: [onCollapse] when it is tapped (the panel hides the editor). */
    fun setOnCollapse(onCollapse: (() -> Unit)?) {
        collapseButton.visibility = if (onCollapse == null) View.GONE else View.VISIBLE
        collapseButton.setOnClickListener(onCollapse?.let { run -> View.OnClickListener { run() } })
    }

    /** A line of the keys and names that may be written, under the editor. */
    fun setHelp(text: CharSequence) {
        help.text = text
        help.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Nothing to edit: [heading] as the title, and why ([message]). */
    fun showNone(heading: CharSequence, message: CharSequence) {
        loadJob?.cancel()
        completion.dismiss()
        pending.clear()
        pendingOpened.clear()
        key = null
        isDraft = false
        loadFailed = false
        loaded = false
        hasDefaults = false
        savedText = null
        this.heading = heading
        title.text = heading
        // not to be typed in behind what says there is nothing (the keyboard goes with the panel's Hide)
        editor.setEditable(false)
        editor.dismissSelectionMenu()
        editor.visibility = View.INVISIBLE
        empty.text = message
        empty.visibility = View.VISIBLE
        status.text = ""
        problems = listOf("")
        updateButtons()
    }

    /**
     * Puts the file of [key] in the editor (named [name], [heading] as the title), as [read]
     * gives it (on a worker thread; null: an empty text), and has it highlighted; editable
     * once that is done.
     */
    fun open(key: String, name: String, heading: CharSequence, read: () -> String?) {
        load(key, name, heading, draft = false, read)
    }

    /** Puts a draft of [key] in the editor: [text], not on disk until it is saved (New). */
    fun openDraft(key: String, name: String, heading: CharSequence, text: String) {
        load(key, name, heading, draft = true) { text }
    }

    private fun load(key: String, name: String, heading: CharSequence, draft: Boolean, read: () -> String?) {
        loadJob?.cancel()
        checkJob?.cancel()
        completion.dismiss()
        pending.clear()
        pendingOpened.clear()
        this.key = key
        this.heading = heading
        isDraft = draft
        loadFailed = false
        loaded = false
        fileName = name
        savedText = null
        defaultsText = null
        problems = listOf("")
        title.text = heading
        empty.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.setEditable(false)
        editor.dismissSelectionMenu()
        status.text = ""
        hasDefaults = defaultOf(key) != null
        updateButtons()
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { read() } }
            if (!isShowing() || this@SheetFileEditor.key != key) return@launch
            // Not read: said, and not to be edited. Taken for an empty text it could be saved
            // over a file that is there, and only could not be read now.
            result.exceptionOrNull()?.let { e ->
                editor.visibility = View.INVISIBLE
                empty.text = activity.getString(R.string.config_read_failed, name, e.message ?: e.javaClass.simpleName)
                empty.visibility = View.VISIBLE
                hasDefaults = false
                loadFailed = true
                updateButtons()
                return@launch
            }
            val text = result.getOrNull().orEmpty()
            lock.withLock {
                try {
                    // the tree of the text before goes with it
                    editor.syntaxEngine.release()
                    editor.refreshHighlighting()
                    // refused while the editor is laid out again (its font and size were just set)
                    while (!editor.setText(text)) delay(BUSY_RETRY_MILLIS)
                    savedText = if (draft) null else text
                    checkText()
                    // the text must not change while it is parsed: editable after
                    highlight(editor, name)
                } finally {
                    // the sheet gone meanwhile: what the parse made goes too
                    if (!isShowing()) editor.syntaxEngine.release()
                }
            }
            if (isShowing() && this@SheetFileEditor.key == key) {
                loaded = true
                editor.setEditable(lockMessage == null)
                updateButtons()
                runWaiting()
            }
        }
    }

    /**
     * Locked, saying why ([message]: what goes on, in a few words): the text stays, to be read
     * and scrolled, and nothing can change it; null: as it was. Called again with another
     * message: only what it says changes.
     */
    fun setLocked(message: CharSequence?) {
        val text = message?.toString()
        if (text == lockMessage) return
        val was = lockMessage != null
        lockMessage = text
        lockView.set(text)
        if ((text != null) == was) return
        if (text != null) {
            completion.dismiss()
            editor.dismissSelectionMenu()
            editor.setEditable(false)
        } else if (loaded) {
            editor.setEditable(true)
        }
        updateButtons()
        if (text == null) runWaiting()
    }

    // what waited for the file: all of it if it can be edited, else what only had to see it
    private fun runWaiting() {
        if (!loaded) return
        val opened = pendingOpened.toList()
        pendingOpened.clear()
        opened.forEach { it() }
        if (lockMessage != null) return
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { it() }
    }

    /**
     * [action] once the file is in the editor and can be edited: now if it is, else when it
     * is (forgotten if another file is opened first).
     */
    fun whenReady(action: () -> Unit) {
        if (isReady()) action() else if (key != null) pending += action
    }

    /** [action] once the file is in the editor, locked or not: only to show a place of it ([select]). */
    fun whenOpened(action: () -> Unit) {
        if (key != null && loaded) action() else if (key != null) pendingOpened += action
    }

    // Save on only when there is something to write and it is right: the file read and
    // editable, a draft or a text not as the file is, nothing going on (locked), and the text
    // right as it was checked last. Undo / Redo on when there is an edit to undo or redo. The
    // title with ● while something is not saved (locked too: it still is).
    private fun updateButtons() {
        val ready = isReady()
        val changed = key != null && loaded && (isDraft || (savedText != null && editor.getText() != savedText))
        saveButton.isEnabled = ready && changed && problems.isEmpty()
        defaultButton.isEnabled = hasDefaults && lockMessage == null
        undoButton.isEnabled = ready && editor.canUndo()
        redoButton.isEnabled = ready && editor.canRedo()
        title.text = if (key != null && (changed || isDraft)) "$heading ●" else heading
    }

    /** Whether there is something in the editor not saved (a draft always is). */
    fun hasUnsavedChanges(): Boolean {
        if (key == null) return false
        if (isDraft) return true
        val reference = savedText ?: return false
        return editor.getText() != reference
    }

    /** The name of the file in the editor (`clangd.json`); empty: none. */
    fun fileName(): String = if (key == null) "" else fileName

    /** Whether the file is in the editor and can be edited (read, highlighted). */
    fun isReady(): Boolean = key != null && loaded && lockMessage == null && editor.isEditable()

    /** The text in the editor. */
    fun text(): String = editor.getText()

    /**
     * [text] in place of the whole text, as an edit (undoable): what the panel adds (a new
     * entry, a tab); [caret]: where the cursor goes then (an offset of [text]).
     */
    fun replaceText(text: String, caret: Int? = null) {
        if (!editor.isEditable()) return
        val lines = editor.getLineCount()
        editor.insert(text, Range(1, 1, lines, editor.getLineEndColumn(lines)))
        checkText()
        caret?.let { select(it, it) }
    }

    /**
     * The text from [start] to [end] (offsets) selected and scrolled to, its line lit up a
     * moment (an entry of a file of one list: Edit); [start] == [end]: the cursor there.
     */
    fun select(start: Int, end: Int) {
        if (key == null || !loaded) return
        val text = editor.getText()
        val (startLine, startColumn) = JsoncSpans.positionOf(text, start)
        val (endLine, endColumn) = JsoncSpans.positionOf(text, end)
        editor.gotoLine(startLine, startColumn, flash = true)
        editor.setSelection(Range(startLine, startColumn, endLine, endColumn))
    }

    /** Checks the text now (it is checked as it is typed too); whether it is right. */
    fun checkNow(): Boolean = checkText()

    /** Saves the text, as Save does. */
    fun saveNow() {
        key?.let(::save)
    }

    /** The editor out of the way: no floating bar, no focus, no keyboard (the editor is hidden). */
    fun hideKeyboard() {
        completion.dismiss()
        editor.dismissSelectionMenu()
        if (editor.hasFocus()) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
        }
    }

    // Restore template: the template in place of the whole text, as an edit (undoable)
    private fun putDefaults(key: String) {
        if (!editor.isEditable()) return
        val text = defaultOf(key) ?: return
        replaceText(text)
        defaultsText = text
        checkText()
    }

    // The text checked, and the check line: right (saved, or not and what saving will do),
    // or the first thing wrong and how many more. Whether it is right.
    private fun checkText(): Boolean {
        checkJob?.cancel()
        val key = key ?: return false
        val text = editor.getText()
        problems = check(key, text).filter { it.isNotBlank() }
        updateButtons()
        status.isClickable = problems.size > 1
        if (problems.isEmpty()) {
            status.setTextColor(StateLevel.GOOD_COLOR)
            val head = activity.getString(
                when {
                    !isDraft && text == savedText -> R.string.config_check_ok
                    text == defaultsText -> R.string.config_check_defaults
                    isDraft -> R.string.config_check_draft
                    else -> R.string.config_check_ok_unsaved
                }
            )
            val after = if (!isDraft && text == savedText) null else afterSave(key, text)
            status.text = if (after.isNullOrEmpty()) head else activity.getString(R.string.config_check_after, head, after)
        } else {
            status.setTextColor(StateLevel.BAD_COLOR)
            val first = activity.getString(R.string.config_check_error, problems.first())
            status.text = if (problems.size > 1) {
                first + "\n" + activity.getString(R.string.config_check_more, problems.size - 1)
            } else {
                first
            }
        }
        return problems.isEmpty()
    }

    // Save: if it is right, asked about if that is called for (confirmSave), then written;
    // else what is wrong
    private fun save(key: String) {
        if (!checkText()) {
            Toast.makeText(activity, R.string.config_save_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isReady()) return
        val text = editor.getText()
        confirmSave(key, text) { writeSave(key, text) }
    }

    // The text written, then what comes of it (onSaved): one operation of the panel, all of it
    // with the editor locked; another going on (it began while the question was up): not done
    private fun writeSave(key: String, text: String) {
        val ticket = gate.begin(PanelOp.SAVE, fileName, key)
        if (ticket == null) {
            gate.current?.let { toastBusy(activity, it.describe(activity)) }
            return
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { write(key, text) } }
                result.onFailure { e ->
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.format_save_failed, fileName, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                // (nothing can be opened meanwhile: locked; the sheet closed, it is all gone)
                if (this@SheetFileEditor.key == key && isShowing()) {
                    isDraft = false
                    savedText = text
                    checkText()
                }
                onSaved(key, text)
            } finally {
                gate.end(ticket)
            }
        }
    }

    /** The sheet is gone: what the editor holds (a parse still running lets go of its own when done). */
    fun release() {
        checkJob?.cancel()
        loadJob?.cancel()
        pending.clear()
        pendingOpened.clear()
        loaded = false
        completion.release()
        editor.onTextChangesListener = null
        editor.dismissSelectionMenu()
        editor.recycleRenderNodes()
        editor.syntaxEngine.release()
    }

    private companion object {
        // the text is checked this long after the typing stops
        const val CHECK_DELAY_MILLIS = 300L

        // the editor refuses a text while it is being zoomed or laid out again: tried again after this
        const val BUSY_RETRY_MILLIS = 50L
    }
}
