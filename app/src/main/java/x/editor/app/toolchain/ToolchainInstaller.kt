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

package x.editor.app.toolchain

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import x.editor.app.console.ConsoleLog
import x.editor.app.download.DownloadState
import x.editor.app.download.FileDownloader
import x.editor.app.lsp.FormattingRules
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.lsp.LanguageServers
import x.editor.app.lsp.LocalFiles
import x.editor.app.util.FileUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import x.editor.app.config.TemplateText
import x.editor.app.config.Templates

/**
 * Installs the packages of the toolchain into the app's [Usr], and takes them away again.
 *
 *  1. **Fetch** the source (PackageSource): downloaded into the cache (a download that
 *     stopped goes on where it stopped), a file of the device read where it is (through
 *     the folder the user granted, if the app may not read it by its path), a picked file
 *     copied.
 *  2. **Check** its SHA-256, if the package gives one.
 *  3. **Unpack** it (Archives) into a staging directory, then move it into usr (under
 *     `into`), what the package had installed before taken away first.
 *  4. **Link** its programs into usr/bin, and write its manifest
 *     (`usr/var/lib/xedit/packages/<id>.json`): what it installed, to take it away again.
 *
 * What happens goes to the console (its "Toolchain" channel) too.
 */
object ToolchainInstaller {

    const val CHANNEL = "Toolchain"
    const val CATALOG = "packages.json"

    @Volatile
    private var cached: Pair<Long, List<InstalledPackage>>? = null

    /** The installed packages (their manifests). Not on the main thread. */
    @WorkerThread
    fun installed(context: Context): List<InstalledPackage> {
        val dir = Usr.packages(context)
        val stamp = dir.lastModified() + (dir.list()?.size ?: 0)
        cached?.let { (time, list) -> if (time == stamp) return list }
        val list = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { InstalledPackage.parse(it.readText()) }.getOrNull() }
            .sortedBy { it.pkg.name.lowercase() }
        cached = stamp to list
        return list
    }

    /**
     * Installs [pkg]; [progress] hears what is being done, and how far a download got
     * (percent, null when not known). What was fetched into the cache (a download, a copy)
     * is deleted once installed, and when it is no good (SHA-256 not as given, cannot be
     * unpacked); a download stopped before it was unpacked (whole or not) is kept, to go on
     * with or to use as it is; [deleteSource]: the file of the device it came from too
     * (a `file://` or `content://` source), once installed. Returns what was installed;
     * throws IOException.
     */
    suspend fun install(
        context: Context,
        pkg: ToolPackage,
        deleteSource: Boolean = false,
        progress: (String, Int?) -> Unit,
    ): InstalledPackage {
        // the package for this device: by its ABI, else the one source
        val chosen = pkg.sourceFor(Build.SUPPORTED_ABIS.toList())
            ?: throw IOException("${pkg.id}: no source for this device (${Build.SUPPORTED_ABIS.joinToString()}) in packages.json")
        @Suppress("NAME_SHADOWING")
        val pkg = pkg.copy(source = chosen.source, sha256 = chosen.sha256, abi = emptyMap())
        val source = try {
            pkg.parsedSource()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        log("installing ${pkg.id} ${pkg.version.orEmpty()} from ${pkg.source}")
        val archive = fetch(context, pkg, source, progress)
        val cache = File(context.cacheDir, DownloadCache.DIR)
        // unpacked and installed; stopped before unpacking (a stop, or the entry removed or
        // changed on save)
        var unpacked = false
        var stopped = false
        try {
            pkg.sha256?.let { expected ->
                progress("verify", null)
                val actual = withContext(Dispatchers.IO) { sha256(archive) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("${pkg.id}: SHA-256 is $actual, not $expected")
                }
            }
            // The last point to stop at before unpacking: stopped here, nothing in usr was touched
            currentCoroutineContext().ensureActive()
            progress("unpack", null)
            // Unpack, move into usr, write the manifest: once started, all of it is done, so
            // no half-installed files are left (if it was cancelled meanwhile, the caller
            // gets a CancellationException and decides whether to uninstall it again)
            return withContext(Dispatchers.IO + NonCancellable) {
                val installed = unpackAndInstall(context, pkg, source, archive)
                unpacked = true
                log("installed ${pkg.id}: ${installed.files.size} files, in usr/bin: ${installed.programs().joinToString(", ").ifEmpty { "-" }}")
                if (deleteSource) deleteSourceFile(context, source)
                installed
            }
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } finally {
            // The archive in the cache (downloaded or copied): deleted once installed, and also
            // when the package itself is bad (SHA-256 mismatch, cannot be unpacked), to be
            // downloaded again next time. A download that finished but was stopped before
            // unpacking is kept: the next install of the same version uses it without
            // downloading again (a changed version and the like is cleaned up by sweepCache).
            // A copy is not kept: it is copied again next time.
            val keep = stopped && !unpacked && source is PackageSource.Remote
            if (archive.parentFile == cache && !keep) withContext(NonCancellable + Dispatchers.IO) {
                if (archive.delete()) log("removed ${archive.name} from the cache")
            }
        }
    }

    // The file in the cache a remote package is downloaded to. While the id, version, URL
    // and sha256 stay the same it is the same file, and a download that stopped (a stop, a
    // failure, the app killed) resumes next time; when they change it is another file, and
    // sweepCache deletes the old one
    private fun downloadFileOf(context: Context, pkg: ToolPackage, url: String): File =
        File(File(context.cacheDir, DownloadCache.DIR).apply { mkdirs() }, DownloadCache.downloadName(pkg.id, pkg.version, url, pkg.sha256))

    /**
     * The cache file names installing [pkg] (an entry of packages.json) on this device uses:
     * the download of a remote package, and the temporary copies of files on the device.
     * Those of an install under way must not be touched by a cleanup.
     */
    fun cacheNamesOf(pkg: ToolPackage): List<String> {
        val names = arrayListOf(DownloadCache.localCopyName(pkg.id), DownloadCache.contentCopyName(pkg.id))
        wantedDownloadName(pkg)?.let { names += it }
        return names
    }

    // The download file name of the remote package of [pkg] on this device; null when it is
    // not a remote package (a local file, content://, no source)
    private fun wantedDownloadName(pkg: ToolPackage): String? {
        val chosen = pkg.sourceFor(Build.SUPPORTED_ABIS.toList()) ?: return null
        val remote = runCatching { PackageSource.parse(chosen.source) }.getOrNull() as? PackageSource.Remote ?: return null
        return DownloadCache.downloadName(pkg.id, pkg.version, remote.url, chosen.sha256)
    }

    /**
     * Cleans up the download cache (cache/toolchain): deletes the partial downloads that no
     * entry of packages.json ([text]; null: the one on disk) has any more (the entry was
     * removed, or its id, version, URL or sha256 changed), and the temporary copies left by
     * an app killed halfway. What can still be resumed, and what is in use ([inUse]), is
     * kept. When packages.json cannot be parsed nothing is deleted (one typo should not lose
     * every partial download). Returns how many files were deleted. Not on the main thread.
     */
    @WorkerThread
    fun sweepCache(context: Context, text: String? = null, inUse: Set<String> = emptySet()): Int {
        val dir = File(context.cacheDir, DownloadCache.DIR)
        val present = dir.list()?.toList().orEmpty()
        if (present.isEmpty()) return 0
        val catalogText = text ?: runCatching { File(LanguageServers.configDirectory(context), CATALOG).readText() }.getOrNull()
            ?: return 0
        val root = runCatching { ToolPackage.json.parseToJsonElement(catalogText) as? JsonObject }.getOrNull()
        if (root?.get("packages") !is JsonArray) return 0
        // disabled entries count too: turned on again, they can resume
        val wanted = ToolPackage.parseCatalog(catalogText).mapNotNull { wantedDownloadName(it) }.toSet()
        var deleted = 0
        for (name in DownloadCache.stale(present, wanted, inUse)) {
            val file = File(dir, name)
            val size = file.length()
            if (file.delete()) {
                deleted++
                log("removed the unfinished download $name (${size / 1024} KiB): nothing in $CATALOG uses it any more")
            }
        }
        return deleted
    }

    // the file a package came from, once it is installed (what the user asked for)
    private fun deleteSourceFile(context: Context, source: PackageSource) {
        val deleted = try {
            when (source) {
                is PackageSource.LocalFile -> source.file.delete() ||
                    LocalFiles.documentUriOf(context, source.file)?.let { DocumentsContract.deleteDocument(context.contentResolver, it) } == true
                is PackageSource.Content -> DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(source.uri))
                is PackageSource.Remote -> false
            }
        } catch (e: Exception) {
            false
        }
        log(if (deleted) "deleted the package ${source.fileName()}" else "could not delete ${source.fileName()}: no access to it")
    }

    // the source as a file of the device
    private suspend fun fetch(context: Context, pkg: ToolPackage, source: PackageSource, progress: (String, Int?) -> Unit): File {
        val cache = File(context.cacheDir, DownloadCache.DIR).apply { mkdirs() }
        return when (source) {
            is PackageSource.Remote -> {
                val file = downloadFileOf(context, pkg, source.url)
                if (file.exists() && file.length() > 0) log("${pkg.id}: going on with the download, ${file.length() / 1024} KiB already there")
                progress("download", 0)
                var failure: Throwable? = null
                FileDownloader.download(source.url, file, if (file.exists()) file.length() else 0L).collect { state ->
                    when (state) {
                        is DownloadState.InProgress -> progress("download", state.progress)
                        is DownloadState.Success -> Unit
                        is DownloadState.Error -> failure = state.throwable
                    }
                }
                failure?.let { throw IOException("download of ${source.url}: ${it.message ?: it}", it) }
                file
            }
            is PackageSource.LocalFile -> withContext(Dispatchers.IO) {
                if (source.file.isFile && source.file.canRead()) return@withContext source.file
                // not readable by its path (no all files access): through a folder the user granted
                val uri = LocalFiles.documentUriOf(context, source.file)
                    ?: throw IOException("${source.file.path} cannot be read: add its folder to the file tree, or pick it with content://")
                copy(context, uri, File(cache, DownloadCache.localCopyName(pkg.id)), progress)
            }
            is PackageSource.Content -> withContext(Dispatchers.IO) {
                copy(context, Uri.parse(source.uri), File(cache, DownloadCache.contentCopyName(pkg.id)), progress)
            }
        }
    }

    private fun copy(context: Context, uri: Uri, target: File, progress: (String, Int?) -> Unit): File {
        progress("copy", null)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return FileUtils.bytesToHex(digest.digest())
    }

    @WorkerThread
    private fun unpackAndInstall(context: Context, given: ToolPackage, source: PackageSource, archive: File): InstalledPackage {
        val usr = Usr.ensure(context)
        val staging = File(usr, "var/lib/xedit/staging/${given.id}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val format = Archives.detect(archive)
            // a single program: by the name the package gives it in usr/bin, else its id
            val singleName = given.bin.keys.singleOrNull() ?: given.id
            // what is not given of the layout, worked out from the names in it
            val layout = Archives.layout(Archives.names(archive, format, singleName), given.id, given.strip, given.into)
            val pkg = given.copy(strip = layout.strip, into = layout.into)
            val into = layout.into
            val written = Archives.extract(archive, format, StagingSink(staging), layout.strip, singleName)
            if (written.isEmpty()) throw IOException("${pkg.id}: nothing in ${source.fileName()} (too many parts stripped?)")

            // what it installed before goes first
            installed(context).firstOrNull { it.pkg.id == pkg.id }?.let { remove(context, it) }

            val base = if (into.isEmpty()) usr else File(usr, into)
            val files = ArrayList<String>()
            for (path in written) {
                val from = File(staging, path)
                val to = File(base, path)
                to.parentFile?.mkdirs()
                if (to.exists() || isLink(to)) to.delete()
                if (!from.renameTo(to)) throw IOException("cannot move $path into ${base.path}")
                files += if (into.isEmpty()) path else "$into/$path"
            }

            // its programs in usr/bin
            val links = LinkedHashMap<String, String>()
            val wanted = pkg.bin.ifEmpty {
                if (into.isEmpty()) emptyMap()
                else files.filter { it.startsWith("$into/bin/") && it.count { c -> c == '/' } == into.count { c -> c == '/' } + 2 }
                    .associateBy { it.substringAfterLast('/') }
            }
            for ((name, path) in wanted) {
                if (!File(usr, path).exists()) throw IOException("${pkg.id}: $path (for usr/bin/$name) is not in the package")
                val link = File(usr, "bin/$name")
                if (link.exists() || isLink(link)) {
                    // a file of the package itself in usr/bin: it is what it is
                    if ("bin/$name" == path) continue
                    link.delete()
                }
                makeLink(relativeTarget("bin/$name", path), link.path)
                links[name] = path
            }
            val installed = InstalledPackage(pkg, files, links, System.currentTimeMillis())
            File(Usr.packages(context), "${pkg.id}.json").writeText(installed.toJson())
            // as dpkg's info/<package>.list: every path it installed, its directories first
            File(Usr.packages(context), "${pkg.id}.list").writeText(packageList(usr, installed))
            cached = null
            return installed
        } finally {
            staging.deleteRecursively()
        }
    }

    // a symbolic link at [path] to [target]; the system's error as an IOException
    private fun makeLink(target: String, path: String) {
        try {
            Os.symlink(target, path)
        } catch (e: ErrnoException) {
            throw IOException("cannot link $path to $target: ${e.message}", e)
        }
    }

    // the target of a link at [linkPath] to [path] (both under usr), relative
    internal fun relativeTarget(linkPath: String, path: String): String {
        val up = linkPath.count { it == '/' }
        return "../".repeat(up) + path
    }

    private fun isLink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    /** Takes [id] away: its files, its links, its manifest. Not on the main thread. */
    @WorkerThread
    fun uninstall(context: Context, id: String): Boolean {
        val installed = installed(context).firstOrNull { it.pkg.id == id } ?: return false
        remove(context, installed)
        log("uninstalled $id")
        return true
    }

    /**
     * What is installed of a toolchain whose id was changed from [before] to [after]
     * (ToolPackage.renames) goes under its new id and name: its manifest and its list. Its
     * files stay where they are, the manifest says where. Not on the main thread. Throws
     * IOException.
     */
    @WorkerThread
    fun followRenames(context: Context, before: List<ToolPackage>, after: List<ToolPackage>) {
        val dir = Usr.packages(context)
        for ((oldId, pkg) in ToolPackage.renames(before, after)) {
            val have = installed(context)
            val old = have.firstOrNull { it.pkg.id == oldId } ?: continue
            if (have.any { it.pkg.id == pkg.id }) continue
            val moved = old.copy(pkg = old.pkg.copy(id = pkg.id, name = pkg.name))
            File(dir, "${pkg.id}.json").writeText(moved.toJson())
            File(dir, "$oldId.list").takeIf { it.isFile }?.renameTo(File(dir, "${pkg.id}.list"))
            File(dir, "$oldId.json").delete()
            cached = null
            log("$oldId is now ${pkg.id}")
        }
    }

    // The paths of a package as dpkg lists them (info/<package>.list): absolute, one a line,
    // the directories it put things in (also those it shares) before what is in them.
    internal fun packageList(usr: File, installed: InstalledPackage): String {
        val paths = installed.files + installed.links.keys.map { "bin/$it" }
        val directories = sortedSetOf<String>()
        for (path in paths) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty()) {
                directories += dir
                dir = dir.substringBeforeLast('/', "")
            }
        }
        return (listOf(usr.path) + directories.map { "${usr.path}/$it" } + paths.map { "${usr.path}/$it" })
            .joinToString("\n", postfix = "\n")
    }

    // Takes a package away as dpkg does by its list: its files and links, then its
    // directories, the deepest first, those it leaves empty (never usr's own).
    private fun remove(context: Context, installed: InstalledPackage) {
        val usr = Usr.root(context)
        val keep = Usr.DIRECTORIES.map { File(usr, it).path }.toSet() + usr.path
        val listFile = File(Usr.packages(context), "${installed.pkg.id}.list")
        val listed = listFile.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.startsWith(usr.path + "/") }
            ?: (installed.files + installed.links.keys.map { "bin/$it" }).map { "${usr.path}/$it" }
        val (directories, entries) = listed.map(::File).partition { it.isDirectory && !isLink(it) }
        for (file in entries) {
            if (file.exists() || isLink(file)) file.delete()
        }
        // what it left empty, also above what is listed
        val candidates = (directories + entries.mapNotNull { it.parentFile }).map { it.path }.toSortedSet(compareByDescending<String> { it.length }.thenBy { it })
        for (path in candidates) {
            var dir: File? = File(path)
            while (dir != null && dir.path !in keep && dir.path.startsWith(usr.path + "/") && dir.list()?.isEmpty() == true) {
                dir.delete()
                dir = dir.parentFile
            }
        }
        File(Usr.packages(context), "${installed.pkg.id}.json").delete()
        listFile.delete()
        cached = null
    }

    // #region catalog

    /**
     * The list of toolchains (`packages.json` next to servers/), written with the common
     * ones if there is none.
     */
    @WorkerThread
    fun catalogFile(context: Context): File {
        val file = File(LanguageServers.configDirectory(context), CATALOG)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(exampleCatalog(context))
        }
        return file
    }

    /**
     * The list as the app writes it the first time (Restore template on the panel): the
     * template of the assets (assets/templates/toolchain/packages.jsonc). Reads the asset.
     */
    @WorkerThread
    fun exampleCatalog(context: Context): String = Templates.read(context, Templates.TOOLCHAIN)

    /**
     * A new toolchain [id] (New on the toolchain panel), to be put at the end of the list:
     * the template of the assets (assets/templates/toolchain/entry.jsonc) with its id. Reads
     * the asset.
     */
    @WorkerThread
    fun newEntry(context: Context, id: String): String = TemplateText.fill(
        Templates.read(context, Templates.TOOLCHAIN_ENTRY).trim(),
        values = mapOf("name" to TemplateText.jsonString(id)),
        text = mapOf("name" to id),
    )

    /**
     * What is wrong with [text] as the list, as ToolchainSheet checks it before it writes
     * it: not JSON (at [CatalogCheck.line]), or what would be left out of it; none: right.
     */
    fun checkCatalog(text: String): CatalogCheck? {
        try {
            ToolPackage.json.parseToJsonElement(text)
        } catch (e: Exception) {
            return CatalogCheck(listOf(FriendlySnippets.errorMessage(e)), FriendlySnippets.errorLine(text, e.message), syntax = true)
        }
        val problems = ArrayList<String>()
        val packages = ToolPackage.parseCatalog(text, problems)
        // two of one id, both on: the list would show both, and every action takes the first
        val on = packages.filter { it.enabled }.groupingBy { it.id }.eachCount()
        on.filterValues { it > 1 }.keys.forEach { problems += "\"$it\" is in the list more than once" }
        return if (problems.isEmpty()) null else CatalogCheck(problems)
    }

    /** What is wrong with a text of the list: [problems], a line each; [syntax]: not JSON, at [line] (0: not known). */
    class CatalogCheck(val problems: List<String>, val line: Int = 0, val syntax: Boolean = false)

    /** Writes [text] as the list, if it is right ([checkCatalog]), whole or not at all. */
    @WorkerThread
    fun writeCatalog(context: Context, text: String) {
        checkCatalog(text)?.let { throw IllegalArgumentException(it.problems.joinToString("; ")) }
        FormattingRules.writeAtomically(
            File(LanguageServers.configDirectory(context), CATALOG),
            if (text.endsWith("\n")) text else text + "\n"
        )
    }

    /**
     * Takes the toolchain [id] out of the list (packages.json): its object in `"packages"`
     * (each of that id, switched on or off), the rest of the file as it is, comments too
     * (CatalogText); written whole or not at all. Nothing else: what is installed of it in
     * X Editor is not touched here. Whether the list had it; throws
     * IOException (or IllegalArgumentException) when it cannot be written.
     */
    @WorkerThread
    fun removeFromCatalog(context: Context, id: String): Boolean {
        val text = catalogFile(context).readText()
        val edited = (
            try {
                CatalogText.removePackage(text, id)
            } catch (e: IllegalArgumentException) {
                // not laid out as a list of toolchains should be: read and written again (its
                // comments are lost), or nothing if it cannot be read at all
                removeParsed(text, id)
            }
        ) ?: return false
        writeCatalog(context, edited)
        log("removed $id from $CATALOG")
        return true
    }

    // [text] without the toolchains of [id], parsed and written again; null: it has none
    private fun removeParsed(text: String, id: String): String? {
        val root = ToolPackage.json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("$CATALOG is not a JSON object")
        val list = root["packages"] as? JsonArray ?: return null
        val kept = list.filterNot { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == id }
        if (kept.size == list.size) return null
        val edited = JsonObject(LinkedHashMap(root).apply { put("packages", JsonArray(kept)) })
        return ToolPackage.json.encodeToString(JsonElement.serializer(), edited) + "\n"
    }

    /** The ids of the list, those switched off too; none if it cannot be read. */
    @WorkerThread
    fun catalogIds(context: Context): Set<String> =
        runCatching { ToolPackage.parseCatalog(catalogFile(context).readText()).map { it.id }.toSet() }.getOrDefault(emptySet())

    /**
     * How many times the list, or what is installed of it, was changed in this session: what
     * shows something that depends on it (the language servers) reads it again when this moves.
     */
    @Volatile
    var changes = 0
        private set

    /** The list, or what is installed of it, was changed. Main thread. */
    fun noteChanged() {
        changes++
    }

    /** The toolchains of the list, those switched on; what is wrong with it goes to [problems]. */
    @WorkerThread
    fun catalog(context: Context, problems: MutableList<String> = ArrayList()): List<ToolPackage> =
        ToolPackage.parseCatalog(catalogFile(context).readText(), problems).filter { it.enabled }

    // #endregion

    private fun log(message: String) = ConsoleLog.append(CHANNEL, ConsoleLog.Level.INFO, message)

    // writes what is unpacked under [root]
    private class StagingSink(private val root: File) : Archives.Sink {
        override fun directory(path: String) {
            File(root, path).mkdirs()
        }

        override fun file(path: String, input: InputStream, executable: Boolean) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            if (file.exists() || isLinkFile(file)) file.delete()
            file.outputStream().use { input.copyTo(it) }
            if (executable) file.setExecutable(true, false)
        }

        override fun symlink(path: String, target: String) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            if (file.exists() || isLinkFile(file)) file.delete()
            makeLink(target, file.path)
        }

        override fun hardlink(path: String, target: String) {
            val from = File(root, target)
            val file = File(root, path)
            file.parentFile?.mkdirs()
            from.copyTo(file, overwrite = true)
            if (from.canExecute()) file.setExecutable(true, false)
        }

        private fun isLinkFile(file: File) = try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (e: Exception) {
            false
        }
    }
}
