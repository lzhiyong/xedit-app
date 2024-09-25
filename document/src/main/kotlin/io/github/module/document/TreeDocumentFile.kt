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
import android.provider.DocumentsContract
import android.util.Log

import androidx.annotation.RequiresApi

@RequiresApi(21)
class TreeDocumentFile(
    parent: DocumentFile?,
    private val mContext: Context,
    private var mUri: Uri
) : DocumentFile(parent) {
    
    private var mDisplayName: String = ""
    private var mMimeType: String = ""
    private var mLastModified: Long = 0L
    private var mFlags: Long = 0L
    private var mChildCount: Int = 0
    
    private val projection: Array<String> = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    )
    
    init {
        executeQuery(mUri, projection) { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                mDisplayName = cursor.getString(0)
                mLastModified = cursor.getLong(1)
                mFlags = cursor.getLong(2)
            }
        }
        
        // query the count of the children file
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            mUri,
            DocumentsContract.getDocumentId(mUri)
        )
        
        executeQuery(childrenUri, arrayOf<String>()) { cursor ->
            mChildCount = cursor.getCount()
        }
    }
    
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        TreeDocumentFile.createFile(mContext, mUri, mimeType, displayName)?.let { uri ->
            return SingleDocumentFile(this, mContext, uri)
        }
        return null
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        TreeDocumentFile.createFile(
            mContext,
            mUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName
        )?.let { uri ->
            return TreeDocumentFile(this, mContext, uri)
        }
        return null
    }

    override fun getUri() = mUri

    override fun getName() = mDisplayName

    override fun getType() = mMimeType

    override fun isDirectory() = true 
    
    override fun isFile() = false
    
    override fun lastModified() = mLastModified

    override fun length() = 0L
    
    override fun getChildCount() = mChildCount
    
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
    
    private fun executeQuery(
        uriTree: Uri, 
        column: Array<String>,
        block: (cursor: Cursor) -> Unit
    ) {
        mContext.contentResolver.query(
            uriTree, column, null, null, null
        )?.use { cursor ->
            block(cursor)
        }
    }
    
    override fun listFiles(): MutableList<DocumentFile> {
        val results = mutableListOf<DocumentFile>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            mUri,
            DocumentsContract.getDocumentId(mUri)
        )
        
        val column = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        
        executeQuery(childrenUri, column) { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    mUri,
                    documentId
                )
                
                if(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    results.add(TreeDocumentFile(this, mContext, documentUri).apply{
                        mMimeType = cursor.getString(1)
                    })
                } else {
                    results.add(SingleDocumentFile(this, mContext, documentUri))
                }
            }
        }
        
        // sort the result
        return results.apply {
            sortWith(kotlin.Comparator { a, b ->
                if (a.isDirectory() != b.isDirectory()) {
                    b.isDirectory().compareTo(a.isDirectory())
                } else {
                    a.getName().lowercase().compareTo(b.getName().lowercase())
                }
            })
        }
    }

    override fun renameTo(name: String): DocumentFile? {
        try {
            DocumentsContract.renameDocument(
                mContext.contentResolver, mUri, name
            )?.let { result ->
                mUri = result
                return TreeDocumentFile(mParent, mContext, mUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename ${e.message}")
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

