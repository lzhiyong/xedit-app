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

package x.editor.app.format

import java.io.File

/**
 * The properties `.editorconfig` files give a file (https://editorconfig.org): those of
 * the directory of the file and of every directory above it, up to one with
 * `root = true`; the nearer file wins, and in a file the later section. Of a section, the
 * glob is matched against the path of the file relative to the directory of the
 * `.editorconfig` (a glob without `/` against its name, in any directory below).
 *
 * The values of the properties the specification knows are lowercased; `unset` takes a
 * property away again.
 */
object EditorConfig {

    const val FILE = ".editorconfig"

    // the properties whose values are not case-sensitive
    private val KNOWN = setOf(
        "indent_style", "indent_size", "tab_width", "end_of_line", "charset",
        "trim_trailing_whitespace", "insert_final_newline", "max_line_length", "root",
    )

    /** One `.editorconfig`: whether it is the [root], and its sections in order. */
    data class Config(val root: Boolean, val sections: List<Section>)

    /** A `[glob]` and its properties. */
    data class Section(val glob: String, val properties: Map<String, String>)

    /**
     * The properties of [file], from the `.editorconfig` files above it as [read] gives
     * them (null: none there, or not readable); the first one found is not looked above
     * when it says `root = true`. [read] is given the `.editorconfig` file to read.
     */
    fun propertiesFor(file: File, read: (File) -> String?): Map<String, String> {
        val target = file.absoluteFile.normalize()
        // nearest first
        val found = ArrayList<Pair<File, Config>>()
        var directory = target.parentFile
        while (directory != null) {
            val text = read(File(directory, FILE))
            if (text != null) {
                val config = parse(text)
                found += directory to config
                if (config.root) break
            }
            directory = directory.parentFile
        }
        val properties = LinkedHashMap<String, String>()
        // the farthest first, so that the nearer ones win
        for ((dir, config) in found.asReversed()) {
            val relative = target.relativeTo(dir).invariantSeparatorsPath
            for (section in config.sections) {
                if (!matches(section.glob, relative)) continue
                for ((key, value) in section.properties) {
                    if (value == "unset") properties.remove(key) else properties[key] = value
                }
            }
        }
        return properties
    }

    /** [text] read as an `.editorconfig`. Lines it does not understand are left out. */
    fun parse(text: String): Config {
        var root = false
        val sections = ArrayList<Section>()
        var glob: String? = null
        var properties = LinkedHashMap<String, String>()
        fun close() {
            glob?.let { sections += Section(it, properties) }
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                close()
                glob = line.substring(1, line.length - 1)
                properties = LinkedHashMap()
                continue
            }
            val equals = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf(':').takeIf { it > 0 } ?: continue
            val key = line.substring(0, equals).trim().lowercase()
            var value = line.substring(equals + 1).trim()
            if (key in KNOWN) value = value.lowercase()
            if (glob == null) {
                // the preamble: only `root`
                if (key == "root") root = value == "true"
            } else {
                properties[key] = value
            }
        }
        close()
        return Config(root, sections)
    }

    /**
     * Whether [glob] (of a section) matches [path] (relative to the `.editorconfig`, with
     * `/`): `*` any chars but `/`, `**` any chars, `?` one char, `[abc]` / `[!abc]`,
     * `{a,b}` one of them, `{1..10}` a number in that range. A glob without `/` matches
     * the name of the file in any directory.
     */
    fun matches(glob: String, path: String): Boolean {
        val pattern = when {
            glob.startsWith("/") -> glob.substring(1)
            '/' in glob -> glob
            else -> "**/$glob"
        }
        val ranges = ArrayList<LongRange>()
        val regex = try {
            Regex(toRegex(pattern, ranges))
        } catch (e: Exception) {
            return false
        }
        val match = regex.matchEntire(path) ?: return false
        // the numbers of the {a..b} ranges, in order of their groups
        return ranges.indices.all { i ->
            match.groups[i + 1]?.value?.toLongOrNull()?.let { it in ranges[i] } == true
        }
    }

    // The regular expression of [glob]; the {a..b} ranges become groups, in [ranges].
    private fun toRegex(glob: String, ranges: MutableList<LongRange>): String {
        val out = StringBuilder()
        var i = 0
        // the braces open, and whether each is a list of choices ({a,b})
        val braces = ArrayList<Boolean>()
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '\\' && i + 1 < glob.length -> {
                    out.append(Regex.escape(glob[i + 1].toString()))
                    i++
                }
                c == '*' -> {
                    if (glob.getOrNull(i + 1) == '*') {
                        // `**/` also matches no directory at all
                        if (glob.getOrNull(i + 2) == '/') {
                            out.append("(?:.*/)?")
                            i += 2
                        } else {
                            out.append(".*")
                            i++
                        }
                    } else {
                        out.append("[^/]*")
                    }
                }
                c == '?' -> out.append("[^/]")
                c == '[' -> {
                    val close = glob.indexOf(']', i + 2)
                    if (close < 0) {
                        out.append("\\[")
                    } else {
                        var set = glob.substring(i + 1, close)
                        val negated = set.startsWith("!")
                        if (negated) set = set.substring(1)
                        out.append(if (negated) "[^" else "[").append(set.replace("\\", "\\\\").replace("[", "\\[")).append("]")
                        i = close
                    }
                }
                c == '{' -> {
                    val close = matchingBrace(glob, i)
                    val inside = if (close > 0) glob.substring(i + 1, close) else null
                    val range = inside?.let { Regex("""^(-?\d+)\.\.(-?\d+)$""").matchEntire(it) }
                    when {
                        range != null -> {
                            val a = range.groupValues[1].toLong()
                            val b = range.groupValues[2].toLong()
                            ranges += minOf(a, b)..maxOf(a, b)
                            out.append("(-?\\d+)")
                            i = close
                        }
                        inside != null && hasTopLevelComma(inside) -> {
                            out.append("(?:")
                            braces += true
                        }
                        // `{single}` and an unclosed brace are literal
                        else -> out.append("\\{")
                    }
                }
                c == ',' && braces.lastOrNull() == true -> out.append("|")
                c == '}' && braces.isNotEmpty() -> {
                    braces.removeAt(braces.lastIndex)
                    out.append(")")
                }
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return out.toString()
    }

    private fun matchingBrace(glob: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < glob.length) {
            when (glob[i]) {
                '\\' -> i++
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun hasTopLevelComma(text: String): Boolean {
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) return true
            }
            i++
        }
        return false
    }
}
