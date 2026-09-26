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
import android.util.Log

import androidx.annotation.RequiresApi

@RequiresApi(21)
class TreeDocumentFile(
    private val parent: DocumentFile?,
    private val context: Context,
    private var uri: Uri
) : DocumentFile() {
    
    private var displayName: String = "."
    private var mimeType: String = "unknown"
    private var lastModified: Long = 0L
    private var flags: Long = 0L
    private var childCount: Int = 0
    
    private val projection: Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    )
    
    init {
        // type directory
        mimeType = Document.MIME_TYPE_DIR
        
        query(uri, projection) { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0)) {
                    displayName = cursor.getString(0)
                }
                if (!cursor.isNull(1)) {
                    lastModified = cursor.getLong(1)
                }
                if (!cursor.isNull(2)) {
                    flags = cursor.getLong(2)
                }
            }
        }
        
        // query the count of the children file
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            DocumentsContract.getDocumentId(uri)
        )
        
        query(childrenUri, arrayOf<String>()) { cursor ->
            childCount = cursor.getCount()
        }
    }
    
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        TreeDocumentFile.createFile(context, uri, mimeType, displayName)?.let { uri ->
            return SingleDocumentFile(this, context, uri)
        }
        return null
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        TreeDocumentFile.createFile(
            context,
            uri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName
        )?.let { uri ->
            return TreeDocumentFile(this, context, uri)
        }
        return null
    }
    
    override fun getContext() = context

    override fun getUri() = uri

    override fun getName() = displayName

    override fun getType() = mimeType
    
    override fun getParent() = parent
    
    override fun isDirectory() = true 
    
    override fun isFile() = false
    
    override fun lastModified() = lastModified

    override fun length() = 0L
    
    override fun getChildCount() = childCount
    
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
    
    private fun query(
        uriTree: Uri, 
        column: Array<String>,
        block: (cursor: Cursor) -> Unit
    ) {
        context.contentResolver.query(
            uriTree, column, null, null, null
        )?.use { cursor ->
            block(cursor)
        }
    }
    
    override fun listFiles(): MutableList<DocumentFile>? {
        val results = mutableListOf<DocumentFile>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri,
            DocumentsContract.getDocumentId(uri)
        )
        
        val column = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        
        query(childrenUri, column) { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    documentId
                )
                
                if(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    results.add(TreeDocumentFile(this, context, documentUri).apply{
                        mimeType = cursor.getString(1)
                    })
                } else {
                    results.add(SingleDocumentFile(this, context, documentUri))
                }
            }
        }
        // return the document file list
        return results
    }

    override fun renameTo(name: String): DocumentFile? {
        try {
            DocumentsContract.renameDocument(
                context.contentResolver, uri, name
            )?.let { result ->
                uri = result
                return TreeDocumentFile(parent, context, uri)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to rename ${e.message}")
        }
        return null
    }

    companion object {

        private fun createFile(
            context: Context,
            self: Uri,
            mimeType: String,
            displayName: String
        ): Uri? {
            return try {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    self,
                    mimeType,
                    displayName
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

