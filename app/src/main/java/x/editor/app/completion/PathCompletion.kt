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

package x.editor.app.completion

import java.io.File

/**
 * Completion of paths, as nvim's cmp-path / blink.cmp's path source: typing a path
 * (`./src/ma`, `../`, `"assets/ic`, `"/sdcard/Down`, and `#include "ma` or `src="ic` for
 * the files next to the document) lists what is in its directory, the
 * names that start with what is typed of the last part; a directory taken lists what is
 * in it at once.
 *
 * Relative paths are relative to the directory of the document. A directory the app
 * cannot read by its path (a document opened through the Storage Access Framework,
 * without all files access) is read through the folder the user granted. So as not to come up
 * for what only looks like a path (`a / b`, `// comment`, `https://`), a path is one
 * that starts with `./`, `../` or `/`, or is in quotes (an `#include "dir/`, an import,
 * a file name in a string); an absolute one outside quotes needs two parts at least
 * (`/sdcard/`).
 */
object PathCompletion {

    /** A path being typed: its [directory] as typed (ending with `/`), and the [prefix] of the name after it. */
    data class Query(val directory: String, val prefix: String, val quoted: Boolean)

    /** One name in a directory. */
    data class Entry(val name: String, val isDirectory: Boolean)

    // what ends the path being typed, looking back from the cursor
    private fun isBoundary(c: Char) = c.isWhitespace() || c in "\"'`()[]{}<>,;=|&!*?:"

    private val QUOTES = "\"'`"

    // what comes before the quote of a string that names a file
    private val FILE_CONTEXT = Regex("""(#\s*(include|import)\s*|\b(src|href)\s*=\s*)$""")

    /** The path being typed at the end of [lineBeforeCursor] (the line up to the cursor), or null. */
    fun query(lineBeforeCursor: String): Query? {
        var start = lineBeforeCursor.length
        while (start > 0 && !isBoundary(lineBeforeCursor[start - 1])) start--
        val token = lineBeforeCursor.substring(start)
        val slash = token.lastIndexOf('/')
        if (token.contains("//")) return null
        val before = lineBeforeCursor.getOrNull(start - 1)
        val quoted = before != null && before in QUOTES && isInString(lineBeforeCursor, start - 1)
        // a name without a directory: the files next to the document, where a file is
        // certainly meant (`#include "ma`, `src="ic`), not in any string
        if (slash < 0) {
            val lead = lineBeforeCursor.substring(0, (start - 1).coerceAtLeast(0))
            return if (quoted && FILE_CONTEXT.containsMatchIn(lead)) Query("", token, true) else null
        }
        val directory = token.substring(0, slash + 1)
        val prefix = token.substring(slash + 1)
        val ok = when {
            directory.startsWith("./") || directory.startsWith("../") -> true
            quoted -> true
            // outside quotes, `/x/` at least: not a division
            directory.startsWith("/") -> directory.count { it == '/' } >= 2
            else -> false
        }
        return if (ok) Query(directory, prefix, quoted) else null
    }

    // whether the quote at [index] of [line] opens a string (an even number of the same
    // quote before it, escaped ones not counted)
    private fun isInString(line: String, index: Int): Boolean {
        val quote = line[index]
        var count = 0
        var i = 0
        while (i < index) {
            val c = line[i]
            if (c == '\\') i++ else if (c == quote) count++
            i++
        }
        return count % 2 == 0
    }

    /** The directory of [query]: absolute, or under [base] (the document's directory; null: unknown); `""`: [base]. */
    fun directoryOf(query: Query, base: File?): File? {
        val directory = query.directory
        return when {
            directory.startsWith("/") -> File(directory)
            base != null -> File(base, directory)
            else -> null
        }?.normalize()
    }

    /**
     * What is in [directory] whose name starts with [prefix] (case ignored), directories
     * first, at most [max]. Hidden ones only once a `.` is typed.
     */
    fun list(directory: File, prefix: String, max: Int): List<Entry> {
        val files = directory.listFiles() ?: return emptyList()
        return filter(files.map { Entry(it.name, it.isDirectory) }, prefix, max)
    }

    /**
     * Of [entries] (what is in a directory, however it was read: by its path, or through
     * the Storage Access Framework), those whose name starts with [prefix] (case ignored),
     * directories first, at most [max]. Hidden ones only once a `.` is typed; the name
     * typed whole is not offered again.
     */
    fun filter(entries: List<Entry>, prefix: String, max: Int): List<Entry> {
        val showHidden = prefix.startsWith(".")
        return entries.asSequence()
            .filter { it.name.startsWith(prefix, ignoreCase = true) && (showHidden || !it.name.startsWith(".")) }
            .filter { it.isDirectory || it.name != prefix }
            .sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(max)
            .toList()
    }
}
