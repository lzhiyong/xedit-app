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
package x.editor.app.completion

import android.content.Context
import android.util.Log
import x.editor.app.completion.JsonSchema.Shape
import x.editor.app.lsp.LanguageServerSpec
import x.editor.app.shortcut.ShortcutCommand
import x.editor.app.shortcut.ShortcutGroup
import x.editor.app.config.Templates

/**
 * What may be written in each file edited on a sheet of the developer tools (see
 * SheetCompletion): the keys and values its reader knows (as app/CONFIG.md and
 * app/TOOLCHAIN.md say them), and what the templates and examples the app ships have
 * (learned from them, so that what they say is offered as it is written there). Made once
 * each, the first time a sheet's editor asks.
 */
internal object SheetSchemas {

    private const val TAG = "SheetSchemas"

    @Volatile private var toolchain: JsonSchema? = null
    @Volatile private var languageServer: JsonSchema? = null
    @Volatile private var formatting: JsonSchema? = null
    @Volatile private var snippets: JsonSchema? = null
    @Volatile private var keymap: JsonSchema? = null
    @Volatile private var theme: JsonSchema? = null

    private val BOOLEANS = arrayOf("true", "false")

    /** packages.json (ToolchainSheet). */
    fun toolchain(context: Context): JsonSchema = toolchain ?: JsonSchema()
        .key("", "packages", Shape.ARRAY)
        .keys(
            "packages/[]",
            "id" to Shape.STRING, "name" to Shape.STRING, "kind" to Shape.STRING, "version" to Shape.STRING,
            "provides" to Shape.ARRAY, "version_command" to Shape.STRING,
            "source" to Shape.STRING, "sha256" to Shape.STRING, "abi" to Shape.OBJECT,
            "description" to Shape.STRING, "enabled" to Shape.BOOLEAN,
            "strip" to Shape.NUMBER, "into" to Shape.STRING, "bin" to Shape.OBJECT, "env" to Shape.OBJECT,
        )
        .values("packages/[]", "kind", "server", "compiler", "tool")
        .values("packages/[]", "enabled", *BOOLEANS, raw = true)
        .values("packages/[]", "source", "https://", "file://", "content://")
        .keys("packages/[]/abi", "arm64-v8a" to Shape.OBJECT, "armeabi-v7a" to Shape.OBJECT, "x86_64" to Shape.OBJECT, "x86" to Shape.OBJECT)
        .keys("packages/[]/abi/*", "source" to Shape.STRING, "sha256" to Shape.STRING)
        .values("packages/[]/abi/*", "source", "https://", "file://", "content://")
        .apply {
            learnAsset(context, Templates.TOOLCHAIN)
            learnAsset(context, Templates.TOOLCHAIN_ENTRY)
        }
        .also { toolchain = it }

    /** A language server, servers/<id>.json (LanguageServersSheet). */
    fun languageServer(context: Context): JsonSchema = languageServer ?: JsonSchema()
        .keys(
            "",
            "enabled" to Shape.BOOLEAN, "extensions" to Shape.ARRAY, "connection" to Shape.OBJECT,
            "languageIds" to Shape.OBJECT, "root" to Shape.STRING, "rootMarkers" to Shape.ARRAY,
            "initializationOptions" to Shape.OBJECT, "initializeTimeout" to Shape.NUMBER,
        )
        .values("", "enabled", *BOOLEANS, raw = true)
        .values("", "initializeTimeout", "60", "120", raw = true)
        .values("extensions", JsonSchema.ITEMS, *LanguageServerSpec.DEFAULT_LANGUAGE_IDS.keys.toTypedArray())
        .values("languageIds", JsonSchema.ANY, *LanguageServerSpec.DEFAULT_LANGUAGE_IDS.values.distinct().toTypedArray())
        .values("rootMarkers", JsonSchema.ITEMS, *LanguageServerSpec.DEFAULT_ROOT_MARKERS.toTypedArray())
        .keys(
            "connection",
            "type" to Shape.STRING, "command" to Shape.ARRAY, "env" to Shape.OBJECT,
            "host" to Shape.STRING, "port" to Shape.NUMBER, "termux" to Shape.OBJECT,
        )
        .values("connection", "type", "auto", "socket", "process")
        .values("connection", "host", "127.0.0.1", "localhost")
        .values("connection", "port", "5001", "5002", raw = true)
        .keys("connection/env", "LD_LIBRARY_PATH" to Shape.STRING, "PATH" to Shape.STRING, "HOME" to Shape.STRING)
        .keys("connection/termux", "command" to Shape.STRING, "arguments" to Shape.ARRAY, "workdir" to Shape.STRING)
        .apply {
            learnAsset(context, Templates.LSP_SERVER)
            for (name in assetNames(context, Templates.LSP_PRESETS)) learnAsset(context, "${Templates.LSP_PRESETS}/$name")
        }
        .also { languageServer = it }

    /** The rules of a language, formatting/<language>.json (FormattingSheet). */
    fun formatting(context: Context): JsonSchema = formatting ?: JsonSchema()
        .keys(
            "",
            "tabSize" to Shape.NUMBER, "insertSpaces" to Shape.BOOLEAN,
            "trimTrailingWhitespace" to Shape.BOOLEAN, "insertFinalNewline" to Shape.BOOLEAN,
            "trimFinalNewlines" to Shape.BOOLEAN,
        )
        .values("", "tabSize", "2", "4", "8", raw = true)
        .values("", "insertSpaces", *BOOLEANS, raw = true)
        // the white space rules: null leaves them to the server
        .values("", "trimTrailingWhitespace", *BOOLEANS, "null", raw = true)
        .values("", "insertFinalNewline", *BOOLEANS, "null", raw = true)
        .values("", "trimFinalNewlines", *BOOLEANS, "null", raw = true)
        .apply {
            learnAsset(context, Templates.FORMATTING_LANGUAGE)
            for (name in assetNames(context, Templates.FORMATTING_PRESETS)) learnAsset(context, "${Templates.FORMATTING_PRESETS}/$name")
        }
        .also { formatting = it }

    /** The user's snippets of a language, snippets/<language>.json (SnippetsSheet), as VS Code writes them. */
    fun snippets(context: Context): JsonSchema = snippets ?: JsonSchema()
        .keys(
            "*",
            "prefix" to Shape.STRING, "body" to Shape.STRING, "description" to Shape.STRING, "scope" to Shape.STRING,
        )
        // what a body may say: Tab stops and the variables of VS Code
        .values(
            "*", "body",
            "\$0", "\${1:default}", "\$TM_FILENAME", "\$TM_FILENAME_BASE", "\$TM_SELECTED_TEXT", "\$CLIPBOARD",
            "\$CURRENT_YEAR", "\$CURRENT_MONTH", "\$CURRENT_DATE", "\$UUID", "\$LINE_COMMENT",
        )
        .values(
            "*/body", JsonSchema.ITEMS,
            "\$0", "\${1:default}", "\$TM_SELECTED_TEXT", "\$CLIPBOARD", "\$LINE_COMMENT",
        )
        .apply { learnAsset(context, Templates.SNIPPETS_LANGUAGE) { path -> if (path.isEmpty()) "" else "*" } }
        .also { snippets = it }

    /** keymap.json (ShortcutsSheet): its actions with what they do, in [context]'s language. */
    fun keymap(context: Context): JsonSchema = keymap ?: JsonSchema()
        .keys(
            "",
            "version" to Shape.NUMBER, "leader" to Shape.STRING, "timeoutlen" to Shape.NUMBER,
            "groups" to Shape.ARRAY, "keymaps" to Shape.ARRAY,
        )
        .values("", "leader", "<C-k>", "<Space>", "<A-k>")
        .values("", "timeoutlen", "1000", "500", raw = true)
        .keys(
            "keymaps/[]",
            "keys" to Shape.STRING, "action" to Shape.STRING, "insert" to Shape.STRING,
            "desc" to Shape.STRING, "group" to Shape.STRING,
        )
        .apply {
            for (command in ShortcutCommand.entries) {
                values("keymaps/[]", "action", command.id, detail = context.getString(command.title))
            }
            values("keymaps/[]", "action", "nop")
            values("keymaps/[]", "group", *ShortcutGroup.entries.map { it.id }.toTypedArray(), ShortcutGroup.INSERT_ID)
            values("keymaps/[]", "keys", "<leader>", "<C-", "<A-", "<C-S-", "<D-", "<F5>")
            values("keymaps/[]", "insert", "\$0", "\${TM_SELECTED_TEXT}", "\${CLIPBOARD}")
            learnAsset(context, Templates.KEYMAP)
        }
        .also { keymap = it }

    /** A highlight theme (ThemeSheet): the editor's colors, the captures, and their styles. */
    fun theme(context: Context): JsonSchema = theme ?: JsonSchema()
        .keys("", "meta" to Shape.OBJECT, "editor" to Shape.OBJECT, "captures" to Shape.OBJECT)
        .keys("meta", "colorscheme" to Shape.STRING, "background" to Shape.STRING)
        .values("meta", "background", "dark", "light")
        .keys("editor/*", "fg" to Shape.STRING, "bg" to Shape.STRING)
        .keys(
            "captures/*",
            "fg" to Shape.STRING, "bg" to Shape.STRING, "bold" to Shape.BOOLEAN, "italic" to Shape.BOOLEAN,
            "underline" to Shape.BOOLEAN, "strikethrough" to Shape.BOOLEAN,
        )
        .values("captures/*", "bold", *BOOLEANS, raw = true)
        .values("captures/*", "italic", *BOOLEANS, raw = true)
        .values("captures/*", "underline", *BOOLEANS, raw = true)
        .values("captures/*", "strikethrough", *BOOLEANS, raw = true)
        .apply {
            // the colors and captures of the template, any capture's styles under captures/*
            learnAsset(context, Templates.THEME) { path ->
                when {
                    path.size >= 2 && (path[0] == "captures" || path[0] == "editor") -> path[0] + "/*"
                    else -> path.joinToString("/")
                }
            }
            // a capture may be a link to another: "@keyword"
            val captures = keysAt(listOf("captures")).map { "@" + it.name }
            values("captures", JsonSchema.ANY, *captures.toTypedArray())
        }
        .also { theme = it }

    // #region assets

    private fun JsonSchema.learnAsset(
        context: Context,
        path: String,
        general: (List<String>) -> String = { it.joinToString("/") },
    ) {
        try {
            val text = Templates.read(context, path)
            learn(text, general)
        } catch (e: Exception) {
            // not in the APK: what the schema says itself is still there
            Log.w(TAG, "cannot read $path: $e")
        }
    }

    private fun assetNames(context: Context, directory: String): List<String> =
        runCatching { context.assets.list(Templates.assetPath(directory))?.toList() }.getOrNull().orEmpty()
            .filter { it.endsWith(".json") || it.endsWith(".jsonc") }

    // #endregion
}
