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

import androidx.core.content.edit

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import x.editor.app.console.ConsoleLog
import x.editor.app.download.DownloadState
import x.editor.app.download.FileDownloader
import x.editor.app.download.RemoteFile
import x.editor.app.util.FileUtils

/**
 * Installs and updates the tree-sitter resources (see TreeSitterResources).
 *
 * How an update goes, so that it never gets in the way:
 *
 *  1. **Check**: one small request per package, which reads only the headers (ETag,
 *     else date and size) and compares them with the version of what is installed.
 *  2. **Download**: into the cache, continuing a download that broke off (with
 *     If-Range: a file that changed meanwhile starts anew).
 *  3. **Stage**: the zip is checked and extracted next to what is installed
 *     (`.staged/<id>`), with a marker of its version. What is installed is not
 *     touched, so nothing is half-updated while the editor uses it.
 *  4. **Apply**: each entry at the top of the staged package (configs: `config`
 *     and `themes`) takes the place of the installed one in the target, by renames;
 *     an entry the old version had and the new one has not goes. The other packages
 *     in the same directory are not touched. A grammar library that is loaded stays
 *     loaded until the process ends, so an installed package is replaced only
 *     before the first library is loaded: at the next start, as a rule. A package
 *     that was not installed at all goes in at once (nothing uses an old one).
 *
 * What is installed is recorded in `.packages/<id>.json`: the version, and the
 * entries the package owns.
 *
 * The first start downloads what is missing in the foreground, with its progress
 * (EditorActivity); the updates are checked and downloaded in the background
 * (TreeSitterUpdateWorker). Both go through here, one at a time.
 */
object TreeSitterUpdater {

    private const val TAG = "TreeSitterUpdater"

    // in a staged package: its version
    private const val MARKER = ".package.json"

    // the installed packages: <id>.json, with the version and the entries
    private const val PACKAGES_DIR = ".packages"

    private const val STAGED_DIR = ".staged"
    private const val STAGING_DIR = ".staging"
    private const val TRASH_DIR = ".trash"

    private const val PREFS = "treesitter_update"

    // how many folders around the content of a zip are taken off at most
    private const val MAX_UNWRAP_DEPTH = 4

    // The layout the records below were written for. A record of another one (an
    // earlier version of the app) counts as nothing installed: the package is
    // downloaded again, and goes where it belongs now.
    private const val LAYOUT = 2
    private const val PREF_LAST_CHECK = "last_check"

    /**
     * A package to download: [remote] is what the server says about it now.
     * [isMissing]: nothing of it is there, neither installed nor staged.
     */
    class Update(
        val pkg: TreeSitterResources.Package,
        val remote: RemoteFile,
        val isMissing: Boolean
    )

    // one download, extraction or apply at a time, in the whole process
    private val mutex = Mutex()

    private val _applied = MutableSharedFlow<List<String>>(extraBufferCapacity = 4)

    /**
     * The ids of the packages each time some were put in place: the editor loads its
     * resources again then. Not when they wait for the next start.
     */
    val applied: SharedFlow<List<String>> = _applied.asSharedFlow()

    // #region versions

    /** What is recorded of an installed package. */
    class Installed(val version: String, val entries: List<String>)

    private fun installedFile(context: Context, pkg: TreeSitterResources.Package): File =
        File(TreeSitterResources.manifest(context).root, "$PACKAGES_DIR/${pkg.id}.json")

    /** What is recorded of the installed [pkg]; null if it is not installed. */
    fun installed(context: Context, pkg: TreeSitterResources.Package): Installed? = try {
        installedFile(context, pkg).takeIf { it.isFile }?.let { file ->
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            val version = json["version"]?.jsonPrimitive?.content ?: return@let null
            if (json["layout"]?.jsonPrimitive?.content != LAYOUT.toString()) return@let null
            val entries = json["entries"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            Installed(version, entries)
        }
    } catch (e: Exception) {
        null
    }

    private fun installedVersion(context: Context, pkg: TreeSitterResources.Package): String? =
        installed(context, pkg)?.version

    private fun writeInstalled(
        context: Context,
        pkg: TreeSitterResources.Package,
        version: String,
        entries: List<String>
    ) {
        val json = buildJsonObject {
            put("id", pkg.id)
            put("url", pkg.url)
            put("version", version)
            put("entries", JsonArray(entries.map { JsonPrimitive(it) }))
            put("layout", JsonPrimitive(LAYOUT))
            put("installedAt", JsonPrimitive(System.currentTimeMillis()))
        }
        val file = installedFile(context, pkg)
        file.parentFile?.mkdirs()
        // written whole and then renamed: never half a record
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("cannot record ${pkg.id}")
        }
    }

    /** The version of [pkg] staged for the next start; null if none is. */
    fun stagedVersion(context: Context, pkg: TreeSitterResources.Package): String? =
        readMarker(stagedDir(context, pkg))

    private fun readMarker(dir: File): String? = try {
        File(dir, MARKER).takeIf { it.isFile }?.let { file ->
            Json.parseToJsonElement(file.readText()).jsonObject["version"]?.jsonPrimitive?.content
        }
    } catch (e: Exception) {
        null
    }

    private fun writeMarker(dir: File, pkg: TreeSitterResources.Package, version: String) {
        val json = buildJsonObject {
            put("id", pkg.id)
            put("version", version)
        }
        File(dir, MARKER).writeText(json.toString())
    }

    /** Whether every package is installed or staged: highlighting can work. */
    fun isComplete(context: Context): Boolean =
        TreeSitterResources.manifest(context).packages.all {
            installedVersion(context, it) != null || stagedVersion(context, it) != null
        }

    // #endregion

    // #region check

    /**
     * What is missing (see [findMissing]).
     *
     * @property updates the packages that are not there at all, with what the
     *   server says about them
     * @property unreachable those of them the server did not answer for (no such
     *   url, an error status, no connection)
     * @property problem why there is nothing to download at all: the manifest
     *   could not be read (see TreeSitterResources.Manifest.problem)
     */
    class Missing(
        val updates: List<Update>,
        val unreachable: List<TreeSitterResources.Package>,
        val problem: String?
    )

    /**
     * The packages that are not there at all (neither installed nor staged), with
     * what the server says about them. Moves off the main thread itself.
     */
    suspend fun findMissing(context: Context): Missing = withContext(Dispatchers.IO) {
        val manifest = TreeSitterResources.manifest(context)
        val updates = ArrayList<Update>()
        val unreachable = ArrayList<TreeSitterResources.Package>()
        manifest.packages
            .filter { installedVersion(context, it) == null && stagedVersion(context, it) == null }
            .forEach { pkg ->
                val remote = FileDownloader.remoteFile(pkg.url)
                if (remote != null) {
                    updates += Update(pkg, remote, isMissing = true)
                } else {
                    Log.w(TAG, "no answer for ${pkg.url}")
                    unreachable += pkg
                }
            }
        Missing(updates, unreachable, manifest.problem)
    }

    /**
     * Every package whose version on the server is not the one installed (or staged
     * for the next start), and every one that is missing. A package the server does
     * not answer for is left out.
     */
    suspend fun findUpdates(context: Context): List<Update> = withContext(Dispatchers.IO) {
        val manifest = TreeSitterResources.manifest(context)
        manifest.problem?.let { log("manifest: $it", error = true) }
        log("checking ${manifest.packages.size} packages")
        manifest.packages.mapNotNull { pkg ->
            val remote = FileDownloader.remoteFile(pkg.url)
            if (remote == null) {
                log("${pkg.id}: no answer for ${pkg.url}", error = true)
                return@mapNotNull null
            }
            val local = stagedVersion(context, pkg) ?: installedVersion(context, pkg)
            when {
                local == null -> Update(pkg, remote, isMissing = true).also {
                    log("${pkg.id}: not installed, ${remote.version ?: "no version"} on the server")
                }
                // a server that tells no version: nothing to compare
                remote.version == null -> {
                    log("${pkg.id}: the server tells no version (no ETag, no date): cannot compare")
                    null
                }
                remote.version != local -> Update(pkg, remote, isMissing = false).also {
                    log("${pkg.id}: $local -> ${remote.version}")
                }
                else -> {
                    log("${pkg.id}: up to date ($local)")
                    null
                }
            }
        }
    }

    /**
     * A line about the resources, in the console (the X Editor channel) and in logcat:
     * what a check found, what was downloaded, what failed.
     */
    fun log(message: String, error: Boolean = false) {
        if (error) Log.w(TAG, message) else Log.i(TAG, message)
        ConsoleLog.append(
            ConsoleLog.APP,
            if (error) ConsoleLog.Level.WARNING else ConsoleLog.Level.INFO,
            "tree-sitter: $message"
        )
    }

    /** Whether the last check is longer ago than the interval of the manifest. */
    fun isCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(PREF_LAST_CHECK, 0L)
        val interval = TreeSitterResources.manifest(context).checkIntervalHours * 3_600_000L
        return System.currentTimeMillis() - last >= interval
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(PREF_LAST_CHECK, System.currentTimeMillis())
        }
    }

    // #endregion

    // #region download and stage

    /**
     * Downloads [update] and stages it (see the class). Its progress goes to
     * [onProgress] (0..100), on the main thread.
     *
     * @throws IOException if the download breaks off (it is continued the next
     *   time) or the zip is no good (it is deleted, and the next time starts anew)
     */
    @Throws(IOException::class)
    suspend fun download(context: Context, update: Update, onProgress: (Int) -> Unit = {}) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val pkg = update.pkg
                val cacheDir = File(context.cacheDir, "treesitter").apply { mkdirs() }
                val archive = File(cacheDir, "${pkg.id}.zip")
                val archiveVersion = File(cacheDir, "${pkg.id}.zip.version")

                // A part of the same version is continued; one of another is not.
                val version = update.remote.version
                val sameVersion = version != null && archiveVersion.isFile && archiveVersion.readText() == version
                val startByte = if (archive.isFile && sameVersion) archive.length() else 0L
                if (startByte == 0L) archive.delete()
                if (version != null) archiveVersion.writeText(version) else archiveVersion.delete()

                var downloaded: String? = null
                var failure: Throwable? = null
                FileDownloader.download(pkg.url, archive, startByte, ifRange = update.remote.etag)
                    .collect { state ->
                        when (state) {
                            is DownloadState.InProgress -> withContext(Dispatchers.Main) {
                                onProgress(state.progress)
                            }
                            is DownloadState.Success -> downloaded = state.etag
                            is DownloadState.Error -> failure = state.throwable
                        }
                    }
                failure?.let { throw if (it is IOException) it else IOException(it.message ?: it.toString(), it) }
                if (downloaded == null) throw IOException("the download of ${pkg.url} did not finish")

                try {
                    // the version as the next check will see it (see findUpdates)
                    val stagedVersion = version
                        ?: downloaded?.takeIf { it.isNotEmpty() }
                        ?: archive.length().toString()
                    stage(context, pkg, archive, stagedVersion)
                } finally {
                    // staged: no longer needed. No good: must not be continued.
                    archive.delete()
                    archiveVersion.delete()
                }
            }
        }

    // Extracts [archive] next to the installed package, as `.staged/<id>`.
    private fun stage(context: Context, pkg: TreeSitterResources.Package, archive: File, version: String) {
        // a zip that cannot be opened, or is empty, is no good
        val entries = try {
            ZipFile(archive).use { it.size() }
        } catch (e: Exception) {
            throw IOException("not a zip: ${pkg.url}", e)
        }
        if (entries == 0) throw IOException("an empty zip: ${pkg.url}")

        val root = TreeSitterResources.manifest(context).root
        val staging = File(root, "$STAGING_DIR/${pkg.id}")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("cannot create ${staging.path}")
        try {
            FileUtils.unzipFile(archive, staging)
            val content = unwrap(staging, pkg, archive)
            writeMarker(content, pkg, version)

            val staged = stagedDir(context, pkg)
            staged.parentFile?.mkdirs()
            staged.deleteRecursively()
            if (!content.renameTo(staged)) throw IOException("cannot stage ${pkg.id}")
        } finally {
            staging.deleteRecursively()
        }
    }

    // A zip that has everything in one folder: the content of that folder is what
    // goes in. A folder is taken off when it is named like a wrapper (the target, the
    // package, the zip, "queries", "query", ...) or holds nothing but folders (then it
    // is not the folder of one grammar, which holds files); again for a folder in a
    // folder, a few levels deep ("lib/aarch64/").
    private fun unwrap(dir: File, pkg: TreeSitterResources.Package, archive: File): File {
        val wrapperNames = setOf(
            pkg.target.name, pkg.id, archive.nameWithoutExtension,
            pkg.url.substringAfterLast('/').removeSuffix(".zip"),
            "queries", "query", "languages", "language", "lib", "libs", "configs", "tree-sitter", "treesitter"
        )
        var content = dir
        repeat(MAX_UNWRAP_DEPTH) {
            val children = content.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && it.name != "__MACOSX" }
            val only = children.singleOrNull()?.takeIf { it.isDirectory } ?: return content
            val onlyFolders = only.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
                .let { inner -> inner.isNotEmpty() && inner.all { it.isDirectory } }
            if (only.name !in wrapperNames && !onlyFolders) return content
            content = only
        }
        return content
    }

    private fun stagedDir(context: Context, pkg: TreeSitterResources.Package): File =
        File(TreeSitterResources.manifest(context).root, "$STAGED_DIR/${pkg.id}")

    // #endregion

    // #region apply

    /**
     * Puts the staged packages in place: those not installed at all, and, before any
     * grammar library was loaded in this process, the updates of installed ones too.
     * An update that has to wait stays staged for the next start. Not on the main
     * thread.
     *
     * @return the ids of the packages put in place
     */
    suspend fun applyStaged(context: Context): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = TreeSitterResources.manifest(context)
            val trashRoot = File(manifest.root, TRASH_DIR)
            val applied = ArrayList<String>()

            for (pkg in manifest.packages) {
                val staged = stagedDir(context, pkg)
                val version = readMarker(staged)
                if (version == null) {
                    // half a staging, from a process that died: no good
                    staged.deleteRecursively()
                    continue
                }
                val previous = installed(context, pkg)
                // a grammar in use stays until the next start; the snippets are only read
                if (previous != null && TreeSitterResources.isAnyLibraryLoaded && pkg.id != TreeSitterResources.SNIPPETS) continue

                try {
                    // Nothing recorded (or of another layout), in a directory of its own:
                    // what is in there is an older, maybe misplaced, extraction. Away.
                    if (previous == null && pkg.target != manifest.root && pkg.target.exists()) {
                        trashRoot.mkdirs()
                        if (!pkg.target.renameTo(File(trashRoot, "${pkg.id}-old-${System.nanoTime()}"))) {
                            pkg.target.deleteRecursively()
                        }
                    }
                    // the directories of the other packages, where they lie in this target
                    val reserved = manifest.packages
                        .filter { it !== pkg && it.target.parentFile == pkg.target }
                        .map { it.target.name }
                        .toSet()
                    val entries = replaceEntries(pkg, staged, previous?.entries.orEmpty(), reserved, trashRoot)
                    writeInstalled(context, pkg, version, entries)
                    applied += pkg.id
                } catch (e: IOException) {
                    Log.w(TAG, "cannot put ${pkg.id} in place", e)
                }
                staged.deleteRecursively()
            }

            trashRoot.deleteRecursively()
            if (applied.isNotEmpty()) {
                Log.i(TAG, "applied: $applied")
                _applied.tryEmit(applied)
            }
            applied
        }
    }

    /**
     * Moves the entries at the top of [staged] into the target of [pkg], in place of
     * the ones there; those of [previousEntries] that the new version has not go too.
     * What is replaced goes to [trashRoot]. The other entries of the target (other
     * packages, in the root) are not touched: an entry named like one of them
     * ([reserved]) is left out.
     *
     * @return the entries the package owns now
     */
    @Throws(IOException::class)
    private fun replaceEntries(
        pkg: TreeSitterResources.Package,
        staged: File,
        previousEntries: List<String>,
        reserved: Set<String>,
        trashRoot: File
    ): List<String> {
        val target = pkg.target
        if (!target.isDirectory && !target.mkdirs()) throw IOException("cannot create ${target.path}")

        val newEntries = staged.listFiles().orEmpty()
            .map { it.name }
            // not the marker, hidden files, macOS leftovers, nor what is another package's
            .filter { it != MARKER && !it.startsWith(".") && it != "__MACOSX" && it !in reserved }
            .sorted()

        // out of the way: the old version of each entry, and what the old one had
        // that the new one has not
        trashRoot.mkdirs()
        for (name in (previousEntries + newEntries).toSet() - reserved) {
            val old = File(target, name)
            if (!old.exists()) continue
            val away = File(trashRoot, "${pkg.id}-${System.nanoTime()}-$name")
            if (!old.renameTo(away) && !old.deleteRecursively()) {
                throw IOException("cannot replace ${old.path}")
            }
        }

        for (name in newEntries) {
            val from = File(staged, name)
            val to = File(target, name)
            // a target on another storage (the shared one, {lsp}): no rename there, a copy
            if (!from.renameTo(to)) {
                val copied = try {
                    from.copyRecursively(to, overwrite = true)
                } catch (e: Exception) {
                    throw IOException("cannot move $name of ${pkg.id} in place", e)
                }
                if (!copied) throw IOException("cannot move $name of ${pkg.id} in place")
                from.deleteRecursively()
            }
        }
        return newEntries
    }

    // #endregion
}
