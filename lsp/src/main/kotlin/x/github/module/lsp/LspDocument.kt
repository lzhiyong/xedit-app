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

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier

/**
 * A document open on the server (see LspClient.open): tells it of the changes,
 * saves and closing of the document, and asks it about places in it.
 *
 * Each change raises [version] by one, as the protocol wants. The changes go the
 * way the server asked for (ServerFeatures.textSync): as they are, or as the whole
 * text, which is then only read when it is needed. Thread-safe: the changes of a
 * document go out one after the other, in the order they are made.
 */
class LspDocument internal constructor(
    private val client: LspClient,
    val uri: String,
    val languageId: String
) {

    /** The version of the text the server has: 1 when opened, one more with each change. */
    @Volatile
    var version: Int = 1
        private set

    @Volatile
    var isOpen: Boolean = true
        private set

    /** The problems the server found in this document, as it published them last. */
    val diagnostics: List<LspDiagnostic>
        get() = client.diagnostics.value[uri].orEmpty()

    internal fun openParams(text: String) =
        DidOpenTextDocumentParams(TextDocumentItem(uri, languageId, version, text))

    /**
     * The document changed by [changes], in their order (each range in the text as
     * it is after the changes before it). [fullText] is the whole text after them,
     * read only if the server wants the whole text (or a change has no range).
     * Nothing if the server wants to hear of no changes.
     */
    @Synchronized
    fun change(changes: List<TextChange>, fullText: () -> String) {
        checkOpen()
        if (changes.isEmpty()) return
        val sync = client.features?.textSync ?: TextSyncKind.NONE
        if (sync == TextSyncKind.NONE) return

        val events = if (sync == TextSyncKind.FULL || changes.any { it.range == null }) {
            listOf(TextDocumentContentChangeEvent().apply { text = fullText() })
        } else {
            changes.map { change ->
                TextDocumentContentChangeEvent().apply {
                    range = LspConverters.toLsp(checkNotNull(change.range))
                    text = change.text
                }
            }
        }
        version += 1
        client.requireServer().textDocumentService.didChange(
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, version), events)
        )
    }

    /** One change, see the other [change]. */
    fun change(change: TextChange, fullText: () -> String) = change(listOf(change), fullText)

    /** The document was saved, with [text] if the server wants it (ServerFeatures.saveIncludesText). */
    @Synchronized
    fun save(text: () -> String = { "" }) {
        checkOpen()
        val params = DidSaveTextDocumentParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            if (client.features?.saveIncludesText == true) this.text = text()
        }
        client.requireServer().textDocumentService.didSave(params)
    }

    /** The document was closed: the server forgets it, and its problems are forgotten. */
    @Synchronized
    fun close() {
        if (!isOpen) return
        isOpen = false
        try {
            if (client.features?.openClose != false) {
                client.requireServer().textDocumentService.didClose(
                    DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
                )
            }
        } catch (e: IllegalStateException) {
            // the client stopped meanwhile: nothing to tell
        }
        client.forget(this)
    }

    // the client stopped: nothing is sent any more
    internal fun markClosed() {
        isOpen = false
    }

    /**
     * The completions at [position]. [trigger] is what was just typed, if completion
     * was asked for because of it (`.`, `->`, ...): the server is told so if it is
     * one of its trigger characters.
     */
    suspend fun completion(position: TextPosition, trigger: String? = null): CompletionResult {
        checkOpen()
        return client.completion(this, position, trigger)
    }

    /** What the server says about the code at [position]; null for nothing. */
    suspend fun hover(position: TextPosition): HoverInfo? {
        checkOpen()
        return client.hover(this, position)
    }

    /** Where what is at [position] is defined (declared, for some servers). */
    suspend fun definition(position: TextPosition): List<LspLocation> {
        checkOpen()
        return client.definition(this, position)
    }

    /** Where what is at [position] is used, its own place too if [includeDeclaration]. */
    suspend fun references(position: TextPosition, includeDeclaration: Boolean = true): List<LspLocation> {
        checkOpen()
        return client.references(this, position, includeDeclaration)
    }

    /** The signatures of the call [position] is in (after its `(`), or null. */
    suspend fun signatureHelp(position: TextPosition): SignatureHelpInfo? {
        checkOpen()
        return client.signatureHelp(this, position)
    }

    /** The edits that format the document, or [range] of it, as [options] say. */
    suspend fun formatting(options: LspFormattingOptions, range: TextRange? = null): List<TextEdit> {
        checkOpen()
        return client.formatting(this, range, options)
    }

    /** The edits that format the document, or [range] of it: only the size of a tab and spaces or not. */
    suspend fun formatting(tabSize: Int, insertSpaces: Boolean, range: TextRange? = null): List<TextEdit> =
        formatting(LspFormattingOptions(tabSize, insertSpaces), range)

    /** The edits (in every document) that rename what is at [position] to [newName]. */
    suspend fun rename(position: TextPosition, newName: String): LspWorkspaceEdit? {
        checkOpen()
        return client.rename(this, position, newName)
    }

    /** What the server offers to do for [range], with the [diagnostics] there. */
    suspend fun codeActions(range: TextRange, diagnostics: List<LspDiagnostic>): List<LspCodeAction> {
        checkOpen()
        return client.codeActions(this, range, diagnostics)
    }

    /** The symbols of the document: its outline. */
    suspend fun documentSymbols(): List<LspSymbol> {
        checkOpen()
        return client.documentSymbols(this)
    }

    private fun checkOpen() = check(isOpen) { "$uri is closed" }

    override fun toString() = "LspDocument($uri, $languageId, v$version)"
}
