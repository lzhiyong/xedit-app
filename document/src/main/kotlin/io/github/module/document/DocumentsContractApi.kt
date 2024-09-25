/*
 * Copyright 2021 The Android Open Source Project
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
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi

object DocumentsContractApi {

    fun isDocumentUri(context: Context, uri: Uri?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return DocumentsContractApi19Impl.isDocumentUri(context, uri)
        } else {
            return false
        }
    }

    fun getDocumentId(documentUri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return DocumentsContractApi19Impl.getDocumentId(documentUri)
        } else {
            return null
        }
    }

    fun getTreeDocumentId(documentUri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return DocumentsContractApi21Impl.getTreeDocumentId(documentUri)
        } else {
            return null
        }
    }

    fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return DocumentsContractApi21Impl.buildDocumentUriUsingTree(treeUri, documentId)
        } else {
            return null
        }
    }

    @RequiresApi(19)
    private object DocumentsContractApi19Impl {
        fun isDocumentUri(context: Context, uri: Uri?): Boolean {
            return DocumentsContract.isDocumentUri(context, uri)
        }

        fun getDocumentId(documentUri: Uri): String {
            return DocumentsContract.getDocumentId(documentUri)
        }
    }

    @RequiresApi(21)
    private object DocumentsContractApi21Impl {
        fun getTreeDocumentId(documentUri: Uri): String {
            return DocumentsContract.getTreeDocumentId(documentUri)
        }

        fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri {
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        }
    }
}
