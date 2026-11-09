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

package x.editor.app.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The name an imported highlight theme gets from its file; the names and the start of a theme made on ThemeSheet (see HighlightThemeFiles). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class HighlightThemeFilesTest {

    @Test
    fun nameOfTheFile() {
        assertEquals("tokyonight", HighlightThemeFiles.safeName("tokyonight.json"))
        assertEquals("tokyonight", HighlightThemeFiles.safeName("tokyonight.JSON"))
        assertEquals("one-dark_pro", HighlightThemeFiles.safeName("one-dark_pro.json"))
        // another extension, or none
        assertEquals("theme", HighlightThemeFiles.safeName("theme.jsonc"))
        assertEquals("gruvbox", HighlightThemeFiles.safeName("gruvbox"))
        // a path: only its last part
        assertEquals("nord", HighlightThemeFiles.safeName("primary:Download/nord.json"))
    }

    @Test
    fun whatIsNotSafeBecomesUnderscores() {
        // the " (1)" of a second download
        assertEquals("astrodark_1", HighlightThemeFiles.safeName("astrodark (1).json"))
        assertEquals("my_theme", HighlightThemeFiles.safeName("my.theme.json"))
        assertEquals("a_b", HighlightThemeFiles.safeName("a<>:\"|?*b.json"))
        // letters of any script are kept
        assertEquals("暗色主题", HighlightThemeFiles.safeName("暗色主题.json"))
    }

    @Test
    fun nothingLeftOrTooLong() {
        assertEquals("theme", HighlightThemeFiles.safeName(null))
        assertEquals("theme", HighlightThemeFiles.safeName(".json"))
        assertEquals("theme", HighlightThemeFiles.safeName("(((.json"))
        assertEquals(64, HighlightThemeFiles.safeName("x".repeat(200) + ".json").length)
    }

    @Test
    fun namesOfThemesMadeOnTheSheet() {
        assertTrue(HighlightThemeFiles.isValidName("my-theme_2"))
        assertFalse(HighlightThemeFiles.isValidName(""))
        assertFalse(HighlightThemeFiles.isValidName("my theme"))
        assertFalse(HighlightThemeFiles.isValidName("dark.json"))
        assertFalse(HighlightThemeFiles.isValidName("-dark"))
    }

    @Test
    fun theThemeToStartFromIsJsonWithItsName() {
        // the template of the assets (unit tests run in the module's directory), JSON with
        // comments; one placeholder
        val template = java.io.File("src/main/assets/templates/theme/theme.jsonc").readText()
        assertEquals(1, Regex("\"\\{\\{name}}\"").findAll(template).count(), "one placeholder")
        assertTrue(template.lineSequence().any { it.trimStart().startsWith("//") }, "no comments")
        val jsonc = Json {
            allowComments = true
            allowTrailingComma = true
        }
        val root = assertIs<JsonObject>(jsonc.parseToJsonElement(HighlightThemeFiles.fillTemplate(template, "my \"theme\"")))
        val meta = assertIs<JsonObject>(root["meta"])
        assertEquals("my \"theme\"", (meta["colorscheme"] as JsonPrimitive).content)
        assertTrue(assertIs<JsonObject>(root["captures"]).size > 20)
        assertIs<JsonObject>(root["editor"])
    }

    @Test
    fun aThemeIsListedByTheNameItSaysItHas() {
        val text = """
            // comments are allowed
            { "meta": { "colorscheme": " Night owl ", "background": "dark" }, "captures": {} }
        """.trimIndent()
        assertEquals("Night owl", HighlightThemeFiles.titleOf(text, "file"))
        assertEquals("file", HighlightThemeFiles.titleOf("""{ "meta": { "colorscheme": " " } }""", "file"))
        assertEquals("file", HighlightThemeFiles.titleOf("""{ "captures": {} }""", "file"))
        assertEquals("file", HighlightThemeFiles.titleOf("not json", "file"))
    }

    private fun themeNamed(name: String) = """{ "meta": { "colorscheme": "$name" }, "captures": {} }"""

    @Test
    fun aThemeIsRenamedOnlyWhenItsNameIsChanged() {
        // left alone, or back to what its file is called: its file stays
        assertEquals(null, HighlightThemeFiles.renameOf("file", themeNamed("file"), themeNamed("file")))
        assertEquals(null, HighlightThemeFiles.renameOf("file", themeNamed("other"), themeNamed("file")))
        // imported under another name than it says: stays while that is left alone
        assertEquals(null, HighlightThemeFiles.renameOf("nord_custom", themeNamed("nord"), themeNamed("nord")))
        // changed
        assertEquals("night", HighlightThemeFiles.renameOf("file", themeNamed("file"), themeNamed("night")))
        assertEquals("nord2", HighlightThemeFiles.renameOf("nord_custom", themeNamed("nord"), themeNamed("nord2")))
        // a new theme, its name changed in the editor
        assertEquals("night", HighlightThemeFiles.renameOf("draft", null, themeNamed("night")))
        assertEquals(null, HighlightThemeFiles.renameOf("draft", null, themeNamed("draft")))
    }

    @Test
    fun aNewNameMustBeOneAFileCanHave() {
        assertTrue(HighlightThemeFiles.isValidName("night-owl_2"))
        assertTrue(HighlightThemeFiles.isValidName("夜间"))
        assertFalse(HighlightThemeFiles.isValidName("night owl"))
        assertFalse(HighlightThemeFiles.isValidName("../night"))
        assertFalse(HighlightThemeFiles.isValidName("night.json"))
    }
}
