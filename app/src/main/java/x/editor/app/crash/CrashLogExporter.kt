/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.crash

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread

import java.io.File
import java.io.IOException
import java.util.Date

/**
 * Writes a crash report into the public Download directory
 * (/sdcard/Download), named crash_<yyyyMMdd>_<HHmmss>.txt.
 *
 * API 29+: through MediaStore.Downloads. No permission needed, and the file
 *          belongs to the user, so it survives uninstalling the app.
 * API < 29: a plain file write; needs WRITE_EXTERNAL_STORAGE
 *          (declared with maxSdkVersion="28" in the manifest).
 */
internal object CrashLogExporter {

    private const val MIME_TYPE = "text/plain"

    /**
     * @return a human-readable location of the exported file
     * @throws IOException if the file could not be written
     */
    @WorkerThread
    @Throws(IOException::class)
    fun export(context: Context, content: String): String {
        val name = CrashReportFormatter.exportFileName(Date())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(context, name, content)
        } else {
            exportToFile(context, name, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStore(context: Context, name: String, content: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until fully written
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("cannot open $uri for writing")
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Do not leave an empty or half-written entry behind
            resolver.delete(uri, null, null)
            throw t
        }

        // MediaStore renames on a name clash ("crash_xxx (1).txt"): report the real name
        val actualName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (t: Throwable) {
            null
        } ?: name
        return "${Environment.DIRECTORY_DOWNLOADS}/$actualName"
    }

    @Suppress("DEPRECATION")
    private fun exportToFile(context: Context, name: String, content: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create ${dir.path}")
        }
        val file = File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        // Make it show up in file managers / over MTP right away
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(MIME_TYPE), null)
        return file.path
    }
}
