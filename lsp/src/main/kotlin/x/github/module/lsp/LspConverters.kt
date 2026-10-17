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

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

import org.eclipse.lsp4j.DiagnosticSeverity as Lsp4jSeverity
import org.eclipse.lsp4j.TextEdit as Lsp4jTextEdit

/**
 * Between the types of lsp4j and those of this module: the editor never sees an
 * `Either`, or the many fields of the protocol that may be missing.
 */
internal object LspConverters {

    // #region positions

    fun toLsp(position: TextPosition) = Position(position.line, position.character)

    fun fromLsp(position: Position) =
        TextPosition(position.line.coerceAtLeast(0), position.character.coerceAtLeast(0))

    fun toLsp(range: TextRange) = Range(toLsp(range.start), toLsp(range.end))

    /** A range of the server; one that ends before it starts is taken as empty at its start. */
    fun fromLsp(range: Range): TextRange {
        val start = fromLsp(range.start)
        val end = fromLsp(range.end)
        return TextRange(start, if (end < start) start else end)
    }

    fun fromLsp(edit: Lsp4jTextEdit) = TextEdit(fromLsp(edit.range), edit.newText ?: "")

    // #endregion

    // #region diagnostics

    fun fromLsp(diagnostic: Diagnostic) = LspDiagnostic(
        range = fromLsp(diagnostic.range),
        severity = when (diagnostic.severity) {
            Lsp4jSeverity.Warning -> DiagnosticSeverity.WARNING
            Lsp4jSeverity.Information -> DiagnosticSeverity.INFORMATION
            Lsp4jSeverity.Hint -> DiagnosticSeverity.HINT
            // the protocol leaves a missing severity to the client: an error
            else -> DiagnosticSeverity.ERROR
        },
        // plain text, or (LSP 3.18, lsp4j 1.0) markup: its text
        message = diagnostic.message?.let { message -> if (message.isLeft) message.left else message.right?.value } ?: "",
        source = diagnostic.source,
        code = diagnostic.code?.let { code -> if (code.isLeft) code.left else code.right?.toString() },
        tags = diagnostic.tags.orEmpty().mapNotNull { tag ->
            when (tag) {
                DiagnosticTag.Unnecessary -> DiagnosticTagKind.UNNECESSARY
                DiagnosticTag.Deprecated -> DiagnosticTagKind.DEPRECATED
                else -> null
            }
        }.toSet()
    )

    // #endregion

    // #region completion

    fun fromLsp(result: Either<MutableList<CompletionItem>, CompletionList>?): CompletionResult = when {
        result == null -> CompletionResult.EMPTY
        result.isLeft -> CompletionResult(result.left.orEmpty().map { fromLsp(it) }, isIncomplete = false)
        else -> {
            val list = result.right
            CompletionResult(list?.items.orEmpty().map { fromLsp(it) }, isIncomplete = list?.isIncomplete == true)
        }
    }

    fun fromLsp(item: CompletionItem): LspCompletionItem {
        val label = item.label ?: ""
        val documentation = item.documentation?.let { documentation ->
            if (documentation.isLeft) {
                documentation.left to false
            } else {
                documentation.right?.let { it.value to (it.kind == MarkupKind.MARKDOWN) }
            }
        }
        // the edit: a plain one, or one that may insert or replace (the insert range,
        // which keeps the text after the cursor)
        val edit = item.textEdit?.let { textEdit ->
            if (textEdit.isLeft) {
                textEdit.left?.let { fromLsp(it) }
            } else {
                textEdit.right?.let { TextEdit(fromLsp(it.insert), it.newText ?: "") }
            }
        }
        @Suppress("DEPRECATION")
        val deprecated = item.deprecated == true || item.tags.orEmpty().contains(CompletionItemTag.Deprecated)
        return LspCompletionItem(
            label = label,
            kind = item.kind?.let { CompletionKind.of(it.value) },
            detail = item.detail,
            labelDetail = item.labelDetails?.let { details ->
                listOfNotNull(details.detail, details.description).joinToString(" ").ifEmpty { null }
            },
            documentation = documentation?.first,
            isMarkdown = documentation?.second == true,
            insertText = edit?.newText ?: item.insertText ?: label,
            isSnippet = item.insertTextFormat == InsertTextFormat.Snippet,
            edit = edit,
            additionalEdits = item.additionalTextEdits.orEmpty().map { fromLsp(it) },
            sortText = item.sortText ?: label,
            filterText = item.filterText ?: label,
            deprecated = deprecated,
            commitCharacters = item.commitCharacters.orEmpty(),
            source = item
        )
    }

    // #endregion

    /** The text of a hover: Markdown if any part of it is (code blocks for marked strings). */
    fun fromLsp(hover: Hover?): HoverInfo? {
        val contents = hover?.contents ?: return null
        val range = hover.range?.let { fromLsp(it) }
        if (contents.isRight) {
            val markup: MarkupContent = contents.right ?: return null
            val text = markup.value ?: ""
            return if (text.isBlank()) null else HoverInfo(text, markup.kind == MarkupKind.MARKDOWN, range)
        }
        val parts = contents.left.orEmpty().mapNotNull { part ->
            when {
                part == null -> null
                part.isLeft -> part.left
                else -> part.right?.let { marked -> "```${marked.language ?: ""}\n${marked.value ?: ""}\n```" }
            }
        }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val markdown = contents.left.orEmpty().any { it != null && it.isRight }
        return HoverInfo(parts.joinToString("\n\n"), markdown, range)
    }

    fun fromLsp(type: MessageType?): MessageKind = when (type) {
        MessageType.Error -> MessageKind.ERROR
        MessageType.Warning -> MessageKind.WARNING
        MessageType.Info -> MessageKind.INFO
        else -> MessageKind.LOG
    }
}
