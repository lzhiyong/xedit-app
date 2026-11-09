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

package x.editor.app.lsp

import x.editor.app.toolchain.ToolchainInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a language server runs, and where it is looked for: it does not depend on the
 * toolchain's packages.json (auto: the app's usr/bin, else Termux; process: the app's own;
 * socket: a port, started by Termux through socat).
 */
class ServerToolsTest {

    private fun spec(id: String, connection: String): LanguageServerSpec = assertIs<LanguageServers.Check.Valid>(
        LanguageServers.check(id, """{ "extensions": ["x"], "connection": $connection }""")
    ).spec

    private fun auto(vararg command: String) =
        """{ "type": "auto", "command": [${command.joinToString { "\"$it\"" }}] }"""

    @Test
    fun theProgramOfAServerIsItsCommand() {
        assertEquals(listOf("clangd"), LanguageServers.programsOf(spec("clangd", auto("clangd", "--background-index"))))
        // by whichever path
        assertEquals(listOf("clangd"), LanguageServers.programsOf(spec("c", auto("/opt/bin/clangd"))))
        assertEquals(
            listOf("rust-analyzer"),
            LanguageServers.programsOf(spec("x", """{ "type": "process", "command": ["rust-analyzer"] }""")),
        )
    }

    @Test
    fun aCommandOfAutoIsMissingOnlyIfTermuxWasAskedAndHasItNot() {
        fun of(inUsr: Boolean = false, termux: Boolean = true, found: Set<String>? = null, asked: Set<String> = emptySet()) =
            LanguageServers.ofAuto("ls", inUsr, termux, found, asked)
        assertEquals(LanguageServers.Installation.Installed, of(inUsr = true))
        assertEquals(LanguageServers.Installation.Missing("ls"), of(termux = false))
        // Termux not asked yet, or not about it: not known
        assertEquals(LanguageServers.Installation.Unknown, of())
        assertEquals(LanguageServers.Installation.Unknown, of(found = emptySet(), asked = setOf("other")))
        assertEquals(LanguageServers.Installation.Installed, of(found = setOf("ls"), asked = setOf("ls")))
        assertEquals(LanguageServers.Installation.Missing("ls"), of(found = emptySet(), asked = setOf("ls")))
    }

    @Test
    fun aSocketBridgedBySocatRunsWhatItsBridgeRuns() {
        // the preset of pyright: socat in Termux, running node
        val pyright = spec(
            "pyright",
            """{ "type": "socket", "port": 5002, "termux": { "command": "socat", "arguments": [
                "TCP-LISTEN:5002,bind=127.0.0.1,reuseaddr,fork", "EXEC:/usr/bin/node /usr/lib/pyright/langserver.index.js --stdio"
            ] } }""",
        )
        assertEquals(listOf("node"), LanguageServers.programsOf(pyright))
        val bridged = spec(
            "c",
            """{ "type": "socket", "port": 5004, "termux": { "command": "socat", "arguments": ["TCP-LISTEN:5004", "EXEC:clangd --background-index"] } }""",
        )
        assertEquals(listOf("clangd"), LanguageServers.programsOf(bridged))
        // nothing started: nothing it runs
        assertEquals(emptyList(), LanguageServers.programsOf(spec("remote", """{ "type": "socket", "port": 5005 }""")))
    }

    @Test
    fun twoToolsOfAnIdAreRefusedOnlyWhenBothAreOn() {
        assertNotNull(ToolchainInstaller.checkCatalog("""{ "packages": [ { "id": "a" }, { "id": "a" } ] }"""))
        assertNull(ToolchainInstaller.checkCatalog("""{ "packages": [ { "id": "a" }, { "id": "a", "enabled": false } ] }"""))
    }
}
