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

import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

import org.eclipse.lsp4j.DiagnosticSeverity as Lsp4jSeverity

/**
 * A language server for the tests: says what it can do as it is told, records what
 * it hears, answers completion and hover with fixed items, and publishes an error
 * for each document opened (or changed, if that document holds "error").
 */
class FakeLanguageServer(
    private val syncKind: TextDocumentSyncKind = TextDocumentSyncKind.Incremental,
    // never answers initialize: for the timeout
    private val silent: Boolean = false
) : LanguageServer, LanguageClientAware {

    lateinit var client: LanguageClient

    val initializeParams = CopyOnWriteArrayList<InitializeParams>()
    val opened = CopyOnWriteArrayList<DidOpenTextDocumentParams>()
    val changed = CopyOnWriteArrayList<DidChangeTextDocumentParams>()
    val saved = CopyOnWriteArrayList<DidSaveTextDocumentParams>()
    val closed = CopyOnWriteArrayList<DidCloseTextDocumentParams>()
    val completions = CopyOnWriteArrayList<CompletionParams>()

    @Volatile var shutdownCalled = false
    @Volatile var exited = false

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    // #region lifecycle

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        initializeParams += params
        if (silent) return CompletableFuture()
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(syncKind)
            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", "->")
                resolveProvider = true
            }
            hoverProvider = Either.forLeft(true)
        }
        val result = InitializeResult().apply {
            this.capabilities = capabilities
            serverInfo = ServerInfo().apply {
                name = "fake"
                version = "1.0"
            }
        }
        return CompletableFuture.completedFuture(result)
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownCalled = true
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        exited = true
    }

    override fun getTextDocumentService(): TextDocumentService = documents

    override fun getWorkspaceService(): WorkspaceService = workspace

    // #endregion

    // the services of the server, objects of their own as lsp4j servers have them
    private val documents = object : TextDocumentService {

        // #region documents

        override fun didOpen(params: DidOpenTextDocumentParams) {
            opened += params
            client.logMessage(MessageParams(MessageType.Log, "opened ${params.textDocument.uri}"))
            publishError(params.textDocument.uri, params.textDocument.version)
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            changed += params
            // the whole text holds no "error" any more: no problems
            val text = params.contentChanges.joinToString("") { it.text }
            if (params.contentChanges.any { it.range == null } && !text.contains("error")) {
                client.publishDiagnostics(PublishDiagnosticsParams(params.textDocument.uri, emptyList(), params.textDocument.version))
            }
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            saved += params
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            closed += params
        }

        private fun publishError(uri: String, version: Int) {
            val diagnostic = Diagnostic().apply {
                range = Range(Position(0, 0), Position(0, 5))
                severity = Lsp4jSeverity.Error
                message = Either.forLeft("expected ';'")
                source = "fake"
            }
            client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(diagnostic), version))
        }

        // #endregion

        // #region requests

        override fun completion(
            position: CompletionParams
        ): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
            completions += position
            val items = listOf(
                CompletionItem("println").apply {
                    kind = CompletionItemKind.Function
                    sortText = "1"
                    data = "println-data"
                },
                CompletionItem("print").apply {
                    kind = CompletionItemKind.Function
                    sortText = "2"
                },
                CompletionItem("value").apply {
                    kind = CompletionItemKind.Variable
                    sortText = "3"
                }
            )
            return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, items)))
        }

        override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
            unresolved.documentation = Either.forRight(MarkupContent(MarkupKind.PLAINTEXT, "Prints a line."))
            return CompletableFuture.completedFuture(unresolved)
        }

        override fun hover(params: HoverParams): CompletableFuture<Hover> =
            CompletableFuture.completedFuture(Hover(MarkupContent(MarkupKind.MARKDOWN, "`int x`")))

        // #endregion
    }

    private val workspace = object : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
    }

    companion object {
        /**
         * [server] on in-memory pipes: the connection of the client to it. Closing the
         * connection ends the server's input, which stops it.
         */
        fun connect(server: FakeLanguageServer): LanguageServerConnection {
            val toServer = Pipe.open()
            val toClient = Pipe.open()
            val launcher = LSPLauncher.createServerLauncher(
                server,
                Channels.newInputStream(toServer.source()),
                Channels.newOutputStream(toClient.sink())
            )
            server.connect(launcher.remoteProxy)
            launcher.startListening()
            return StreamConnection(
                input = Channels.newInputStream(toClient.source()),
                output = Channels.newOutputStream(toServer.sink()),
                onClose = {
                    toServer.sink().close()
                    toClient.source().close()
                }
            )
        }
    }
}
