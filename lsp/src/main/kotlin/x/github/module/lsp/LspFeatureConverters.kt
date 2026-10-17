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

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

// Between lsp4j and the types of LspFeatureTypes.
internal object LspFeatureConverters {

    fun locations(result: Either<out List<Location>, out List<LocationLink>>?): List<LspLocation> = when {
        result == null -> emptyList()
        result.isLeft -> result.left.orEmpty().mapNotNull { location(it) }
        else -> result.right.orEmpty().mapNotNull { link ->
            val uri = link.targetUri ?: return@mapNotNull null
            val range = link.targetSelectionRange ?: link.targetRange ?: return@mapNotNull null
            LspLocation(uri, LspConverters.fromLsp(range))
        }
    }

    fun location(location: Location?): LspLocation? {
        val uri = location?.uri ?: return null
        val range = location.range ?: return null
        return LspLocation(uri, LspConverters.fromLsp(range))
    }

    private fun text(documentation: Either<String, MarkupContent>?): String? = when {
        documentation == null -> null
        documentation.isLeft -> documentation.left
        else -> documentation.right?.value
    }

    fun signatureHelp(help: SignatureHelp?): SignatureHelpInfo? {
        val signatures = help?.signatures.orEmpty().map { signature ->
            val label = signature.label ?: ""
            var searchFrom = 0
            val parameters = signature.parameters.orEmpty().map { parameter ->
                val name = parameter.label
                // a parameter is named by its text in the label, or by its offsets
                val (start, end) = when {
                    name == null -> -1 to -1
                    name.isRight -> (name.right.first ?: -1) to (name.right.second ?: -1)
                    else -> {
                        val at = label.indexOf(name.left ?: "", searchFrom)
                        if (at >= 0) {
                            searchFrom = at + name.left.length
                            at to at + name.left.length
                        } else {
                            -1 to -1
                        }
                    }
                }
                ParameterInfo(start, end, text(parameter.documentation))
            }
            SignatureInfo(label, text(signature.documentation), parameters, signature.activeParameter)
        }
        if (signatures.isEmpty()) return null
        return SignatureHelpInfo(signatures, help?.activeSignature ?: 0, help?.activeParameter ?: 0)
    }

    fun workspaceEdit(edit: WorkspaceEdit?): LspWorkspaceEdit? {
        if (edit == null) return null
        val changes = LinkedHashMap<String, MutableList<TextEdit>>()
        for ((uri, edits) in edit.changes.orEmpty()) {
            changes.getOrPut(uri) { ArrayList() } += edits.orEmpty().map { LspConverters.fromLsp(it) }
        }
        // documentChanges: the edits of documents (creating, renaming, deleting files is left out)
        for (change in edit.documentChanges.orEmpty()) {
            if (!change.isLeft) continue
            val documentEdit = change.left ?: continue
            val uri = documentEdit.textDocument?.uri ?: continue
            // a text edit, or (LSP 3.18, lsp4j 1.0) a snippet edit: its snippet as the text it expands to
            changes.getOrPut(uri) { ArrayList() } += documentEdit.edits.orEmpty().mapNotNull { either ->
                when {
                    either == null -> null
                    either.isLeft -> either.left?.let { LspConverters.fromLsp(it) }
                    else -> either.right?.let { snippet ->
                        val range = snippet.range ?: return@let null
                        TextEdit(LspConverters.fromLsp(range), SnippetParser.parse(snippet.snippet?.value ?: "").text)
                    }
                }
            }
        }
        return LspWorkspaceEdit(changes)
    }

    fun command(command: Command?): LspCommand? {
        val name = command?.command ?: return null
        return LspCommand(command.title ?: name, name, command.arguments.orEmpty())
    }

    fun codeActions(result: List<Either<Command, CodeAction>>?): List<LspCodeAction> = result.orEmpty().mapNotNull { either ->
        if (either.isLeft) {
            val command = command(either.left) ?: return@mapNotNull null
            LspCodeAction(command.title, null, null, command)
        } else {
            val action = either.right ?: return@mapNotNull null
            if (action.disabled != null) return@mapNotNull null
            LspCodeAction(
                title = action.title ?: return@mapNotNull null,
                kind = action.kind,
                edit = workspaceEdit(action.edit),
                command = command(action.command),
                isPreferred = action.isPreferred == true,
            )
        }
    }

    fun symbols(result: List<Either<SymbolInformation, DocumentSymbol>>?): List<LspSymbol> = result.orEmpty().mapNotNull { either ->
        if (either.isRight) symbol(either.right) else {
            val information = either.left ?: return@mapNotNull null
            val range = information.location?.range?.let { LspConverters.fromLsp(it) } ?: return@mapNotNull null
            LspSymbol(information.name ?: "", information.containerName, information.kind?.value?.let { SymbolKindInfo.of(it) }, range, range)
        }
    }

    private fun symbol(symbol: DocumentSymbol?): LspSymbol? {
        if (symbol == null) return null
        val range = symbol.range?.let { LspConverters.fromLsp(it) } ?: return null
        val selection = symbol.selectionRange?.let { LspConverters.fromLsp(it) } ?: range
        return LspSymbol(
            symbol.name ?: "", symbol.detail, symbol.kind?.value?.let { SymbolKindInfo.of(it) }, range, selection,
            symbol.children.orEmpty().mapNotNull { symbol(it) }
        )
    }

    // back to lsp4j, for the context of code actions
    fun toLsp(diagnostic: LspDiagnostic): Diagnostic {
        val severity = when (diagnostic.severity) {
            DiagnosticSeverity_.ERROR -> DiagnosticSeverity.Error
            DiagnosticSeverity_.WARNING -> DiagnosticSeverity.Warning
            DiagnosticSeverity_.INFORMATION -> DiagnosticSeverity.Information
            DiagnosticSeverity_.HINT -> DiagnosticSeverity.Hint
        }
        return Diagnostic(LspConverters.toLsp(diagnostic.range), diagnostic.message, severity, diagnostic.source, diagnostic.code)
    }
}

// the module's own severity, apart from lsp4j's of the same name
private typealias DiagnosticSeverity_ = x.github.module.lsp.DiagnosticSeverity
