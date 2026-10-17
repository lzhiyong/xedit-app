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
import android.provider.DocumentsContract.Document
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * A [DocumentFile] backed by a [File] on disk. Created by [DocumentFile.fromFile].
 *
 * Nothing is cached: every accessor reads the file system. Names passed to [createFile],
 * [createDirectory] and [renameTo] must be plain names; names containing a path separator,
 * as well as `.` and `..`, are rejected so that a document can never escape its directory.
 */
class RawDocumentFile(
    private val parent: DocumentFile?,
    private val context: Context,
    private val file: File,
) : DocumentFile() {

    private val uri: Uri = Uri.fromFile(file)

    /**
     * Appends the extension registered for [mimeType], unless [displayName] already ends
     * with it. If no extension is registered (e.g. `application/octet-stream`), the file is
     * created with [displayName] unchanged.
     *
     * @return the new file, or `null` if it already exists or could not be created.
     */
    override fun createFile(mimeType: String, displayName: String): DocumentFile? {
        if (!isPlainName(displayName)) return null
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val name = if (extension.isNullOrEmpty() || displayName.endsWith(".$extension", ignoreCase = true)) {
            displayName
        } else {
            "$displayName.$extension"
        }
        val target = File(file, name)
        return try {
            if (target.createNewFile()) RawDocumentFile(this, context, target) else null
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create $target", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to create $target", e)
            null
        }
    }

    /** @return the directory, also if it already existed, or `null` on failure. */
    override fun createDirectory(displayName: String): DocumentFile? {
        if (!isPlainName(displayName)) return null
        val target = File(file, displayName)
        return if (target.isDirectory || target.mkdir()) RawDocumentFile(this, context, target) else null
    }

    override fun getContext(): Context = context

    override fun getUri(): Uri = uri

    override fun getName(): String = file.name

    override fun getType(): String =
        if (file.isDirectory) Document.MIME_TYPE_DIR else typeForName(file.name)

    override fun getParent(): DocumentFile? = parent

    override fun isDirectory(): Boolean = file.isDirectory

    override fun isFile(): Boolean = file.isFile

    override fun isVirtual(): Boolean = false

    override fun lastModified(): Long = file.lastModified()

    override fun length(): Long = file.length()

    override fun canRead(): Boolean = file.canRead()

    override fun canWrite(): Boolean = file.canWrite()

    /**
     * Deletes this file, or this directory and its contents. Symbolic links are deleted
     * themselves; their targets are never touched.
     */
    override fun delete(): Boolean {
        if (file.isDirectory && !file.isSymlink()) deleteContents(file)
        return file.delete()
    }

    override fun exists(): Boolean = file.exists()

    override fun getChildCount(): Int = file.list()?.size ?: 0

    override fun listFiles(): MutableList<DocumentFile>? {
        if (!file.isDirectory) return null
        val children = file.listFiles() ?: return null
        return children.mapTo(ArrayList(children.size)) { RawDocumentFile(this, context, it) }
    }

    /**
     * Renames the file within its directory. Fails, rather than overwriting, if a file
     * with the new name already exists.
     */
    override fun renameTo(name: String): DocumentFile? {
        if (!isPlainName(name)) return null
        val directory = file.parentFile ?: return null
        val target = File(directory, name)
        if (target.exists()) return null
        return if (file.renameTo(target)) RawDocumentFile(parent, context, target) else null
    }

    private companion object {

        private const val DEFAULT_MIME_TYPE = "application/octet-stream"

        fun typeForName(name: String): String {
            val lastDot = name.lastIndexOf('.')
            if (lastDot < 0) return DEFAULT_MIME_TYPE
            val extension = name.substring(lastDot + 1).lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
        }

        fun isPlainName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\u0000' !in name

        /**
         * Detects a symbolic link without java.nio.file (API 26): the canonical path of a
         * link differs from the path built from its canonical parent and its own name.
         */
        fun File.isSymlink(): Boolean = try {
            val canonicalParent = parentFile?.canonicalFile
            canonicalParent != null && File(canonicalParent, name).absolutePath != canonicalPath
        } catch (e: IOException) {
            false
        }

        fun deleteContents(directory: File): Boolean {
            var success = true
            directory.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.isSymlink()) {
                    success = deleteContents(child) and success
                }
                if (!child.delete()) {
                    Log.w(TAG, "Failed to delete ${child.path}")
                    success = false
                }
            }
            return success
        }
    }
}
