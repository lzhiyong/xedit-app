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

package x.editor.app

import android.content.Context

import java.io.File

/**
 * Where the app keeps its files. Three places, all the app's own (they go when it is
 * uninstalled), nothing on the shared storage:
 *
 *  - [home], `/data/data/<package>/files/home`: what the app reads and writes itself,
 *    which the user does not edit:
 *      - `treesitter/`: the highlighting resources (assets/treesitter.jsonc, `{home}`);
 *      - `lsp/friendly-snippets/`: the snippets of the completion (FriendlySnippets);
 *      - `sessions/`: the editing sessions of the documents, as JSON (EditorService);
 *      - `logs/`, `native_crash/`: the crash logs (the crash module);
 *      - `local/`: what the user brought in or made ([local]): `local/themes/` (highlight
 *        themes imported or made on ThemeSheet, HighlightThemeFiles), `local/fonts/`
 *        (fonts imported, EditorFonts). Files of earlier versions elsewhere are not moved:
 *        they go when the app is uninstalled;
 *    and the HOME of the language servers the app starts (InstalledServers).
 *  - [prefix], `/data/data/<package>/files/usr`: the app's own prefix, as Termux's
 *    `$PREFIX` (`/data/data/com.termux/files/usr`), where the toolchain is installed
 *    (`bin/`, `lib/`, `include/`, …, see Usr and ToolchainInstaller); what runs with it has
 *    it as `PREFIX` (Toolchain.environment).
 *  - [external], `/sdcard/Android/data/<package>/files`: what the user may open and edit
 *    with the editor or another app: `lsp/servers/` (a file a language server), `lsp/lsp.log`
 *    (LanguageServers.configDirectory), `snippets/` (the user's own snippets,
 *    FriendlySnippets.userDirectory). Where there is no such storage, [home].
 */
object AppDirs {

    /** The name of [home] in the app's files directory. */
    const val HOME = "home"

    /** `/data/data/<package>/files/home`, made if it is not there. */
    fun home(context: Context): File = File(context.filesDir, HOME).apply { mkdirs() }

    /**
     * `home/local`: what the user brought in or made, kept apart from what the app
     * downloads (`themes/` and `fonts/` in it). Made if it is not there.
     */
    fun local(context: Context): File = File(home(context), "local").apply { mkdirs() }

    /** The name of [prefix] in the app's files directory. */
    const val PREFIX = "usr"

    /**
     * `/data/data/<package>/files/usr`, the app's own prefix (as Termux's `$PREFIX`): where
     * the toolchain is installed. Not made here: Usr.ensure makes it with its directories.
     */
    fun prefix(context: Context): File = File(context.filesDir, PREFIX)

    /** `/sdcard/Android/data/<package>/files`; [home] if there is no external storage. */
    fun external(context: Context): File = context.getExternalFilesDir(null) ?: home(context)
}
