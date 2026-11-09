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

package x.editor.app.agent

/**
 * The text work of the tools, without files or Android (so that it can be tested alone):
 * an exact replacement, line numbers, a glob, output cut to a size.
 */
object AgentText {

    /**
     * [text] with [old] replaced by [new]: [old] must be there exactly once, or (with
     * [all]) at least once. The model is told why not otherwise, and what to do.
     */
    fun replace(text: String, old: String, new: String, all: Boolean): String {
        if (old.isEmpty()) throw ToolException("old_text is empty: to write a whole file use write_file")
        if (old == new) throw ToolException("old_text and new_text are the same: nothing to change")
        val count = occurrences(text, old)
        // the file's line ends may be \r\n where the model wrote \n
        if (count == 0 && text.contains("\r\n") && !old.contains("\r\n")) {
            val crlfOld = old.replace("\n", "\r\n")
            if (occurrences(text, crlfOld) > 0) return replace(text, crlfOld, new.replace("\n", "\r\n"), all)
        }
        return when {
            count == 0 -> throw ToolException(
                "old_text was not found in the file. It must match exactly, spaces, tabs and line ends included: read the file again (read_file) and copy the text from it"
            )
            count > 1 && !all -> throw ToolException(
                "old_text is in the file $count times: add lines around it so that it is there only once, or set replace_all to true"
            )
            all -> text.replace(old, new)
            else -> {
                val at = text.indexOf(old)
                text.substring(0, at) + new + text.substring(at + old.length)
            }
        }
    }

    fun occurrences(text: String, part: String): Int {
        if (part.isEmpty()) return 0
        var count = 0
        var at = text.indexOf(part)
        while (at >= 0) {
            count++
            at = text.indexOf(part, at + part.length)
        }
        return count
    }

    /** The lines of [text], split at \n (a \r before it dropped). */
    fun lines(text: String): List<String> = text.split('\n').map { it.removeSuffix("\r") }

    /** Lines [from]..[to] (1-based, inclusive) of [lines], each after its number and a tab. */
    fun numbered(lines: List<String>, from: Int, to: Int): String {
        val out = StringBuilder()
        for (n in from..to) out.append(n.toString().padStart(6)).append('\t').append(lines[n - 1]).append('\n')
        return out.toString()
    }

    /** A regex of a file name glob: `*`, `?`, `{a,b}`; the rest as it is. */
    fun globRegex(glob: String): Regex {
        val out = StringBuilder()
        var inBraces = false
        for (c in glob) {
            when {
                c == '*' -> out.append(".*")
                c == '?' -> out.append('.')
                c == '{' -> { out.append("(?:"); inBraces = true }
                c == '}' && inBraces -> { out.append(')'); inBraces = false }
                c == ',' && inBraces -> out.append('|')
                else -> out.append(Regex.escape(c.toString()))
            }
        }
        return Regex(out.toString(), RegexOption.IGNORE_CASE)
    }

    /** [text] cut to about [max] characters: its start and its end, what is left out said in between. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = max / 5
        val tail = max - head
        val dropped = text.length - head - tail
        return text.substring(0, head) + "\n… [$dropped characters left out] …\n" + text.substring(text.length - tail)
    }

    /**
     * What an edit changes, for the user to look at before allowing it: the lines of [old]
     * (`-`), then those of [new] (`+`); at most [maxLines] of each.
     */
    fun diff(old: String, new: String, maxLines: Int = 40): String {
        val out = StringBuilder()
        fun add(prefix: Char, text: String) {
            val lines = lines(text)
            lines.take(maxLines).forEach { out.append(prefix).append(' ').append(it).append('\n') }
            if (lines.size > maxLines) out.append(prefix).append(" … ${lines.size - maxLines} more lines\n")
        }
        if (old.isNotEmpty()) add('-', old)
        if (new.isNotEmpty()) add('+', new)
        return out.toString().trimEnd('\n')
    }
}
