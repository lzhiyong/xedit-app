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

package x.editor.app.controller

import android.app.Activity
import android.text.style.TypefaceSpan
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import x.editor.app.R
import x.editor.app.lsp.HoverSpans
import x.editor.app.lsp.HoverMarkdown
import x.editor.app.highlight.HighlightThemes
import x.editor.app.AppSettings
import x.editor.app.completion.BufferWords
import x.editor.app.completion.PathCompletion
import x.editor.app.format.ProjectStyle
import x.editor.app.console.ConsoleLog
import x.editor.app.console.Problem
import x.editor.app.lsp.CompletionTyping
import x.editor.app.lsp.CallSnippet
import x.editor.app.lsp.FormattingRules
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.lsp.LocalFiles
import x.editor.app.lsp.InstalledServers
import x.editor.app.lsp.LanguageServerSpec
import x.editor.app.lsp.LanguageServers
import x.editor.app.lsp.LspLog
import x.editor.app.lsp.Termux
import x.editor.app.lsp.TermuxSocketConnection
import x.editor.app.settings.EditorSettings
import x.editor.app.treesitter.TreeSitterResources
import x.editor.app.treesitter.TreeSitterUpdater
import x.editor.app.toolchain.Toolchain
import x.editor.app.view.CodeEditorView
import x.editor.app.view.PickerSheet
import x.github.module.document.DocumentFile
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.view.CompletionWindow
import x.github.module.editor.view.EditorView
import x.github.module.lsp.CompletionKind
import x.github.module.lsp.CompletionRanker
import x.github.module.lsp.CompletionResult
import x.github.module.lsp.LspClient
import x.github.module.lsp.LspClientConfig
import x.github.module.lsp.LspClientListener
import x.github.module.lsp.LspCodeAction
import x.github.module.lsp.LspCompletionItem
import x.github.module.lsp.LspDiagnostic
import x.github.module.lsp.LspDocument
import x.github.module.lsp.LspFormattingOptions
import x.github.module.lsp.LspLocation
import x.github.module.lsp.LspSymbol
import x.github.module.lsp.SymbolKindInfo
import x.github.module.lsp.LspWorkspaceEdit
import x.github.module.lsp.MessageKind
import x.github.module.lsp.ServerFeatures
import x.github.module.lsp.Snippet
import x.github.module.lsp.SnippetParser
import x.github.module.lsp.TextChange
import x.github.module.lsp.TextPosition
import x.github.module.lsp.TextRange
import x.github.module.lsp.TextSyncKind
import x.github.module.lsp.DiagnosticSeverity
import x.github.module.piecetable.PieceTreeSnapshot
import x.github.module.piecetable.common.Range
import java.io.File

/**
 * Completion for the editors of [activity], from two sources, as blink.cmp has them:
 *
 *  - **a language server** (the lsp module) the user set up (LanguageServers): reached in
 *    Termux over a socket, or started from a binary they installed. The app ships none.
 *  - **the words of the text** (BufferWords): for every document, with or without a
 *    server; what is offered when there is no server, or when it has nothing.
 *  - **snippets** (FriendlySnippets): those of friendly-snippets for the document's
 *    language, with the server's items or the words; one typed whole comes first.
 *    Taken, it is expanded (its variables, its indentation) and Tab goes through its
 *    placeholders (EditorView.CompletionEdit.tabStops), as do the server's snippets.
 *
 * For each document that is loaded: the words of its text are indexed in the background
 * (again a little after edits); if it has a server, the server is started (once per
 * server and project root), the document opened with it, and every edit sent to it.
 * As the user types (see CompletionTyping: a soft keyboard composing a word replaces it at
 * every key): the server is asked at once after one of its trigger characters, after a
 * short pause on a word, and its list is filtered on the client as typing goes on; the
 * words of the text are matched at every key, fuzzily. Taking one replaces the word (or
 * the range the server gave), expands snippets and applies the additional edits, as one
 * step to undo.
 *
 * Main thread; what goes to a server goes out on one thread, in order (an edit before the
 * completion that follows it); the words are found on other threads, from snapshots.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LspController(private val activity: Activity) {

    private val context = activity.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // what goes to the servers, one after the other: notifications in the order they are
    // made, a request after the edits made before it
    private val serial = Dispatchers.IO.limitedParallelism(1)

    // one per server and project root
    private class Server(val key: String, val spec: LanguageServerSpec, val client: LspClient) {
        lateinit var started: Deferred<ServerFeatures?>
    }

    // one per loaded document
    private inner class Attached(val editor: CodeEditorView, val document: DocumentFile) {
        // #region its language server, if it has one
        var server: Server? = null
        var uri: String? = null
        var lsp: LspDocument? = null
        var features: ServerFeatures? = null
        // edited while it was being opened: the whole text goes after the open
        var editedWhileOpening = false
        // the last answer of the server, filtered again as the user types
        var result: CompletionResult? = null
        var resultLine = 0
        // #endregion

        // the words of its text and how often each is there, found in the background
        var words: HashMap<String, Int>? = null
        var indexJob: Job? = null
        // what the list shows: the server's items, or words of the text
        var showingWords = false
        // where the cursor was left by the last completion taken: an edit there that puts
        // the same word in again (a keyboard committing what it composed) brings no list
        var completedAt = -1
        // what the server last said of the document
        var diagnostics: List<LspDiagnostic> = emptyList()
        // the snippets for its language (friendly-snippets), read in the background
        var snippets: List<FriendlySnippets.Snippet> = emptyList()
        var snippetsRequested = false
    }

    /** Called to show a place in another file: open [file] and go to [line]:[column] (1-based). */
    var navigator: ((file: File, line: Int, column: Int) -> Unit)? = null

    // an edit this controller makes (a completion, a format, a rename): not typing
    private var editing = false

    private val servers = HashMap<String, Server>()
    private val attached = HashMap<CodeEditorView, Attached>()
    // when a server that failed was given up, so that it is not started at every file
    private val failedAt = HashMap<String, Long>()
    private var completionJob: Job? = null
    // reported once each: the servers whose completions failed, the documents not on local storage
    private val reportedErrors = HashSet<String>()
    private val reportedNotLocal = HashSet<String>()
    // the directories path completion could not read whole, said once each
    private val reportedPathAccess = HashSet<String>()

    init {
        LspLog.init(context)
        // a new friendly-snippets put in place: read again for every document
        scope.launch {
            TreeSitterUpdater.applied.collect { ids ->
                if (TreeSitterResources.SNIPPETS in ids) {
                    FriendlySnippets.invalidate()
                    attached.values.forEach { loadSnippets(it) }
                }
            }
        }
    }

    private val wordsOn: Boolean get() = EditorSettings.isBufferCompletion(activity)
    private val formatOnPaste: Boolean get() = EditorSettings.isFormatOnPaste(activity)
    private val snippetsOn: Boolean get() = EditorSettings.isSnippetCompletion(activity)

    // the snippets of [entry]'s language, read in the background (again after an update)
    private fun loadSnippets(entry: Attached) {
        entry.snippetsRequested = true
        scope.launch {
            val snippets = withContext(Dispatchers.IO) {
                try {
                    FriendlySnippets.forFile(context, entry.document.getName())
                } catch (e: Exception) {
                    LspLog.w("snippets", e)
                    emptyList()
                }
            }
            if (attached[entry.editor] === entry) entry.snippets = snippets
        }
    }

    // whether [entry] has snippets to offer; read for the first time when asked (the
    // setting may have been turned on meanwhile)
    private fun hasSnippets(entry: Attached): Boolean {
        if (!snippetsOn) return false
        if (!entry.snippetsRequested) loadSnippets(entry)
        return entry.snippets.isNotEmpty()
    }

    // #region documents

    /** [editor] has the text of [document] now: its words are indexed, and it goes to its server, if it has one. */
    fun onDocumentLoaded(editor: CodeEditorView, document: DocumentFile) {
        detach(editor)
        val entry = Attached(editor, document)
        attached[editor] = entry
        editor.onTextChangesListener = { changes -> onTextChanged(entry, changes) }
        editor.onPasted = { start, end -> formatPasted(entry, start, end) }
        if (wordsOn) indexWords(entry, delayMillis = 0)
        if (snippetsOn) loadSnippets(entry)
        scope.launch { applyProjectStyle(entry) }
        scope.launch { attachServer(entry) }
    }

    // #region the project's style

    // What the project's files (.editorconfig, .clang-format) set for the document of
    // [entry], read in the background: see ProjectStyle. Null for a document that is not a
    // file (another app's), or when they cannot be read.
    private suspend fun projectStyleOf(entry: Attached, languageId: String): ProjectStyle? = withContext(Dispatchers.IO) {
        val file = LocalFiles.fileOf(context, entry.document.getUri()) ?: return@withContext null
        try {
            // by its path, or through the folder the user granted
            ProjectStyle.find(file, languageId) { LocalFiles.readText(context, it) }
        } catch (e: Exception) {
            LspLog.w("project style of ${file.name}", e)
            null
        }
    }

    // The editor of [entry] indents as the project says while typing: one level of
    // indentation and the width of a tab, if its files set them.
    private suspend fun applyProjectStyle(entry: Attached) {
        val languageId = FormattingRules.languageIdOf(entry.document.getUri().lastPathSegment.orEmpty())
        val style = projectStyleOf(entry, languageId) ?: return
        if (attached[entry.editor] !== entry) return
        val editor = entry.editor
        style.indentUnit()?.let { editor.setIndentUnit(it) }
        style.tabWidth()?.takeIf { it in 1..16 && it != editor.getTabSize() }?.let { editor.setTabSize(it) }
        style.describe()?.let { LspLog.i("${entry.document.getUri().lastPathSegment}: indentation of $it") }
    }

    // #endregion

    /**
     * [editor]'s text (of [document]) was written to its file; the configuration of the
     * servers is read again if it was that, the snippets if it was one of the user's.
     */
    fun onDocumentSaved(editor: EditorView, document: DocumentFile) {
        scope.launch {
            val entry = (editor as? CodeEditorView)?.let { attached[it] }
            entry?.lsp?.let { lsp -> withContext(serial) { lsp.save { "" } } }
            val file = withContext(Dispatchers.IO) { LocalFiles.fileOf(context, document.getUri()) }
                ?: return@launch
            val (isConfig, isSnippets) = withContext(Dispatchers.IO) {
                (file.canonicalFile == LanguageServers.configFile(context).canonicalFile) to
                    FriendlySnippets.isUserFile(context, file)
            }
            if (isConfig) reloadServers()
            if (isSnippets) reloadUserSnippets(file)
            if (withContext(Dispatchers.IO) { FormattingRules.isRulesFile(context, file) }) reloadFormattingRules(file)
        }
    }

    // the formatting rules were saved: read again (the next formatting goes by them), and
    // the user told how many languages they have or what is wrong
    private suspend fun reloadFormattingRules(file: File) {
        val result = withContext(Dispatchers.IO) { FormattingRules.reload(context) }
        val message = when (result) {
            is FormattingRules.Reload.Read -> {
                LspLog.i("formatting rules: ${result.languages} languages")
                activity.getString(R.string.format_rules_reloaded, result.languages)
            }
            is FormattingRules.Reload.Failed -> {
                LspLog.w("formatting rules: ${file.name}: ${result.message}")
                activity.getString(R.string.format_rules_read_error, file.name, result.message)
            }
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    // [file], of the user's snippets, was saved: read again, for every document, and the
    // user told how many it has or where it is wrong
    private suspend fun reloadUserSnippets(file: File) {
        val result = withContext(Dispatchers.IO) { FriendlySnippets.reload(file) }
        // read again now, or when the setting is turned on
        attached.values.forEach { if (snippetsOn) loadSnippets(it) else it.snippetsRequested = false }
        val message = when (result) {
            is FriendlySnippets.Reload.Read -> {
                LspLog.i("snippets: ${file.name}: ${result.count}")
                activity.getString(R.string.snippets_reloaded, file.name, result.count)
            }
            is FriendlySnippets.Reload.Failed -> {
                LspLog.i("snippets: ${file.name}:${result.line}: ${result.message}")
                if (result.line > 0) activity.getString(R.string.snippets_parse_error, file.name, result.line, result.message)
                else activity.getString(R.string.snippets_read_error, file.name, result.message)
            }
        }
        val failed = result is FriendlySnippets.Reload.Failed
        Toast.makeText(activity, message, if (failed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** [editor] goes: its document is closed with its server. */
    fun onDocumentClosed(editor: CodeEditorView) = detach(editor)

    /** The activity goes: the servers are shut down. */
    fun release() {
        for (editor in attached.keys.toList()) detach(editor)
        shutDownServers()
        scope.cancel()
    }

    private fun detach(editor: CodeEditorView) {
        val entry = attached.remove(editor) ?: return
        editor.onTextChangesListener = null
        editor.onPasted = null
        editor.dismissCompletions()
        entry.indexJob?.cancel()
        closeWithServer(entry)
    }

    private fun closeWithServer(entry: Attached) {
        entry.diagnostics = emptyList()
        entry.editor.setDiagnostics(emptyList())
        val lsp = entry.lsp ?: return
        entry.lsp = null
        entry.server = null
        entry.features = null
        scope.launch(serial + NonCancellable) { runCatching { lsp.close() } }
    }

    // servers.json was saved: the servers are started again as it says now
    private fun reloadServers() {
        LspLog.i("${LanguageServers.CONFIG_FILE} changed: the servers start again")
        for (entry in attached.values) closeWithServer(entry)
        shutDownServers()
        failedAt.clear()
        reportedErrors.clear()
        for (entry in attached.values) scope.launch { attachServer(entry) }
        Toast.makeText(activity, R.string.lsp_config_reloaded, Toast.LENGTH_SHORT).show()
    }

    private fun shutDownServers() {
        val clients = servers.values.map { it.client }
        servers.clear()
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            for (client in clients) {
                withTimeoutOrNull(2_000) { runCatching { client.shutdown() } }
                runCatching { client.close() }
            }
        }
    }

    // #endregion

    // #region servers

    private suspend fun attachServer(entry: Attached) {
        val editor = entry.editor
        val name = entry.document.getName()
        val uri = entry.document.getUri()
        // the configuration of the servers itself: no server is looked for
        val isConfig = withContext(Dispatchers.IO) {
            LocalFiles.fileOf(context, uri)?.canonicalFile == LanguageServers.configFile(context).canonicalFile
        }
        if (isConfig) return
        val spec = withContext(Dispatchers.IO) { LanguageServers.specFor(context, name) }
        if (spec == null) {
            val known = withContext(Dispatchers.IO) { LanguageServers.describe(context) }
            LspLog.i("$name: no server is on for it; $known")
            return
        }
        // a server reads files by their path: one from a cloud drive has none
        val file = LocalFiles.fileOf(context, uri)
        if (file == null) {
            LspLog.w("$name ($uri): not on local storage, not sent to ${spec.id}")
            if (reportedNotLocal.add(uri.toString())) {
                Toast.makeText(activity, activity.getString(R.string.lsp_file_not_local, name), Toast.LENGTH_LONG).show()
            }
            return
        }
        val root = LocalFiles.rootFor(file, spec)
        LspLog.i("$file: server ${spec.id}, project root $root")
        val server = serverFor(spec, root) ?: return
        entry.server = server
        entry.uri = LocalFiles.uriOf(file)
        val features = server.started.await() ?: return
        if (attached[editor] !== entry || entry.server !== server) return
        entry.features = features
        // the text as it is now; what is typed meanwhile goes after it
        val text = editor.getText()
        val opened = withContext(serial) { server.client.open(entry.uri!!, spec.languageIdFor(file), text) }
        if (attached[editor] !== entry || entry.server !== server) {
            withContext(serial + NonCancellable) { opened.close() }
            return
        }
        entry.lsp = opened
        LspLog.i("opened ${entry.uri} with ${spec.id} (completion: ${features.completion}, triggers ${features.completionTriggers})")
        if (entry.editedWhileOpening) sendFullText(entry)
    }

    private fun serverFor(spec: LanguageServerSpec, root: File): Server? {
        val key = "${spec.id}@${root.path}"
        servers[key]?.let { return it }
        failedAt[key]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MILLIS) return null }

        val connection = when (val c = spec.connection) {
            is LanguageServerSpec.Connection.Socket -> {
                if (c.termux != null && Termux.isInstalled(context) && !Termux.hasPermission(context)) {
                    // the connection waits for the answer (TermuxSocketConnection)
                    ActivityCompat.requestPermissions(activity, arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
                }
                TermuxSocketConnection(context, c.host, c.port, c.termux)
            }
            is LanguageServerSpec.Connection.Process ->
                InstalledServers.connection(context, c.command, c.environment, root)
            // the user's own (in the app's usr/bin) first, Termux's otherwise
            is LanguageServerSpec.Connection.Auto -> {
                val own = Toolchain.locate(context, c.command.first())
                if (own != null) {
                    LspLog.i("${spec.id}: ${c.command.first()} of the app's toolchain ($own)")
                    InstalledServers.connection(context, listOf(own.path) + c.command.drop(1), c.environment, root)
                } else {
                    LspLog.i("${spec.id}: ${c.command.first()} is not in the app's usr/bin: Termux's, on ${c.host}:${c.port}")
                    if (Termux.isInstalled(context) && !Termux.hasPermission(context)) {
                        ActivityCompat.requestPermissions(activity, arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
                    }
                    TermuxSocketConnection(context, c.host, c.port, c.termuxBridge())
                }
            }
        }
        val client = LspClient(
            connection,
            LspClientConfig(
                rootUri = Uri.fromFile(root).toString(),
                clientName = "xedit",
                initializationOptions = spec.initializationOptions,
                initializeTimeoutMillis = spec.initializeTimeoutMillis ?: INITIALIZE_TIMEOUT_MILLIS,
            ),
            object : LspClientListener {
                override fun onShowMessage(kind: MessageKind, message: String) {
                    ConsoleLog.append(ConsoleLog.serverChannel(spec.id), levelOf(kind), message)
                    if (kind == MessageKind.ERROR) scope.launch { toast(spec.id, message) }
                }

                // the server's own log, in its channel of the console
                override fun onLogMessage(kind: MessageKind, message: String) {
                    ConsoleLog.append(ConsoleLog.serverChannel(spec.id), levelOf(kind), message)
                }

                override fun onDiagnostics(uri: String, diagnostics: List<LspDiagnostic>, version: Int?) {
                    scope.launch { showDiagnostics(uri, diagnostics) }
                }

                // what a code action's command asks for
                override fun applyEdit(edit: LspWorkspaceEdit): java.util.concurrent.CompletableFuture<Boolean> {
                    val done = java.util.concurrent.CompletableFuture<Boolean>()
                    scope.launch { done.complete(runCatching { applyWorkspaceEdit(edit) > 0 }.getOrDefault(false)) }
                    return done
                }
            }
        )
        val server = Server(key, spec, client)
        LspLog.i("starting ${spec.id}: $connection")
        server.started = scope.async(Dispatchers.IO) {
            try {
                client.start().also { features ->
                    LspLog.i("${spec.id} started: ${features.name ?: "?"} ${features.version ?: ""}, sync ${features.textSync}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, activity.getString(R.string.lsp_server_connected, spec.id), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LspLog.w("cannot start ${spec.id}", e)
                if (e.message?.contains("initialize: no answer") == true) {
                    LspLog.w(
                        "${spec.id} did not answer: it may need longer to start the first time (\"initializeTimeout\" " +
                            "in seconds, in ${LanguageServers.CONFIG_FILE}), or it is not there (run its command in Termux)"
                    )
                }
                withContext(Dispatchers.Main) {
                    servers.remove(key)
                    failedAt[key] = System.currentTimeMillis()
                    toast(spec.id, e.message ?: e.javaClass.simpleName)
                }
                runCatching { client.close() }
                null
            }
        }
        servers[key] = server
        return server
    }

    private fun toast(server: String, message: String) {
        Toast.makeText(activity, activity.getString(R.string.lsp_server_failed, server, message), Toast.LENGTH_LONG).show()
    }

    // #endregion

    // #region edits

    private fun onTextChanged(entry: Attached, changes: List<EditorDocument.AppliedChange>) {
        // the words: indexed again once the user stops for a moment
        if (wordsOn) indexWords(entry, INDEX_AFTER_EDIT_MILLIS)
        val change = changes.singleOrNull()
        val buffer = entry.editor.getTextBuffer()
        // the text inserted, from the text as it is now (the change starts at rangeOffset)
        val inserted = change?.let {
            val from = buffer.getPositionAt(it.rangeOffset)
            val to = buffer.getPositionAt(it.rangeOffset + it.insertedTextLength)
            buffer.getValueInRange(Range(from.lineNumber, from.column, to.lineNumber, to.column))
        }

        // the server hears of it
        val lsp = entry.lsp
        if (lsp == null) {
            if (entry.server != null) entry.editedWhileOpening = true
        } else if (change == null || inserted == null || entry.features?.textSync == TextSyncKind.FULL) {
            sendFullText(entry)
        } else {
            val r = change.range
            val textChange = TextChange(
                TextRange(TextPosition.fromOneBased(r.startLine, r.startColumn), TextPosition.fromOneBased(r.endLine, r.endColumn)),
                inserted
            )
            scope.launch(serial) { lsp.change(textChange) { "" } }
        }

        if (change == null || inserted == null) {
            cancelCompletion(entry)
            return
        }
        // a completion, a format, a rename made here: not typing, nothing comes up for it
        if (editing) {
            val editor = entry.editor
            entry.completedAt = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
            return
        }
        onTyped(entry, change, inserted)
    }

    private fun sendFullText(entry: Attached) {
        val lsp = entry.lsp ?: return
        val text = entry.editor.getText()
        scope.launch(serial) { lsp.change(TextChange.full(text)) { text } }
    }

    // What typing [inserted] in place of [change] does to the completions (see
    // CompletionTyping: a soft keyboard composing a word replaces it at every key).
    private fun onTyped(entry: Attached, change: EditorDocument.AppliedChange, inserted: String) {
        val editor = entry.editor
        if (!editor.isEditable() || editor.isSelected()) return
        val features = entry.features
        val serverOn = entry.lsp != null && features?.completion == true
        // a path being typed (`./src/ma`, `"assets/ic`): the files there, with or without a
        // server (PathCompletion)
        val path = PathCompletion.query(lineBeforeCursor(editor))
        if (path == null && !serverOn && !wordsOn && !hasSnippets(entry)) return
        val buffer = editor.getTextBuffer()
        val cursorOffset = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
        // The word a completion put in, put in again where it ended (a keyboard committing
        // what it was composing): it was taken already, no list for it.
        if (cursorOffset == entry.completedAt && change.deletedTextLength == change.insertedTextLength) return
        entry.completedAt = -1
        // the signature of the call being typed: after `(` or `,`, gone after `)`
        if (serverOn && features.signatureHelp) {
            when {
                inserted.length == 1 && inserted in features.signatureTriggers -> requestSignature(entry)
                inserted == ")" -> editor.dismissInfo()
            }
        }
        val result = entry.result
        val action = CompletionTyping.decide(
            lineBeforeCursor = lineBeforeCursor(editor),
            inserted = inserted,
            deletedLength = change.deletedTextLength,
            cursorAtInsertEnd = cursorOffset == change.rangeOffset + change.insertedTextLength,
            showing = editor.isShowingCompletions,
            // the words of the text are matched again at every key, the server's list is filtered
            hasCompleteResult = !entry.showingWords && result != null && !result.isIncomplete &&
                entry.resultLine == editor.getCursorLine(),
            triggers = if (serverOn) features.completionTriggers else emptySet(),
        )
        val perform: () -> Unit = {
            when (action) {
                is CompletionTyping.Action.Request -> {
                    val wait = if (action.immediate || !serverOn) 0L else TYPING_PAUSE_MILLIS
                    requestCompletion(entry, action.trigger, wait)
                }
                CompletionTyping.Action.Refilter -> result?.let { showRanked(entry, it) }
                CompletionTyping.Action.Dismiss -> cancelCompletion(entry)
                CompletionTyping.Action.None -> Unit
            }
            Unit
        }
        // the files of the path first; none there: what typing does otherwise
        if (path != null) completePath(entry, path) { if (serverOn || wordsOn || hasSnippets(entry)) perform() else cancelCompletion(entry) }
        else perform()
    }

    // #region paths

    // a file or directory of a path, taken from the list: [replaceLength] chars before the
    // cursor (what was typed of its name) go for it
    private class PathPick(val name: String, val isDirectory: Boolean, val replaceLength: Int)

    // The files in the directory of [query] whose names start with what is typed of the
    // last part, shown as the list; [otherwise] if there are none (or the directory cannot
    // be read). Relative paths are relative to the directory of the document.
    private fun completePath(entry: Attached, query: PathCompletion.Query, otherwise: () -> Unit) {
        completionJob?.cancel()
        val editor = entry.editor
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        completionJob = scope.launch {
            // by its path, or through the folder the user granted (SAF)
            var directory: File? = null
            var complete = true
            val entries = withContext(Dispatchers.IO) {
                try {
                    val base = LocalFiles.fileOf(context, entry.document.getUri())?.absoluteFile?.parentFile
                    directory = PathCompletion.directoryOf(query, base)
                    val listing = directory?.let { LocalFiles.list(context, it) }
                    complete = listing?.complete ?: false
                    PathCompletion.filter(
                        listing?.entries.orEmpty().map { (name, isDirectory) -> PathCompletion.Entry(name, isDirectory) },
                        query.prefix, MAX_SHOWN,
                    )
                } catch (e: Exception) {
                    LspLog.w("path completion in $directory", e)
                    emptyList()
                }
            }
            if (attached[editor] !== entry || editor.getCursorLine() != line || editor.getCursorColumn() != column) return@launch
            // Only part of the directory could be read (its folders: scoped storage), or
            // none of it: said once for it, with what would let all of it be read.
            if (!complete) {
                val where = directory
                LspLog.i("path completion: ${where ?: query.directory} is not in a folder of the file tree (${entries.size} found)")
                if (where != null && reportedPathAccess.add(where.path)) {
                    Toast.makeText(activity, activity.getString(R.string.completion_path_no_access, where.name.ifEmpty { where.path }), Toast.LENGTH_LONG).show()
                }
            }
            if (entries.isEmpty()) {
                otherwise()
                return@launch
            }
            val (folderBadge, folderColor) = badgeOf(CompletionKind.FOLDER)
            val (fileBadge, fileColor) = badgeOf(CompletionKind.FILE)
            val items = entries.map { found ->
                val label = if (found.isDirectory) found.name + "/" else found.name
                CompletionWindow.Item(
                    label = label,
                    detail = activity.getString(if (found.isDirectory) R.string.completion_source_folder else R.string.completion_source_file),
                    kind = if (found.isDirectory) folderBadge else fileBadge,
                    kindColor = if (found.isDirectory) folderColor else fileColor,
                    matchIndices = BufferWords.highlight(label, query.prefix),
                    tag = PathPick(found.name, found.isDirectory, query.prefix.length),
                )
            }
            // listed again at every key, as the words are
            entry.showingWords = true
            entry.result = null
            editor.showCompletions(items) { picked -> onPicked(entry, picked) }
        }
    }

    // [pick] taken: its name in place of what was typed of it; a directory gets its `/`
    // and lists what is in it at once (as cmp-path does)
    private fun applyPath(entry: Attached, pick: PathPick) {
        cancelCompletion(entry)
        val editor = entry.editor
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        val length = pick.replaceLength.coerceAtMost(column - 1)
        val text = if (pick.isDirectory) pick.name + "/" else pick.name
        val made = editing {
            editor.applyCompletion(EditorView.CompletionEdit(Range(line, column - length, line, column), text))
        }
        if (made && pick.isDirectory) {
            editor.post {
                if (attached[editor] !== entry) return@post
                PathCompletion.query(lineBeforeCursor(editor))?.let { completePath(entry, it) {} }
            }
        }
    }

    // #endregion

    // #endregion

    // #region completion

    private fun requestCompletion(entry: Attached, trigger: String?, delayMillis: Long) {
        completionJob?.cancel()
        completionJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val editor = entry.editor
            val line = editor.getCursorLine()
            val column = editor.getCursorColumn()
            val lsp = entry.lsp
            if (lsp != null && entry.features?.completion == true && (trigger != null || currentPrefix(editor).isNotEmpty())) {
                val result = try {
                    withContext(serial) { lsp.completion(TextPosition.fromOneBased(line, column), trigger) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LspLog.w("completion at $line:$column from ${entry.server?.spec?.id}", e)
                    entry.server?.let { if (reportedErrors.add(it.key)) toast(it.spec.id, e.message ?: e.javaClass.simpleName) }
                    null
                }
                // the cursor left the line meanwhile: of no use any more
                if (attached[editor] !== entry || editor.getCursorLine() != line) return@launch
                if (result != null) {
                    LspLog.i("completion at $line:$column (trigger ${trigger ?: "none"}): ${result.items.size} items" +
                        if (result.isIncomplete) ", incomplete" else "")
                }
                if (result != null && result.items.isNotEmpty()) {
                    entry.result = result
                    entry.resultLine = line
                    entry.showingWords = false
                    showRanked(entry, result)
                    return@launch
                }
                // nothing from the server: the words of the text, as blink.cmp falls back
            }
            if (wordsOn) showWords(entry) else showOnly(entry, emptyList())
        }
    }

    private fun cancelCompletion(entry: Attached) {
        completionJob?.cancel()
        entry.result = null
        entry.showingWords = false
        entry.editor.dismissCompletions()
    }

    // the items of [result] that match what is typed before the cursor, best first
    private fun showRanked(entry: Attached, result: CompletionResult) {
        val editor = entry.editor
        val prefix = currentPrefix(editor)
        val ranked = CompletionRanker.rank(result.items, prefix).take(MAX_SHOWN)
        val items = ranked.map { item ->
            val (badge, color) = badgeOf(item.kind)
            CompletionWindow.Item(
                label = item.label,
                detail = item.labelDetail ?: item.detail,
                kind = badge,
                kindColor = color,
                // what matches what was typed, also inside the label (clangd's start with a space)
                matchIndices = BufferWords.highlight(item.label, prefix),
                tag = item,
            )
        }
        editor.showCompletions(withSnippets(entry, items)) { picked -> onPicked(entry, picked) }
    }

    // The words of the text that match the word before the cursor (fuzzily), best first:
    // from the index of the text (found now if there is none yet), and the words near the
    // cursor, which count for more.
    private suspend fun showWords(entry: Attached) {
        val editor = entry.editor
        val prefix = currentPrefix(editor)
        if (prefix.isEmpty()) {
            cancelCompletion(entry)
            return
        }
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        val buffer = editor.getTextBuffer()
        val cursor = buffer.getOffsetAt(line, column)
        val snapshot = buffer.createSnapshot()
        val indexed = entry.words
        val (counts, candidates) = withContext(Dispatchers.Default) {
            val counts = indexed ?: wordsOf(snapshot, cursor, cursor - prefix.length)
            val near = BufferWords.near(snapshot, cursor, NEAR_WINDOW, cursor - prefix.length, cursor)
            counts to BufferWords.rank(counts, near, prefix, MAX_SHOWN)
        }
        if (attached[editor] !== entry || editor.getCursorLine() != line || editor.getCursorColumn() != column) return
        if (indexed == null) entry.words = counts
        val label = activity.getString(R.string.completion_source_words)
        val items = candidates.map { candidate ->
            CompletionWindow.Item(
                label = candidate.word,
                detail = label,
                kind = "w",
                kindColor = WORD_COLOR,
                matchIndices = candidate.positions,
                tag = Word(candidate.word),
            )
        }
        showOnly(entry, items)
    }

    // [items] that are not the server's (words of the text, or none), with the snippets
    // that match; nothing at all: no list
    private fun showOnly(entry: Attached, items: List<CompletionWindow.Item>) {
        val editor = entry.editor
        val shown = withSnippets(entry, items)
        if (shown.isEmpty()) {
            editor.dismissCompletions()
            return
        }
        // matched again at every key, as the words are
        entry.showingWords = true
        entry.result = null
        editor.showCompletions(shown) { picked -> onPicked(entry, picked) }
    }

    // #region snippets

    // a snippet of friendly-snippets, taken from the list: [replaceLength] chars before
    // the cursor go for it
    private class SnippetPick(val snippet: FriendlySnippets.Snippet, val replaceLength: Int)

    // [items] with the snippets that match what was typed: those typed whole first, then
    // the items that start with it, then the other snippets, then the other items
    private fun withSnippets(entry: Attached, items: List<CompletionWindow.Item>): List<CompletionWindow.Item> {
        if (!hasSnippets(entry)) return items
        val line = lineBeforeCursor(entry.editor)
        val matches = FriendlySnippets.match(entry.snippets, line, MAX_SNIPPETS)
        if (matches.isEmpty()) return items
        val (badge, color) = badgeOf(CompletionKind.SNIPPET)
        fun itemOf(match: FriendlySnippets.Match): CompletionWindow.Item {
            val typed = line.takeLast(match.replaceLength)
            return CompletionWindow.Item(
                label = match.snippet.prefix,
                detail = match.snippet.name,
                kind = badge,
                kindColor = color,
                matchIndices = BufferWords.highlight(match.snippet.prefix, typed),
                tag = SnippetPick(match.snippet, match.replaceLength),
            )
        }
        val (exact, partial) = matches.partition { it.exact }
        val word = CompletionTyping.wordBefore(line)
        val (strong, weak) = items.partition { word.isNotEmpty() && it.label.trimStart().startsWith(word, ignoreCase = true) }
        return (exact.map(::itemOf) + strong + partial.map(::itemOf) + weak).take(MAX_SHOWN)
    }

    // A snippet taken: expanded in place of what was typed for it, with its variables, the
    // indentation of the line on its lines and the editor's indentation for its tabs; Tab
    // goes through its placeholders then.
    private fun applySnippet(entry: Attached, pick: SnippetPick) {
        cancelCompletion(entry)
        val editor = entry.editor
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        val before = lineBeforeCursor(editor)
        val length = pick.replaceLength.coerceAtMost(before.length)
        val lineText = editor.getLineText(line)
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val source = pick.snippet.body
            .replace("\t", editor.getIndentUnit())
            .replace("\n", editor.getEOL() + indent)
        val document = entry.document
        val variables = FriendlySnippets.variables(
            fileName = document.getName(),
            filePath = LocalFiles.fileOf(context, document.getUri())?.path,
            lineNumber = line,
            lineText = lineText,
            currentWord = CompletionTyping.wordBefore(before),
            clipboard = { clipboardText() },
        )
        val snippet = SnippetParser.parse(source, variables)
        editing { editor.applyCompletion(snippetEdit(Range(line, column - length, line, column), snippet)) }
    }

    /**
     * Puts the snippet [body] in place of the selection, or at the cursor: a keyboard
     * shortcut of the user's own (Shortcuts). Its variables are those of the snippets of
     * the completion, with `TM_SELECTED_TEXT` the text it replaces; Tab goes through its
     * tab stops. Its lines are indented as the line it goes in, a tab is the indentation.
     */
    fun insertSnippet(editor: CodeEditorView, body: String) {
        if (!editor.isEditable()) return
        val range = editor.getSelection()
            ?: Range(editor.getCursorLine(), editor.getCursorColumn(), editor.getCursorLine(), editor.getCursorColumn())
        val line = range.startLine
        val lineText = editor.getLineText(line)
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val selected = if (editor.isSelected()) editor.getSelectedText() else ""
        val source = body
            .replace("\t", editor.getIndentUnit())
            .replace("\n", editor.getEOL() + indent)
        val document = attached[editor]?.document
        val base = FriendlySnippets.variables(
            fileName = document?.getName().orEmpty(),
            filePath = document?.let { LocalFiles.fileOf(context, it.getUri())?.path },
            lineNumber = line,
            lineText = lineText,
            currentWord = CompletionTyping.wordBefore(lineBeforeCursor(editor)),
            clipboard = { clipboardText() },
        )
        val variables: (String) -> String? = { name ->
            if (name == "TM_SELECTED_TEXT" || name == "SELECTION") selected else base(name)
        }
        val snippet = SnippetParser.parse(source, variables)
        editing { editor.applyCompletion(snippetEdit(range, snippet)) }
    }

    // What the editor makes of [snippet] in place of [range]: its text, the cursor at its
    // first tab stop (the placeholder selected), and its tab stops for Tab, the last
    // where it ends ($0, or the end of its text).
    private fun snippetEdit(
        range: Range,
        snippet: Snippet,
        additional: List<Pair<Range, String>> = emptyList()
    ): EditorView.CompletionEdit {
        val stops = snippet.tabStops.map { it.start to it.end }.toMutableList()
        if (snippet.tabStops.none { it.index == 0 }) stops += snippet.text.length to snippet.text.length
        val first = snippet.cursor
        return EditorView.CompletionEdit(range, snippet.text, first.start, first.end - first.start, additional, stops)
    }

    private fun clipboardText(): String? = try {
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString()
    } catch (e: Exception) {
        null
    }

    // #endregion

    // the words of [snapshot]: all of it, or the part around the cursor of a very large
    // text; the word being typed ([wordStart] to [cursor]) is left out
    private fun wordsOf(snapshot: PieceTreeSnapshot, cursor: Int, wordStart: Int): HashMap<String, Int> {
        val length = snapshot.length
        val from = if (length > MAX_INDEXED_CHARS) (cursor - MAX_INDEXED_CHARS / 2).coerceAtLeast(0) else 0
        val to = if (length > MAX_INDEXED_CHARS) (from + MAX_INDEXED_CHARS).coerceAtMost(length) else length
        return BufferWords.index(snapshot, from, to, wordStart, cursor)
    }

    // the words of the text are indexed again after [delayMillis] (again later if it is edited meanwhile)
    private fun indexWords(entry: Attached, delayMillis: Long) {
        entry.indexJob?.cancel()
        entry.indexJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val editor = entry.editor
            val buffer = editor.getTextBuffer()
            val cursor = buffer.getOffsetAt(editor.getCursorLine(), editor.getCursorColumn())
            val wordStart = cursor - currentPrefix(editor).length
            val snapshot = buffer.createSnapshot()
            val counts = withContext(Dispatchers.Default) { wordsOf(snapshot, cursor, wordStart) }
            if (attached[editor] === entry) entry.words = counts
        }
    }

    // a word of the text, taken from the list
    private class Word(val text: String)

    private fun onPicked(entry: Attached, picked: CompletionWindow.Item) {
        when (val tag = picked.tag) {
            is LspCompletionItem -> pick(entry, tag)
            is SnippetPick -> applySnippet(entry, tag)
            is PathPick -> applyPath(entry, tag)
            is Word -> {
                cancelCompletion(entry)
                val editor = entry.editor
                val line = editor.getCursorLine()
                val column = editor.getCursorColumn()
                val prefix = currentPrefix(editor)
                editing {
                    editor.applyCompletion(EditorView.CompletionEdit(Range(line, column - prefix.length, line, column), tag.text))
                }
            }
        }
    }

    // [item] was taken: completed by the server if it can (documentation, additional
    // edits), then applied
    private fun pick(entry: Attached, item: LspCompletionItem) {
        completionJob?.cancel()
        entry.result = null
        scope.launch {
            val features = entry.features
            val server = entry.server
            val complete = if (server != null && features?.completionResolve == true && item.additionalEdits.isEmpty()) {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
                    runCatching { withContext(serial) { server.client.resolve(item) } }.getOrNull()
                } ?: item
            } else {
                item
            }
            if (attached[entry.editor] === entry) apply(entry, complete)
        }
    }

    private fun apply(entry: Attached, item: LspCompletionItem) {
        val editor = entry.editor
        val line = editor.getCursorLine()
        val column = editor.getCursorColumn()
        val prefix = currentPrefix(editor)
        // the server's range, reaching to the cursor (the user may have typed on since)
        val range = item.edit?.range?.let { r ->
            val start = r.start.toOneBased()
            val end = r.end.toOneBased()
            if (end.first == line && end.second < column) Range(start.first, start.second, line, column)
            else Range(start.first, start.second, end.first, end.second)
        } ?: Range(line, column - prefix.length, line, column)
        val raw = item.edit?.newText ?: item.insertText
        // clangd adds the #include of what was taken: not wanted (it is also started with
        // --header-insertion=never, see ClangdDefaults; a server started before is not)
        val noIncludes = entry.server?.spec?.let(::isClangd) == true
        val additional = item.additionalEdits
            .filterNot { noIncludes && it.newText.trimStart().startsWith("#include") }
            .map { edit ->
                val s = edit.range.start.toOneBased()
                val e = edit.range.end.toOneBased()
                Range(s.first, s.second, e.first, e.second) to edit.newText
            }
        // a snippet: Tab goes through its placeholders (the arguments of a call), as in nvim;
        // a function the server sent only the name of: its call, with placeholders for the
        // parameters of its signature (CallSnippet)
        val call = if (item.isSnippet) null else callSnippet(editor, item, raw, range)
        val edit = when {
            item.isSnippet -> snippetEdit(range, SnippetParser.parse(raw), additional)
            call != null -> snippetEdit(range, SnippetParser.parse(call), additional)
            else -> EditorView.CompletionEdit(range, raw, raw.length, 0, additional)
        }
        editing { editor.applyCompletion(edit) }
    }

    // The call of [item], a function put in as [raw] in place of [range]: see CallSnippet.
    // Null where it is not called (not a function, a `(` after it, an import line).
    private fun callSnippet(editor: CodeEditorView, item: LspCompletionItem, raw: String, range: Range): String? {
        if (range.startLine != range.endLine) return null
        val lineText = editor.getLineText(range.endLine)
        val before = lineText.take((range.startColumn - 1).coerceIn(0, lineText.length))
        val after = lineText.drop((range.endColumn - 1).coerceIn(0, lineText.length))
        return CallSnippet.of(
            name = raw,
            kind = item.kind,
            signatures = listOf(item.labelDetail, item.detail, item.documentation, item.label),
            textBefore = before,
            textAfter = after,
        )
    }

    private fun isClangd(spec: LanguageServerSpec): Boolean =
        spec.id.contains("clangd", ignoreCase = true) || spec.connection.toString().contains("clangd")

    // [block] makes an edit of this controller (see onTextChanged)
    private inline fun <T> editing(block: () -> T): T {
        editing = true
        try {
            return block()
        } finally {
            editing = false
        }
    }

    // #region more features

    /** The diagnostics of the open documents, for the console's problems. */
    fun problems(): List<Problem> = attached.values.flatMap { entry ->
        val file = entry.uri?.let { fileOfUri(it) } ?: return@flatMap emptyList()
        entry.diagnostics.map { d ->
            val (line, column) = d.range.start.toOneBased()
            Problem(file, line, column, d.severity.ordinal + 1, d.message, d.source ?: entry.server?.spec?.id ?: "lsp")
        }
    }

    private fun levelOf(kind: MessageKind) = when (kind) {
        MessageKind.ERROR -> ConsoleLog.Level.ERROR
        MessageKind.WARNING -> ConsoleLog.Level.WARNING
        MessageKind.INFO -> ConsoleLog.Level.INFO
        MessageKind.LOG -> ConsoleLog.Level.DEBUG
    }

    /** What the server of [editor]'s document can do; null if it has none (yet). */
    fun features(editor: EditorView): ServerFeatures? =
        (editor as? CodeEditorView)?.let { attached[it] }?.takeIf { it.lsp != null }?.features

    // the document with its server, or a word that there is none
    private fun served(editor: EditorView): Pair<Attached, LspDocument>? {
        val entry = (editor as? CodeEditorView)?.let { attached[it] }
        val lsp = entry?.lsp
        if (entry == null || lsp == null) {
            Toast.makeText(activity, R.string.lsp_server_unavailable, Toast.LENGTH_SHORT).show()
            return null
        }
        return entry to lsp
    }

    private fun cursorPosition(editor: EditorView) = TextPosition.fromOneBased(editor.getCursorLine(), editor.getCursorColumn())

    // a request of [name]; its failure is logged, and said unless [quiet] (asked for
    // without the user asking: a format after a paste)
    private suspend fun <T> ask(entry: Attached, name: String, quiet: Boolean = false, request: suspend () -> T): T? = try {
        withContext(serial) { request() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LspLog.w("$name from ${entry.server?.spec?.id}", e)
        if (!quiet) toast(entry.server?.spec?.id ?: "?", e.message ?: e.javaClass.simpleName)
        null
    }

    private fun nothingFound() = Toast.makeText(activity, R.string.lsp_result_empty, Toast.LENGTH_SHORT).show()

    // what the server said of the document, drawn in its editor
    private fun showDiagnostics(uri: String, diagnostics: List<LspDiagnostic>) {
        val entry = attached.values.firstOrNull { it.uri == uri } ?: return
        entry.diagnostics = diagnostics
        entry.editor.setDiagnostics(diagnostics.map { d ->
            EditorView.Diagnostic(editorRange(d.range), d.severity.ordinal + 1, listOfNotNull(d.message, d.source?.let { "($it)" }).joinToString(" "))
        })
    }

    private fun editorRange(range: TextRange): Range {
        val (sl, sc) = range.start.toOneBased()
        val (el, ec) = range.end.toOneBased()
        return Range(sl, sc, el, ec)
    }

    /** The diagnostics at the cursor and what the server says of what is there (hover). */
    fun showHover(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        scope.launch {
            val here = editor.diagnosticsAt(editor.getCursorLine(), editor.getCursorColumn())
            val hover = if (entry.features?.hover == true) ask(entry, "hover") { lsp.hover(cursorPosition(editor)) } else null
            val text = SpannableStringBuilder()
            for (d in here) {
                val (label, color) = when (d.severity) {
                    EditorView.SEVERITY_ERROR -> R.string.lsp_severity_error to 0xFFE5534B.toInt()
                    EditorView.SEVERITY_WARNING -> R.string.lsp_severity_warning to 0xFFD4A72C.toInt()
                    EditorView.SEVERITY_INFORMATION -> R.string.lsp_severity_info to 0xFF4FA3E0.toInt()
                    else -> R.string.lsp_severity_hint to 0xFF8B949E.toInt()
                }
                val start = text.length
                text.append(activity.getString(label))
                text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append(": ").append(d.message).append('\n')
            }
            hover?.text?.takeIf { it.isNotBlank() }?.let { content ->
                if (text.isNotEmpty()) text.append('\n')
                // Markdown as it is meant to look: headings, `code`, code in the theme's colors
                text.append(if (hover.isMarkdown) HoverSpans.spans(HoverMarkdown.render(content), palette()) else content.trim())
            }
            while (text.isNotEmpty() && text[text.length - 1] == '\n') text.delete(text.length - 1, text.length)
            if (text.isEmpty()) nothingFound() else editor.showInfo(text)
        }
    }

    // the signature of the call the cursor is in, the parameter typed in the accent color
    private fun requestSignature(entry: Attached) {
        val lsp = entry.lsp ?: return
        val editor = entry.editor
        scope.launch {
            val help = runCatching { withContext(serial) { lsp.signatureHelp(cursorPosition(editor)) } }.getOrNull()
            val (signature, parameter) = help?.active ?: run {
                editor.dismissInfo()
                return@launch
            }
            val text = SpannableStringBuilder(signature.label)
            text.setSpan(TypefaceSpan("monospace"), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            signature.parameters.getOrNull(parameter)?.let { p ->
                if (p.start in 0..p.end && p.end <= text.length) {
                    val accent = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())
                    text.setSpan(ForegroundColorSpan(accent), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(StyleSpan(Typeface.BOLD), p.start, p.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                p.documentation?.takeIf { it.isNotBlank() }?.let { text.append("\n\n").append(HoverSpans.spans(HoverMarkdown.render(it), palette())) }
            }
            if (help.signatures.size > 1) text.append("\n(${help.activeSignature + 1}/${help.signatures.size})")
            editor.showInfo(text)
        }
    }

    /** Where what is at the cursor is defined: there at once, or a list to choose from. */
    fun gotoDefinition(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        scope.launch {
            val found = ask(entry, "definition") { lsp.definition(cursorPosition(editor)) } ?: return@launch
            when (found.size) {
                0 -> nothingFound()
                1 -> navigate(entry, found.single())
                else -> chooseLocation(entry, activity.getString(R.string.lsp_action_definition), found)
            }
        }
    }

    /** Where what is at the cursor is used: a list, a tap goes there. */
    fun findReferences(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        scope.launch {
            val found = ask(entry, "references") { lsp.references(cursorPosition(editor)) } ?: return@launch
            if (found.isEmpty()) nothingFound()
            else chooseLocation(entry, activity.getString(R.string.lsp_references_title), found)
        }
    }

    // A list of places (PickerSheet): grouped by file, this document first, each its line
    // of code and its number; the place at the cursor marked.
    private suspend fun chooseLocation(entry: Attached, title: String, locations: List<LspLocation>) {
        val editor = entry.editor
        val cursorLine = editor.getCursorLine() - 1
        // the lines of the other files, read once each
        val texts = withContext(Dispatchers.IO) {
            locations.map { location ->
                val line = location.range.start.line + 1
                if (location.uri == entry.uri) null
                else runCatching { fileOfUri(location.uri)?.useLines { it.drop(line - 1).firstOrNull() } }.getOrNull()
            }
        }
        val byFile = locations.indices.groupBy { locations[it].uri }
            .toList()
            .sortedBy { (uri, _) -> if (uri == entry.uri) 0 else 1 }
        val rows = ArrayList<PickerSheet.Row>()
        for ((uri, indices) in byFile) {
            val name = fileOfUri(uri)?.name ?: uri.substringAfterLast('/')
            if (byFile.size > 1) rows += PickerSheet.Row.Header("$name · ${indices.size}")
            for (i in indices.sortedBy { locations[it].range.start }) {
                val location = locations[i]
                val line = location.range.start.line + 1
                val text = if (uri == entry.uri) editor.getLineText(line) else texts[i]
                rows += PickerSheet.Row.Item(
                    title = text?.trim()?.take(160).orEmpty().ifEmpty { name },
                    subtitle = if (byFile.size > 1) null else name,
                    trailing = line.toString(),
                    icon = R.drawable.ic_picker_link,
                    color = LINK_COLOR,
                    current = uri == entry.uri && location.range.start.line == cursorLine,
                    monospaceTitle = true,
                    data = location,
                )
            }
        }
        PickerSheet(activity).show(
            title = title,
            rows = rows,
            icon = R.drawable.ic_lsp_references,
            count = locations.size.toString(),
            searchable = locations.size > SEARCHABLE_FROM,
        ) { picked -> (picked.data as? LspLocation)?.let { navigate(entry, it) } }
    }

    // to [location]: in this document, or another one (opened by the navigator)
    private fun navigate(entry: Attached, location: LspLocation) {
        val (line, column) = location.range.start.toOneBased()
        if (location.uri == entry.uri) {
            entry.editor.gotoLine(line, column, flash = true)
            return
        }
        val file = fileOfUri(location.uri) ?: return nothingFound()
        navigator?.invoke(file, line, column)
    }

    private fun fileOfUri(uri: String): File? = Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let(::File)

    /** Renames what is at the cursor, everywhere the server says: asks for the new name first. */
    fun rename(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        val word = wordAtCursor(editor)
        if (word.isEmpty()) return nothingFound()
        val field = TextInputEditText(activity).apply {
            setText(word)
            setSelection(0, word.length)
            isSingleLine = true
        }
        val layout = TextInputLayout(activity).apply { addView(field) }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = LinearLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val position = cursorPosition(editor)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.lsp_rename_title, word))
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lsp_action_rename) { _, _ ->
                val name = field.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == word) return@setPositiveButton
                scope.launch {
                    val edit = ask(entry, "rename") { lsp.rename(position, name) }
                    if (edit == null || edit.isEmpty) return@launch nothingFound()
                    val files = applyWorkspaceEdit(edit)
                    Toast.makeText(activity, activity.getString(R.string.lsp_rename_done, files), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** What the server offers to do at the cursor (or for the selection): a list, a tap does it. */
    fun codeActions(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        val line = editor.getCursorLine()
        val position = cursorPosition(editor)
        val range = TextRange(position, position)
        // the diagnostics of the line: what quick fixes are for
        val diagnostics = entry.diagnostics.filter { it.range.start.line <= line - 1 && it.range.end.line >= line - 1 }
        scope.launch {
            val actions = ask(entry, "codeAction") { lsp.codeActions(range, diagnostics) } ?: return@launch
            if (actions.isEmpty()) {
                Toast.makeText(activity, R.string.lsp_code_actions_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showActions(entry, actions, diagnostics)
        }
    }

    // The kinds of code actions, as the sheet groups them: quick fixes first.
    private enum class ActionGroup(val prefix: String, val title: Int, val icon: Int, val color: Int) {
        QUICKFIX("quickfix", R.string.lsp_code_action_group_quickfix, R.drawable.ic_picker_lightbulb, 0xFFE5C07B.toInt()),
        REFACTOR("refactor", R.string.lsp_code_action_group_refactor, R.drawable.ic_picker_wrench, 0xFF61AFEF.toInt()),
        SOURCE("source", R.string.lsp_code_action_group_source, R.drawable.ic_picker_code, 0xFF98C379.toInt()),
        OTHER("", R.string.lsp_code_action_group_other, R.drawable.ic_lsp_code_action, 0xFFABB2BF.toInt());

        companion object {
            fun of(kind: String?): ActionGroup =
                entries.firstOrNull { it.prefix.isNotEmpty() && kind != null && (kind == it.prefix || kind.startsWith(it.prefix + ".")) } ?: OTHER
        }
    }

    // What a kind of code action does, in a word (refactor.extract: Extract); null if not known.
    private fun actionKindLabel(kind: String?): String? = when {
        kind == null -> null
        kind.startsWith("refactor.extract") -> activity.getString(R.string.lsp_code_action_kind_extract)
        kind.startsWith("refactor.inline") -> activity.getString(R.string.lsp_code_action_kind_inline)
        kind.startsWith("refactor.rewrite") -> activity.getString(R.string.lsp_code_action_kind_rewrite)
        kind.startsWith("source.organizeImports") -> activity.getString(R.string.lsp_code_action_kind_organize_imports)
        kind.startsWith("source.fixAll") -> activity.getString(R.string.lsp_code_action_kind_fix_all)
        else -> null
    }

    // The code actions (PickerSheet): grouped by kind, quick fixes first, the preferred one
    // of each group first and tagged; a quick fix says what it is for (the diagnostic of the
    // line, when there is one).
    private fun showActions(entry: Attached, actions: List<LspCodeAction>, diagnostics: List<LspDiagnostic>) {
        val grouped = actions.groupBy { ActionGroup.of(it.kind) }.toSortedMap(compareBy { it.ordinal })
        val problem = diagnostics.singleOrNull()?.message?.lineSequence()?.firstOrNull()
        val rows = ArrayList<PickerSheet.Row>()
        for ((group, members) in grouped) {
            if (grouped.size > 1) rows += PickerSheet.Row.Header(activity.getString(group.title))
            for (action in members.sortedByDescending { it.isPreferred }) {
                rows += PickerSheet.Row.Item(
                    title = action.title,
                    subtitle = actionKindLabel(action.kind) ?: problem?.takeIf { group == ActionGroup.QUICKFIX },
                    icon = group.icon,
                    color = group.color,
                    tag = if (action.isPreferred) activity.getString(R.string.lsp_code_action_preferred) else null,
                    data = action,
                )
            }
        }
        PickerSheet(activity).show(
            title = activity.getString(R.string.lsp_action_code_actions),
            rows = rows,
            icon = R.drawable.ic_lsp_code_action,
            searchable = actions.size > SEARCHABLE_FROM,
        ) { picked -> (picked.data as? LspCodeAction)?.let { action -> scope.launch { runAction(entry, action) } } }
    }

    private suspend fun runAction(entry: Attached, action: LspCodeAction) {
        action.edit?.let { applyWorkspaceEdit(it) }
        val command = action.command ?: return
        val client = entry.server?.client ?: return
        // its edits, if it makes any, come back as workspace/applyEdit (see the listener)
        ask(entry, "executeCommand") { client.executeCommand(command) }
    }

    /**
     * Formats the document as the server does, or only what is selected if [selection]
     * (and the server can), with the rules for its language (FormattingRules): generated
     * from the editor's indentation, with the user's over them.
     */
    fun format(editor: EditorView, selection: Boolean = false) {
        val (entry, lsp) = served(editor) ?: return
        val range = if (selection && entry.features?.rangeFormatting == true) {
            editor.getSelection()?.let {
                TextRange(TextPosition.fromOneBased(it.startLine, it.startColumn), TextPosition.fromOneBased(it.endLine, it.endColumn))
            }
        } else {
            null
        }
        val generated = FormattingRules.defaults(lsp.languageId, editor.getIndentUnit(), editor.getTabSize())
        scope.launch {
            val options = formattingOptions(entry, lsp, generated)
            val name = if (range == null) "formatting" else "rangeFormatting"
            val edits = ask(entry, name) { lsp.formatting(options, range) } ?: return@launch
            if (edits.isEmpty()) return@launch
            editing { editor.applyTextEdits(edits.map { editorRange(it.range) to it.newText }) }
        }
    }

    /**
     * What was just pasted, from [start] to [end] (offsets), formatted as the server does:
     * if the setting is on and the server formats ranges (rangeFormatting). Its own step
     * to undo, after the paste. The server has the pasted text by then (its change was
     * sent before, in order, see onTextChanged). Nothing is done if the text has changed
     * meanwhile (typed on, another paste), nor said if the server fails: the paste stands.
     */
    private fun formatPasted(entry: Attached, start: Int, end: Int) {
        if (!formatOnPaste) return
        val lsp = entry.lsp ?: return
        if (entry.features?.rangeFormatting != true) return
        val editor = entry.editor
        if (!editor.isEditable()) return
        val buffer = editor.getTextBuffer()
        val from = buffer.getPositionAt(start)
        val to = buffer.getPositionAt(end)
        val range = TextRange(
            TextPosition.fromOneBased(from.lineNumber, from.column),
            TextPosition.fromOneBased(to.lineNumber, to.column)
        )
        val version = editor.getTextVersion()
        val generated = FormattingRules.defaults(lsp.languageId, editor.getIndentUnit(), editor.getTabSize())
        scope.launch {
            val options = formattingOptions(entry, lsp, generated)
            val edits = ask(entry, "rangeFormatting", quiet = true) { lsp.formatting(options, range) } ?: return@launch
            if (edits.isEmpty()) return@launch
            // still the same document with the same server, and the text as it was pasted
            if (attached[editor] !== entry || entry.lsp !== lsp || editor.getTextVersion() != version) return@launch
            editing { editor.applyTextEdits(edits.map { editorRange(it.range) to it.newText }) }
        }
    }

    // The options of a format of the document of [entry]: the rules for its language
    // ([generated] from the editor's indentation, the user's over them), the project's
    // files (.editorconfig) over those; a server with a style file of its own (clangd's
    // .clang-format) reads that itself.
    private suspend fun formattingOptions(entry: Attached, lsp: LspDocument, generated: LspFormattingOptions): LspFormattingOptions {
        val rules = withContext(Dispatchers.IO) {
            runCatching { FormattingRules.rulesFor(context, lsp.languageId, generated) }.getOrElse { e ->
                LspLog.w("formatting rules of ${lsp.languageId}", e)
                generated
            }
        }
        val style = projectStyleOf(entry, lsp.languageId)
        val options = style?.applyTo(rules) ?: rules
        style?.describe()?.let { LspLog.i("formatting ${lsp.languageId} with $it") }
        return options
    }

    /** The outline of the document: its symbols, a tap goes there. */
    fun showOutline(editor: EditorView) {
        val (entry, lsp) = served(editor) ?: return
        scope.launch {
            val symbols = ask(entry, "documentSymbol") { lsp.documentSymbols() } ?: return@launch
            val flat = ArrayList<Pair<Int, LspSymbol>>()
            fun walk(list: List<LspSymbol>, depth: Int) {
                for (symbol in list.sortedBy { it.range.start }) {
                    flat += depth to symbol
                    walk(symbol.children, depth + 1)
                }
            }
            walk(symbols, 0)
            if (flat.isEmpty()) return@launch nothingFound()
            // the symbol the cursor is in: the innermost one whose range has its line
            val cursorLine = editor.getCursorLine() - 1
            val current = flat.lastOrNull { (_, symbol) -> cursorLine in symbol.range.start.line..symbol.range.end.line }?.second
            val rows = flat.map { (depth, symbol) ->
                val (badge, color) = symbolBadge(symbol.kind)
                PickerSheet.Row.Item(
                    title = symbol.name,
                    subtitle = symbol.detail?.takeIf { it.isNotBlank() }?.lineSequence()?.firstOrNull(),
                    trailing = (symbol.selectionRange.start.line + 1).toString(),
                    badge = badge,
                    color = color,
                    depth = depth,
                    current = symbol === current,
                    monospaceTitle = true,
                    data = symbol,
                )
            }
            PickerSheet(activity).show(
                title = activity.getString(R.string.lsp_action_outline),
                rows = rows,
                icon = R.drawable.ic_lsp_outline,
                searchable = flat.size > SEARCHABLE_FROM,
            ) { picked ->
                val symbol = picked.data as? LspSymbol ?: return@show
                val (line, column) = symbol.selectionRange.start.toOneBased()
                editor.gotoLine(line, column, flash = true)
            }
        }
    }

    // The badge of a kind of symbol in the outline: a letter and a color, those of the
    // completion list for the same kinds.
    private fun symbolBadge(kind: SymbolKindInfo?): Pair<String, Int> = when (kind) {
        SymbolKindInfo.CLASS -> "C" to 0xFFE8A04C.toInt()
        SymbolKindInfo.STRUCT -> "S" to 0xFFE8A04C.toInt()
        SymbolKindInfo.INTERFACE -> "I" to 0xFFE8A04C.toInt()
        SymbolKindInfo.ENUM -> "E" to 0xFFD7BA7D.toInt()
        SymbolKindInfo.ENUM_MEMBER -> "e" to 0xFFD7BA7D.toInt()
        SymbolKindInfo.METHOD -> "m" to 0xFFB180F0.toInt()
        SymbolKindInfo.FUNCTION -> "ƒ" to 0xFFB180F0.toInt()
        SymbolKindInfo.CONSTRUCTOR -> "c" to 0xFFB180F0.toInt()
        SymbolKindInfo.OPERATOR -> "±" to 0xFFB180F0.toInt()
        SymbolKindInfo.FIELD, SymbolKindInfo.PROPERTY -> "p" to 0xFF4EC9B0.toInt()
        SymbolKindInfo.VARIABLE -> "v" to 0xFF5AA9F0.toInt()
        SymbolKindInfo.CONSTANT -> "K" to 0xFF98C379.toInt()
        SymbolKindInfo.STRING, SymbolKindInfo.NUMBER, SymbolKindInfo.BOOLEAN, SymbolKindInfo.NULL -> "#" to 0xFF98C379.toInt()
        SymbolKindInfo.ARRAY, SymbolKindInfo.OBJECT, SymbolKindInfo.KEY -> "{}" to 0xFF56B6C2.toInt()
        SymbolKindInfo.NAMESPACE, SymbolKindInfo.PACKAGE, SymbolKindInfo.MODULE -> "N" to 0xFF8FA6C0.toInt()
        SymbolKindInfo.FILE -> "F" to 0xFF8FA6C0.toInt()
        SymbolKindInfo.TYPE_PARAMETER -> "T" to 0xFFE8A04C.toInt()
        SymbolKindInfo.EVENT -> "!" to 0xFFE06C9F.toInt()
        null -> "·" to 0xFF9E9E9E.toInt()
    }

    /**
     * Makes [edit]: in the editors of the documents that are open, as one step to undo each;
     * in the files of the others, written at once. Returns how many documents changed.
     */
    private suspend fun applyWorkspaceEdit(edit: LspWorkspaceEdit): Int {
        var changed = 0
        for ((uri, edits) in edit.changes) {
            if (edits.isEmpty()) continue
            val open = attached.values.firstOrNull { it.uri == uri }
            if (open != null) {
                if (editing { open.editor.applyTextEdits(edits.map { editorRange(it.range) to it.newText }) }) changed++
                continue
            }
            val file = fileOfUri(uri) ?: continue
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    file.writeText(applyToText(file.readText(), edits))
                    true
                }.getOrElse { e ->
                    LspLog.w("cannot apply the edits to $file", e)
                    false
                }
            }
            if (written) changed++
        }
        return changed
    }

    // [edits] (not overlapping) made to [text], the last first so that the others stay where they are
    private fun applyToText(text: String, edits: List<x.github.module.lsp.TextEdit>): String {
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }
        fun offset(position: TextPosition): Int {
            val start = lineStarts.getOrElse(position.line) { text.length }
            return (start + position.character).coerceIn(0, text.length)
        }
        val out = StringBuilder(text)
        for (edit in edits.sortedByDescending { offset(it.range.start) }) {
            out.replace(offset(edit.range.start), offset(edit.range.end), edit.newText)
        }
        return out.toString()
    }

    // the word around the cursor (what a rename is for)
    private fun wordAtCursor(editor: EditorView): String {
        val text = editor.getLineText(editor.getCursorLine())
        val at = (editor.getCursorColumn() - 1).coerceIn(0, text.length)
        var start = at
        var end = at
        while (start > 0 && CompletionTyping.isWordChar(text[start - 1])) start--
        while (end < text.length && CompletionTyping.isWordChar(text[end])) end++
        return text.substring(start, end)
    }

    // The colors of a hover: of the editor's highlight theme for code (light or dark, as
    // the app is now), the theme's accent; worked out once per theme.
    private var palette: Pair<String, HoverSpans.Palette>? = null

    private suspend fun palette(): HoverSpans.Palette {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val name = AppSettings.resolveHighlightTheme(activity, night)
        palette?.let { (cached, colors) -> if (cached == name) return colors }
        val theme = withContext(Dispatchers.IO) { runCatching { HighlightThemes.get(activity, name) }.getOrNull() }
        fun color(capture: String, fallback: Long) = theme?.styleFor(capture, null)?.foreground ?: fallback.toInt()
        val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFFE6E6E6.toInt())
        val colors = HoverSpans.Palette(
            keyword = color("keyword", 0xFFC678DD),
            string = color("string", 0xFF98C379),
            comment = color("comment", 0xFF7F848E),
            number = color("number", 0xFFD19A66),
            type = color("type", 0xFFE5C07B),
            function = color("function", 0xFF61AFEF),
            accent = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt()),
            codeBackground = (onSurface and 0x00FFFFFF) or 0x14000000,
            dim = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF8B949E.toInt()),
        )
        palette = name to colors
        return colors
    }

    // #endregion

    private fun lineBeforeCursor(editor: EditorView): String {
        val text = editor.getLineText(editor.getCursorLine())
        return text.substring(0, (editor.getCursorColumn() - 1).coerceIn(0, text.length))
    }

    private fun currentPrefix(editor: EditorView): String = CompletionTyping.wordBefore(lineBeforeCursor(editor))

    // a letter (or two) and a color for each kind of completion
    private fun badgeOf(kind: CompletionKind?): Pair<String, Int> = when (kind) {
        CompletionKind.METHOD, CompletionKind.FUNCTION, CompletionKind.CONSTRUCTOR -> "ƒ" to 0xFFB180F0.toInt()
        CompletionKind.VARIABLE -> "v" to 0xFF5AA9F0.toInt()
        CompletionKind.FIELD, CompletionKind.PROPERTY -> "p" to 0xFF4EC9B0.toInt()
        CompletionKind.CLASS, CompletionKind.STRUCT, CompletionKind.INTERFACE -> "C" to 0xFFE8A04C.toInt()
        CompletionKind.ENUM, CompletionKind.ENUM_MEMBER -> "E" to 0xFFD7BA7D.toInt()
        CompletionKind.KEYWORD -> "k" to 0xFFE06C9F.toInt()
        CompletionKind.MODULE, CompletionKind.REFERENCE -> "m" to 0xFF8FA6C0.toInt()
        CompletionKind.FOLDER -> "d" to 0xFFE5C07B.toInt()
        CompletionKind.FILE -> "f" to 0xFF8FA6C0.toInt()
        CompletionKind.CONSTANT, CompletionKind.VALUE, CompletionKind.UNIT -> "#" to 0xFF98C379.toInt()
        CompletionKind.SNIPPET -> "s" to 0xFF56B6C2.toInt()
        CompletionKind.TYPE_PARAMETER -> "T" to 0xFFE8A04C.toInt()
        CompletionKind.OPERATOR -> "±" to 0xFF8FA6C0.toInt()
        else -> "·" to 0xFF9E9E9E.toInt()
    }

    // #endregion

    private companion object {
        // a word typed: the server is asked after this pause (each key a request would flood it)
        const val TYPING_PAUSE_MILLIS = 120L
        const val RESOLVE_TIMEOUT_MILLIS = 600L
        const val MAX_SHOWN = 80
        // a sheet of more rows than this gets a search field
        const val SEARCHABLE_FROM = 12
        const val LINK_COLOR = 0xFF61AFEF.toInt()
        // the snippets that match, at most
        const val MAX_SNIPPETS = 12
        // a server that failed to start is not tried again before this
        const val RETRY_AFTER_MILLIS = 30_000L
        // what a server may take to answer initialize, unless servers.json says (a Node.js
        // server on a phone loads for a while the first time)
        const val INITIALIZE_TIMEOUT_MILLIS = 60_000L
        const val REQUEST_TERMUX = 0x15F
        // the words: indexed again this long after the last edit; at most this much of a
        // text (around the cursor); the words this near the cursor count for more
        const val INDEX_AFTER_EDIT_MILLIS = 1_500L
        const val MAX_INDEXED_CHARS = 2_000_000
        const val NEAR_WINDOW = 4_000
        const val WORD_COLOR = 0xFF8FA6C0.toInt()
    }
}
