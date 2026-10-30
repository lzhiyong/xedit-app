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

package x.github.module.lsp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionItemCapabilities
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemKindCapabilities
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.HoverCapabilities
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PositionEncodingKind
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer

/**
 * A client of one language server, over [connection].
 *
 * ```
 * val client = LspClient(SocketConnection("127.0.0.1", 5007), LspClientConfig(rootUri = "file:///sdcard/project"))
 * client.start()                                   // initialize, initialized
 * val document = client.open(uri, "c", text)        // didOpen
 * document.change(listOf(change)) { buffer.toString() }  // didChange
 * val completions = document.completion(position)   // textDocument/completion
 * client.diagnostics.collect { ... }                // textDocument/publishDiagnostics
 * client.shutdown()                                 // shutdown, exit
 * ```
 *
 * Requests are suspend functions, which time out after
 * [LspClientConfig.requestTimeoutMillis] (a request that is cancelled or times out
 * is cancelled on the server too). Notifications go out in the order they are
 * called in, before any request called after them: a completion asked for after a
 * change sees the text of that change. lsp4j reads the messages of the server on a
 * thread of its own, from which [listener] is called.
 */
class LspClient(
    private val connection: LanguageServerConnection,
    val config: LspClientConfig = LspClientConfig(),
    private val listener: LspClientListener = object : LspClientListener {}
) : AutoCloseable {

    /** Where the client is. */
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Running(val features: ServerFeatures) : State
        data object ShuttingDown : State
        data class Failed(val error: Throwable) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    /** What the server can do; null unless it is running. */
    val features: ServerFeatures?
        get() = (_state.value as? State.Running)?.features

    private val _diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())

    /** The problems of each document (by uri) the server found, as it publishes them. */
    val diagnostics: StateFlow<Map<String, List<LspDiagnostic>>> = _diagnostics.asStateFlow()

    private val threadCount = AtomicInteger()
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lsp-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var server: LanguageServer? = null

    @Volatile
    private var listening: Future<Void>? = null

    private val documents = ConcurrentHashMap<String, LspDocument>()

    // #region lifecycle

    /**
     * Connects, and introduces the client (`initialize`, then `initialized`).
     *
     * @return what the server can do
     * @throws LspException if it cannot connect, or the server does not answer
     */
    suspend fun start(): ServerFeatures {
        val current = _state.value
        check(current is State.Stopped || current is State.Failed) { "cannot start while $current" }
        setState(State.Starting)
        try {
            withContext(Dispatchers.IO) { connection.start() }
            val launcher = LSPLauncher.createClientLauncher(
                languageClient, connection.input, connection.output, executor, null
            )
            val remote = launcher.remoteProxy
            server = remote
            listening = launcher.startListening()

            val result = request("initialize", config.initializeTimeoutMillis) { remote.initialize(initializeParams()) }
            remote.initialized(InitializedParams())
            val features = ServerFeatures.from(result)
            setState(State.Running(features))
            return features
        } catch (e: CancellationException) {
            fail(e)
            throw e
        } catch (e: Exception) {
            fail(e)
            throw e as? LspException ?: LspException("cannot start the language server: ${e.message}", e)
        }
    }

    /**
     * Asks the server to stop (`shutdown`, then `exit`) and closes the connection.
     * Does nothing unless it is running.
     */
    suspend fun shutdown() {
        val remote = server
        if (_state.value !is State.Running || remote == null) return
        setState(State.ShuttingDown)
        try {
            request("shutdown") { remote.shutdown() }
            remote.exit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // it goes anyway
        } finally {
            release()
            setState(State.Stopped)
        }
    }

    /** Stops at once, without asking the server (which sees its input end). */
    override fun close() {
        try {
            server?.exit()
        } catch (e: Exception) {
            // gone already
        }
        release()
        if (_state.value !is State.Failed) setState(State.Stopped)
    }

    private fun release() {
        documents.values.forEach { it.markClosed() }
        documents.clear()
        server = null
        listening?.cancel(true)
        listening = null
        connection.close()
        executor.shutdownNow()
        _diagnostics.value = emptyMap()
    }

    private fun fail(error: Throwable) {
        release()
        setState(State.Failed(error))
    }

    private fun setState(state: State) {
        _state.value = state
        listener.onStateChanged(state)
    }

    // #endregion

    // #region documents

    /**
     * Opens [uri] (`file:///...`) with its [text] (`didOpen`): from now on the server
     * hears of its changes through the document returned, until it is closed.
     *
     * @param languageId the language as the protocol names it: `c`, `cpp`, `java`,
     *   `kotlin`, `python`, `rust`, `typescript`, ...
     */
    fun open(uri: String, languageId: String, text: String): LspDocument {
        val remote = requireServer()
        documents.remove(uri)?.close()
        val document = LspDocument(this, uri, languageId)
        documents[uri] = document
        if (features?.openClose != false) {
            remote.textDocumentService.didOpen(document.openParams(text))
        }
        return document
    }

    /** The open document of [uri]; null if it is not open. */
    fun document(uri: String): LspDocument? = documents[uri]

    internal fun forget(document: LspDocument) {
        documents.remove(document.uri, document)
        _diagnostics.update { it - document.uri }
    }

    internal fun requireServer(): LanguageServer =
        server.takeIf { _state.value is State.Running } ?: throw IllegalStateException("not running: ${_state.value}")

    // #endregion

    // #region requests

    internal suspend fun completion(document: LspDocument, position: TextPosition, trigger: String?): CompletionResult {
        val features = features ?: return CompletionResult.EMPTY
        if (!features.completion) return CompletionResult.EMPTY
        val remote = requireServer()
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(document.uri)
            this.position = LspConverters.toLsp(position)
            context = CompletionContext().apply {
                if (trigger != null && trigger in features.completionTriggers) {
                    triggerKind = CompletionTriggerKind.TriggerCharacter
                    triggerCharacter = trigger
                } else {
                    triggerKind = CompletionTriggerKind.Invoked
                }
            }
        }
        return LspConverters.fromLsp(request("completion") { remote.textDocumentService.completion(params) })
    }

    /**
     * [item] with what the server had left out of it (its documentation, the edits
     * that come with it, ...): `completionItem/resolve`, if the server does that;
     * else [item] as it is.
     */
    suspend fun resolve(item: LspCompletionItem): LspCompletionItem {
        val source = item.source ?: return item
        if (features?.completionResolve != true) return item
        val remote = requireServer()
        val resolved = request("resolve") { remote.textDocumentService.resolveCompletionItem(source) }
        return resolved?.let { LspConverters.fromLsp(it) } ?: item
    }

    internal suspend fun hover(document: LspDocument, position: TextPosition): HoverInfo? {
        if (features?.hover != true) return null
        val remote = requireServer()
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(document.uri)
            this.position = LspConverters.toLsp(position)
        }
        return LspConverters.fromLsp(request("hover") { remote.textDocumentService.hover(params) })
    }

    // #region more features

    internal suspend fun definition(document: LspDocument, position: TextPosition): List<LspLocation> {
        if (features?.definition != true) return emptyList()
        val remote = requireServer()
        val params = org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(document.uri), LspConverters.toLsp(position))
        return LspFeatureConverters.locations(request("definition") { remote.textDocumentService.definition(params) })
    }

    internal suspend fun references(document: LspDocument, position: TextPosition, includeDeclaration: Boolean): List<LspLocation> {
        if (features?.references != true) return emptyList()
        val remote = requireServer()
        val params = org.eclipse.lsp4j.ReferenceParams(
            TextDocumentIdentifier(document.uri), LspConverters.toLsp(position), org.eclipse.lsp4j.ReferenceContext(includeDeclaration)
        )
        return request("references") { remote.textDocumentService.references(params) }.orEmpty()
            .mapNotNull { LspFeatureConverters.location(it) }
    }

    internal suspend fun signatureHelp(document: LspDocument, position: TextPosition): SignatureHelpInfo? {
        if (features?.signatureHelp != true) return null
        val remote = requireServer()
        val params = org.eclipse.lsp4j.SignatureHelpParams(TextDocumentIdentifier(document.uri), LspConverters.toLsp(position))
        return LspFeatureConverters.signatureHelp(request("signatureHelp") { remote.textDocumentService.signatureHelp(params) })
    }

    internal suspend fun formatting(document: LspDocument, range: TextRange?, formatting: LspFormattingOptions): List<TextEdit> {
        val options = LspFeatureConverters.formattingOptions(formatting)
        val identifier = TextDocumentIdentifier(document.uri)
        val remote = requireServer()
        val edits = if (range == null) {
            if (features?.formatting != true) return emptyList()
            val params = org.eclipse.lsp4j.DocumentFormattingParams(identifier, options)
            request("formatting") { remote.textDocumentService.formatting(params) }
        } else {
            if (features?.rangeFormatting != true) return emptyList()
            val params = org.eclipse.lsp4j.DocumentRangeFormattingParams(identifier, options, LspConverters.toLsp(range))
            request("rangeFormatting") { remote.textDocumentService.rangeFormatting(params) }
        }
        return edits.orEmpty().map { LspConverters.fromLsp(it) }
    }

    internal suspend fun rename(document: LspDocument, position: TextPosition, newName: String): LspWorkspaceEdit? {
        if (features?.rename != true) return null
        val remote = requireServer()
        val params = org.eclipse.lsp4j.RenameParams(TextDocumentIdentifier(document.uri), LspConverters.toLsp(position), newName)
        return LspFeatureConverters.workspaceEdit(request("rename") { remote.textDocumentService.rename(params) })
    }

    internal suspend fun codeActions(document: LspDocument, range: TextRange, diagnostics: List<LspDiagnostic>): List<LspCodeAction> {
        if (features?.codeAction != true) return emptyList()
        val remote = requireServer()
        val context = org.eclipse.lsp4j.CodeActionContext(diagnostics.map { LspFeatureConverters.toLsp(it) })
        val params = org.eclipse.lsp4j.CodeActionParams(TextDocumentIdentifier(document.uri), LspConverters.toLsp(range), context)
        return LspFeatureConverters.codeActions(request("codeAction") { remote.textDocumentService.codeAction(params) })
    }

    internal suspend fun documentSymbols(document: LspDocument): List<LspSymbol> {
        if (features?.documentSymbol != true) return emptyList()
        val remote = requireServer()
        val params = org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        return LspFeatureConverters.symbols(request("documentSymbol") { remote.textDocumentService.documentSymbol(params) })
    }

    /** Runs [command] on the server (what a code action may come with). Returns what it answers. */
    suspend fun executeCommand(command: LspCommand): Any? {
        if (features?.executeCommand != true) return null
        val remote = requireServer()
        val params = org.eclipse.lsp4j.ExecuteCommandParams(command.command, command.arguments)
        return request("executeCommand") { remote.workspaceService.executeCommand(params) }
    }

    // #endregion

    // A request, with the timeout of the config; its errors as LspException. Cancelled
    // (also by the timeout), the future is cancelled, which cancels it on the server.
    private suspend fun <T> request(
        name: String,
        timeoutMillis: Long = config.requestTimeoutMillis,
        send: () -> CompletableFuture<T>,
    ): T {
        val future = send()
        return try {
            withTimeout(timeoutMillis) { future.await() }
        } catch (e: TimeoutCancellationException) {
            future.cancel(true)
            throw LspException("$name: no answer in $timeoutMillis ms", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseErrorException) {
            throw LspException("$name: ${e.responseError?.message ?: e.message}", e)
        } catch (e: Exception) {
            throw LspException("$name: ${e.message}", e)
        }
    }

    // #endregion

    // #region what the client says of itself

    private fun initializeParams() = InitializeParams().apply {
        processId = config.processId
        clientInfo = ClientInfo(config.clientName, config.clientVersion)
        // deprecated in favor of workspaceFolders, and still what many servers read
        rootUri = config.rootUri
        workspaceFolders = config.workspaceFolders.map { (uri, name) -> WorkspaceFolder(uri, name) }
        initializationOptions = config.initializationOptions
        capabilities = clientCapabilities()
    }

    // What this client can do. Nothing it does not handle: no dynamic registration,
    // no workspace/configuration, no progress, no edits of the workspace.
    private fun clientCapabilities() = ClientCapabilities().apply {
        textDocument = TextDocumentClientCapabilities().apply {
            synchronization = SynchronizationCapabilities().apply {
                willSave = false
                willSaveWaitUntil = false
                didSave = true
            }
            completion = CompletionCapabilities().apply {
                contextSupport = true
                completionItem = CompletionItemCapabilities().apply {
                    snippetSupport = config.snippetSupport
                    documentationFormat = listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT)
                    deprecatedSupport = true
                    insertReplaceSupport = true
                    resolveSupport = CompletionItemResolveSupportCapabilities().apply {
                        properties = listOf("documentation", "detail", "additionalTextEdits")
                    }
                }
                completionItemKind = CompletionItemKindCapabilities().apply {
                    valueSet = CompletionItemKind.values().toList()
                }
            }
            hover = HoverCapabilities().apply {
                contentFormat = listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT)
            }
            publishDiagnostics = PublishDiagnosticsCapabilities().apply {
                relatedInformation = false
                versionSupport = true
            }
            // (each made before its apply: in there, names are those of the receiver)
            val markup = listOf(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT)
            definition = org.eclipse.lsp4j.DefinitionCapabilities()
            references = org.eclipse.lsp4j.ReferencesCapabilities()
            signatureHelp = org.eclipse.lsp4j.SignatureHelpCapabilities().apply {
                signatureInformation = org.eclipse.lsp4j.SignatureInformationCapabilities(markup).apply {
                    parameterInformation = org.eclipse.lsp4j.ParameterInformationCapabilities(true)
                    activeParameterSupport = true
                }
            }
            formatting = org.eclipse.lsp4j.FormattingCapabilities()
            rangeFormatting = org.eclipse.lsp4j.RangeFormattingCapabilities()
            rename = org.eclipse.lsp4j.RenameCapabilities()
            val actionKinds = listOf(
                org.eclipse.lsp4j.CodeActionKind.QuickFix, org.eclipse.lsp4j.CodeActionKind.Refactor,
                org.eclipse.lsp4j.CodeActionKind.RefactorExtract, org.eclipse.lsp4j.CodeActionKind.RefactorInline,
                org.eclipse.lsp4j.CodeActionKind.RefactorRewrite, org.eclipse.lsp4j.CodeActionKind.Source,
                org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports,
            )
            codeAction = org.eclipse.lsp4j.CodeActionCapabilities().apply {
                codeActionLiteralSupport = org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities(
                    org.eclipse.lsp4j.CodeActionKindCapabilities(actionKinds)
                )
                isPreferredSupport = true
                disabledSupport = true
            }
            documentSymbol = org.eclipse.lsp4j.DocumentSymbolCapabilities().apply {
                hierarchicalDocumentSymbolSupport = true
            }
        }
        workspace = WorkspaceClientCapabilities().apply {
            workspaceFolders = true
            configuration = false
            // a code action's command may ask the client to make its edits
            applyEdit = true
            workspaceEdit = org.eclipse.lsp4j.WorkspaceEditCapabilities().apply { documentChanges = true }
            executeCommand = org.eclipse.lsp4j.ExecuteCommandCapabilities()
        }
        window = WindowClientCapabilities().apply {
            workDoneProgress = false
        }
        // positions in UTF-16 code units: the chars of a Kotlin String
        general = GeneralClientCapabilities().apply {
            positionEncodings = listOf(PositionEncodingKind.UTF16)
        }
    }

    // What the server sends by itself.
    private val languageClient = object : LanguageClient {

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            val uri = diagnostics.uri ?: return
            val converted = diagnostics.diagnostics.orEmpty().map { LspConverters.fromLsp(it) }
            _diagnostics.update { if (converted.isEmpty()) it - uri else it + (uri to converted) }
            listener.onDiagnostics(uri, converted, diagnostics.version)
        }

        override fun logMessage(message: MessageParams) {
            listener.onLogMessage(LspConverters.fromLsp(message.type), message.message ?: "")
        }

        override fun showMessage(messageParams: MessageParams) {
            listener.onShowMessage(LspConverters.fromLsp(messageParams.type), messageParams.message ?: "")
        }

        // a question for the user: shown, no answer chosen
        override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
            listener.onShowMessage(LspConverters.fromLsp(requestParams.type), requestParams.message ?: "")
            return CompletableFuture.completedFuture(null)
        }

        override fun telemetryEvent(`object`: Any?) = Unit

        // not asked for (see clientCapabilities), answered all the same
        override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        // what a code action's command asks for: the listener makes the edits
        override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
            val edit = LspFeatureConverters.workspaceEdit(params.edit)
                ?: return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(false))
            return listener.applyEdit(edit).thenApply { applied -> ApplyWorkspaceEditResponse(applied) }
        }
    }

    // #endregion
}
