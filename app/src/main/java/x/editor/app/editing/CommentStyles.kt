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

package x.editor.app.editing

import x.github.module.editor.input.CommentToggle

/**
 * The comment marks of a file's language, by its name: for commenting a region out and
 * back in (EditorView.toggleComment, from the floating bar). Null for a kind of file that
 * has no comments (JSON, plain text) or that is not known.
 */
object CommentStyles {

    private val C = CommentToggle.Style("//", "/*", "*/")
    private val HASH = CommentToggle.Style("#", null, null)
    private val XML = CommentToggle.Style(null, "<!--", "-->")

    private val BY_EXTENSION: Map<String, CommentToggle.Style> = buildMap {
        for (e in listOf("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "m", "mm", "java", "kt", "kts", "scala",
            "groovy", "gradle", "js", "mjs", "cjs", "jsx", "ts", "tsx", "go", "rs", "swift", "dart", "cs", "php",
            "zig", "v", "glsl", "vert", "frag", "hlsl", "cu", "proto", "jsonc", "json5", "scss", "less", "sol", "d")) put(e, C)
        for (e in listOf("py", "pyi", "sh", "bash", "zsh", "fish", "rb", "pl", "pm", "r", "yaml", "yml", "toml",
            "cmake", "mk", "nix", "ex", "exs", "jl", "tcl", "ps1", "conf", "ini", "properties", "nim", "cr",
            "gd", "nu", "dockerfile", "awk", "editorconfig", "gitignore", "env")) put(e, HASH)
        put("css", CommentToggle.Style(null, "/*", "*/"))
        for (e in listOf("html", "htm", "xml", "xhtml", "svg", "vue", "svelte", "md", "markdown")) put(e, XML)
        put("lua", CommentToggle.Style("--", "--[[", "]]"))
        for (e in listOf("sql", "hs", "elm", "ada", "adb", "ads")) put(e, CommentToggle.Style("--", null, null))
        put("hs", CommentToggle.Style("--", "{-", "-}"))
        put("elm", CommentToggle.Style("--", "{-", "-}"))
        for (e in listOf("tex", "sty", "cls", "erl", "hrl", "m4")) put(e, CommentToggle.Style("%", null, null))
        for (e in listOf("ml", "mli")) put(e, CommentToggle.Style(null, "(*", "*)"))
        for (e in listOf("lisp", "el", "clj", "cljs", "scm", "rkt", "asm", "s")) put(e, CommentToggle.Style(";", null, null))
        put("vim", CommentToggle.Style("\"", null, null))
        put("bat", CommentToggle.Style("REM", null, null))
        put("cmd", CommentToggle.Style("REM", null, null))
        put("f90", CommentToggle.Style("!", null, null))
    }

    // files known by their whole name
    private val BY_NAME: Map<String, CommentToggle.Style> = mapOf(
        "makefile" to HASH, "gnumakefile" to HASH, "dockerfile" to HASH, "cmakelists.txt" to HASH,
        "build" to HASH, "workspace" to HASH, ".bashrc" to HASH, ".zshrc" to HASH, ".profile" to HASH,
        ".gitignore" to HASH, ".editorconfig" to HASH, ".clang-format" to HASH, "_clang-format" to HASH,
        "vimrc" to CommentToggle.Style("\"", null, null), ".vimrc" to CommentToggle.Style("\"", null, null),
    )

    /** The marks of the language of a file named [fileName] (a path or a uri's last segment will do). */
    fun forFile(fileName: String): CommentToggle.Style? {
        val name = fileName.substringAfterLast('/').substringAfterLast(':').lowercase()
        BY_NAME[name]?.let { return it }
        val extension = name.substringAfterLast('.', "")
        return BY_EXTENSION[extension]
    }
}
