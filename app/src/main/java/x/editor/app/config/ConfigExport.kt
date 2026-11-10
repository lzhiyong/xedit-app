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

package x.editor.app.config

import android.Manifest
import android.app.Activity
import android.widget.Toast

import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import x.editor.app.R
import x.editor.app.highlight.HighlightThemeFiles
import x.editor.app.lsp.FormattingRules
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.lsp.LanguageServers
import x.editor.app.shortcut.KeymapFile
import x.editor.app.toolchain.ToolchainInstaller
import x.editor.app.util.Downloads

/**
 * All the configuration of the developer tools in one zip (ⓘ on any of their panels →
 * Export all), in the public Download directory (`/sdcard/Download`): what a file for each
 * entry does not give, one file to back up or move to another device.
 *
 * ```
 * xedit-config-20261102-143000.zip
 * ├── servers/<id>.json        the language servers
 * ├── formatting/<lang>.json   the formatting rules
 * ├── snippets/<lang>.json     the user's snippets
 * ├── themes/<name>.json       the user's highlight themes
 * ├── keymap.json              the shortcuts
 * └── packages.json            the toolchain
 * ```
 *
 * Only what is there is put in (nothing is made to be exported). Below Android 10 the
 * storage permission is asked for first (Downloads.needsPermission).
 */
internal object ConfigExport {

    /** Exports, then says where the zip is (or what went wrong). */
    fun export(activity: Activity, scope: CoroutineScope) {
        if (Downloads.needsPermission(activity)) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            Toast.makeText(activity, R.string.config_export_permission, Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (bytes, count) = zip(activity)
                    if (count == 0) null else Downloads.save(activity, fileName(), MIME_TYPE, bytes) to count
                }
            }
            val message = result.fold(
                onSuccess = { saved ->
                    if (saved == null) activity.getString(R.string.config_export_nothing)
                    else activity.getString(R.string.config_export_done, saved.second, saved.first)
                },
                onFailure = { e -> activity.getString(R.string.config_export_failed, e.message ?: e.javaClass.simpleName) },
            )
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** The zip of all of it, and how many files are in it. */
    @WorkerThread
    fun zip(activity: Activity): Pair<ByteArray, Int> {
        val entries = ArrayList<Pair<String, File>>()
        fun directory(name: String, dir: File) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                .sortedBy { it.name }
                .forEach { entries += "$name/${it.name}" to it }
        }
        directory("servers", LanguageServers.directory(activity))
        directory("formatting", FormattingRules.directory(activity))
        directory("snippets", FriendlySnippets.userDirectory(activity))
        directory("themes", HighlightThemeFiles.customDirectory(activity))
        KeymapFile.file(activity).takeIf { it.isFile }?.let { entries += KeymapFile.FILE to it }
        File(LanguageServers.configDirectory(activity), ToolchainInstaller.CATALOG).takeIf { it.isFile }
            ?.let { entries += ToolchainInstaller.CATALOG to it }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, file) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray() to entries.size
    }

    private fun fileName(): String =
        "xedit-config-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"

    private const val MIME_TYPE = "application/zip"

    // the request of the storage permission (below Android 10)
    private const val REQUEST_STORAGE = 0x7f
}
