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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x.github.module.lsp.LspFormattingOptions

/** The formatting rules: generated from the editor, and the user's over them. */
class FormattingRulesTest {

    @Test
    fun generatedFromTheEditorsIndentation() {
        val spaces = FormattingRules.defaults("cpp", "  ", 8)
        assertEquals(2, spaces.tabSize)
        assertTrue(spaces.insertSpaces)
        assertEquals(true, spaces.trimTrailingWhitespace)
        assertEquals(true, spaces.insertFinalNewline)
        assertEquals(true, spaces.trimFinalNewlines)

        val tabs = FormattingRules.defaults("cpp", "\t", 8)
        assertEquals(8, tabs.tabSize)
        assertFalse(tabs.insertSpaces)

        // Go indents with tabs whatever the editor does
        assertFalse(FormattingRules.defaults("go", "    ", 4).insertSpaces)
    }

    @Test
    fun theUsersRulesOverTheGenerated() {
        val generated = FormattingRules.defaults("lua", "    ", 4)
        val section = FormattingRules.parse(
            """
            {
              // comments are let through
              "lua": {
                "tabSize": 2,
                "insertSpaces": false,
                "insertFinalNewline": null,
                "trimFinalNewlines": "yes",
                "quote_style": "single",
                "max_line_length": 100,
                "align_args": true,
              },
              "broken": 3
            }
            """.trimIndent()
        )
        assertEquals(setOf("lua"), section.keys)
        val rules = FormattingRules.merge(generated, section.getValue("lua"))
        assertEquals(2, rules.tabSize)
        assertFalse(rules.insertSpaces)
        // left out: generated
        assertEquals(true, rules.trimTrailingWhitespace)
        // null: not sent
        assertNull(rules.insertFinalNewline)
        // not a boolean: generated
        assertEquals(true, rules.trimFinalNewlines)
        assertEquals(mapOf<String, Any>("quote_style" to "single", "max_line_length" to 100, "align_args" to true), rules.properties)
    }

    @Test
    fun aTabSizeOutOfRangeIsGenerated() {
        val generated = LspFormattingOptions(tabSize = 4)
        val section = FormattingRules.parse("""{"c":{"tabSize":0}}""").getValue("c")
        assertEquals(4, FormattingRules.merge(generated, section).tabSize)
    }

    @Test
    fun aSectionWrittenReadsBackTheSame() {
        val options = FormattingRules.defaults("python", "    ", 4).copy(insertFinalNewline = null)
        val section = FormattingRules.toJson(options)
        assertEquals(options, FormattingRules.merge(LspFormattingOptions(tabSize = 8, insertSpaces = false), section))
    }

    @Test
    fun languageIdsOfFiles() {
        assertEquals("cpp", FormattingRules.languageIdOf("a/b/main.cc"))
        assertEquals("python", FormattingRules.languageIdOf("x.py"))
        assertEquals("makefile", FormattingRules.languageIdOf("src/Makefile"))
        assertEquals("zzz", FormattingRules.languageIdOf("a.ZZZ"))
    }
}
