/*
 * Copyright 2018 The Android Open Source Project
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

package x.github.module.document

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import java.io.File

/**
 * Representation of a document backed by either a [android.provider.DocumentsProvider]
 * or a raw file on disk, emulating the traditional [File] interface.
 *
 * Instances are created through the factory methods in the companion object:
 *
 * - [fromFile] for a file or directory on disk;
 * - [fromSingleUri] for a single document, e.g. the result of
 *   [android.content.Intent.ACTION_OPEN_DOCUMENT] or [android.content.Intent.ACTION_CREATE_DOCUMENT];
 * - [fromTreeUri] for a document tree, e.g. the result of
 *   [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE];
 * - [fromUri] to pick one of the above from the shape of the Uri.
 *
 * Differences between documents and traditional files:
 *
 * - Documents express their display name and MIME type as separate fields instead of
 *   relying on file extensions.
 * - A single document may appear as the child of several directories, so documents have
 *   no strong notion of path. [getParent] only reflects how this instance was reached.
 * - Each document has a unique, opaque identifier within its provider that must not be
 *   parsed.
 *
 * ### Metadata snapshots
 *
 * Provider-backed instances ([SingleDocumentFile], [TreeDocumentFile]) read their metadata
 * (name, MIME type, size, last modified time, flags) **once**, either from the query that
 * listed them or lazily on first access, and then serve it from memory. Call [refresh] to
 * read it again. [exists] always queries the provider. [RawDocumentFile] always reads the
 * file system directly.
 *
 * ### Immutability
 *
 * The Uri of an instance never changes. Operations that may change it, such as [renameTo],
 * return a **new** instance; the original keeps pointing at the old Uri.
 *
 * ### Threading
 *
 * Methods annotated with [WorkerThread] may perform file system or provider I/O and should
 * not be called on the main thread. Instances are safe to share between threads.
 *
 * ### Errors
 *
 * No method throws because a document was deleted, a permission was revoked or a provider
 * failed. Such failures are logged and reported through the return value (`null`, `false`,
 * `0` or an empty string), as documented on each method. The only exceptions thrown are
 * [UnsupportedOperationException] for operations a document type cannot support, and
 * [IllegalArgumentException] for malformed Uris passed to a constructor.
 *
 * @see android.provider.DocumentsProvider
 * @see android.provider.DocumentsContract
 */
abstract class DocumentFile {

    /** The [Context] this instance was created with. */
    abstract fun getContext(): Context

    /**
     * Creates a new document as a direct child of this directory.
     *
     * @param mimeType MIME type of the new document, such as `image/png`.
     * @param displayName name of the new document. The underlying provider may append a
     *   file extension that matches [mimeType].
     * @return the newly created document, or `null` if it could not be created.
     * @throws UnsupportedOperationException for a document created with [fromSingleUri].
     */
    @WorkerThread
    abstract fun createFile(mimeType: String, displayName: String): DocumentFile?

    /**
     * Creates a new directory as a direct child of this directory.
     *
     * @return the newly created directory, or `null` if it could not be created.
     * @throws UnsupportedOperationException for a document created with [fromSingleUri].
     */
    @WorkerThread
    abstract fun createDirectory(displayName: String): DocumentFile?

    /**
     * The Uri of the underlying document: a `file://` Uri for [RawDocumentFile], a
     * `content://` Uri otherwise. It never changes for a given instance.
     */
    abstract fun getUri(): Uri

    /**
     * The display name of this document, or an empty string if it is unknown.
     *
     * @see android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
     */
    @WorkerThread
    abstract fun getName(): String

    /**
     * The MIME type of this document, or an empty string if it is unknown.
     *
     * @see android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
     */
    @WorkerThread
    abstract fun getType(): String

    /**
     * The directory this instance was reached from, or `null` for an instance created by
     * a factory method. This is a convenience only: providers define a mapping from parent
     * to child, not the other way round, and the result may be wrong if the tree changes.
     */
    abstract fun getParent(): DocumentFile?

    /** Whether this document is a directory. */
    @WorkerThread
    abstract fun isDirectory(): Boolean

    /** Whether this document is a regular file, i.e. it has a MIME type and isn't a directory. */
    @WorkerThread
    abstract fun isFile(): Boolean

    /**
     * Whether this is a virtual document, which has no byte representation of its own.
     *
     * @see android.provider.DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT
     */
    @WorkerThread
    abstract fun isVirtual(): Boolean

    /**
     * The last modification time in milliseconds since the epoch, or `0` if it is unknown
     * or the document doesn't exist.
     */
    @WorkerThread
    abstract fun lastModified(): Long

    /**
     * The size in bytes, or `0` if it is unknown or the document doesn't exist. The result
     * for a directory is not defined.
     */
    @WorkerThread
    abstract fun length(): Long

    /** Whether the current context is allowed to read this document. */
    @WorkerThread
    abstract fun canRead(): Boolean

    /**
     * Whether the current context is allowed to write to this document.
     *
     * @see android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE
     * @see android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE
     * @see android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
     */
    @WorkerThread
    abstract fun canWrite(): Boolean

    /**
     * Deletes this document. A directory is deleted together with its contents.
     *
     * @return `true` if the document was deleted.
     */
    @WorkerThread
    abstract fun delete(): Boolean

    /** Whether this document currently exists. Always checks the underlying storage. */
    @WorkerThread
    abstract fun exists(): Boolean

    /**
     * Lists the documents contained in this directory.
     *
     * For [TreeDocumentFile] this costs a single provider query, regardless of the number
     * of children: the returned children already carry their metadata.
     *
     * @return the children (an empty list for an empty directory), or `null` if this is
     *   not a directory or its contents could not be read.
     */
    @WorkerThread
    abstract fun listFiles(): MutableList<DocumentFile>?

    /**
     * The number of documents contained in this directory, or `0` if this is not a
     * directory or the count could not be determined.
     *
     * [TreeDocumentFile] queries the provider on the first call and caches the result
     * until [refresh] is called or this instance creates a child.
     */
    @WorkerThread
    abstract fun getChildCount(): Int

    /**
     * Returns the first child whose display name equals [name], or `null` if there is no
     * such child or this is not a directory.
     */
    @WorkerThread
    open fun find(name: String): DocumentFile? =
        listFiles()?.firstOrNull { it.getName() == name }

    /**
     * Renames this document.
     *
     * This instance is **not** modified: it keeps its old Uri and metadata, and usually no
     * longer [exists] afterwards. Use the returned instance instead. Some providers create
     * a new document to reflect the rename, so its Uri and MIME type may differ. When a
     * directory is renamed, children previously obtained from [listFiles] may become
     * invalid.
     *
     * @param name the new display name.
     * @return the renamed document, or `null` if the rename failed.
     * @throws UnsupportedOperationException never thrown by the built-in implementations;
     *   declared for custom subclasses.
     */
    @WorkerThread
    abstract fun renameTo(name: String): DocumentFile?

    /**
     * Discards cached metadata, so that the next access reads it again from the
     * underlying storage. Does nothing for [RawDocumentFile], which caches nothing.
     */
    @WorkerThread
    open fun refresh() {}

    /** Two instances are equal when they refer to the same Uri. */
    final override fun equals(other: Any?): Boolean =
        this === other || (other is DocumentFile && other.getUri() == getUri())

    final override fun hashCode(): Int = getUri().hashCode()

    override fun toString(): String = "${javaClass.simpleName}(${getUri()})"

    companion object {

        internal const val TAG = "DocumentFile"

        /**
         * Creates a [DocumentFile] for the file system tree rooted at [file]. This gives no
         * access beyond what the app already has. [getUri] returns `file://` Uris.
         */
        @JvmStatic
        fun fromFile(context: Context, file: File): DocumentFile =
            RawDocumentFile(null, context, file)

        /**
         * Creates a [DocumentFile] for the single document at [singleUri]. No I/O happens
         * until a metadata accessor is called.
         *
         * @param singleUri the data of a successful
         *   [android.content.Intent.ACTION_OPEN_DOCUMENT] or
         *   [android.content.Intent.ACTION_CREATE_DOCUMENT] result.
         */
        @JvmStatic
        fun fromSingleUri(context: Context, singleUri: Uri): DocumentFile =
            SingleDocumentFile(null, context, singleUri)

        /**
         * Creates a [DocumentFile] for the document that [treeUri] points to.
         *
         * [treeUri] may be the tree Uri returned by
         * [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE], or a document Uri inside a tree
         * (as built by [DocumentsContract.buildDocumentUriUsingTree]). Performs one provider
         * query to read the document's metadata.
         *
         * @return a [TreeDocumentFile] if the document is a directory, or if its type could
         *   not be determined; a [SingleDocumentFile] if it is a file; `null` if [treeUri]
         *   is not a tree Uri.
         */
        @JvmStatic
        @WorkerThread
        fun fromTreeUri(context: Context, treeUri: Uri): DocumentFile? {
            if (!DocumentsContract.isTreeUri(treeUri)) return null
            val documentId = if (DocumentQueries.hasDocumentPath(treeUri)) {
                DocumentsContract.getDocumentId(treeUri)
            } else {
                DocumentsContract.getTreeDocumentId(treeUri)
            }
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val info = DocumentInfo.query(context, documentUri)
            return if (info == null || info.isDirectory) {
                TreeDocumentFile(null, context, documentUri, info)
            } else {
                SingleDocumentFile(null, context, documentUri, info)
            }
        }

        /**
         * Creates a [DocumentFile] for any supported Uri:
         *
         * - `file://` → [fromFile];
         * - a tree Uri → [fromTreeUri];
         * - any other `content://` Uri → [fromSingleUri].
         *
         * @return the document, or `null` for an unsupported scheme.
         */
        @JvmStatic
        @WorkerThread
        fun fromUri(context: Context, uri: Uri): DocumentFile? = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path?.let { fromFile(context, File(it)) }
            uri.scheme != ContentResolver.SCHEME_CONTENT -> null
            DocumentsContract.isTreeUri(uri) -> fromTreeUri(context, uri)
            else -> fromSingleUri(context, uri)
        }

        /** Whether [uri] is backed by a [android.provider.DocumentsProvider]. */
        @JvmStatic
        fun isDocumentUri(context: Context, uri: Uri?): Boolean =
            DocumentsContract.isDocumentUri(context, uri)

        /**
         * Whether [uri] points to something that currently exists: a file for `file://`
         * Uris, a document for `content://` Uris. Never throws.
         */
        @JvmStatic
        @WorkerThread
        fun isValid(context: Context, uri: Uri): Boolean = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).exists() } ?: false
            else -> DocumentQueries.exists(context, uri)
        }
    }
}
