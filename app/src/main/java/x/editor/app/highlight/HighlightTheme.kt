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
import android.graphics.Color
import android.util.Log

import androidx.core.graphics.ColorUtils

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

import x.editor.app.treesitter.TreeSitterResources

/**
 * A highlight theme, `themes/<name>.json`, in the format exported from
 * Neovim (see astrodark.json):
 *
 * ```
 * {
 *   "meta":     { "colorscheme": "astrodark", "background": "dark", ... },
 *   "editor":   { "background": {"fg": "#adb0bb", "bg": "#1a1d23"}, "cursor": {...}, ... },
 *   "captures": {
 *     "keyword": {"fg": "#dd97f1"},
 *     "comment": {"italic": true, "fg": "#696c76"},
 *     "conditional": "@keyword.conditional",
 *     ...
 *   }
 * }
 * ```
 *
 * A capture is a style (`fg`, `bg`, `bold`, `italic`, `underline`, `undercurl`,
 * `strikethrough`, ...) or a link to another capture (`"@other.name"`). The editor
 * block is optional (see [EditorColorScheme]). Older themes, with the styles in an
 * object `highlights`, are read too.
 */
class HighlightTheme private constructor(
    val name: String,
    /** "dark" or "light", from meta.background; null if it does not say. */
    val background: String?,
    private val captures: Map<String, Capture>,
    /** The colors of the editor itself; null if the theme has none. */
    val editorColors: EditorColorScheme?
) {

    private sealed class Capture {
        class Style(val style: HighlightStyle) : Capture()
        class Link(val target: String) : Capture()
    }

    // a resolved capture: the style, or NONE for none
    private val resolved = ConcurrentHashMap<String, HighlightStyle>()

    /**
     * The style of the capture [name] of a query of the grammar [language] (like
     * "rust"), the way Neovim finds it: `name.language` first, then `name`, then the
     * name with its last part taken off, and so on (`function.method.call` →
     * `function.method` → `function`). Links are followed. Null if nothing matches.
     * Any thread; cached.
     */
    fun styleFor(name: String, language: String?): HighlightStyle? {
        val key = if (language == null) name else "$name\u0000$language"
        resolved[key]?.let { return it.takeIf { it !== NONE } }
        val style = resolve(name, language, depth = 0)
        resolved[key] = style ?: NONE
        return style
    }

    private fun resolve(name: String, language: String?, depth: Int): HighlightStyle? {
        // a link of links of ...: a theme with a cycle
        if (depth > MAX_LINK_DEPTH) return null
        var key = name
        if (language != null) {
            find("$key.$language", depth)?.let { return it }
        }
        while (true) {
            find(key, depth)?.let { return it }
            val dot = key.lastIndexOf('.')
            if (dot < 0) return null
            key = key.substring(0, dot)
        }
    }

    // [key] itself; its link resolved as a capture of its own (with no language)
    private fun find(key: String, depth: Int): HighlightStyle? = when (val capture = captures[key]) {
        is Capture.Style -> capture.style
        is Capture.Link -> resolve(capture.target, null, depth + 1)
        null -> null
    }

    companion object {
        private const val TAG = "HighlightTheme"
        private const val MAX_LINK_DEPTH = 16
        private val NONE = HighlightStyle()

        /**
         * Reads [file], the theme [name].
         *
         * @throws Exception if it is not JSON of that shape (IOException,
         *   SerializationException, IllegalArgumentException)
         */
        fun read(name: String, file: File): HighlightTheme {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val meta = root["meta"] as? JsonObject
            val background = (meta?.get("background") as? JsonPrimitive)?.contentOrNull

            val captures = HashMap<String, Capture>()
            val source = (root["captures"] ?: root["highlights"]) as? JsonObject
            source?.forEach { (key, value) ->
                // keys as Neovim writes them, with or without the @
                val captureName = key.removePrefix("@")
                when (value) {
                    is JsonObject -> parseStyle(value)?.let { captures[captureName] = Capture.Style(it) }
                    is JsonPrimitive -> {
                        val text = value.contentOrNull ?: return@forEach
                        if (text.startsWith("@")) {
                            captures[captureName] = Capture.Link(text.removePrefix("@"))
                        } else {
                            // an older theme: only the color, the foreground
                            parseColor(text)?.let { captures[captureName] = Capture.Style(HighlightStyle(it)) }
                        }
                    }
                    else -> Unit
                }
            }

            val editor = (root["editor"] as? JsonObject)?.let { EditorColorScheme.parse(it) }
            return HighlightTheme(name, background, captures, editor)
        }

        // A style; null if it has nothing that shows (only "sp", say).
        private fun parseStyle(value: JsonObject): HighlightStyle? {
            fun flag(key: String) = (value[key] as? JsonPrimitive)?.booleanOrNull == true
            val style = HighlightStyle(
                foreground = colorOf(value, "fg"),
                background = colorOf(value, "bg"),
                bold = flag("bold"),
                italic = flag("italic"),
                strikethrough = flag("strikethrough"),
                // the editor draws one kind of line under text
                underline = flag("underline") || flag("undercurl") || flag("underdouble") ||
                    flag("underdotted") || flag("underdashed")
            )
            val empty = style.foreground == null && style.background == null && !style.bold &&
                !style.italic && !style.strikethrough && !style.underline
            return if (empty) null else style
        }

        /** The color [key] of [value] ("#rrggbb"); null if missing or not a color. */
        fun colorOf(value: JsonObject?, key: String): Int? =
            (value?.get(key) as? JsonPrimitive)?.contentOrNull?.let { parseColor(it) }

        private fun parseColor(text: String): Int? = try {
            Color.parseColor(text)
        } catch (e: IllegalArgumentException) {
            // "NONE", or a name Android does not know
            null
        }
    }
}

/**
 * The highlight themes that were read, so that a theme is read once however many
 * editors use it, and read again when its file changes (an update). Any thread.
 */
object HighlightThemes {

    private const val TAG = "HighlightThemes"

    private class Entry(val modified: Long, val length: Long, val theme: HighlightTheme?)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * The theme [name]; null if there is no such file, or it cannot be read (logged).
     * Reads the file the first time, and when it has changed: not on the main thread.
     */
    fun get(context: Context, name: String): HighlightTheme? {
        val file = TreeSitterResources.themeFile(context, name)
        if (!file.isFile) {
            cache.remove(name)
            return null
        }
        val modified = file.lastModified()
        val length = file.length()
        cache[name]?.let { if (it.modified == modified && it.length == length) return it.theme }
        val theme = try {
            HighlightTheme.read(name, file)
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the theme ${file.path}", e)
            null
        }
        cache[name] = Entry(modified, length, theme)
        return theme
    }

    /**
     * Whether the theme [name] is a dark one, as [isDark] of the theme tells if it was
     * read already, else what its name says ("...light..." is light). Cheap: no file
     * is read, so it may be asked on the main thread.
     */
    fun isDark(name: String): Boolean {
        cache[name]?.theme?.let { return isDark(it) }
        return !name.contains("light", ignoreCase = true)
    }

    /**
     * Whether [theme] is a dark one: by the background of its editor colors if it has
     * them (what is actually seen), else by what it says (meta.background), else by
     * its name.
     */
    fun isDark(theme: HighlightTheme): Boolean {
        theme.editorColors?.background?.let { return ColorUtils.calculateLuminance(it) < 0.5 }
        theme.background?.let { return it != "light" }
        return !theme.name.contains("light", ignoreCase = true)
    }

    /** Whether the theme [name] was read already (then [isDark] is sure of it). */
    fun isLoaded(name: String): Boolean = cache[name]?.theme != null

    /** Forgets every theme read: after an update of the themes. */
    fun clear() = cache.clear()
}
