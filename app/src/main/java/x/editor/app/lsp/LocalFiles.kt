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

package x.editor.app.lsp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import x.github.module.document.DocumentFile
import java.io.File

/**
 * Where a document is on the file system, for a language server, which reads files by
 * their path (`file://` URIs), not through the Storage Access Framework. (Not
 * filetree.DocumentPaths, which is about the URIs of documents and their directories.)
 */
object LocalFiles {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The file of [uri]: a file URI, or a document of the external storage provider. Null otherwise. */
    fun fileOf(context: Context, uri: Uri): File? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)
        "content" -> if (uri.authority == EXTERNAL_STORAGE && DocumentsContract.isDocumentUri(context, uri)) {
            fileOfDocumentId(DocumentsContract.getDocumentId(uri))
        } else {
            null
        }
        else -> null
    }

    /** `primary:dir/a.c` is in the shared storage, `1A2B-3C4D:dir/a.c` on that volume. */
    internal fun fileOfDocumentId(id: String, primary: File = Environment.getExternalStorageDirectory()): File? {
        val volume = id.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val path = id.substringAfter(':')
        val base = if (volume.equals("primary", ignoreCase = true)) primary else File("/storage", volume)
        return if (path.isEmpty()) base else File(base, path)
    }

    /**
     * The document of [file] in a folder (or the file itself) the user granted through the
     * Storage Access Framework, by its uri there; null if there is none, or it is not there.
     * For files the app cannot read by their path (no all files access): a server names
     * files by path, the files of a project are found by path (.editorconfig, a path typed).
     */
    @WorkerThread
    fun documentUriOf(context: Context, file: File): Uri? {
        val target = file.absoluteFile.normalize()
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            val granted = permission.uri
            try {
                if (!DocumentsContract.isTreeUri(granted)) {
                    if (fileOf(context, granted)?.absoluteFile?.normalize() == target) return granted
                    continue
                }
                val treeId = DocumentsContract.getTreeDocumentId(granted)
                val root = fileOf(context, DocumentsContract.buildDocumentUriUsingTree(granted, treeId))
                    ?.absoluteFile?.normalize() ?: continue
                if (!target.startsWith(root)) continue
                // the ids of the external storage provider are paths: `primary:dir/a.c`
                val relative = target.relativeTo(root).invariantSeparatorsPath
                val id = when {
                    relative.isEmpty() -> treeId
                    treeId.endsWith(':') || treeId.endsWith('/') -> treeId + relative
                    else -> "$treeId/$relative"
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(granted, id)
                if (DocumentFile.isValid(context, uri)) return uri
            } catch (e: Exception) {
                // a grant of a provider that is gone, a uri of another kind: not this one
            }
        }
        return null
    }

    /**
     * Whether the app may read the shared storage by path: all files access (Android 11 and
     * above), the storage permission below. Without it a directory there still lists by its
     * path, but only its folders and media files (scoped storage): not the files of code.
     */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** What is in a directory: [entries] (names, and whether each is a directory), and whether that is all of it ([complete]). */
    data class Listing(val entries: List<Pair<String, Boolean>>, val complete: Boolean)

    /**
     * What is in [directory]. By its path if the app may read the storage so (see
     * [hasAllFilesAccess]); else through a folder the user granted (see [documentUriOf]):
     * by its path the app would see only the folders and media files in it. Neither: what
     * its path shows, not [Listing.complete]; null if not even that.
     */
    @WorkerThread
    fun list(context: Context, directory: File): Listing? {
        val byPath = { directory.listFiles()?.map { it.name to it.isDirectory } }
        // the app's own directories, and all of the storage with all files access
        if (hasAllFilesAccess(context) || !isSharedStorage(context, directory)) {
            byPath()?.let { return Listing(it, complete = true) }
        }
        listGranted(context, directory)?.let { return Listing(it, complete = true) }
        return byPath()?.let { Listing(it, complete = false) }
    }

    // the shared storage (/storage/..., /sdcard), where scoped storage hides files; not the
    // app's own directory there (Android/data/<package>), which it reads whole
    private fun isSharedStorage(context: Context, file: File): Boolean {
        val path = file.absolutePath
        val own = context.getExternalFilesDir(null)?.parentFile?.absolutePath
        if (own != null && (path == own || path.startsWith("$own/"))) return false
        return path.startsWith("/storage/") || path.startsWith("/sdcard") ||
            path.startsWith(Environment.getExternalStorageDirectory().absolutePath)
    }

    // what is in [directory] through a folder the user granted; null if there is none
    @WorkerThread
    private fun listGranted(context: Context, directory: File): List<Pair<String, Boolean>>? {
        val uri = documentUriOf(context, directory) ?: return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return try {
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                val entries = ArrayList<Pair<String, Boolean>>(cursor.count)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    entries += name to (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
                entries
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The text of [file] (a small one: a configuration), by its path or through a folder
     * the user granted; null if it is not there or cannot be read, or is larger than
     * [maxBytes].
     */
    @WorkerThread
    fun readText(context: Context, file: File, maxBytes: Long = 256 * 1024): String? {
        try {
            if (file.isFile && file.canRead()) {
                return if (file.length() <= maxBytes) file.readText() else null
            }
        } catch (e: Exception) {
            // not readable by its path after all: through the grant
        }
        val uri = documentUriOf(context, file) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    // at most [limit] bytes of the stream (InputStream.readNBytes is Android 13+)
    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** The URI the protocol names [file] with. */
    fun uriOf(file: File): String = Uri.fromFile(file.absoluteFile).toString()

    /**
     * The root of the project [file] is in: [LanguageServerSpec.root] if it is set, else the
     * nearest directory above it with one of its markers, else the directory of the file.
     */
    fun rootFor(file: File, spec: LanguageServerSpec): File {
        spec.root?.let { return File(it) }
        return findRoot(file, spec.rootMarkers)
    }

    internal fun findRoot(file: File, markers: List<String>, stopAt: File? = null): File {
        var directory = file.absoluteFile.parentFile
        val start = directory
        while (directory != null && directory != stopAt) {
            if (markers.any { File(directory, it).exists() }) return directory
            directory = directory.parentFile
        }
        return start ?: file.absoluteFile
    }
}
