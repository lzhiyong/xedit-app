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

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

import android.provider.DocumentsContract.Document

class RawDocumentFile(
    parent: DocumentFile?,
    private var mFile: File
) : DocumentFile(parent) {

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        // Tack on extension when valid MIME type provided
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { extension ->

            val target = File(mFile, displayName + "." + extension)
            try {
                if (target.createNewFile()) {
                    return@createFile RawDocumentFile(this, target)
                } else {
                    return@createFile null
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create file ${e.message}")
            }
        }
        return@createFile null
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        val target = File(mFile, displayName)
        if (target.isDirectory() || target.mkdir()) {
            return RawDocumentFile(this, target)
        } else {
            return null
        }
    }

    override fun getUri() = Uri.fromFile(mFile)

    override fun getName() = mFile.getName()

    override fun getType() = when (mFile.isDirectory()) {
        true -> Document.MIME_TYPE_DIR
        else -> getTypeForName(mFile.getName())
    }

    override fun isDirectory() = mFile.isDirectory()

    override fun isFile() = mFile.isFile()

    override fun isVirtual() = false

    override fun lastModified() = mFile.lastModified()

    override fun length() = mFile.length()

    override fun canRead() = mFile.canRead()

    override fun canWrite() = mFile.canWrite()

    override fun delete(): Boolean {
        deleteContents(mFile)
        return mFile.delete()
    }

    override fun exists() = mFile.exists()

    override fun getChildCount() = mFile.listFiles().size

    override fun listFiles(): MutableList<DocumentFile> {
        return mutableListOf<DocumentFile>().apply {
            mFile.listFiles()?.forEach { file ->
                add(RawDocumentFile(this@RawDocumentFile, file))
            }
        }.apply {
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
        val target = File(mFile.getParentFile(), name)
        if (mFile.renameTo(target)) {
            mFile = target
            return RawDocumentFile(mParent, mFile)
        } else {
            return null
        }
    }

    companion object {

        private fun getTypeForName(name: String): String {
            val lastDot = name.lastIndexOf('.')
            if (lastDot >= 0) {
                val extension = name.substring(lastDot + 1).lowercase()
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { mime ->
                    return@getTypeForName mime
                }
            }

            return "application/octet-stream"
        }

        private fun deleteContents(dir: File): Boolean {
            var success = true
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory()) {
                    success = success and deleteContents(file)
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete ${file.path}")
                    success = false
                }
            }
            return success
        }
    }
}

