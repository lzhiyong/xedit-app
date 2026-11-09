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

package x.editor.app.config

import android.content.Context

import androidx.annotation.WorkerThread

/**
 * The templates of the developer tools, all under assets/templates (one place, one way of
 * writing placeholders: TemplateText):
 *
 * ```
 * templates/lsp/server.jsonc            a new language server
 * templates/lsp/presets/<id>.jsonc      a server the app knows (clangd, pyright, …)
 * templates/formatting/language.jsonc   the rules of a language
 * templates/formatting/presets/<id>.jsonc  a language's own (go, make, makefile)
 * templates/snippets/language.jsonc     a file of snippets
 * templates/theme/theme.jsonc           a highlight theme
 * templates/keymap/keymap.jsonc         keymap.json, whole
 * templates/keymap/entry.jsonc          one mapping (New on the shortcuts panel)
 * templates/toolchain/packages.jsonc    packages.json, whole
 * templates/toolchain/entry.jsonc       one toolchain (New on the toolchain panel)
 * ```
 */
internal object Templates {

    const val ROOT = "templates"

    const val LSP_SERVER = "lsp/server.jsonc"
    const val LSP_PRESETS = "lsp/presets"
    const val FORMATTING_LANGUAGE = "formatting/language.jsonc"
    const val FORMATTING_PRESETS = "formatting/presets"
    const val SNIPPETS_LANGUAGE = "snippets/language.jsonc"
    const val THEME = "theme/theme.jsonc"
    const val KEYMAP = "keymap/keymap.jsonc"
    const val KEYMAP_ENTRY = "keymap/entry.jsonc"
    const val TOOLCHAIN = "toolchain/packages.jsonc"
    const val TOOLCHAIN_ENTRY = "toolchain/entry.jsonc"

    /** The extension of a template. */
    const val EXTENSION = ".jsonc"

    /** The path of [path] (relative to templates/) in the assets. */
    fun assetPath(path: String): String = "$ROOT/$path"

    /** The template at [path] (relative to templates/), as it is shipped. Reads the asset. */
    @WorkerThread
    fun read(context: Context, path: String): String =
        context.assets.open(assetPath(path)).bufferedReader().use { it.readText() }

    /** The names (without .jsonc) of the templates in the directory [path] (relative to templates/). */
    fun namesIn(context: Context, path: String): List<String> = runCatching {
        context.assets.list(assetPath(path)).orEmpty()
            .filter { it.endsWith(EXTENSION) }
            .map { it.removeSuffix(EXTENSION) }
            .sorted()
    }.getOrDefault(emptyList())
}
