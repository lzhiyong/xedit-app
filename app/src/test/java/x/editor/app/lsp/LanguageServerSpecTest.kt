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

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The files of servers read into LanguageServerSpecs; where documents are, for a server (LocalFiles). */
class LanguageServerSpecTest {

    // a server of the file of [id] (servers/<id>.json)
    private fun spec(id: String, text: String): LanguageServerSpec =
        assertIs<LanguageServers.Check.Valid>(LanguageServers.check(id, text)).spec

    private val clangd = spec("clangd", """
      {"enabled":false,"extensions":["c","h","cc","cpp"],
       "connection":{"type":"socket","host":"127.0.0.1","port":5007,
         "termux":{"command":"socat","arguments":["TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork","EXEC:clangd"]}}}
    """)
    private val lua = spec("lua", """
      {"extensions":[".LUA"],"initializeTimeout":90,"initializationOptions":{"a":1,"b":[true,"x"],"c":{"d":2.5}},
       "connection":{"type":"process","command":["lua-language-server","--stdio"],"env":{"K":"V"}}}
    """)
    private val specs = listOf(clangd, lua)

    @Test
    fun aServerWithSomethingWrongSaysWhat() {
        val broken = assertIs<LanguageServers.Check.Invalid>(
            LanguageServers.check("broken", """{"extensions":["x"],"connection":{"type":"socket","port":99999}}""")
        )
        assertTrue(broken.message.contains("broken") && broken.message.contains("port"), broken.message)
        assertEquals(listOf("clangd", "lua"), specs.map { it.id })
    }

    @Test
    fun connections() {
        val socket = specs[0].connection as LanguageServerSpec.Connection.Socket
        assertEquals("127.0.0.1", socket.host)
        assertEquals(5007, socket.port)
        assertEquals(TermuxCommand("socat", listOf("TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork", "EXEC:clangd")), socket.termux)
        val process = specs[1].connection as LanguageServerSpec.Connection.Process
        assertEquals(listOf("lua-language-server", "--stdio"), process.command)
        assertEquals(mapOf("K" to "V"), process.environment)
    }

    @Test
    fun whichFilesAndLanguages() {
        assertFalse(specs[0].handles("main.c"), "off")
        assertTrue(specs[1].handles("init.lua") && specs[1].handles("A.LUA") && !specs[1].handles("a.luac"))
        assertEquals("cpp", specs[0].languageIdFor(File("x.hpp")))
        assertEquals("c", specs[0].languageIdFor(File("a.h")))
        assertEquals("cpp", specs[0].copy(languageIds = mapOf("h" to "cpp")).languageIdFor(File("a.h")))
        assertEquals(mapOf("a" to 1L, "b" to listOf(true, "x"), "c" to mapOf("d" to 2.5)), specs[1].initializationOptions)
        // in seconds; none: the client's default
        assertEquals(90_000L, specs[1].initializeTimeoutMillis)
        assertEquals(null, specs[0].initializeTimeoutMillis)
    }

    @Test
    fun documentIdsOfTheExternalStorage() {
        val primary = File("/storage/emulated/0")
        assertEquals(File(primary, "dev/a.c"), LocalFiles.fileOfDocumentId("primary:dev/a.c", primary))
        assertEquals(File("/storage/1A2B-3C4D/src/x.py"), LocalFiles.fileOfDocumentId("1A2B-3C4D:src/x.py", primary))
        assertEquals(primary, LocalFiles.fileOfDocumentId("primary:", primary))
        assertEquals(null, LocalFiles.fileOfDocumentId("12345", primary))
    }

    @Test
    fun theRootOfAGradleBuildNotOfItsModule() {
        val tmp = Files.createTempDirectory("lsp").toFile()
        try {
            val project = File(tmp, "proj").apply { mkdirs() }
            File(project, "settings.gradle.kts").writeText("")
            File(project, "build.gradle.kts").writeText("")
            val app = File(project, "app").apply { mkdirs() }
            File(app, "build.gradle.kts").writeText("")
            val file = File(app, "src/main/java/a/Main.kt").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(project, LocalFiles.findRoot(file, LanguageServerSpec.DEFAULT_ROOT_MARKERS, tmp))
            // a module on its own (no settings above, not past a .git): itself
            val alone = File(tmp, "alone").apply { mkdirs() }
            File(alone, ".git").mkdirs()
            val module = File(alone, "lib").apply { mkdirs() }
            File(module, "build.gradle").writeText("")
            val other = File(module, "src/B.java").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(module, LocalFiles.findRoot(other, LanguageServerSpec.DEFAULT_ROOT_MARKERS, tmp))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun theProjectRoot() {
        val tmp = Files.createTempDirectory("lsp").toFile()
        try {
            val project = File(tmp, "proj").apply { mkdirs() }
            File(project, "compile_commands.json").writeText("[]")
            val file = File(project, "src/deep/main.c").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(project, LocalFiles.findRoot(file, LanguageServerSpec.DEFAULT_ROOT_MARKERS, tmp))
            val loose = File(tmp, "loose/a.c").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(loose.parentFile, LocalFiles.findRoot(loose, LanguageServerSpec.DEFAULT_ROOT_MARKERS, tmp))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun autoIsTheUsersOwnElseTermuxs() {
        val auto = listOf(
            spec("clangd", """{"extensions":["c"],"connection":{"type":"auto","command":["clangd","--background-index"]}}"""),
            spec("py", """{"extensions":["py"],"connection":{"type":"auto","command":["pyright-langserver","--stdio"],"port":5010}}"""),
        )
        val clangd = auto[0].connection as LanguageServerSpec.Connection.Auto
        // clangd's own defaults, a port of its own
        assertEquals(listOf("clangd", ClangdDefaults.HEADER_INSERTION, "--background-index"), clangd.command)
        assertEquals(LanguageServerSpec.defaultPort("clangd", listOf("clangd", "--background-index")), clangd.port)
        assertTrue(clangd.port in 5100..5899)
        // another command, another bridge
        assertTrue(clangd.port != LanguageServerSpec.defaultPort("clangd", listOf("clangd")))
        val py = auto[1].connection as LanguageServerSpec.Connection.Auto
        assertEquals(
            TermuxCommand("socat", listOf("TCP-LISTEN:5010,bind=127.0.0.1,reuseaddr,fork", "EXEC:pyright-langserver --stdio")),
            py.termuxBridge()
        )
    }
}
