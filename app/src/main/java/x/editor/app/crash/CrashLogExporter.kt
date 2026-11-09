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

import android.content.Context

import androidx.annotation.WorkerThread

import java.io.IOException
import java.util.Date

import x.editor.app.util.Downloads

/**
 * Writes a crash report into the public Download directory
 * (/sdcard/Download), named crash_<yyyyMMdd>_<HHmmss>.txt.
 *
 * See [Downloads]: through MediaStore.Downloads on API 29+ (no permission needed, and
 * the file belongs to the user, so it survives uninstalling the app), a plain file
 * below (WRITE_EXTERNAL_STORAGE).
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
        return Downloads.save(context, name, MIME_TYPE, content.toByteArray(Charsets.UTF_8))
    }
}
