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

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Where a language server is: two streams, [input] (what the server sends) and
 * [output] (what is sent to it), over which LspClient speaks JSON-RPC.
 *
 *  - [ProcessConnection]: a server the app starts itself (a binary it can execute);
 *  - [SocketConnection]: a server that listens on a TCP port, such as one running
 *    in Termux behind a bridge (an app cannot execute the binaries of another app);
 *  - [StreamConnection]: any two streams (tests, a WebSocket of one's own, ...).
 */
interface LanguageServerConnection : Closeable {

    /** What the server sends; there after [start]. */
    val input: InputStream

    /** What is sent to the server; there after [start]. */
    val output: OutputStream

    /** Makes the connection: starts the process, connects the socket. Blocks. */
    @Throws(IOException::class)
    fun start()

    /** Ends the connection: stops the process, closes the socket. Can be called more than once. */
    override fun close()
}

/**
 * A server started as a process of [command] (the binary and its arguments), in
 * [workingDirectory], with [environment] added to that of the app. What it writes
 * to stderr goes line by line to [onStderr], on a thread of its own.
 */
class ProcessConnection(
    private val command: List<String>,
    private val workingDirectory: File? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val onStderr: (String) -> Unit = {}
) : LanguageServerConnection {

    private var process: Process? = null

    override val input: InputStream
        get() = checkNotNull(process) { "not started" }.inputStream

    override val output: OutputStream
        get() = checkNotNull(process) { "not started" }.outputStream

    override fun start() {
        check(process == null) { "already started" }
        val started = ProcessBuilder(command)
            .apply {
                workingDirectory?.let { directory(it) }
                environment().putAll(environment)
            }
            .start()
        process = started
        Thread({
            try {
                started.errorStream.bufferedReader().forEachLine(onStderr)
            } catch (e: IOException) {
                // the process ended
            }
        }, "lsp-stderr").apply { isDaemon = true }.start()
    }

    override fun close() {
        // (waitFor with a timeout, isAlive and destroyForcibly need API 26)
        process?.destroy()
    }

    override fun toString() = "ProcessConnection(${command.joinToString(" ")})"
}

/** A server that listens on [host]:[port]. */
class SocketConnection(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 5_000
) : LanguageServerConnection {

    private var socket: Socket? = null

    override val input: InputStream
        get() = checkNotNull(socket) { "not started" }.getInputStream()

    override val output: OutputStream
        get() = checkNotNull(socket) { "not started" }.getOutputStream()

    override fun start() {
        check(socket == null) { "already started" }
        // The address is made outside the Socket's apply: in there, `port` is the Socket's
        // own (getPort(), 0 while it is not connected), not this connection's.
        val address = InetSocketAddress(host, port)
        socket = Socket().apply {
            tcpNoDelay = true
            connect(address, connectTimeoutMillis)
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // closed already
        }
    }

    override fun toString() = "SocketConnection($host:$port)"
}

/** A server behind two streams that are there already; [onClose] when it is closed. */
class StreamConnection(
    override val input: InputStream,
    override val output: OutputStream,
    private val onClose: () -> Unit = {}
) : LanguageServerConnection {

    override fun start() = Unit

    override fun close() {
        try {
            input.close()
            output.close()
        } catch (e: IOException) {
            // closed already
        }
        onClose()
    }
}
