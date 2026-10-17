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

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * A [DocumentFile] for a directory inside a document tree granted by
 * `ACTION_OPEN_DOCUMENT_TREE`. Created by [DocumentFile.fromTreeUri] or as a child of
 * another [TreeDocumentFile].
 *
 * Construction performs no I/O. [listFiles] reads all children and their metadata with a
 * single query; [getChildCount] queries lazily and caches the result. Metadata is cached
 * until [refresh] is called.
 */
class TreeDocumentFile internal constructor(
    private val parent: DocumentFile?,
    private val context: Context,
    private val uri: Uri,
    initialInfo: DocumentInfo?,
) : DocumentFile() {

    /**
     * @param uri a document Uri inside a tree, as built by
     *   [DocumentsContract.buildDocumentUriUsingTree].
     * @throws IllegalArgumentException if [uri] doesn't name a document.
     */
    constructor(parent: DocumentFile?, context: Context, uri: Uri) : this(parent, context, uri, null)

    private val childrenUri: Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))

    @Volatile
    private var info: DocumentInfo? = initialInfo

    @Volatile
    private var childCount: Int = UNKNOWN_COUNT

    private fun info(): DocumentInfo =
        info ?: (DocumentInfo.query(context, uri) ?: DocumentInfo.EMPTY).also { info = it }

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        val created = DocumentQueries.create(context, uri, mimeType, displayName) ?: return null
        childCount = UNKNOWN_COUNT
        return if (mimeType == Document.MIME_TYPE_DIR) {
            TreeDocumentFile(this, context, created, null)
        } else {
            SingleDocumentFile(this, context, created, null)
        }
    }

    override fun createDirectory(displayName: String): DocumentFile? =
        createFile(Document.MIME_TYPE_DIR, displayName)

    override fun getContext(): Context = context

    override fun getUri(): Uri = uri

    override fun getName(): String = info().displayName

    override fun getType(): String = Document.MIME_TYPE_DIR

    override fun getParent(): DocumentFile? = parent

    override fun isDirectory(): Boolean = true

    override fun isFile(): Boolean = false

    override fun isVirtual(): Boolean = DocumentFlags.isVirtual(info().flags)

    override fun lastModified(): Long = info().lastModified

    override fun length(): Long = 0L

    override fun canRead(): Boolean = DocumentQueries.canRead(context, uri, Document.MIME_TYPE_DIR)

    override fun canWrite(): Boolean =
        DocumentQueries.canWrite(context, uri, Document.MIME_TYPE_DIR, info().flags)

    override fun delete(): Boolean = DocumentQueries.delete(context, uri)

    override fun exists(): Boolean = DocumentQueries.exists(context, uri)

    override fun getChildCount(): Int {
        val cached = childCount
        if (cached != UNKNOWN_COUNT) return cached
        val count = DocumentQueries.query(context, childrenUri, DocumentQueries.ID_PROJECTION) { it.count }
            ?: return 0
        childCount = count
        return count
    }

    override fun listFiles(): MutableList<DocumentFile>? =
        DocumentQueries.query(context, childrenUri, CHILD_PROJECTION) { cursor ->
            val columns = ChildColumns(cursor)
            val children = ArrayList<DocumentFile>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) {
                columns.read(cursor)?.let(children::add)
            }
            childCount = children.size
            children
        }

    /** Scans the children in one query and builds only the matching one. */
    override fun find(name: String): DocumentFile? =
        DocumentQueries.query(context, childrenUri, CHILD_PROJECTION) { cursor ->
            val columns = ChildColumns(cursor)
            var match: DocumentFile? = null
            while (match == null && cursor.moveToNext()) {
                if (columns.displayName(cursor) == name) match = columns.read(cursor)
            }
            match
        }

    override fun renameTo(name: String): DocumentFile? =
        DocumentQueries.rename(context, uri, name)?.let { TreeDocumentFile(parent, context, it, null) }

    override fun refresh() {
        info = DocumentInfo.query(context, uri) ?: DocumentInfo.EMPTY
        childCount = UNKNOWN_COUNT
    }

    /** Builds child documents from the rows of a children query. */
    private inner class ChildColumns(cursor: Cursor) {
        private val documentId = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        private val displayName = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        private val infoColumns = DocumentInfo.Columns(cursor)

        fun displayName(cursor: Cursor): String? = cursor.stringOrNull(displayName)

        /** @return the child at the current row, or `null` if the row has no document ID. */
        fun read(cursor: Cursor): DocumentFile? {
            val id = cursor.stringOrNull(documentId) ?: return null
            val childInfo = infoColumns.read(cursor)
            val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
            return if (childInfo.isDirectory) {
                TreeDocumentFile(this@TreeDocumentFile, context, childUri, childInfo)
            } else {
                SingleDocumentFile(this@TreeDocumentFile, context, childUri, childInfo)
            }
        }
    }

    private companion object {
        const val UNKNOWN_COUNT = -1

        val CHILD_PROJECTION: Array<String> =
            arrayOf(Document.COLUMN_DOCUMENT_ID) + DocumentInfo.PROJECTION
    }
}
