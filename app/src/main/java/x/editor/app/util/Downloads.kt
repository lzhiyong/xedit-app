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

package x.editor.app.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat

import java.io.File
import java.io.IOException

/**
 * Writes files into the public Download directory (`/sdcard/Download`), where the user
 * finds them with any file manager, and where they stay when the app is uninstalled.
 *
 *  - API 29+: through MediaStore.Downloads. No permission needed.
 *  - API < 29: a plain file; needs WRITE_EXTERNAL_STORAGE ([needsPermission]).
 *
 * A file of that name already there is not replaced: the new one gets a name of its own
 * (`name (1).json`), the one [save] returns.
 */
object Downloads {

    /** The public Download directory, as a path for the user (`/storage/emulated/0/Download`). */
    @Suppress("DEPRECATION")
    val directory: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Whether [save] needs the permission to write to the storage, and does not have it yet. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes [content] as the file [name] of the type [mimeType] into the Download
     * directory. Not on the main thread.
     *
     * @return the path of the file written (its name may differ from [name], see above)
     * @throws IOException if it could not be written
     */
    @WorkerThread
    @Throws(IOException::class)
    fun save(context: Context, name: String, mimeType: String, content: ByteArray): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, name, mimeType, content)
        } else {
            saveToFile(context, name, mimeType, content)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, name: String, mimeType: String, content: ByteArray): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // hidden from other apps until it is all written
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")

        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("cannot open $uri for writing")
            out.use { it.write(content) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // no empty or half-written entry left behind
            resolver.delete(uri, null, null)
            throw t
        }

        // MediaStore renames on a clash ("name (1).json"): the name it got
        val actualName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (t: Throwable) {
            null
        } ?: name
        return File(directory, actualName).path
    }

    private fun saveToFile(context: Context, name: String, mimeType: String, content: ByteArray): String {
        val dir = directory
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        val file = uniqueFile(dir, name)
        file.writeBytes(content)
        // seen at once by file managers, and over MTP
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mimeType), null)
        return file.path
    }

    // [name], or `name (1).ext`, `name (2).ext`, ... as MediaStore names them
    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (file.exists()) {
            file = File(dir, "$base ($n)$extension")
            n++
        }
        return file
    }
}
