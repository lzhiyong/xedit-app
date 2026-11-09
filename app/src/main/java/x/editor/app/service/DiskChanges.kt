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

package x.editor.app.service

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log

import androidx.annotation.WorkerThread

import java.io.File
import java.io.FileNotFoundException

/**
 * What the storage tells of a file without reading it: when it was changed last and
 * how long it is. Two stamps that differ mean the file was (probably) written; the
 * content decides (see [DiskChanges.verdict]).
 *
 * @param lastModified milliseconds since the epoch; [UNKNOWN] if the provider does not say
 * @param size in bytes; [UNKNOWN] if the provider does not say
 */
data class DocumentStamp(val lastModified: Long, val size: Long) {

    /** Whether there is anything in it to compare. */
    val isKnown: Boolean get() = lastModified != UNKNOWN || size != UNKNOWN

    companion object {
        const val UNKNOWN = -1L
    }
}

/** What the storage says about the file of a document right now. */
sealed interface DiskState {
    /** It is there, with this [stamp]. */
    data class Present(val stamp: DocumentStamp) : DiskState

    /** It is gone: the provider has no such document, or there is no such file. */
    data object Missing : DiskState

    /**
     * Nothing can be told: the permission is gone, the provider failed or does not
     * answer such questions. Never taken for a change.
     */
    data object Unknown : DiskState
}

/**
 * Finds out whether the file of an open document was changed or deleted by someone
 * else (another app, a build in Termux, a git checkout): see EditorActivity, which
 * asks [stateOf] for the document shown, when the screen comes back, when its tab is
 * selected, and at the interval of the settings while it is shown.
 *
 * Only the stamp is asked for each time, one query or one `stat`; the file is read
 * (and hashed) only when the stamp changed, and it counts as changed only when its
 * content differs from the one the editor knows. A file that was only touched, or
 * written back by the editor itself, is not reported.
 */
object DiskChanges {

    private const val TAG = "DiskChanges"

    /** What [verdict] makes of a new [DiskState]. */
    enum class Verdict {
        /** The same stamp as before, or nothing that can be compared. */
        UNCHANGED,

        /** Another stamp: the content may have changed, compare it. */
        STAMP_CHANGED,

        /** The file is gone. */
        DELETED
    }

    /**
     * What [now] means for a document whose file had the stamp [known] when it was
     * read or written last. A document without a known stamp (the provider never said)
     * only notices that its file is gone.
     */
    fun verdict(known: DocumentStamp?, now: DiskState): Verdict = when (now) {
        DiskState.Missing -> Verdict.DELETED
        DiskState.Unknown -> Verdict.UNCHANGED
        is DiskState.Present -> when {
            known == null || !known.isKnown || !now.stamp.isKnown -> Verdict.UNCHANGED
            changed(known.lastModified, now.stamp.lastModified) -> Verdict.STAMP_CHANGED
            changed(known.size, now.stamp.size) -> Verdict.STAMP_CHANGED
            else -> Verdict.UNCHANGED
        }
    }

    // a value that one of the two does not know cannot tell a change
    private fun changed(before: Long, now: Long): Boolean =
        before != DocumentStamp.UNKNOWN && now != DocumentStamp.UNKNOWN && before != now

    /**
     * What the storage says about [uri] now. Never throws: what cannot be told is
     * [DiskState.Unknown].
     */
    @WorkerThread
    fun stateOf(context: Context, uri: Uri): DiskState = try {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> fileState(uri)
            ContentResolver.SCHEME_CONTENT -> contentState(context, uri)
            else -> DiskState.Unknown
        }
    } catch (e: SecurityException) {
        // the permission is gone: opening it again says so, a check does not
        DiskState.Unknown
    } catch (e: Exception) {
        Log.w(TAG, "cannot check $uri", e)
        DiskState.Unknown
    }

    private fun fileState(uri: Uri): DiskState {
        val file = File(uri.path ?: return DiskState.Unknown)
        if (!file.exists()) {
            // a parent that cannot be read hides the file as well as deleting it does
            val parent = file.parentFile
            return if (parent == null || parent.canRead()) DiskState.Missing else DiskState.Unknown
        }
        return DiskState.Present(DocumentStamp(file.lastModified(), file.length()))
    }

    // Every column the provider has: a DocumentsProvider answers with last_modified, a
    // MediaStore with date_modified (seconds), a FileProvider with the size only.
    private fun contentState(context: Context, uri: Uri): DiskState {
        val cursor = try {
            context.contentResolver.query(uri, null, null, null, null)
        } catch (e: FileNotFoundException) {
            return DiskState.Missing
        } catch (e: IllegalArgumentException) {
            // some providers (ExternalStorageProvider among them) say a missing document
            // this way; a document that is still there is opened as usual afterwards
            return if (isDocumentMissing(context, uri)) DiskState.Missing else DiskState.Unknown
        }
        // A DocumentsProvider answers a query for a document that is gone with null (it
        // logs the FileNotFoundException), or with no row.
        if (cursor == null) {
            return if (DocumentsContract.isDocumentUri(context, uri)) DiskState.Missing else DiskState.Unknown
        }
        return cursor.use {
            if (!it.moveToFirst()) {
                return@use if (DocumentsContract.isDocumentUri(context, uri)) DiskState.Missing else DiskState.Unknown
            }
            val lastModified = it.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                ?: it.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.let { seconds -> seconds * 1000 }
                ?: DocumentStamp.UNKNOWN
            val size = it.longOrNull(OpenableColumns.SIZE) ?: DocumentStamp.UNKNOWN
            DiskState.Present(DocumentStamp(lastModified, size))
        }
    }

    // Whether opening [uri] fails because it is not there (rather than for a missing
    // permission or a failing provider).
    private fun isDocumentMissing(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
        false
    } catch (e: FileNotFoundException) {
        true
    } catch (e: Exception) {
        false
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getLong(index)
        } catch (e: Exception) {
            null
        }
    }
}
