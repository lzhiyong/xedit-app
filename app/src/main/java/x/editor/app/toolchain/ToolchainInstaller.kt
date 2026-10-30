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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import x.editor.app.console.ConsoleLog
import x.editor.app.download.DownloadState
import x.editor.app.download.FileDownloader
import x.editor.app.lsp.LanguageServers
import x.editor.app.lsp.LocalFiles
import x.editor.app.util.FileUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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
     * is deleted when it is done with, installed or not (a download that stopped before its
     * end is kept, to go on with); [deleteSource]: the file of the device it came from too
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
        val cache = File(context.cacheDir, "toolchain")
        return withContext(Dispatchers.IO) {
            try {
                pkg.sha256?.let { expected ->
                    progress("verify", null)
                    val actual = sha256(archive)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw IOException("${pkg.id}: SHA-256 is $actual, not $expected")
                    }
                }
                progress("unpack", null)
                val installed = unpackAndInstall(context, pkg, source, archive)
                log("installed ${pkg.id}: ${installed.files.size} files, in usr/bin: ${installed.programs().joinToString(", ").ifEmpty { "-" }}")
                if (deleteSource) deleteSourceFile(context, source)
                installed
            } finally {
                // the download or the copy: not kept
                if (archive.parentFile == cache) archive.delete()
            }
        }
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
        val cache = File(context.cacheDir, "toolchain").apply { mkdirs() }
        return when (source) {
            is PackageSource.Remote -> {
                val file = File(cache, "${pkg.id}-${source.url.hashCode().toUInt()}.download")
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
                copy(context, uri, File(cache, "${pkg.id}.local"), progress)
            }
            is PackageSource.Content -> withContext(Dispatchers.IO) {
                copy(context, Uri.parse(source.uri), File(cache, "${pkg.id}.content"), progress)
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
     * The list of toolchains (`packages.json` next to servers.json), written with the common
     * ones if there is none.
     */
    @WorkerThread
    fun catalogFile(context: Context): File {
        val file = File(LanguageServers.configDirectory(context), CATALOG)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(EXAMPLE_CATALOG)
        }
        return file
    }

    /** The toolchains of the list, those switched on; what is wrong with it goes to [problems]. */
    @WorkerThread
    fun catalog(context: Context, problems: MutableList<String> = ArrayList()): List<ToolPackage> =
        ToolPackage.parseCatalog(catalogFile(context).readText(), problems).filter { it.enabled }

    private val EXAMPLE_CATALOG = """
        {
          // The toolchains of X Editor (Menu → Toolchain). Of each:
          //   "provides": its commands, which are also looked for in Termux;
          //   "termux": how it is had in Termux (you run that in Termux yourself);
          //   "source", "version", "sha256": to install it into X Editor's own usr
          //     (https://, file://, content://), or by ABI:
          //     "abi": { "arm64-v8a": { "source": "…", "sha256": "…" } };
          //   "strip", "into", "bin", "env": only if its layout is not worked out right.
          // A new "version" here is an update of what is installed.
          "packages": [
            { "id": "clang", "name": "clang, clangd", "kind": "compiler", "provides": ["clang", "clangd"], "termux": "pkg install clang" },
            { "id": "cmake", "name": "CMake, Ninja, make", "kind": "tool", "provides": ["cmake", "ninja", "make"], "termux": "pkg install cmake ninja make" },
            { "id": "jdk", "name": "OpenJDK 21, Gradle", "kind": "compiler", "provides": ["java", "gradle"], "termux": "pkg install openjdk-21 gradle" },
            { "id": "python", "name": "Python", "kind": "compiler", "provides": ["python"], "termux": "pkg install python" },
            { "id": "lua-language-server", "name": "lua-language-server", "kind": "server", "provides": ["lua-language-server"], "termux": "pkg install lua-language-server" },
            { "id": "pyright", "name": "pyright", "kind": "server", "provides": ["pyright-langserver"], "termux": "pkg install nodejs && npm install -g pyright" },
            { "id": "socat", "name": "socat (for language servers in Termux)", "kind": "tool", "provides": ["socat"], "termux": "pkg install socat" },
            {
              "id": "clangd-android",
              "name": "clangd for Android (an example: switch it on with a real address)",
              "kind": "server",
              "enabled": false,
              "version": "18.1.8",
              "provides": ["clangd"],
              "abi": {
                "arm64-v8a": { "source": "https://example.org/clangd-18.1.8-android-aarch64.tar.gz", "sha256": "" }
              }
            }
          ]
        }
    """.trimIndent() + "\n"

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
