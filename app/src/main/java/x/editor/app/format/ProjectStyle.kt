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

import x.github.module.lsp.LspFormattingOptions
import java.io.File

/**
 * The style a project sets for a file in files of its own, as far as the editor goes by it:
 *
 *  - `.editorconfig` ([EditorConfig]): the indentation (`indent_style`, `indent_size`,
 *    `tab_width`), `trim_trailing_whitespace`, `insert_final_newline`. They are what the
 *    editor indents with while typing, and they go over the user's formatting rules
 *    (formatting.json) in what is sent to the server: the project's style wins, as in
 *    VS Code and nvim.
 *  - `.clang-format` / `_clang-format`, for the C family: `IndentWidth`, `TabWidth`,
 *    `UseTab` (and the width of its `BasedOnStyle`). The editor indents with them while
 *    typing; formatting itself is clangd's, which reads the file (from the directory of
 *    the document up), whatever options it is sent.
 *
 * Other formatters' files (rustfmt.toml, pyproject.toml, .prettierrc, stylua.toml) are
 * read by their servers alone.
 */
data class ProjectStyle(
    /** The properties `.editorconfig` files give the file (none: empty). */
    val editorConfig: Map<String, String> = emptyMap(),
    /** The `.clang-format` that applies, and what it says of indentation. */
    val clangFormat: ClangFormat.Style? = null,
    val clangFormatFile: File? = null,
) {

    /** One level of indentation for typing: `"\t"` or spaces; null if the project says nothing. */
    fun indentUnit(): String? {
        clangFormat?.let { style ->
            val width = style.indentWidth ?: return@let
            return if (style.useTabs == true) "\t" else " ".repeat(width.coerceIn(1, 16))
        }
        val style = editorConfig["indent_style"]
        val size = indentSize()
        return when {
            style == "tab" -> "\t"
            style == "space" -> " ".repeat((size ?: 4).coerceIn(1, 16))
            size != null && editorConfig["indent_size"] != "tab" -> " ".repeat(size.coerceIn(1, 16))
            else -> null
        }
    }

    /** How wide a tab is shown; null if the project says nothing. */
    fun tabWidth(): Int? =
        clangFormat?.tabWidth ?: editorConfig["tab_width"]?.toIntOrNull()
            ?: editorConfig["indent_size"]?.toIntOrNull()?.takeIf { editorConfig["indent_style"] == "tab" }

    // indent_size, or tab_width when it is `tab`
    private fun indentSize(): Int? {
        val size = editorConfig["indent_size"] ?: return null
        return if (size == "tab") editorConfig["tab_width"]?.toIntOrNull() else size.toIntOrNull()
    }

    /** [options] with what the project's files say over them. */
    fun applyTo(options: LspFormattingOptions): LspFormattingOptions {
        var result = options
        val style = editorConfig["indent_style"]
        if (style == "tab" || style == "space") result = result.copy(insertSpaces = style == "space")
        val size = if (style == "tab") tabWidth() ?: indentSize() else indentSize() ?: tabWidth()
        size?.takeIf { it in 1..16 }?.let { result = result.copy(tabSize = it) }
        bool("trim_trailing_whitespace")?.let { result = result.copy(trimTrailingWhitespace = it) }
        bool("insert_final_newline")?.let { insert ->
            // no final newline wanted: nor are the empty lines before it taken off
            result = result.copy(insertFinalNewline = insert, trimFinalNewlines = if (insert) result.trimFinalNewlines else null)
        }
        // the C family: as .clang-format says (clangd goes by the file anyway)
        clangFormat?.let { cf ->
            cf.useTabs?.let { result = result.copy(insertSpaces = !it) }
            (cf.indentWidth ?: cf.tabWidth)?.takeIf { it in 1..16 }?.let { result = result.copy(tabSize = it) }
        }
        return result
    }

    private fun bool(key: String): Boolean? = when (editorConfig[key]) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Where they are read from: the files that apply, for the user to know. */
    fun describe(): String? = listOfNotNull(
        EditorConfig.FILE.takeIf { editorConfig.isNotEmpty() },
        clangFormatFile?.name,
    ).joinToString(", ").ifEmpty { null }

    companion object {

        // the languages clang-format is for
        private val C_FAMILY = setOf("c", "cpp", "objective-c", "objective-cpp", "cuda", "proto", "java", "javascript", "csharp")

        /**
         * The style of [file] of [languageId], from the files above it as [read] gives them
         * (null: not there or not readable). Not on the main thread.
         */
        fun find(file: File, languageId: String, read: (File) -> String?): ProjectStyle {
            val editorConfig = EditorConfig.propertiesFor(file, read)
            var clangFile: File? = null
            var clang: ClangFormat.Style? = null
            if (languageId.lowercase() in C_FAMILY) {
                var directory = file.absoluteFile.normalize().parentFile
                while (directory != null && clang == null) {
                    for (name in ClangFormat.FILES) {
                        val candidate = File(directory, name)
                        val text = read(candidate) ?: continue
                        clang = ClangFormat.parse(text, languageId)
                        clangFile = candidate
                        break
                    }
                    directory = directory.parentFile
                }
            }
            return ProjectStyle(editorConfig, clang, clangFile)
        }
    }
}
