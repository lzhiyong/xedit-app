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

import android.net.Uri

import androidx.annotation.WorkerThread

import x.github.module.document.DocumentFile

/**
 * The value of one node of the file tree in the start drawer.
 *
 * [name], [isDirectory] and [isText] are read once, off the main thread, when the
 * entry is created ([of]): binding a row and measuring its width must not query a
 * provider.
 *
 * @param grantUri for [Kind.FOLDER] and [Kind.FILE]: the uri the persisted permission
 *   was taken for (for a folder that is the tree uri, not [DocumentFile.getUri]).
 *   Releasing the permission and checking for duplicates go by this uri.
 */
class FileTreeEntry(
    val document: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val kind: Kind,
    val grantUri: Uri? = null,
    val isText: Boolean = true,
) {
    enum class Kind {
        /** The fixed "external storage" root (/sdcard), read through java.io.File. */
        STORAGE,

        /** A root folder picked with OpenDocumentTree. */
        FOLDER,

        /** A root file picked with OpenMultipleDocuments (or handed over by another app). */
        FILE,

        /** Anything found inside one of the roots. */
        CHILD,
    }

    val uri: Uri
        get() = document.getUri()

    /** A root picked through SAF: taken off the list ("remove"), never deleted. */
    val isRemovable: Boolean
        get() = kind == Kind.FOLDER || kind == Kind.FILE

    /** Inside a root: may be renamed and deleted from the disk. */
    val isChild: Boolean
        get() = kind == Kind.CHILD

    /** Read through java.io.File: needs the storage permission. */
    val isRawFile: Boolean
        get() = uri.scheme == "file"

    /** A file the editor can open; other files are greyed out and not clickable. */
    val canOpen: Boolean
        get() = !isDirectory && isText

    /** The same entry for the document after a rename. */
    @WorkerThread
    fun renamed(newDocument: DocumentFile): FileTreeEntry = of(newDocument, kind, grantUri)

    companion object {
        /** Reads the name and type of [document]: queries its provider. */
        @WorkerThread
        fun of(document: DocumentFile, kind: Kind, grantUri: Uri? = null): FileTreeEntry {
            val uri = document.getUri()
            val name = runCatching { document.getName() }.getOrDefault("")
                .ifEmpty { uri.lastPathSegment ?: uri.toString() }
            val isDirectory = runCatching { document.isDirectory() }.getOrDefault(false)
            val mimeType = if (isDirectory) "" else runCatching { document.getType() }.getOrDefault("")
            return FileTreeEntry(
                document = document,
                name = name,
                isDirectory = isDirectory,
                kind = kind,
                grantUri = grantUri,
                isText = isDirectory || TextFileTypes.isText(name, mimeType)
            )
        }
    }
}
