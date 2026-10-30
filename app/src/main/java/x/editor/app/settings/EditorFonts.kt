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

package x.editor.app.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

import androidx.core.content.res.ResourcesCompat

import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier

import x.editor.app.R

/**
 * The fonts the editor can use. A font is stored in the settings as a value:
 *
 *  - [DEFAULT]: the default typeface of the system, what the editor had before;
 *  - [MONOSPACE]: the monospace typeface of the system;
 *  - `res:<name>`: a font of the app, `res/font/<name>.ttf` (every font put there
 *    shows up in the list, see [bundledFonts]);
 *  - `file:<name>`: a font imported from the storage of the device ([import]),
 *    copied into the app's files so it stays usable.
 */
object EditorFonts {

    private const val TAG = "EditorFonts"

    const val DEFAULT = "default"
    const val MONOSPACE = "monospace"

    private const val RES_PREFIX = "res:"
    private const val FILE_PREFIX = "file:"

    // where imported fonts are kept, in the files directory
    private const val FONT_DIR = "fonts"

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** What the system file picker offers: fonts, and files it cannot tell the type of. */
    val PICKER_MIME_TYPES = arrayOf(
        "font/*",
        "application/x-font-ttf",
        "application/x-font-otf",
        "application/font-sfnt",
        "application/vnd.ms-opentype",
        "application/octet-stream"
    )

    /**
     * The fonts in `res/font`, by name, sorted. Read from the fields of `R.font`, so a
     * font dropped into the folder needs no code (see proguard-rules.pro, which keeps
     * the fields).
     */
    fun bundledFonts(): List<String> =
        R.font::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.name }
            .sorted()

    /** The fonts that were imported, by file name, sorted. */
    fun importedFonts(context: Context): List<String> =
        fontDir(context).listFiles { file -> file.isFile && file.extension.lowercase() in FONT_EXTENSIONS }
            ?.map { it.name }
            ?.sortedBy { it.lowercase() }
            .orEmpty()

    /** Every value the font setting can have now, in the order of the list. */
    fun allValues(context: Context): List<String> =
        listOf(DEFAULT, MONOSPACE) +
            bundledFonts().map { RES_PREFIX + it } +
            importedFonts(context).map { FILE_PREFIX + it }

    fun isImported(value: String): Boolean = value.startsWith(FILE_PREFIX)

    /** The name of the font [value] for the list. */
    fun label(context: Context, value: String): String = when {
        value == DEFAULT -> context.getString(R.string.pref_editor_font_system_default)
        value == MONOSPACE -> context.getString(R.string.pref_editor_font_system_monospace)
        value.startsWith(RES_PREFIX) -> prettify(value.removePrefix(RES_PREFIX))
        value.startsWith(FILE_PREFIX) -> value.removePrefix(FILE_PREFIX).substringBeforeLast('.')
        else -> value
    }

    /**
     * The typeface of [value]; null for one that cannot be loaded (a file that is
     * gone or broken), which falls back to the default.
     */
    fun typeface(context: Context, value: String): Typeface? = when {
        value == DEFAULT -> Typeface.DEFAULT
        value == MONOSPACE -> Typeface.MONOSPACE
        value.startsWith(RES_PREFIX) -> {
            val id = context.resources.getIdentifier(value.removePrefix(RES_PREFIX), "font", context.packageName)
            if (id == 0) null else runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()
        }
        value.startsWith(FILE_PREFIX) -> {
            val file = File(fontDir(context), value.removePrefix(FILE_PREFIX))
            if (file.isFile) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
        }
        else -> null
    }

    /**
     * Copies the font at [uri] into the app's files, checking that it is a font.
     * Not on the main thread.
     *
     * @return the value of the font, for the setting
     * @throws IOException if it cannot be read, or is not a font
     */
    @Throws(IOException::class)
    fun import(context: Context, uri: Uri): String {
        val name = displayName(context, uri)
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "font_${System.currentTimeMillis()}.ttf"
        val fileName = if (name.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS) name else "$name.ttf"

        val dir = fontDir(context).apply { mkdirs() }
        val temp = File(dir, ".$fileName.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open $uri")
            input.use { source -> temp.outputStream().use { source.copyTo(it) } }

            // A file that is not a font gives an empty typeface, or throws.
            val typeface = try {
                Typeface.createFromFile(temp)
            } catch (e: RuntimeException) {
                throw IOException("not a font: $fileName", e)
            }
            if (typeface == Typeface.DEFAULT) throw IOException("not a font: $fileName")

            val target = File(dir, fileName)
            if (target.exists() && !target.delete()) throw IOException("cannot replace $fileName")
            if (!temp.renameTo(target)) throw IOException("cannot store $fileName")
            return FILE_PREFIX + fileName
        } finally {
            temp.delete()
        }
    }

    /** Deletes the imported font [value]. */
    fun delete(context: Context, value: String): Boolean {
        if (!isImported(value)) return false
        val deleted = File(fontDir(context), value.removePrefix(FILE_PREFIX)).delete()
        if (!deleted) Log.w(TAG, "cannot delete $value")
        return deleted
    }

    private fun fontDir(context: Context) = File(context.filesDir, FONT_DIR)

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // jetbrains_mono_regular → Jetbrains Mono Regular
    private fun prettify(name: String): String =
        name.split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}
