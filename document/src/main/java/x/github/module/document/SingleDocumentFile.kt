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
import android.net.Uri
import android.provider.DocumentsContract.Document

/**
 * A [DocumentFile] for a single document, without access to its siblings or children.
 *
 * Created by [DocumentFile.fromSingleUri], or as a non-directory child of a
 * [TreeDocumentFile]. Construction performs no I/O: metadata is read on first access (or
 * comes from the listing query) and cached until [refresh] is called. If it cannot be read,
 * accessors return empty values (`""`, `0`, `false`) until a [refresh] succeeds.
 */
class SingleDocumentFile internal constructor(
    private val parent: DocumentFile?,
    private val context: Context,
    private val uri: Uri,
    initialInfo: DocumentInfo?,
) : DocumentFile() {

    /** @param uri a document Uri, such as the result of `ACTION_OPEN_DOCUMENT`. */
    constructor(parent: DocumentFile?, context: Context, uri: Uri) : this(parent, context, uri, null)

    @Volatile
    private var info: DocumentInfo? = initialInfo

    private fun info(): DocumentInfo =
        info ?: (DocumentInfo.query(context, uri) ?: DocumentInfo.EMPTY).also { info = it }

    /** @throws UnsupportedOperationException always. */
    override fun createFile(mimeType: String, displayName: String): DocumentFile? =
        throw UnsupportedOperationException("A single document has no children")

    /** @throws UnsupportedOperationException always. */
    override fun createDirectory(displayName: String): DocumentFile? =
        throw UnsupportedOperationException("A single document has no children")

    override fun getContext(): Context = context

    override fun getUri(): Uri = uri

    override fun getName(): String = info().displayName

    override fun getType(): String = info().mimeType

    override fun getParent(): DocumentFile? = parent

    override fun isDirectory(): Boolean = info().mimeType == Document.MIME_TYPE_DIR

    override fun isFile(): Boolean = info().let { it.mimeType.isNotEmpty() && !it.isDirectory }

    override fun isVirtual(): Boolean = DocumentFlags.isVirtual(info().flags)

    override fun lastModified(): Long = info().lastModified

    override fun length(): Long = info().size

    override fun canRead(): Boolean = DocumentQueries.canRead(context, uri, info().mimeType)

    override fun canWrite(): Boolean =
        info().let { DocumentQueries.canWrite(context, uri, it.mimeType, it.flags) }

    override fun delete(): Boolean = DocumentQueries.delete(context, uri)

    override fun exists(): Boolean = DocumentQueries.exists(context, uri)

    /** Always `0`: the children of a single document can't be enumerated. */
    override fun getChildCount(): Int = 0

    /** Always `null`: the children of a single document can't be enumerated. */
    override fun listFiles(): MutableList<DocumentFile>? = null

    override fun renameTo(name: String): DocumentFile? =
        DocumentQueries.rename(context, uri, name)?.let { SingleDocumentFile(parent, context, it) }

    override fun refresh() {
        info = DocumentInfo.query(context, uri) ?: DocumentInfo.EMPTY
    }
}
