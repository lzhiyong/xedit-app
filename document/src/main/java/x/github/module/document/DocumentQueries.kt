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
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/**
 * Provider operations used by the document implementations.
 *
 * Every function here catches the exceptions a provider may throw (a deleted document, a
 * revoked permission, a dead provider process) and reports them as `null` or `false`, so
 * that callers never crash because of the state of external storage.
 */
internal object DocumentQueries {

    private const val PATH_TREE = "tree"
    private const val PATH_DOCUMENT = "document"

    /** The smallest useful projection; some providers reject an empty one. */
    val ID_PROJECTION: Array<String> = arrayOf(Document.COLUMN_DOCUMENT_ID)

    /**
     * Runs [block] on the cursor returned for [uri] and closes it.
     *
     * @return the result of [block], or `null` if the query failed, returned no cursor, or
     *   [block] threw.
     */
    inline fun <T> query(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        block: (Cursor) -> T,
    ): T? = try {
        context.contentResolver.query(uri, projection, null, null, null)?.use(block)
    } catch (e: Exception) {
        Log.w(DocumentFile.TAG, "Query failed: $uri", e)
        null
    }

    fun exists(context: Context, uri: Uri): Boolean =
        query(context, uri, ID_PROJECTION) { it.count > 0 } ?: false

    /**
     * Whether a tree Uri also names a document inside the tree
     * (`content://authority/tree/<treeId>/document/<documentId>`), as opposed to only the
     * tree itself (`content://authority/tree/<treeId>`).
     */
    fun hasDocumentPath(treeUri: Uri): Boolean {
        val segments = treeUri.pathSegments
        return segments.size >= 4 && segments[0] == PATH_TREE && segments[2] == PATH_DOCUMENT
    }

    fun canRead(context: Context, uri: Uri, mimeType: String): Boolean =
        DocumentFlags.isReadable(mimeType) &&
            hasUriPermission(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun canWrite(context: Context, uri: Uri, mimeType: String, flags: Int): Boolean =
        DocumentFlags.isWritable(mimeType, flags) &&
            hasUriPermission(context, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun hasUriPermission(context: Context, uri: Uri, modeFlags: Int): Boolean =
        context.checkCallingOrSelfUriPermission(uri, modeFlags) ==
            PackageManager.PERMISSION_GRANTED

    /** @return the Uri of the new document, or `null` on failure. */
    fun create(context: Context, parentUri: Uri, mimeType: String, displayName: String): Uri? =
        try {
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, displayName)
        } catch (e: Exception) {
            Log.w(DocumentFile.TAG, "Failed to create '$displayName' in $parentUri", e)
            null
        }

    /** @return the Uri of the renamed document, or `null` on failure. */
    fun rename(context: Context, uri: Uri, displayName: String): Uri? =
        try {
            DocumentsContract.renameDocument(context.contentResolver, uri, displayName)
        } catch (e: Exception) {
            Log.w(DocumentFile.TAG, "Failed to rename $uri to '$displayName'", e)
            null
        }

    fun delete(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            Log.w(DocumentFile.TAG, "Failed to delete $uri", e)
            false
        }
}
