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

package x.editor.app.filetree

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Paths of the documents in the file tree: for "copy path", and to follow the open
 * document when a folder above it is renamed or deleted.
 *
 * file:// uris are plain paths. For content:// uris inside a picked folder only the
 * document ids are compared: for the external storage provider an id is a path
 * ("primary:Download/a.txt"), so a document lies inside a folder when its id starts
 * with the id of the folder. Other providers use opaque ids; nothing is found there.
 */
object DocumentPaths {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** A path to show and copy: the file path when there is one, else the uri. */
    fun displayPath(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path ?: uri.toString()
        val documentId = documentIdOf(uri) ?: return uri.toString()
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return uri.toString()
        val volume = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) "/sdcard" else "/storage/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** Whether [uri] is [directory] itself or lies somewhere inside it. */
    fun isWithin(uri: Uri, directory: Uri): Boolean = relocate(uri, directory, directory) != null

    /**
     * Where [uri] is after [oldDirectory] (it or one of its folders above) was
     * renamed to [newDirectory]; null if [uri] is not inside [oldDirectory].
     */
    fun relocate(uri: Uri, oldDirectory: Uri, newDirectory: Uri): Uri? {
        if (uri == oldDirectory) return newDirectory
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            if (oldDirectory.scheme != ContentResolver.SCHEME_FILE) return null
            val path = uri.path ?: return null
            val oldPath = oldDirectory.path ?: return null
            val newPath = newDirectory.path ?: return null
            if (!path.startsWith("$oldPath/")) return null
            return Uri.fromFile(java.io.File(newPath + path.substring(oldPath.length)))
        }

        if (uri.authority != oldDirectory.authority) return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        val id = documentIdOf(uri) ?: return null
        val oldId = documentIdOf(oldDirectory) ?: return null
        val newId = documentIdOf(newDirectory) ?: return null
        if (!id.startsWith("$oldId/")) return null
        return DocumentsContract.buildDocumentUriUsingTree(uri, newId + id.substring(oldId.length))
    }

    private fun documentIdOf(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: IllegalArgumentException) {
        null
    }
}
