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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The language servers that format, as the app ships them (assets/formatting/servers.jsonc). */
class FormatterServersTest {

    // the asset (unit tests run in the module's directory)
    private val asset = java.io.File("src/main/assets/formatting/servers.jsonc")

    @Test
    fun theAssetIsReadWhole() {
        val text = asset.readText()
        val catalog = FormatterServers.parse(text)
        assertNotNull(catalog.directory)
        assertTrue(catalog.directory!!.startsWith("https://"))
        // every server of the file is read: none left out for a missing name, url or language
        assertEquals(Regex("\"name\"\\s*:").findAll(text).count(), catalog.servers.size)
        for (server in catalog.servers) {
            assertTrue(server.url.startsWith("https://github.com/"), "${server.name}: ${server.url}")
            assertFalse(server.languages.isEmpty(), server.name)
            assertNotNull(server.command, server.name)
            assertNotNull(server.note("en"), server.name)
            assertNotNull(server.note("zh"), server.name)
        }
    }

    @Test
    fun theCommonLanguagesHaveAServer() {
        val catalog = FormatterServers.parse(asset.readText())
        for (language in listOf("c", "cpp", "python", "rust", "go", "javascript", "typescript", "lua", "shellscript", "json", "html", "css", "yaml", "toml", "java", "kotlin")) {
            assertTrue(catalog.forLanguage(language).isNotEmpty(), language)
        }
        assertEquals(listOf("ruff", "python-lsp-server (pylsp)"), catalog.forLanguage("Python").map { it.name })
        assertTrue(catalog.forLanguage("brainfuck").isEmpty())
    }

    @Test
    fun aServerWithoutWhatItNeedsIsLeftOut() {
        val catalog = FormatterServers.parse(
            """
            {
              // comments and trailing commas
              "servers": [
                { "name": "a", "url": "https://github.com/a/a", "languages": ["x"], "note": "plain" },
                { "name": "b", "languages": ["x"] },
                { "name": "c", "url": "https://github.com/c/c", "languages": [] },
              ],
            }
            """.trimIndent()
        )
        assertEquals(listOf("a"), catalog.servers.map { it.name })
        assertEquals("plain", catalog.servers.single().note("zh"))
        assertEquals(null, catalog.directory)
    }
}
