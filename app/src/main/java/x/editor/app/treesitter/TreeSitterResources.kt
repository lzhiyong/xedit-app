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

package x.editor.app.treesitter

import android.content.Context
import android.util.Log

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

import x.editor.app.AppDirs
import x.editor.app.util.DeviceUtils

/**
 * Where the tree-sitter resources are, and where they come from.
 *
 * They are packages, downloaded as zip files (see `assets/treesitter.json`, which has
 * the urls; none is in the code), all in the app's home, `files/home` (AppDirs):
 *
 *  - `configs`: `config/filetypes.json` (which grammar for which file) and
 *    `themes/` (the highlight themes), extracted to `files/home/treesitter` itself;
 *  - `languages`: the grammar libraries of the device's architecture, extracted to
 *    `files/home/treesitter/<arch>` (aarch64, ...);
 *  - `queries`: the queries of each grammar (`<grammar>/highlights.scm`, ...),
 *    extracted to `files/home/treesitter/queries`;
 *  - `snippets`: friendly-snippets, for the completion, extracted to
 *    `files/home/lsp/friendly-snippets` (see FriendlySnippets).
 *
 * So the layout is:
 *
 * ```
 * files/home/treesitter/
 *     config/filetypes.json
 *     themes/<name>.json
 *     aarch64/lib<grammar>.so
 *     queries/<grammar>/highlights.scm
 * files/home/lsp/friendly-snippets/
 *     package.json
 *     snippets/...
 * ```
 *
 * A package owns the entries at the top of its zip in its target (configs:
 * `config` and `themes`); what is installed, and which entries, is kept in
 * `files/home/treesitter/.packages/<id>.json` (see TreeSitterUpdater). Files of
 * another layout (an earlier version: `files/treesitter`) are not moved: they stay
 * until the app is uninstalled.
 */
object TreeSitterResources {

    private const val TAG = "TreeSitterResources"

    private const val MANIFEST_ASSET = "treesitter.json"

    // the layout when the asset says nothing (or is not there): files/treesitter
    private const val DEFAULT_ROOT = "treesitter"

    const val CONFIGS = "configs"
    const val LANGUAGES = "languages"
    const val QUERIES = "queries"

    /** A package: [url] to download, [target] the directory it is extracted into. */
    data class Package(val id: String, val url: String, val target: File)

    /**
     * What the asset says, with {arch} filled in.
     *
     * @property problem why the asset could not be read (missing, not JSON, ...);
     *   null if it was. Without it there are no packages to download, but the
     *   layout is the default one, so what is installed still works.
     */
    class Manifest(
        val root: File,
        val packages: List<Package>,
        val checkIntervalHours: Long,
        val meteredMaxBytes: Long,
        val problem: String? = null
    ) {
        fun packageOf(id: String): Package? = packages.firstOrNull { it.id == id }
    }

    @Volatile
    private var manifest: Manifest? = null

    /**
     * The manifest from the assets; read once. Never throws: an asset that is missing
     * or broken gives a manifest without packages, which says why ([Manifest.problem]).
     */
    fun manifest(context: Context): Manifest {
        manifest?.let { return it }
        synchronized(this) {
            manifest?.let { return it }
            val appContext = context.applicationContext
            val read = try {
                readManifest(appContext)
            } catch (e: Exception) {
                // IOException (not in the APK), SerializationException, IllegalArgumentException
                Log.e(TAG, "cannot read assets/$MANIFEST_ASSET", e)
                Manifest(
                    root = File(AppDirs.home(appContext), DEFAULT_ROOT),
                    packages = emptyList(),
                    checkIntervalHours = 12L,
                    meteredMaxBytes = 2L shl 20,
                    problem = "assets/$MANIFEST_ASSET: ${e.message ?: e.javaClass.simpleName}"
                )
            }
            return read.also { manifest = it }
        }
    }

    private fun readManifest(context: Context): Manifest {
        val arch = DeviceUtils.getArchName()
        val json = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(json).jsonObject
        val home = AppDirs.home(context).path
        // the root: "{home}/treesitter", or a name in the app's home
        val rootDir = (root["root"]?.jsonPrimitive?.content ?: DEFAULT_ROOT)
            .replace(HOME_PLACEHOLDER, home)
            .let { if (it.startsWith("/")) File(it) else File(home, it) }
        val packages = root["packages"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            fun field(name: String) = item[name]?.jsonPrimitive?.content
                ?.replace("{arch}", arch)
                ?.replace(HOME_PLACEHOLDER, home)
                ?: throw IllegalArgumentException("$MANIFEST_ASSET: a package without $name")
            Package(field("id"), field("url"), targetDir(rootDir, field("target")))
        }
        val update = root["update"]?.jsonObject
        return Manifest(
            root = rootDir,
            packages = packages,
            checkIntervalHours = update?.get("checkIntervalHours")?.jsonPrimitive?.intOrNull?.toLong() ?: 12L,
            meteredMaxBytes = update?.get("meteredMaxBytes")?.jsonPrimitive?.longOrNull ?: (2L shl 20)
        )
    }

    // "." (or nothing): the root itself; an absolute path ({home}/...) as it is
    private fun targetDir(root: File, target: String): File = when {
        target.isEmpty() || target == "." -> root
        target.startsWith("/") -> File(target)
        else -> File(root, target)
    }

    /** In the root and the targets: the app's home, files/home (AppDirs). */
    private const val HOME_PLACEHOLDER = "{home}"

    /**
     * The id of the package of snippets (friendly-snippets, in `{home}/lsp/friendly-snippets`):
     * no grammar library in it, so an update of it goes in at once (see TreeSitterUpdater).
     */
    const val SNIPPETS = "snippets"

    // #region the layout

    /** Where the configs package goes: the root, files/treesitter. */
    fun configsDir(context: Context): File = dirOf(context, CONFIGS, ".")

    fun fileTypesFile(context: Context): File = File(configsDir(context), "config/filetypes.json")

    fun themesDir(context: Context): File = File(configsDir(context), "themes")

    fun themeFile(context: Context, name: String): File = File(themesDir(context), "$name.json")

    fun languagesDir(context: Context): File = dirOf(context, LANGUAGES, DeviceUtils.getArchName())

    fun queriesDir(context: Context): File = dirOf(context, QUERIES, "queries")

    private fun dirOf(context: Context, id: String, fallback: String): File {
        val manifest = manifest(context)
        return manifest.packageOf(id)?.target ?: targetDir(manifest.root, fallback)
    }

    // #endregion

    // #region the grammar libraries in this process

    private val librariesLoaded = AtomicBoolean(false)

    /**
     * Whether a grammar library was loaded into this process. A library stays loaded
     * until the process ends, whatever happens to its file: from then on an update
     * of the installed resources waits for the next start (see TreeSitterUpdater).
     */
    val isAnyLibraryLoaded: Boolean
        get() = librariesLoaded.get()

    /** Loads the grammar library [library] (System.load), and remembers that one was. */
    fun loadLibrary(library: File): Boolean = try {
        System.load(library.absolutePath)
        librariesLoaded.set(true)
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "cannot load ${library.path}", e)
        false
    }

    // #endregion
}
