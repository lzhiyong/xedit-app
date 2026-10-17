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

import java.net.InetAddress
import java.net.ServerSocket
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SocketConnection against a real socket: the other tests speak over pipes. */
class SocketConnectionTest {

    @Test
    fun connectsToThePortItIsGiven() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            var received = ""
            val accepting = thread {
                server.accept().use { client -> received = client.getInputStream().bufferedReader().readLine() }
            }
            val connection = SocketConnection("127.0.0.1", port, connectTimeoutMillis = 2_000)
            connection.start()
            connection.output.write("hello\n".toByteArray())
            connection.output.flush()
            accepting.join(2_000)
            connection.close()
            assertEquals("hello", received)
        }
    }

    @Test
    fun failsWhereNothingListens() {
        // a port that was free a moment ago
        val port = ServerSocket(0).use { it.localPort }
        assertFailsWith<IOException> { SocketConnection("127.0.0.1", port, connectTimeoutMillis = 1_000).start() }
    }
}
