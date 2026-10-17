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

import java.util.concurrent.CopyOnWriteArrayList

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.PositionEncodingKind
import org.eclipse.lsp4j.TextDocumentSyncKind

class LspClientTest {

    private val uri = "file:///project/main.c"

    private val clients = CopyOnWriteArrayList<LspClient>()

    // what the listener heard
    private val logs = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<LspClient.State>()

    private val listener = object : LspClientListener {
        override fun onLogMessage(kind: MessageKind, message: String) {
            logs += message
        }

        override fun onStateChanged(state: LspClient.State) {
            states += state
        }
    }

    @AfterTest
    fun closeClients() {
        clients.forEach { it.close() }
    }

    private fun client(server: FakeLanguageServer, timeoutMillis: Long = 5_000) =
        LspClient(
            FakeLanguageServer.connect(server),
            // initialize has a timeout of its own (longer by default): the same here, so that
            // a server that does not answer fails the start as soon as a request would
            LspClientConfig(rootUri = "file:///project", requestTimeoutMillis = timeoutMillis, initializeTimeoutMillis = timeoutMillis),
            listener
        ).also { clients += it }

    // waits until [condition] holds: what the server hears, it hears on a thread of its own
    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun `start introduces the client and learns what the server can do`() = test {
        val server = FakeLanguageServer()
        val client = client(server)

        val features = client.start()

        assertEquals("fake", features.name)
        assertEquals(TextSyncKind.INCREMENTAL, features.textSync)
        assertEquals(setOf(".", "->"), features.completionTriggers)
        assertTrue(features.completionResolve)
        assertTrue(features.hover)
        assertIs<LspClient.State.Running>(client.state.value)

        val params = server.initializeParams.single()
        assertEquals("file:///project", params.workspaceFolders.single().uri)
        assertEquals("project", params.workspaceFolders.single().name)
        assertEquals("xedit", params.clientInfo.name)
        assertEquals(listOf(PositionEncodingKind.UTF16), params.capabilities.general.positionEncodings)
        assertEquals(true, params.capabilities.textDocument.completion.completionItem.snippetSupport)
        assertEquals(listOf(LspClient.State.Starting::class, LspClient.State.Running::class), states.map { it::class })
    }

    @Test
    fun `open, change incrementally, save and close`() = test {
        val server = FakeLanguageServer(TextDocumentSyncKind.Incremental)
        val client = client(server)
        client.start()

        val document = client.open(uri, "c", "int x = 1")
        eventually { server.opened.isNotEmpty() }
        val opened = server.opened.single().textDocument
        assertEquals(uri, opened.uri)
        assertEquals("c", opened.languageId)
        assertEquals(1, opened.version)
        assertEquals("int x = 1", opened.text)

        // "1" → "42"; the whole text is not needed
        val range = TextRange(TextPosition(0, 8), TextPosition(0, 9))
        document.change(TextChange(range, "42")) { error("the whole text was not asked for") }
        eventually { server.changed.isNotEmpty() }
        val change = server.changed.single()
        assertEquals(2, change.textDocument.version)
        assertEquals(2, document.version)
        val event = change.contentChanges.single()
        assertEquals(8, event.range.start.character)
        assertEquals(9, event.range.end.character)
        assertEquals("42", event.text)

        document.save()
        eventually { server.saved.isNotEmpty() }

        document.close()
        eventually { server.closed.isNotEmpty() }
        assertEquals(uri, server.closed.single().textDocument.uri)
        assertNull(client.document(uri))
        assertFailsWith<IllegalStateException> { document.change(TextChange.full("")) { "" } }
    }

    @Test
    fun `a server that wants the whole text gets it`() = test {
        val server = FakeLanguageServer(TextDocumentSyncKind.Full)
        val client = client(server)
        client.start()

        val document = client.open(uri, "c", "int x = 1")
        val range = TextRange(TextPosition(0, 8), TextPosition(0, 9))
        document.change(TextChange(range, "42")) { "int x = 42" }

        eventually { server.changed.isNotEmpty() }
        val event = server.changed.single().contentChanges.single()
        assertNull(event.range)
        assertEquals("int x = 42", event.text)
    }

    @Test
    fun `diagnostics are published and cleared`() = test {
        val server = FakeLanguageServer(TextDocumentSyncKind.Full)
        val client = client(server)
        client.start()

        val document = client.open(uri, "c", "error here")
        val published = client.diagnostics.first { it.containsKey(uri) }.getValue(uri)
        val diagnostic = published.single()
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("expected ';'", diagnostic.message)
        assertEquals("fake", diagnostic.source)
        assertEquals(diagnostic, document.diagnostics.single())

        // the server publishes nothing for a text without "error": cleared
        document.change(TextChange.full("int x;")) { "int x;" }
        client.diagnostics.first { !it.containsKey(uri) }
        assertEquals(emptyList(), document.diagnostics)

        // its log message came through too
        eventually { logs.any { it.startsWith("opened") } }
    }

    @Test
    fun `completion, resolve and hover`() = test {
        val server = FakeLanguageServer()
        val client = client(server)
        client.start()
        val document = client.open(uri, "c", "foo.")

        val result = document.completion(TextPosition(0, 4), trigger = ".")
        assertEquals(listOf("println", "print", "value"), result.items.map { it.label })
        assertEquals(CompletionKind.FUNCTION, result.items.first().kind)

        // "." is a trigger character of the server: it was told so
        val context = server.completions.single().context
        assertEquals(CompletionTriggerKind.TriggerCharacter, context.triggerKind)
        assertEquals(".", context.triggerCharacter)
        assertEquals(4, server.completions.single().position.character)

        // filtered on the client as the user types
        assertEquals(listOf("println", "print"), CompletionRanker.rank(result.items, "pri").map { it.label })

        val resolved = client.resolve(result.items.first())
        assertEquals("Prints a line.", resolved.documentation)

        val hover = document.hover(TextPosition(0, 1))
        assertEquals(HoverInfo("`int x`", isMarkdown = true), hover)
    }

    @Test
    fun `a character that is no trigger is an invoked completion`() = test {
        val server = FakeLanguageServer()
        val client = client(server)
        client.start()
        val document = client.open(uri, "c", "x")

        document.completion(TextPosition(0, 1), trigger = "x")
        assertEquals(CompletionTriggerKind.Invoked, server.completions.single().context.triggerKind)
    }

    @Test
    fun `shutdown asks the server to stop, then stops`() = test {
        val server = FakeLanguageServer()
        val client = client(server)
        client.start()
        val document = client.open(uri, "c", "")

        client.shutdown()

        eventually { server.exited }
        assertTrue(server.shutdownCalled)
        assertEquals(LspClient.State.Stopped, client.state.value)
        assertTrue(!document.isOpen)
        assertFailsWith<IllegalStateException> { client.open(uri, "c", "") }
    }

    @Test
    fun `a server that does not answer fails the start`() = test {
        val server = FakeLanguageServer(silent = true)
        val client = client(server, timeoutMillis = 300)

        val error = assertFailsWith<LspException> { client.start() }
        assertTrue(error.message.orEmpty().contains("initialize"))
        assertIs<LspClient.State.Failed>(client.state.value)
    }

    @Test
    fun `nothing can be asked before the start`() {
        val client = client(FakeLanguageServer())
        assertFailsWith<IllegalStateException> { client.open(uri, "c", "") }
        assertEquals(LspClient.State.Stopped, client.state.value)
    }
}
