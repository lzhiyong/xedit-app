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

import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.TextDocumentSyncKind

/**
 * How LspClient introduces itself to a server.
 *
 * @property rootUri the root of the project (`file:///...`); servers like clangd
 *   look for their configuration (compile_commands.json, ...) from there
 * @property workspaceFolders the folders of the project, as (uri, name); by default
 *   the root
 * @property initializationOptions what the server takes at the start, as a JSON
 *   value (Map, List, String, ...), see its documentation
 * @property snippetSupport whether completions may be snippets (`${1:arg}`), see
 *   SnippetParser
 * @property requestTimeoutMillis how long a request waits for its answer
 * @property initializeTimeoutMillis how long `initialize` waits for its answer: longer, a
 *   server may take long to start (a Node.js one on a phone, pyright, loads for a while)
 * @property processId the id of the process of the client: a server ends itself
 *   when that process is gone
 */
data class LspClientConfig(
    val rootUri: String? = null,
    val workspaceFolders: List<Pair<String, String>> =
        rootUri?.let { listOf(it to it.trimEnd('/').substringAfterLast('/')) }.orEmpty(),
    val clientName: String = "xedit",
    val clientVersion: String? = null,
    val initializationOptions: Any? = null,
    val snippetSupport: Boolean = true,
    val requestTimeoutMillis: Long = 10_000,
    val processId: Int? = null,
    val initializeTimeoutMillis: Long = 60_000,
)

/** What LspClient hears from the server, besides the answers to its requests. Any thread. */
interface LspClientListener {

    /** The problems of [uri] now (empty: none any more), found in its [version] if the server says. */
    fun onDiagnostics(uri: String, diagnostics: List<LspDiagnostic>, version: Int?) {}

    /** For the log of the server. */
    fun onLogMessage(kind: MessageKind, message: String) {}

    /** For the user: the server wants it shown. */
    fun onShowMessage(kind: MessageKind, message: String) {}

    fun onStateChanged(state: LspClient.State) {}

    /**
     * The server wants [edit] made (workspace/applyEdit: what a code action's command
     * does). Complete the future with whether it was. Called on a thread of the client.
     */
    fun applyEdit(edit: LspWorkspaceEdit): java.util.concurrent.CompletableFuture<Boolean> =
        java.util.concurrent.CompletableFuture.completedFuture(false)
}

/**
 * What the server can do, from its answer to `initialize`.
 *
 * @property completionTriggers the characters after which completion is asked for
 *   by itself (`.`, `->`, ...: each a string)
 * @property completionResolve whether a completion item has more to it (its
 *   documentation, say) when asked, see LspClient.resolve
 * @property saveIncludesText whether `didSave` carries the text
 */
data class ServerFeatures(
    val name: String?,
    val version: String?,
    val textSync: TextSyncKind,
    val openClose: Boolean,
    val saveIncludesText: Boolean,
    val completion: Boolean,
    val completionTriggers: Set<String>,
    val completionResolve: Boolean,
    val hover: Boolean,
    val definition: Boolean = false,
    val references: Boolean = false,
    val signatureHelp: Boolean = false,
    /** What makes the server's signature help come: `(`, `,`. */
    val signatureTriggers: Set<String> = emptySet(),
    val formatting: Boolean = false,
    val rangeFormatting: Boolean = false,
    val rename: Boolean = false,
    val codeAction: Boolean = false,
    val documentSymbol: Boolean = false,
    val executeCommand: Boolean = false,
) {
    companion object {
        // a capability that is `true`, or options (which mean it is there)
        private fun isOn(provider: org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, *>?): Boolean =
            provider != null && ((provider.isLeft && provider.left == true) || (provider.isRight && provider.right != null))

        internal fun from(result: InitializeResult?): ServerFeatures {
            val capabilities = result?.capabilities
            val sync = capabilities?.textDocumentSync
            val options = sync?.takeIf { it.isRight }?.right
            val kind = if (sync?.isLeft == true) sync.left else options?.change
            val save = options?.save
            val completion: CompletionOptions? = capabilities?.completionProvider
            val hover = capabilities?.hoverProvider
            val signature = capabilities?.signatureHelpProvider
            return ServerFeatures(
                name = result?.serverInfo?.name,
                version = result?.serverInfo?.version,
                textSync = when (kind) {
                    TextDocumentSyncKind.Full -> TextSyncKind.FULL
                    TextDocumentSyncKind.Incremental -> TextSyncKind.INCREMENTAL
                    // said: none; not said: none too, as the protocol has it
                    else -> TextSyncKind.NONE
                },
                // with only a kind, the documents are opened and closed
                openClose = options?.openClose ?: (sync != null),
                saveIncludesText = save != null && save.isRight && save.right?.includeText == true,
                completion = completion != null,
                completionTriggers = completion?.triggerCharacters.orEmpty().toSet(),
                completionResolve = completion?.resolveProvider == true,
                hover = hover != null && ((hover.isLeft && hover.left == true) || (hover.isRight && hover.right != null)),
                definition = isOn(capabilities?.definitionProvider),
                references = isOn(capabilities?.referencesProvider),
                signatureHelp = signature != null,
                signatureTriggers = (signature?.triggerCharacters.orEmpty() + signature?.retriggerCharacters.orEmpty()).toSet(),
                formatting = isOn(capabilities?.documentFormattingProvider),
                rangeFormatting = isOn(capabilities?.documentRangeFormattingProvider),
                rename = isOn(capabilities?.renameProvider),
                codeAction = isOn(capabilities?.codeActionProvider),
                documentSymbol = isOn(capabilities?.documentSymbolProvider),
                executeCommand = capabilities?.executeCommandProvider != null,
            )
        }
    }
}
