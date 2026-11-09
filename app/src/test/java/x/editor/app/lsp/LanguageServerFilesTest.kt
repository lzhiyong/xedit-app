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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The file of a server (servers/<id>.json): named by the id, checked as LanguageServersSheet saves it. */
class LanguageServerFilesTest {

    @Test
    fun theFileNameIsTheId() {
        val check = LanguageServers.check(
            "clangd",
            """
            // comments and a trailing comma, as people copy
            {
              "id": "not this one",
              "extensions": ["c", "cpp"],
              "connection": { "type": "auto", "command": ["clangd"], "port": 5001 },
            }
            """.trimIndent()
        )
        val spec = assertIs<LanguageServers.Check.Valid>(check).spec
        assertEquals("clangd", spec.id)
        assertEquals(setOf("c", "cpp"), spec.extensions)
        assertTrue(spec.enabled)
    }

    @Test
    fun notJsonSaysWhere() {
        val check = assertIs<LanguageServers.Check.Invalid>(
            LanguageServers.check("lua", "{\n  \"extensions\": [\"lua\"]\n  \"connection\": {}\n}")
        )
        assertTrue(check.syntax)
        assertTrue(check.line in 2..3, check.line.toString())
    }

    @Test
    fun notAnObjectOrNotAServer() {
        assertTrue(assertIs<LanguageServers.Check.Invalid>(LanguageServers.check("x", "[1, 2]")).notObject)
        val noConnection = assertIs<LanguageServers.Check.Invalid>(LanguageServers.check("x", """{ "extensions": ["x"] }"""))
        assertFalse(noConnection.syntax || noConnection.notObject)
        assertTrue(noConnection.message.contains("connection"), noConnection.message)
    }

    // the assets (unit tests run in the module's directory)
    private val assets = java.io.File("src/main/assets/templates/lsp")

    @Test
    fun theExamplesAreServersAndOff() {
        val examples = java.io.File(assets, "presets").listFiles { f -> f.name.endsWith(".jsonc") }.orEmpty()
        assertEquals(setOf("clangd", "pyright", "lua-language-server"), examples.map { it.name.removeSuffix(".jsonc") }.toSet())
        for (file in examples) {
            val id = file.name.removeSuffix(".jsonc")
            val spec = assertIs<LanguageServers.Check.Valid>(LanguageServers.check(id, file.readText())).spec
            assertFalse(spec.enabled, id)
        }
        // clangd adds no #include of what it completes
        val clangd = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check("clangd", java.io.File(assets, "presets/clangd.jsonc").readText())
        ).spec.connection
        assertTrue(assertIs<LanguageServerSpec.Connection.Auto>(clangd).command.contains("--header-insertion=never"))
    }

    @Test
    fun theServerOfALanguageIsTheFirstOnForItsExtensions() {
        fun spec(id: String) = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check(id, java.io.File(assets, "presets/$id.jsonc").readText())
        ).spec
        val clangd = spec("clangd").copy(enabled = true)
        val pyright = spec("pyright")
        val lua = spec("lua-language-server").copy(enabled = true)
        val specs = listOf(clangd, lua, pyright)
        // by the language ids of their extensions: .cc, .hpp → cpp
        assertEquals("clangd", LanguageServers.specForLanguage(specs, "cpp")?.id)
        assertEquals("clangd", LanguageServers.specForLanguage(specs, "c")?.id)
        assertEquals("lua-language-server", LanguageServers.specForLanguage(specs, "lua")?.id)
        // off: not the server of python; none for rust
        assertEquals(null, LanguageServers.specForLanguage(specs, "python"))
        assertEquals(null, LanguageServers.specForLanguage(specs, "rust"))
        assertEquals("pyright", LanguageServers.specForLanguage(specs + pyright.copy(enabled = true), "python")?.id)
    }

    @Test
    fun theExamplesHavePortsOfTheirOwn() {
        fun spec(id: String) = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check(id, java.io.File(assets, "presets/$id.jsonc").readText())
        ).spec
        assertEquals(5001, assertIs<LanguageServerSpec.Connection.Auto>(spec("clangd").connection).port)
        val pyright = assertIs<LanguageServerSpec.Connection.Socket>(spec("pyright").connection)
        assertEquals(5002, pyright.port)
        // the bridge Termux starts listens where the client connects
        assertTrue(pyright.termux!!.arguments.any { it.startsWith("TCP-LISTEN:5002,") })
    }

    @Test
    fun serversOnTheSamePort() {
        fun server(id: String, connection: String, enabled: Boolean = true) = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check(id, """{ "enabled": $enabled, "extensions": ["$id"], "connection": $connection }""")
        ).spec
        val clangd = server("clangd", """{ "type": "auto", "command": ["clangd"], "port": 5001 }""")
        val pyright = server("pyright", """{ "type": "socket", "host": "localhost", "port": 5001 }""")
        val lua = server("lua", """{ "type": "process", "command": ["lua-language-server"] }""")
        val off = server("off", """{ "type": "socket", "port": 5001 }""", enabled = false)
        // localhost and 127.0.0.1 are the same place; a process has no port; one off does not count
        val shared = LanguageServers.sharedAddresses(listOf(clangd, pyright, lua, off))
        assertEquals(mapOf("clangd" to listOf("pyright"), "pyright" to listOf("clangd")), shared)
        val apart = server("pyright", """{ "type": "socket", "port": 5002 }""")
        assertTrue(LanguageServers.sharedAddresses(listOf(clangd, apart, lua)).isEmpty())
    }

    @Test
    fun extensionsOfAnEarlierServer() {
        fun server(id: String, extensions: String, enabled: Boolean = true) = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check(
                id,
                """{ "enabled": $enabled, "extensions": $extensions, "connection": { "type": "process", "command": ["$id"] } }"""
            )
        ).spec
        val pyright = server("pyright", """["py", "pyi"]""")
        val pylsp = server("pylsp", """["py"]""")
        val both = server("both", """["pyi", "pyx"]""")
        val off = server("off", """["py"]""", enabled = false)
        // in the order of the list: the first one of an extension has its files
        val shadowed = LanguageServers.shadowedExtensions(listOf(off, pyright, pylsp, both))
        assertEquals(mapOf("pylsp" to mapOf("py" to "pyright"), "both" to mapOf("pyi" to "pyright")), shadowed)
        assertTrue(LanguageServers.shadowedExtensions(listOf(pyright, off)).isEmpty())
    }

    @Test
    fun aNewServerFromTheTemplate() {
        val template = java.io.File(assets, "server.jsonc").readText()
        // with the document's extension: a server, off until it is looked at, running its id
        val spec = assertIs<LanguageServers.Check.Valid>(
            LanguageServers.check("rust-analyzer", LanguageServers.fillTemplate(template, "rust-analyzer", ".RS"))
        ).spec
        assertFalse(spec.enabled)
        assertEquals(setOf("rs"), spec.extensions)
        assertEquals(listOf("rust-analyzer"), assertIs<LanguageServerSpec.Connection.Auto>(spec.connection).command)
        // with none: JSON all the same, its extensions to be filled in
        val none = LanguageServers.check("x", LanguageServers.fillTemplate(template, "x", null))
        assertFalse(assertIs<LanguageServers.Check.Invalid>(none).syntax)
    }

    @Test
    fun namesOfFiles() {
        assertEquals("clangd.json", LanguageServers.fileNameOf("clangd"))
        assertEquals("clangd (Termux).json", LanguageServers.fileNameOf("clangd (Termux)"))
        assertEquals("a_b.json", LanguageServers.fileNameOf("a/b"))
        assertEquals("_hidden.json", LanguageServers.fileNameOf(".hidden"))
    }
}
