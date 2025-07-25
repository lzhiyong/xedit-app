/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
import android.content.Intent
import android.database.Cursor
import android.provider.DocumentsContract
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Representation of a document backed by either a
 * {@link android.provider.DocumentsProvider} or a raw file on disk. This is a
 * utility class designed to emulate the traditional {@link File} interface. It
 * offers a simplified view of a tree of documents, but it has substantial
 * overhead. For optimal performance and a richer feature set, use the
 * {@link android.provider.DocumentsContract} methods and constants directly.
 * <p>
 * There are several differences between documents and traditional files:
 * <ul>
 * <li>Documents express their display name and MIME type as separate fields,
 * instead of relying on file extensions. Some documents providers may still
 * choose to append extensions to their display names, but that's an
 * implementation detail.
 * <li>A single document may appear as the child of multiple directories, so it
 * doesn't inherently know who its parent is. That is, documents don't have a
 * strong notion of path. You can easily traverse a tree of documents from
 * parent to child, but not from child to parent.
 * <li>Each document has a unique identifier within that provider. This
 * identifier is an <em>opaque</em> implementation detail of the provider, and
 * as such it must not be parsed.
 * </ul>
 * <p>
 * Before using this class, first consider if you really need access to an
 * entire subtree of documents. The principle of least privilege dictates that
 * you should only ask for access to documents you really need. If you only need
 * the user to pick a single file, use {@link Intent#ACTION_OPEN_DOCUMENT} or
 * {@link Intent#ACTION_GET_CONTENT}. If you want to let the user pick multiple
 * files, add {@link Intent#EXTRA_ALLOW_MULTIPLE}. If you only need the user to
 * save a single file, use {@link Intent#ACTION_CREATE_DOCUMENT}. If you use
 * these APIs, you can pass the resulting {@link Intent#getData()} into
 * {@link #fromSingleUri(Context, Uri)} to work with that document.
 * <p>
 * If you really do need full access to an entire subtree of documents, start by
 * launching {@link Intent#ACTION_OPEN_DOCUMENT_TREE} to let the user pick a
 * directory. Then pass the resulting {@link Intent#getData()} into
 * {@link #fromTreeUri(Context, Uri)} to start working with the user selected
 * tree.
 * <p>
 * As you navigate the tree of DocumentFile instances, you can always use
 * {@link #getUri()} to obtain the Uri representing the underlying document for
 * that object, for use with {@link ContentResolver#openInputStream(Uri)}, etc.
 * <p>
 * To simplify your code on devices running
 * {@link android.os.Build.VERSION_CODES#KITKAT} or earlier, you can use
 * {@link #fromFile(File)} which emulates the behavior of a
 * {@link android.provider.DocumentsProvider}.
 *
 * @see android.provider.DocumentsProvider
 * @see android.provider.DocumentsContract
 */
public abstract class DocumentFile {
    companion object {

        val LOG_TAG = DocumentFile::class.simpleName
        
        /**
         * Create a {@link DocumentFile} representing the filesystem tree rooted at
         * the given {@link File}. This doesn't give you any additional access to the
         * underlying files beyond what your app already has.
         * <p>
         * {@link #getUri()} will return {@code file://} Uris for files explored
         * through this tree.
         */
        fun fromFile(context: Context, file: File): DocumentFile? {
            return RawDocumentFile(null, context, file)
        }
        
        fun fromUri(context: Context, uri: Uri): DocumentFile? {
            return fromTreeUri(context, uri) ?: fromSingleUri(context, uri)
        }

        /**
         * Create a {@link DocumentFile} representing the single document at the
         * given {@link Uri}. This is only useful on devices running
         * {@link android.os.Build.VERSION_CODES#KITKAT} or later, and will return
         * {@code null} when called on earlier platform versions.
         *
         * @param singleUri the {@link Intent#getData()} from a successful
         *            {@link Intent#ACTION_OPEN_DOCUMENT} or
         *            {@link Intent#ACTION_CREATE_DOCUMENT} request.
         */
        fun fromSingleUri(context: Context, singleUri: Uri): DocumentFile? {
            if (Build.VERSION.SDK_INT >= 19) {
                return SingleDocumentFile(null, context, singleUri)
            } else {
                return null
            }
        }

        /**
         * Create a {@link DocumentFile} representing the document tree rooted at
         * the given {@link Uri}. This is only useful on devices running
         * {@link android.os.Build.VERSION_CODES#LOLLIPOP} or later, and will return
         * {@code null} when called on earlier platform versions.
         *
         * @param treeUri the {@link Intent#getData()} from a successful
         *            {@link Intent#ACTION_OPEN_DOCUMENT_TREE} request.
         */
        fun fromTreeUri(context: Context, treeUri: Uri): DocumentFile? {
            if (Build.VERSION.SDK_INT >= 21) {
                var documentId = DocumentsContractApi.getTreeDocumentId(treeUri)
                if (DocumentsContractApi.isDocumentUri(context, treeUri)) {
                    documentId = DocumentsContractApi.getDocumentId(treeUri)
                }
                if (documentId == null) {
                    throw IllegalArgumentException(
                        "Could not get document ID from Uri: $treeUri"
                    )
                }
                val treeDocumentUri =
                    DocumentsContractApi.buildDocumentUriUsingTree(treeUri, documentId)
                if (treeDocumentUri == null) {
                    throw NullPointerException(
                        "Failed to build documentUri from a tree: $treeUri"
                    )
                }
                return TreeDocumentFile(null, context, treeDocumentUri)
            } else {
                return null
            }
        }

        /**
         * Test if given Uri is backed by a
         * {@link android.provider.DocumentsProvider}.
         */
        fun isDocumentUri(context: Context, uri: Uri): Boolean {
            return DocumentsContractApi.isDocumentUri(context, uri)
        }
        
        fun isValid(context: Context, uri: Uri): Boolean {
            return DocumentsContractApi19.exists(context, uri)
        }
    }   
    
    public abstract fun getContext(): Context

    /**
     * Create a new document as a direct child of this directory.
     *
     * @param mimeType MIME type of new document, such as {@code image/png} or
     *            {@code audio/flac}
     * @param displayName name of new document, without any file extension
     *            appended the underlying provider may choose to append the
     *            extension
     * @return file representing newly created document, or null if failed
     * @throws UnsupportedOperationException when working with a single document
     *             created from {@link #fromSingleUri(Context, Uri)}.
     * @see android.provider.DocumentsContract#createDocument(ContentResolver,
     *      Uri, String, String)
     */
    public abstract fun createFile(mimeType: String, displayName: String): DocumentFile?

    /**
     * Create a new directory as a direct child of this directory.
     *
     * @param displayName name of new directory
     * @return file representing newly created directory, or null if failed
     * @throws UnsupportedOperationException when working with a single document
     *             created from {@link #fromSingleUri(Context, Uri)}.
     * @see android.provider.DocumentsContract#createDocument(ContentResolver,
     *      Uri, String, String)
     */
    public abstract fun createDirectory(displayName: String): DocumentFile?

    /**
     * Return a Uri for the underlying document represented by this file. This
     * can be used with other platform APIs to manipulate or share the
     * underlying content. You can use {@link #isDocumentUri(Context, Uri)} to
     * test if the returned Uri is backed by a
     * {@link android.provider.DocumentsProvider}.
     *
     * @see Intent#setData(Uri)
     * @see Intent#setClipData(android.content.ClipData)
     * @see ContentResolver#openInputStream(Uri)
     * @see ContentResolver#openOutputStream(Uri)
     * @see ContentResolver#openFileDescriptor(Uri, String)
     */
    public abstract fun getUri(): Uri

    /**
     * Return the display name of this document.
     *
     * @see android.provider.DocumentsContract.Document#COLUMN_DISPLAY_NAME
     */
    public abstract fun getName(): String

    /**
     * Return the MIME type of this document.
     *
     * @see android.provider.DocumentsContract.Document#COLUMN_MIME_TYPE
     */
    public abstract fun getType(): String

    /**
     * Return the parent file of this document. Only defined inside of the
     * user-selected tree you can never escape above the top of the tree.
     * <p>
     * The underlying {@link android.provider.DocumentsProvider} only defines a
     * forward mapping from parent to child, so the reverse mapping of child to
     * parent offered here is purely a convenience method, and it may be
     * incorrect if the underlying tree structure changes.
     */
    public abstract fun getParent(): DocumentFile?

    /**
     * Indicates if this file represents a <em>directory</em>.
     *
     * @return {@code true} if this file is a directory, {@code false}
     *         otherwise.
     * @see android.provider.DocumentsContract.Document#MIME_TYPE_DIR
     */
    public abstract fun isDirectory(): Boolean

    /**
     * Indicates if this file represents a <em>file</em>.
     *
     * @return {@code true} if this file is a file, {@code false} otherwise.
     * @see android.provider.DocumentsContract.Document#COLUMN_MIME_TYPE
     */
    public abstract fun isFile(): Boolean

    /**
     * Indicates if this file represents a <em>virtual</em> document.
     *
     * @return {@code true} if this file is a virtual document.
     * @see android.provider.DocumentsContract.Document#FLAG_VIRTUAL_DOCUMENT
     */
    public abstract fun isVirtual(): Boolean

    /**
     * Returns the time when this file was last modified, measured in
     * milliseconds since January 1st, 1970, midnight. Returns 0 if the file
     * does not exist, or if the modified time is unknown.
     *
     * @return the time when this file was last modified.
     * @see android.provider.DocumentsContract.Document#COLUMN_LAST_MODIFIED
     */
    public abstract fun lastModified(): Long

    /**
     * Returns the length of this file in bytes. Returns 0 if the file does not
     * exist, or if the length is unknown. The result for a directory is not
     * defined.
     *
     * @return the number of bytes in this file.
     * @see android.provider.DocumentsContract.Document#COLUMN_SIZE
     */
    public abstract fun length(): Long

    /**
     * Indicates whether the current context is allowed to read from this file.
     *
     * @return {@code true} if this file can be read, {@code false} otherwise.
     */
    public abstract fun canRead(): Boolean

    /**
     * Indicates whether the current context is allowed to write to this file.
     *
     * @return {@code true} if this file can be written, {@code false}
     *         otherwise.
     * @see android.provider.DocumentsContract.Document#COLUMN_FLAGS
     * @see android.provider.DocumentsContract.Document#FLAG_SUPPORTS_DELETE
     * @see android.provider.DocumentsContract.Document#FLAG_SUPPORTS_WRITE
     * @see android.provider.DocumentsContract.Document#FLAG_DIR_SUPPORTS_CREATE
     */
    public abstract fun canWrite(): Boolean

    /**
     * Deletes this file.
     * <p>
     * Note that this method does <i>not</i> throw {@code IOException} on
     * failure. Callers must check the return value.
     *
     * @return {@code true} if this file was deleted, {@code false} otherwise.
     * @see android.provider.DocumentsContract#deleteDocument(ContentResolver,
     *      Uri)
     */
    public abstract fun delete(): Boolean

    /**
     * Returns a boolean indicating whether this file can be found.
     *
     * @return {@code true} if this file exists, {@code false} otherwise.
     */
    public abstract fun exists(): Boolean
   
    /**
     * Returns an array of files contained in the directory represented by this
     * file.
     *
     * @return an array of files.
     * @throws UnsupportedOperationException when working with a single document
     *             created from {@link #fromSingleUri(Context, Uri)}.
     * @see android.provider.DocumentsContract#buildChildDocumentsUriUsingTree(Uri,
     *      String)
     */
    public abstract fun listFiles(): MutableList<DocumentFile>?
    
    public abstract fun getChildCount(): Int

    /**
     * Search through {@link #listFiles()} for the first document matching the
     * given display name. Returns {@code null} when no matching document is
     * found.
     *
     * @throws UnsupportedOperationException when working with a single document
     *             created from {@link #fromSingleUri(Context, Uri)}.
     */
    public fun find(name: String): DocumentFile? {
        listFiles()?.forEach { file ->
            if (name.equals(file.getName())) {
                return@find file
            }
        }
        return null
    }

    /**
     * Renames this file to {@code displayName}.
     * <p>
     * Note that this method does <i>not</i> throw {@code IOException} on
     * failure. Callers must check the return value.
     * <p>
     * Some providers may need to create a new document to reflect the rename,
     * potentially with a different MIME type, so {@link #getUri()} and
     * {@link #getType()} may change to reflect the rename.
     * <p>
     * When renaming a directory, children previously enumerated through
     * {@link #listFiles()} may no longer be valid.
     *
     * @param displayName the new display name.
     * @return true on success.
     * @throws UnsupportedOperationException when working with a single document
     *             created from {@link #fromSingleUri(Context, Uri)}.
     * @see android.provider.DocumentsContract#renameDocument(ContentResolver,
     *      Uri, String)
     */
    public abstract fun renameTo(name: String): DocumentFile?
}
