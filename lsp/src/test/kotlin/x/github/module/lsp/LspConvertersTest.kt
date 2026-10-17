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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.StringValue
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

import org.eclipse.lsp4j.DiagnosticSeverity as Lsp4jSeverity
import org.eclipse.lsp4j.TextEdit as Lsp4jTextEdit

class LspConvertersTest {

    private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = Range(Position(l1, c1), Position(l2, c2))

    @Test
    fun `a diagnostic with its severity, code and tags`() {
        val diagnostic = Diagnostic().apply {
            range = range(3, 4, 3, 9)
            severity = Lsp4jSeverity.Warning
            message = Either.forLeft("unused variable 'x'")
            source = "clang"
            code = Either.forLeft("-Wunused-variable")
            tags = listOf(DiagnosticTag.Unnecessary)
        }
        val converted = LspConverters.fromLsp(diagnostic)
        assertEquals(TextRange(TextPosition(3, 4), TextPosition(3, 9)), converted.range)
        assertEquals(DiagnosticSeverity.WARNING, converted.severity)
        assertEquals("unused variable 'x'", converted.message)
        assertEquals("clang", converted.source)
        assertEquals("-Wunused-variable", converted.code)
        assertEquals(setOf(DiagnosticTagKind.UNNECESSARY), converted.tags)
    }

    @Test
    fun `a diagnostic without severity is an error, a number code a string`() {
        val diagnostic = Diagnostic().apply {
            range = range(0, 0, 0, 1)
            message = Either.forLeft("expected ';'")
            code = Either.forRight(1002)
        }
        val converted = LspConverters.fromLsp(diagnostic)
        assertEquals(DiagnosticSeverity.ERROR, converted.severity)
        assertEquals("1002", converted.code)
    }

    @Test
    fun `a reversed range of the server is taken as empty`() {
        val converted = LspConverters.fromLsp(range(5, 3, 2, 0))
        assertEquals(TextRange.at(TextPosition(5, 3)), converted)
    }

    @Test
    fun `a completion item with an edit, a snippet and markdown documentation`() {
        val item = CompletionItem().apply {
            label = "printf"
            kind = CompletionItemKind.Function
            detail = "int printf(const char *, ...)"
            documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "Prints **formatted** output."))
            insertTextFormat = InsertTextFormat.Snippet
            textEdit = Either.forLeft(Lsp4jTextEdit(range(2, 4, 2, 7), "printf(\${1:fmt})"))
            sortText = "0001"
            filterText = "printf"
        }
        val converted = LspConverters.fromLsp(item)
        assertEquals("printf", converted.label)
        assertEquals(CompletionKind.FUNCTION, converted.kind)
        assertEquals("int printf(const char *, ...)", converted.detail)
        assertEquals("Prints **formatted** output.", converted.documentation)
        assertTrue(converted.isMarkdown)
        assertTrue(converted.isSnippet)
        assertEquals("printf(\${1:fmt})", converted.insertText)
        assertEquals(TextEdit(TextRange(TextPosition(2, 4), TextPosition(2, 7)), "printf(\${1:fmt})"), converted.edit)
        assertEquals("0001", converted.sortText)
    }

    @Test
    fun `a completion item with only a label, deprecated by its tag`() {
        val item = CompletionItem("size").apply {
            tags = listOf(CompletionItemTag.Deprecated)
        }
        val converted = LspConverters.fromLsp(item)
        assertEquals("size", converted.insertText)
        assertEquals("size", converted.sortText)
        assertEquals("size", converted.filterText)
        assertNull(converted.edit)
        assertFalse(converted.isSnippet)
        assertTrue(converted.deprecated)
    }

    @Test
    fun `an insert-or-replace edit inserts`() {
        val item = CompletionItem("value").apply {
            textEdit = Either.forRight(InsertReplaceEdit("value", range(1, 2, 1, 4), range(1, 2, 1, 9)))
        }
        val edit = LspConverters.fromLsp(item).edit
        assertEquals(TextRange(TextPosition(1, 2), TextPosition(1, 4)), edit?.range)
    }

    @Test
    fun `a completion list and a plain list of items`() {
        val list = CompletionList(true, listOf(CompletionItem("a"), CompletionItem("b")))
        val fromList = LspConverters.fromLsp(Either.forRight<MutableList<CompletionItem>, CompletionList>(list))
        assertTrue(fromList.isIncomplete)
        assertEquals(listOf("a", "b"), fromList.items.map { it.label })

        val plain = Either.forLeft<MutableList<CompletionItem>, CompletionList>(mutableListOf(CompletionItem("c")))
        val fromPlain = LspConverters.fromLsp(plain)
        assertFalse(fromPlain.isIncomplete)
        assertEquals(listOf("c"), fromPlain.items.map { it.label })

        assertEquals(CompletionResult.EMPTY, LspConverters.fromLsp(null as Either<MutableList<CompletionItem>, CompletionList>?))
    }

    @Test
    fun `hover as markup, and as marked strings`() {
        val markup = Hover(MarkupContent(MarkupKind.PLAINTEXT, "int x"))
        assertEquals(HoverInfo("int x", isMarkdown = false), LspConverters.fromLsp(markup))

        val marked = Hover(
            listOf(
                Either.forLeft<String, MarkedString>("A variable."),
                Either.forRight<String, MarkedString>(MarkedString("c", "int x;"))
            )
        )
        val converted = LspConverters.fromLsp(marked)
        assertEquals("A variable.\n\n```c\nint x;\n```", converted?.text)
        assertTrue(converted?.isMarkdown == true)

        assertNull(LspConverters.fromLsp(Hover(MarkupContent(MarkupKind.PLAINTEXT, "  "))))
        assertNull(LspConverters.fromLsp(null as Hover?))
    }

    @Test
    fun `message types`() {
        assertEquals(MessageKind.ERROR, LspConverters.fromLsp(MessageType.Error))
        assertEquals(MessageKind.WARNING, LspConverters.fromLsp(MessageType.Warning))
        assertEquals(MessageKind.INFO, LspConverters.fromLsp(MessageType.Info))
        assertEquals(MessageKind.LOG, LspConverters.fromLsp(MessageType.Log))
        assertEquals(MessageKind.LOG, LspConverters.fromLsp(null as MessageType?))
    }

    // lsp4j 1.0 (LSP 3.18): a diagnostic's message may be markup
    @Test
    fun diagnosticWithMarkupMessage() {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, 1))
            message = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "use `x`"))
        }
        assertEquals("use `x`", LspConverters.fromLsp(diagnostic).message)
    }

    // lsp4j 1.0 (LSP 3.18): a document's edits may be snippets, made as the text they expand to
    @Test
    fun workspaceEditWithSnippetEdit() {
        val snippet = SnippetTextEdit().apply {
            range = Range(Position(1, 2), Position(1, 5))
            this.snippet = StringValue().apply { value = "foo(\${1:arg})" }
        }
        val plain = Lsp4jTextEdit(Range(Position(0, 0), Position(0, 0)), "// x\n")
        val edit = WorkspaceEdit().apply {
            documentChanges = listOf(
                Either.forLeft(
                    TextDocumentEdit(
                        VersionedTextDocumentIdentifier("file:///a.c", 1),
                        listOf(Either.forLeft(plain), Either.forRight(snippet))
                    )
                )
            )
        }
        val edits = LspFeatureConverters.workspaceEdit(edit)!!.changes.getValue("file:///a.c")
        assertEquals(listOf("// x\n", "foo(arg)"), edits.map { it.newText })
        assertEquals(TextRange(TextPosition(1, 2), TextPosition(1, 5)), edits[1].range)
    }
}
