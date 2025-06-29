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
import android.provider.DocumentsContract
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(19)
class SingleDocumentFile(
    private val parent: DocumentFile?,
    private val context: Context,
    private var uri: Uri
) : DocumentFile() {
    
    private var displayName: String = "."
    private var mimeType: String = "unknown"
    private var lastModified: Long = 0L
    private var size: Long = 0L
    private var flags: Long = 0L
    
    private val projection: Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS
    )
    
    init {
        context.contentResolver.query(
            uri, projection, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) { 
                    displayName = cursor.getString(0) 
                }
                
                if (!cursor.isNull(1)) {
                    lastModified = cursor.getLong(1)
                }
                
                if (!cursor.isNull(2)) {
                    mimeType = cursor.getString(2)
                }
                
                if (!cursor.isNull(3)) {
                    size = cursor.getLong(3)
                }
                
                if (!cursor.isNull(4)) { 
                    flags = cursor.getLong(4) 
                }
            }
        }
    }
    
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }
    
    override fun getContext() = context

    override fun getUri() = uri

    override fun getName() = displayName

    override fun getType() = mimeType
    
    override fun getParent() = parent
    
    override fun isDirectory() = false

    override fun isFile() = true

    override fun lastModified() = lastModified

    override fun length() = size

    override fun isVirtual() = DocumentsContractApi19.isVirtual(context, uri, flags.toInt())

    override fun canRead() = DocumentsContractApi19.canRead(context, uri, mimeType, flags.toInt())

    override fun canWrite() = DocumentsContractApi19.canWrite(context, uri, mimeType, flags.toInt())
    
    override fun delete(): Boolean {
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            false
        }
    }

    override fun exists() = DocumentsContractApi19.exists(context, uri)

    override fun getChildCount() = 0

    override fun listFiles() = null
    
    override fun renameTo(name: String): DocumentFile? {
        try {
            DocumentsContract.renameDocument(
                context.contentResolver, uri, name
            )?.let { result ->
                uri = result
                return SingleDocumentFile(parent, context, uri)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to rename ${e.message}")
        }
        return null
    }
    
}

