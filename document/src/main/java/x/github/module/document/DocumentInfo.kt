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
import android.provider.DocumentsContract.Document

/**
 * Immutable snapshot of a document's metadata, read from a single cursor row.
 *
 * Rows are read by column name rather than by position, so providers that return the
 * columns in a different order, or omit some of them, are handled correctly.
 */
internal class DocumentInfo(
    val displayName: String,
    val mimeType: String,
    val lastModified: Long,
    val size: Long,
    val flags: Int,
) {
    val isDirectory: Boolean
        get() = mimeType == Document.MIME_TYPE_DIR

    /** Column indices of a cursor, resolved once and reused for every row. */
    class Columns(cursor: Cursor) {
        private val displayName = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        private val mimeType = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        private val lastModified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        private val size = cursor.getColumnIndex(Document.COLUMN_SIZE)
        private val flags = cursor.getColumnIndex(Document.COLUMN_FLAGS)

        /** Reads the row the cursor is currently positioned on. */
        fun read(cursor: Cursor) = DocumentInfo(
            displayName = cursor.stringOrNull(displayName).orEmpty(),
            mimeType = cursor.stringOrNull(mimeType).orEmpty(),
            lastModified = cursor.longOrZero(lastModified),
            size = cursor.longOrZero(size),
            flags = cursor.intOrZero(flags),
        )
    }

    companion object {
        /** Used when the metadata could not be read. */
        val EMPTY = DocumentInfo("", "", 0L, 0L, 0)

        /** The columns needed to build a [DocumentInfo]. */
        val PROJECTION: Array<String> = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )

        /**
         * Queries the metadata of a single document.
         *
         * @return the metadata, or `null` if the query failed or returned no row.
         */
        fun query(context: Context, documentUri: Uri): DocumentInfo? =
            DocumentQueries.query(context, documentUri, PROJECTION) { cursor ->
                if (cursor.moveToFirst()) Columns(cursor).read(cursor) else null
            }
    }
}

internal fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

internal fun Cursor.longOrZero(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

internal fun Cursor.intOrZero(index: Int): Int =
    if (index < 0 || isNull(index)) 0 else getInt(index)
