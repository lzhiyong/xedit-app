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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import x.github.module.lsp.LspFormattingOptions

/** The formatting rules: generated from the editor, the user's over them, and their checks. */
class FormattingRulesTest {

    private fun rules(text: String) = Json.parseToJsonElement(text).jsonObject

    // the templates of the rules in the assets (unit tests run in the module's directory)
    private val assets = java.io.File("src/main/assets/templates/formatting")

    // the template of [id] as the app picks it: its own, else the general one
    private fun templateOf(id: String): String =
        java.io.File(assets, "presets/$id.jsonc").takeIf { it.isFile }?.readText()
            ?: java.io.File(assets, "language.jsonc").readText()

    // the defaults of [id] as FormattingRules.defaults(context, …) makes them
    private fun defaults(id: String, indentUnit: String, tabSize: Int): LspFormattingOptions =
        FormattingRules.defaults(templateOf(id), indentUnit, tabSize)

    private fun problems(text: String): List<FormattingRules.Problem> =
        assertIs<FormattingRules.Validation.Invalid>(FormattingRules.validate(text)).problems

    @Test
    fun generatedFromTheEditorsIndentation() {
        val spaces = defaults("cpp", "  ", 8)
        assertEquals(2, spaces.tabSize)
        assertTrue(spaces.insertSpaces)
        assertEquals(true, spaces.trimTrailingWhitespace)
        assertEquals(true, spaces.insertFinalNewline)
        assertEquals(true, spaces.trimFinalNewlines)

        val tabs = defaults("cpp", "\t", 8)
        assertEquals(8, tabs.tabSize)
        assertFalse(tabs.insertSpaces)

        // Go indents with tabs whatever the editor does: its own template says so
        assertFalse(defaults("go", "    ", 4).insertSpaces)
        assertFalse(defaults("makefile", "  ", 4).insertSpaces)
    }

    @Test
    fun theTemplatesAreFilledInToRightRules() {
        val templates = java.io.File(assets, "presets").listFiles { f -> f.name.endsWith(".jsonc") }.orEmpty().toList() +
            java.io.File(assets, "language.jsonc")
        assertTrue(templates.size > 1)
        for (file in templates) {
            val text = file.readText()
            assertTrue(FormattingRules.TAB_SIZE_PLACEHOLDER in text, "${file.name}: no ${FormattingRules.TAB_SIZE_PLACEHOLDER}")
            for ((unit, size) in listOf("  " to 8, "\t" to 4)) {
                val filled = FormattingRules.fillTemplate(text, "lua", FormattingRules.indentation(unit, size))
                assertFalse(FormattingRules.TAB_SIZE_PLACEHOLDER in filled, file.name)
                assertFalse(FormattingRules.INSERT_SPACES_PLACEHOLDER in filled, file.name)
                assertFalse(FormattingRules.LANGUAGE_PLACEHOLDER in filled, file.name)
                // what the file made of it says is what a language without a file goes by
                val valid = assertIs<FormattingRules.Validation.Valid>(FormattingRules.validate(filled), file.name)
                assertEquals(
                    FormattingRules.defaults(text, unit, size),
                    FormattingRules.merge(LspFormattingOptions(tabSize = 3, insertSpaces = unit != "\t"), valid.rules),
                    file.name,
                )
            }
        }
    }

    @Test
    fun withoutATemplateTheEditorsIndentationAlone() {
        val options = FormattingRules.defaults("", "    ", 8)
        assertEquals(LspFormattingOptions(tabSize = 4, insertSpaces = true), options)
        assertNull(options.trimTrailingWhitespace)
    }

    @Test
    fun theUsersRulesOverTheGenerated() {
        val generated = defaults("lua", "    ", 4)
        val section = rules(
            """
            {
              "tabSize": 2,
              "insertSpaces": false,
              "insertFinalNewline": null,
              "trimFinalNewlines": "yes",
              "quote_style": "single",
              "max_line_length": 100,
              "align_args": true
            }
            """.trimIndent()
        )
        val merged = FormattingRules.merge(generated, section)
        assertEquals(2, merged.tabSize)
        assertFalse(merged.insertSpaces)
        // left out: generated
        assertEquals(true, merged.trimTrailingWhitespace)
        // null: not sent
        assertNull(merged.insertFinalNewline)
        // not a boolean: generated (merge is lenient; validate is not)
        assertEquals(true, merged.trimFinalNewlines)
        assertEquals(mapOf<String, Any>("quote_style" to "single", "max_line_length" to 100, "align_args" to true), merged.properties)
    }

    @Test
    fun aTabSizeOutOfRangeIsGenerated() {
        val generated = LspFormattingOptions(tabSize = 4)
        assertEquals(4, FormattingRules.merge(generated, rules("""{"tabSize":0}""")).tabSize)
    }

    @Test
    fun rulesWrittenReadBackTheSame() {
        val options = defaults("python", "    ", 4).copy(insertFinalNewline = null)
        val text = FormattingRules.textOf(options)
        val valid = assertIs<FormattingRules.Validation.Valid>(FormattingRules.validate(text))
        assertEquals(options, FormattingRules.merge(LspFormattingOptions(tabSize = 8, insertSpaces = false), valid.rules))
    }

    @Test
    fun rightRulesPass() {
        val text = """
            {
              // comments and a trailing comma, as people copy them
              "tabSize": 2,
              "insertSpaces": true,
              "trimTrailingWhitespace": null,
              "quote_style": "single",
            }
        """.trimIndent()
        assertIs<FormattingRules.Validation.Valid>(FormattingRules.validate(text))
    }

    @Test
    fun eachWrongRuleIsSaidWithItsLine() {
        val found = problems(
            """
            {
              "tabSize": "4",
              "insertSpaces": 1,
              "insertFinalNewline": "yes",
              "trimFinalNewlines": null,
              "list": [1, 2],
              "cpp": { "tabSize": 4 },
              "": 1
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                2 to FormattingRules.Kind.TAB_SIZE,
                3 to FormattingRules.Kind.NOT_BOOLEAN,
                4 to FormattingRules.Kind.NOT_BOOLEAN_OR_NULL,
                6 to FormattingRules.Kind.NOT_PLAIN,
                7 to FormattingRules.Kind.NESTED,
                8 to FormattingRules.Kind.EMPTY_KEY,
            ),
            found.map { it.line to it.kind }
        )
    }

    @Test
    fun tabSizeIsAWholeNumberInRange() {
        for (value in listOf("0", "17", "4.5", "true", "null")) {
            assertEquals(FormattingRules.Kind.TAB_SIZE, problems("""{"tabSize": $value}""").single().kind, value)
        }
        for (value in listOf("1", "8", "16")) {
            assertIs<FormattingRules.Validation.Valid>(FormattingRules.validate("""{"tabSize": $value}"""), value)
        }
    }

    @Test
    fun notJsonOrNotAnObject() {
        val syntax = problems("{\n  \"tabSize\": 4\n  \"insertSpaces\": true\n}").single()
        assertEquals(FormattingRules.Kind.SYNTAX, syntax.kind)
        assertTrue(syntax.line > 0)
        assertEquals(FormattingRules.Kind.NOT_OBJECT, problems("[1, 2]").single().kind)
    }

    @Test
    fun aKeyWrittenTwiceIsSaidButNotOneCommentedOut() {
        assertEquals(FormattingRules.Kind.DUPLICATE, problems("{\"tabSize\": 2,\n\"tabSize\": 4}").single().kind)
        assertIs<FormattingRules.Validation.Valid>(FormattingRules.validate("{\n// \"tabSize\": 8,\n/* \"tabSize\": 3, */\n\"tabSize\": 4\n}"))
    }

    @Test
    fun commentsAreBlankedOutsideStrings() {
        val text = "{\"a\": \"// not a comment\", // one\n/* two\n */ \"b\": 1}"
        val blanked = FormattingRules.withoutComments(text)
        assertEquals(text.length, blanked.length)
        assertEquals(text.count { it == '\n' }, blanked.count { it == '\n' })
        assertTrue("// not a comment" in blanked)
        assertFalse("one" in blanked)
        assertFalse("two" in blanked)
    }

    @Test
    fun fileNamesOfLanguages() {
        assertEquals("cpp.json", FormattingRules.fileNameOf("cpp"))
        assertEquals("objective-c.json", FormattingRules.fileNameOf("objective-c"))
        assertEquals("a_b.json", FormattingRules.fileNameOf("a/b"))
        assertEquals("_hidden.json", FormattingRules.fileNameOf(".hidden"))
    }

    @Test
    fun languageIdsOfFiles() {
        assertEquals("cpp", FormattingRules.languageIdOf("a/b/main.cc"))
        assertEquals("python", FormattingRules.languageIdOf("x.py"))
        assertEquals("makefile", FormattingRules.languageIdOf("src/Makefile"))
        assertEquals("zzz", FormattingRules.languageIdOf("a.ZZZ"))
    }
}
