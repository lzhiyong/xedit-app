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

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Where a document is on the file system, for a language server, which reads files by
 * their path (`file://` URIs), not through the Storage Access Framework.
 */
object DocumentPaths {

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
