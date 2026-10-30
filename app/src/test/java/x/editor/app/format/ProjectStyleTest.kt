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

package x.editor.app.format

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x.github.module.lsp.LspFormattingOptions

/** .editorconfig and .clang-format, as the editor goes by them (see ProjectStyle). */
class ProjectStyleTest {

    @Test
    fun globsOfEditorConfig() {
        assertTrue(EditorConfig.matches("*", "src/main.c"))
        assertTrue(EditorConfig.matches("*.c", "main.c"))
        assertTrue(EditorConfig.matches("*.c", "src/deep/main.c"))
        assertFalse(EditorConfig.matches("*.c", "main.cc"))
        assertTrue(EditorConfig.matches("*.{c,h}", "inc/a.h"))
        assertTrue(EditorConfig.matches("{Makefile,*.mk}", "Makefile"))
        assertTrue(EditorConfig.matches("src/*.py", "src/a.py"))
        assertFalse(EditorConfig.matches("src/*.py", "lib/src/a.py"))
        assertFalse(EditorConfig.matches("src/*.py", "src/x/a.py"))
        assertTrue(EditorConfig.matches("src/**.py", "src/x/a.py"))
        assertTrue(EditorConfig.matches("/lib/**/*.js", "lib/a/b/c.js"))
        assertTrue(EditorConfig.matches("lib/**/*.js", "lib/c.js"))
        assertTrue(EditorConfig.matches("test[0-9].txt", "test3.txt"))
        assertFalse(EditorConfig.matches("test[!0-9].txt", "test3.txt"))
        assertTrue(EditorConfig.matches("v{1..12}.txt", "v10.txt"))
        assertFalse(EditorConfig.matches("v{1..12}.txt", "v13.txt"))
    }

    @Test
    fun theNearerFileAndTheLaterSectionWin() {
        val files = mapOf(
            "/p/.editorconfig" to """
                root = true
                [*]
                indent_style = space
                indent_size = 4
                trim_trailing_whitespace = true
                [*.go]
                indent_style = tab
            """.trimIndent(),
            "/p/web/.editorconfig" to """
                # comment
                [*.{js,ts}]
                indent_size = 2
                [*.min.js]
                trim_trailing_whitespace = unset
            """.trimIndent(),
            // above the root: not read
            "/.editorconfig" to "[*]\nindent_size = 8",
        )
        val read = { f: File -> files[f.invariantSeparatorsPath] }
        assertEquals(
            mapOf("indent_style" to "space", "indent_size" to "2", "trim_trailing_whitespace" to "true"),
            EditorConfig.propertiesFor(File("/p/web/app.ts"), read)
        )
        assertEquals(mapOf("indent_style" to "space", "indent_size" to "2"), EditorConfig.propertiesFor(File("/p/web/a.min.js"), read))
        assertEquals("tab", EditorConfig.propertiesFor(File("/p/cmd/main.go"), read)["indent_style"])
    }

    @Test
    fun editorConfigOverTheUsersRules() {
        val style = ProjectStyle(mapOf("indent_style" to "tab", "tab_width" to "8", "insert_final_newline" to "false", "trim_trailing_whitespace" to "false"))
        val options = style.applyTo(LspFormattingOptions(tabSize = 4, insertSpaces = true, trimTrailingWhitespace = true, insertFinalNewline = true, trimFinalNewlines = true))
        assertEquals(LspFormattingOptions(tabSize = 8, insertSpaces = false, trimTrailingWhitespace = false, insertFinalNewline = false, trimFinalNewlines = null), options)
        assertEquals("\t", style.indentUnit())
        assertEquals(8, style.tabWidth())
        assertEquals("  ", ProjectStyle(mapOf("indent_style" to "space", "indent_size" to "2")).indentUnit())
        assertNull(ProjectStyle().indentUnit())
        // nothing said: nothing changed
        val unchanged = LspFormattingOptions(tabSize = 3, insertSpaces = false)
        assertEquals(unchanged, ProjectStyle().applyTo(unchanged))
    }

    @Test
    fun clangFormatForTheCFamily() {
        val text = """
            ---
            BasedOnStyle: Google
            ColumnLimit: 100
            ---
            Language: Cpp
            IndentWidth: 4
            UseTab: ForIndentation
            TabWidth: 4
            ---
            Language: JavaScript
            IndentWidth: 2
            ...
        """.trimIndent()
        assertEquals(ClangFormat.Style(indentWidth = 4, tabWidth = 4, useTabs = true), ClangFormat.parse(text, "cpp"))
        assertEquals(ClangFormat.Style(indentWidth = 2, tabWidth = null, useTabs = null), ClangFormat.parse(text, "javascript"))
        // no Language section of its own: the general one, Google's width
        assertEquals(ClangFormat.Style(indentWidth = 2, tabWidth = null, useTabs = null), ClangFormat.parse(text, "java"))
        assertEquals(4, ClangFormat.parse("BasedOnStyle: WebKit", "c").indentWidth)

        val files = mapOf("/p/.clang-format" to "IndentWidth: 3\nUseTab: Never", "/p/src/.editorconfig" to "[*]\nindent_size = 8")
        val style = ProjectStyle.find(File("/p/src/a.c"), "c") { files[it.invariantSeparatorsPath] }
        assertEquals("   ", style.indentUnit())
        assertEquals(File("/p/.clang-format"), style.clangFormatFile)
        assertEquals(".editorconfig, .clang-format", style.describe())
        // not the C family: .clang-format is not looked for
        assertNull(ProjectStyle.find(File("/p/src/a.py"), "python") { files[it.invariantSeparatorsPath] }.clangFormat)
    }
}
