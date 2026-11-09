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

package x.editor.app.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CatalogTextTest {

    // the ids of a list, those switched off too
    private fun ids(text: String): List<String> = ToolPackage.parseCatalog(text).map { it.id }

    @Test
    fun aToolchainGoesWithItsCommaAndLine() {
        val text = """
            {
              // the toolchains
              "packages": [
                { "id": "clang", "termux": "pkg install clang" },
                { "id": "pyright", "termux": "npm install -g pyright" },
                { "id": "socat", "termux": "pkg install socat" }
              ]
            }
        """.trimIndent() + "\n"
        val edited = CatalogText.removePackage(text, "pyright")!!
        assertEquals(
            """
            {
              // the toolchains
              "packages": [
                { "id": "clang", "termux": "pkg install clang" },
                { "id": "socat", "termux": "pkg install socat" }
              ]
            }
            """.trimIndent() + "\n",
            edited,
        )
        assertNull(ToolchainInstaller.checkCatalog(edited))
    }

    @Test
    fun theLastGoesWithTheCommaBeforeIt() {
        val text = "{\n  \"packages\": [\n    { \"id\": \"a\" }, // about a\n    // about b\n    {\n      \"id\": \"b\"\n    }\n  ]\n}\n"
        val edited = CatalogText.removePackage(text, "b")!!
        assertEquals("{\n  \"packages\": [\n    { \"id\": \"a\" } // about a\n  ]\n}\n", edited)
        assertEquals(listOf("a"), ids(edited))
    }

    @Test
    fun theFirstAndTheOnlyOne() {
        assertEquals("""{"packages":[ {"id":"b"} ]}""", CatalogText.removePackage("""{"packages":[ {"id":"a"}, {"id":"b"} ]}""", "a"))
        assertEquals("{\n  \"packages\": [\n  ]\n}", CatalogText.removePackage("{\n  \"packages\": [\n    {\"id\":\"a\"}\n  ]\n}", "a"))
    }

    @Test
    fun everyOneOfTheIdGoesAndNothingElse() {
        // a "packages" elsewhere, brackets in strings, one switched off
        val text = """{"x":{"packages":[{"id":"a"}]},"packages":[{"id":"a"},{"id":"b","s":"}]//"},{"id":"a","enabled":false}]}"""
        assertEquals("""{"x":{"packages":[{"id":"a"}]},"packages":[{"id":"b","s":"}]//"}]}""", CatalogText.removePackage(text, "a"))
        assertNull(CatalogText.removePackage(text, "c"))
    }

    @Test
    fun theShippedListKeepsItsComments() {
        val shipped = java.io.File("src/main/assets/templates/toolchain/packages.jsonc").readText()
        val edited = CatalogText.removePackage(shipped, "clang")!!
        assertNull(ToolchainInstaller.checkCatalog(edited))
        assertEquals(ids(shipped).filter { it != "clang" }, ids(edited))
        assertEquals(shipped.lines().filter { it.trim().startsWith("//") }, edited.lines().filter { it.trim().startsWith("//") })
    }

    @Test
    fun notAListOfToolchains() {
        assertFailsWith<IllegalArgumentException> { CatalogText.removePackage("""{"servers":[]}""", "a") }
        assertFailsWith<IllegalArgumentException> { CatalogText.removePackage("[]", "a") }
    }
}
