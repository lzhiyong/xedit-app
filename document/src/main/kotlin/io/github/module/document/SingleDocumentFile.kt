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

package io.github.module.document

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi

@RequiresApi(19)
class SingleDocumentFile(
    parent: DocumentFile?,
    private val mContext: Context,
    private var mUri: Uri
) : DocumentFile(parent) {
    
    private var mDisplayName: String = ""
    private var mMimeType: String = ""
    private var mLastModified: Long = 0L
    private var mSize: Long = 0L
    private var mFlags: Long = 0L
    
    private val projection: Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS
    )
    
    init {
        mContext.contentResolver.query(
            mUri, projection, null, null, null
        )?.use{ cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                mDisplayName = cursor.getString(0)
                mMimeType = cursor.getString(1)
                mLastModified = cursor.getLong(2)
                mSize = cursor.getLong(3)
                mFlags = cursor.getLong(4)
            }
        }
    }
    
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }

    override fun getUri() = mUri

    override fun getName() = mDisplayName

    override fun getType() = mMimeType

    override fun isDirectory() = false

    override fun isFile() = true

    override fun lastModified() = mLastModified

    override fun length() = mSize

    override fun isVirtual() = DocumentsContractApi19.isVirtual(mContext, mUri, mFlags.toInt())

    override fun canRead() = DocumentsContractApi19.canRead(mContext, mUri, mMimeType, mFlags.toInt())

    override fun canWrite() = DocumentsContractApi19.canWrite(mContext, mUri, mMimeType, mFlags.toInt())
    
    override fun delete(): Boolean {
        return try {
            DocumentsContract.deleteDocument(mContext.contentResolver, mUri)
        } catch (e: Exception) {
            false
        }
    }

    override fun exists() = DocumentsContractApi19.exists(mContext, mUri)

    override fun getChildCount(): Int{
        throw UnsupportedOperationException()
    }

    override fun listFiles(): MutableList<DocumentFile> {
        throw UnsupportedOperationException()
    }
    
    override fun renameTo(name: String): DocumentFile? {
        try {
            DocumentsContract.renameDocument(
                mContext.contentResolver, mUri, name
            )?.let { result ->
                mUri = result
                return SingleDocumentFile(mParent, mContext, mUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename ${e.message}")
        }
        return null
    }
    
}

