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
import android.net.Uri
import android.util.Log
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap

import java.io.File
import java.io.IOException


class RawDocumentFile(
    private val parent: DocumentFile?,
    private val context: Context,
    private var file: File
) : DocumentFile() {

    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        // Tack on extension when valid MIME type provided
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { extension ->

            val target = File(file, displayName + "." + extension)
            try {
                if (target.createNewFile()) {
                    return@createFile RawDocumentFile(this, context, target)
                } else {
                    return@createFile null
                }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Failed to create file ${e.message}")
            }
        }
        return@createFile null
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        val target = File(file, displayName)
        if (target.isDirectory() || target.mkdir()) {
            return RawDocumentFile(this, context, target)
        } else {
            return null
        }
    }
    
    override fun getContext() = context

    override fun getUri() = Uri.fromFile(file)

    override fun getName() = file.getName()

    override fun getType() = when (file.isDirectory()) {
        true -> Document.MIME_TYPE_DIR
        else -> getTypeForName(file.getName())
    }
    
    override fun getParent() = parent

    override fun isDirectory() = file.isDirectory()

    override fun isFile() = file.isFile()

    override fun isVirtual() = false

    override fun lastModified() = file.lastModified()

    override fun length() = file.length()

    override fun canRead() = file.canRead()

    override fun canWrite() = file.canWrite()

    override fun delete(): Boolean {
        deleteContents(file)
        return file.delete()
    }

    override fun exists() = file.exists()

    override fun getChildCount() = file.listFiles()?.size ?: 0

    override fun listFiles(): MutableList<DocumentFile>? {
        if (isDirectory()) {
            mutableListOf<DocumentFile>().apply {
                file.listFiles()!!.forEach { file ->
                    add(RawDocumentFile(this@RawDocumentFile, context, file))
               }
            }
        }
        // the file isn't directory          
        return null
    }

    override fun renameTo(name: String): DocumentFile? {
        val target = File(file.getParentFile(), name)
        if (file.renameTo(target)) {
            file = target
            return RawDocumentFile(parent, context, file)
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
                    Log.w(LOG_TAG, "Failed to delete ${file.path}")
                    success = false
                }
            }
            return success
        }
    }
}

