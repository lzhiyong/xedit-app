/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import android.net.Uri

class EmptyDocumentFile(
    private val displayName: String,
    private val fakeUri: Uri
): DocumentFile(null) {
    
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        throw UnsupportedOperationException()
    }

    override fun getUri() = fakeUri

    override fun getName() = displayName

    override fun getType() = ""

    override fun isDirectory() = false

    override fun isFile() = false

    override fun lastModified() = 0L

    override fun length() = 0L

    override fun isVirtual() = false

    override fun canRead() = false

    override fun canWrite() = false
    
    override fun delete(): Boolean {
        throw UnsupportedOperationException()
    }

    override fun exists() = false

    override fun getChildCount() = 0

    override fun listFiles(): MutableList<DocumentFile> {
        return mutableListOf<DocumentFile>()
    }
    
    override fun renameTo(name: String): DocumentFile? {
        throw UnsupportedOperationException()
    }
}

