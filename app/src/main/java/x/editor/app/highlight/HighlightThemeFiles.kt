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

package x.editor.app.highlight

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

import androidx.annotation.WorkerThread

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import x.editor.app.AppDirs
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.treesitter.TreeSitterResources
import x.editor.app.util.Downloads
import x.editor.app.config.TemplateText
import x.editor.app.config.Templates

/**
 * Where the highlight themes are, and the ones the user brings in or takes out.
 *
 *  - Downloaded: `themes/` of the tree-sitter resources (TreeSitterResources.themesDir),
 *    replaced as a whole when they are updated.
 *  - The user's own, in `home/local/themes/<name>.json` (AppDirs.local), where an update
 *    does not touch them:
 *      - imported ([import]) from the storage of the device, checked to be a theme. A
 *        name that a downloaded theme has already gets [IMPORTED_SUFFIX], so that both
 *        stay; an imported theme of the same name is replaced (the newer version of it);
 *      - made on ThemeSheet ([create], [write]): under a name no theme has yet; renamed
 *        there ([rename]) when the name it says it has is changed.
 *    Themes kept elsewhere by earlier versions are not moved (they go with the app).
 *
 * The settings list both ([allThemes]) and choose one; ThemeSheet lists the user's own
 * ([importedThemes]) and makes, edits and removes them. A theme is read from its file
 * ([fileOf]).
 * [export] writes the file of a theme, as it is, into the Download directory.
 */
object HighlightThemeFiles {

    private const val TAG = "HighlightThemeFiles"

    // where the user's own themes are kept: home/local/themes (AppDirs.local)
    private const val DIR = "themes"

    private const val EXTENSION = ".json"

    const val MIME_TYPE = "application/json"

    /** What the system file picker offers: JSON, text, and files it cannot tell the type of. */
    val PICKER_MIME_TYPES = arrayOf(MIME_TYPE, "text/*", "application/octet-stream")

    /** What a theme file may weigh at most (themes are tens of KB). */
    const val MAX_SIZE_MB = 2
    private const val MAX_BYTES = MAX_SIZE_MB * 1024L * 1024L

    private const val MAX_NAME_LENGTH = 64

    /** Put after the name of an imported theme that a downloaded one has already. */
    const val IMPORTED_SUFFIX = "_custom"

    /** An import that did not go in: [message] says why, for the user. */
    class ImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // #region where the themes are

    private fun importedDir(context: Context): File = File(AppDirs.local(context), DIR)

    /** Where the user's own themes are: home/local/themes. */
    fun customDirectory(context: Context): File = importedDir(context)

    private fun importedFile(context: Context, name: String): File = File(importedDir(context), name + EXTENSION)

    /** Whether the theme [name] was imported (and can be removed). */
    fun isImported(context: Context, name: String): Boolean = importedFile(context, name).isFile

    /** The names of the themes in [dir], `<name>.json`. */
    private fun namesIn(dir: File): List<String> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.map { it.name.removeSuffix(EXTENSION) }
            .orEmpty()

    /** The themes imported, by name. */
    fun importedThemes(context: Context): List<String> = namesIn(importedDir(context)).sorted()

    /** Every theme there is, downloaded or imported, by name, sorted. */
    fun allThemes(context: Context): List<String> =
        (namesIn(TreeSitterResources.themesDir(context)) + namesIn(importedDir(context))).distinct().sorted()

    /**
     * The file of the theme [name]: the imported one if there is one, else the downloaded
     * one; null if there is neither.
     */
    fun fileOf(context: Context, name: String): File? =
        importedFile(context, name).takeIf { it.isFile }
            ?: TreeSitterResources.themeFile(context, name).takeIf { it.isFile }

    // #endregion

    // #region import

    /**
     * Imports the theme at [uri]: reads it, checks that it is a highlight theme ([validate])
     * and keeps it, under the name of its file (made safe, see [nameFor]). Not on the
     * main thread.
     *
     * @return the name of the theme, for the setting
     * @throws ImportException if it cannot be read, is not a theme, or cannot be kept;
     *   its message says which, for the user
     */
    @WorkerThread
    @Throws(ImportException::class)
    fun import(context: Context, uri: Uri): String {
        val bytes: ByteArray? = try {
            val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            input.use { readAtMost(it, MAX_BYTES) }
        } catch (e: IOException) {
            throw ImportException(context.getString(R.string.theme_import_error_read), e)
        } catch (e: SecurityException) {
            throw ImportException(context.getString(R.string.theme_import_error_read), e)
        }
        if (bytes == null) throw ImportException(context.getString(R.string.theme_import_error_too_large, MAX_SIZE_MB))

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val name = nameFor(context, displayName(context, uri))
        validate(context, name, text)

        val dir = importedDir(context)
        val temp = File(dir, ".$name$EXTENSION.part")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make ${dir.path}")
            temp.writeText(text, Charsets.UTF_8)
            val target = importedFile(context, name)
            if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
            if (!temp.renameTo(target)) throw IOException("cannot store ${target.name}")
        } catch (e: IOException) {
            throw ImportException(context.getString(R.string.theme_import_error_store), e)
        } finally {
            temp.delete()
        }
        // read again from the new file, wherever it is used
        HighlightThemes.forget(name)
        return name
    }

    /**
     * The theme [name] of [text], if it is one: a JSON object with an object of captures
     * (`captures`, or `highlights` of older themes) of which at least one can be used,
     * and if it has `editor`, an object.
     *
     * @throws ImportException if it is not; its message says why, for the user
     */
    @Throws(ImportException::class)
    fun validate(context: Context, name: String, text: String): HighlightTheme {
        if (text.isBlank()) throw ImportException(context.getString(R.string.theme_import_error_empty))
        val root: JsonElement = try {
            // comments and trailing commas allowed, as the themes are read
            HighlightTheme.JSON.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException: not JSON; where, in a line of the file
            val line = FriendlySnippets.errorLine(text, e.message)
            val reason = FriendlySnippets.errorMessage(e)
            throw ImportException(
                if (line > 0) context.getString(R.string.theme_import_error_json_line, line, reason)
                else context.getString(R.string.theme_import_error_json, reason),
                e
            )
        }
        if (root !is JsonObject) throw ImportException(context.getString(R.string.theme_import_error_not_object))
        if ((root["captures"] ?: root["highlights"]) !is JsonObject) {
            throw ImportException(context.getString(R.string.theme_import_error_no_captures))
        }
        val editor = root["editor"]
        if (editor != null && editor !is JsonObject) {
            throw ImportException(context.getString(R.string.theme_import_error_editor))
        }
        val theme = HighlightTheme.parse(name, root)
        if (theme.captureCount == 0) throw ImportException(context.getString(R.string.theme_import_error_no_styles))
        return theme
    }

    /**
     * The name the theme [text] says it has (`meta.colorscheme`), shown on ThemeSheet; [name]
     * (of its file, which it goes by everywhere else) if it says none, or is not JSON.
     */
    internal fun titleOf(text: String, name: String): String = try {
        val meta = (HighlightTheme.JSON.parseToJsonElement(text) as? JsonObject)?.get("meta") as? JsonObject
        (meta?.get("colorscheme") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: name
    } catch (e: IllegalArgumentException) {
        name
    }

    /**
     * What the file of the theme [name] is to be called when [text] is saved: the name [text]
     * says it has ([titleOf]), if that is not what [saved] (its file; null: a new theme, not
     * saved yet, whose name is [name]) said, nor [name] itself; null: it stays as it is.
     */
    internal fun renameOf(name: String, saved: String?, text: String): String? {
        val before = saved?.let { titleOf(it, name) } ?: name
        return titleOf(text, name).takeIf { it != before && it != name }
    }

    /**
     * The name of an imported theme of the file [displayName]: its name without
     * `.json`, made safe ([safeName]); one a downloaded theme has already (or the value
     * of "follow the app theme") gets [IMPORTED_SUFFIX], and a number if need be.
     */
    private fun nameFor(context: Context, displayName: String?): String {
        val base = safeName(displayName)
        fun taken(name: String) =
            name == Constants.HIGHLIGHT_THEME_AUTO || TreeSitterResources.themeFile(context, name).isFile
        if (!taken(base)) return base
        var name = base + IMPORTED_SUFFIX
        var n = 2
        while (taken(name)) name = "$base${IMPORTED_SUFFIX}_${n++}"
        return name
    }

    /**
     * A name for the theme of the file [displayName]: without the extension, letters,
     * digits, `-` and `_` only (anything else, such as the ` (1)` of a second download,
     * becomes `_`), at most [MAX_NAME_LENGTH] chars; "theme" if nothing is left.
     */
    internal fun safeName(displayName: String?): String {
        val file = displayName.orEmpty().substringAfterLast('/')
        val stem = if (file.endsWith(EXTENSION, ignoreCase = true)) file.dropLast(EXTENSION.length)
            else file.substringBeforeLast('.', file)
        return stem.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .replace(Regex("_{2,}"), "_")
            .trim('_', '-')
            .take(MAX_NAME_LENGTH)
            .ifEmpty { "theme" }
    }

    // all of [input]; null if it is longer than [max] bytes
    private fun readAtMost(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // #endregion

    /**
     * Writes the file of the theme [name], as it is, into the Download directory, as
     * `<name>.json` (see Downloads). Not on the main thread.
     *
     * @return the path of the file written
     * @throws IOException if the theme has no file, or it could not be written
     */
    @WorkerThread
    @Throws(IOException::class)
    fun export(context: Context, name: String): String {
        val file = fileOf(context, name) ?: throw FileNotFoundException("no theme $name")
        return Downloads.save(context, name + EXTENSION, MIME_TYPE, file.readBytes())
    }

    // #region the user's own (ThemeSheet)

    /** Whether [name] can name a theme of the user's: letters, digits, `-` and `_`, as [safeName] makes them. */
    fun isValidName(name: String): Boolean = name.isNotEmpty() && safeName(name) == name

    /**
     * Whether there is a theme [name] already: the user's own, or a downloaded one (or it
     * is the value of "follow the app theme"). A new theme of that name is not saved.
     */
    fun exists(context: Context, name: String): Boolean =
        name == Constants.HIGHLIGHT_THEME_AUTO || importedFile(context, name).exists() ||
            TreeSitterResources.themeFile(context, name).exists()

    /** The text of the user's theme [name]; null if there is none. Not on the main thread. */
    @WorkerThread
    fun readCustom(context: Context, name: String): String? =
        importedFile(context, name).takeIf { it.isFile }?.readText()

    /**
     * Saves [text] as a new theme of the user's, [name]: not if there is a theme of that
     * name already ([exists]); checked to be a theme first ([validate]). Not on the main
     * thread.
     *
     * @return false if there is a theme of that name already (nothing written)
     * @throws ImportException if it is not a theme, or cannot be written
     */
    @WorkerThread
    @Throws(ImportException::class)
    fun create(context: Context, name: String, text: String): Boolean {
        if (exists(context, name)) return false
        write(context, name, text)
        return true
    }

    /**
     * Writes [text] as the theme of the user's [name] (made, or replaced: one opened on
     * ThemeSheet), as it is written, whole or not at all; checked to be a theme first.
     * Not on the main thread.
     *
     * @throws ImportException if it is not a theme, or cannot be written
     */
    @WorkerThread
    @Throws(ImportException::class)
    fun write(context: Context, name: String, text: String) {
        validate(context, name, text)
        val dir = importedDir(context)
        val temp = File(dir, ".$name$EXTENSION.part")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make ${dir.path}")
            temp.writeText(if (text.endsWith("\n")) text else text + "\n", Charsets.UTF_8)
            val target = importedFile(context, name)
            if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.name}")
            if (!temp.renameTo(target)) throw IOException("cannot store ${target.name}")
        } catch (e: IOException) {
            throw ImportException(context.getString(R.string.theme_import_error_store), e)
        } finally {
            temp.delete()
        }
        // read again from the new file, wherever it is used
        HighlightThemes.forget(name)
    }

    /**
     * Saves [text] as the user's theme [from] under the name [to]: written there, whole or not
     * at all, then its file under the old name goes. Not if there is a theme of that name
     * already ([exists]). Not on the main thread.
     *
     * @return false if there is a theme of that name already (nothing changed)
     * @throws ImportException if [to] cannot name a file, it is not a theme, or it cannot be written
     */
    @WorkerThread
    @Throws(ImportException::class)
    fun rename(context: Context, from: String, to: String, text: String): Boolean {
        if (!isValidName(to)) throw ImportException(context.getString(R.string.theme_rename_invalid, to))
        if (exists(context, to)) return false
        write(context, to, text)
        if (!importedFile(context, from).delete()) Log.w(TAG, "cannot delete the theme $from, now $to")
        HighlightThemes.forget(from)
        return true
    }

    /**
     * A new theme [name] to start from: the template of the assets (`templates/theme/theme.jsonc`:
     * dark, with the colors of the editor and the captures most grammars have, each a style
     * or a link to another), its name put in for its placeholder ([fillTemplate]). Reads
     * the asset: not on the main thread.
     */
    @WorkerThread
    fun template(context: Context, name: String): String =
        fillTemplate(Templates.read(context, Templates.THEME), name)

    /**
     * [template] with [name] in place of its placeholder, the string `"{{name}}"` (the value
     * of `meta.colorscheme`), quotes and all: a JSON string, quoted and escaped. The
     * comments of the template do not write it in quotes, so that nothing else is taken
     * for the placeholder.
     */
    internal fun fillTemplate(template: String, name: String): String =
        TemplateText.fill(template, values = mapOf("name" to TemplateText.jsonString(name)))


    // #endregion

    /** Removes the imported theme [name]; a downloaded one of that name is used again. */
    fun delete(context: Context, name: String): Boolean {
        val file = importedFile(context, name)
        // (one gone already is what was asked for)
        val deleted = file.delete() || !file.exists()
        if (!deleted) Log.w(TAG, "cannot delete the theme $name")
        HighlightThemes.forget(name)
        return deleted
    }
}
