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
 * The text work behind the code blocks of the AI assistant, without Android (so that it can
 * be tested alone): an answer cut into its text and its fenced code blocks (each shown in an
 * editor of the app, AgentCodeBlock), the file name a fence's language is highlighted as,
 * and what read_file gave back without its line numbers.
 */
object AgentCodeBlocks {

    /** A part of an answer: Markdown text, or a fenced code block. */
    sealed class Segment {
        data class Text(val text: String) : Segment()

        /**
         * A code block: the language its fence names (lower case, empty: none), its code
         * (without the fences), and whether its closing fence has come (a block still
         * streaming in has none yet).
         */
        data class Code(val language: String, val code: String, val closed: Boolean) : Segment()
    }

    /**
     * [text] cut into its text and its code blocks, in order. A block opens with a line of
     * three or more backticks (or tildes) and the language after them, indented or not, and
     * closes with a line of at least as many of the same and nothing else (CommonMark);
     * a block with no closing fence goes on to the end (it is still streaming in). The lines
     * of a block lose as much indentation as its fence has (a block in a list item). Blank
     * lines around the text are dropped, and text that is only blank goes.
     *
     * Some models (or the servers in between) glue a fence to the line before it or after
     * it: `as follows:```c`, `}```` or ```` ```That is all. ````. Those are taken as fences too
     * (gluedFences): a fence at the end of a line of text whose language is a single word,
     * a closing fence at the end of a line of code, and a closing fence with a sentence after it.
     */
    fun split(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val prose = ArrayList<String>()
        val code = ArrayList<String>()
        var fence: String? = null
        var fenceIndent = 0
        var language = ""

        fun flushProse() {
            val joined = prose.joinToString("\n").trim('\n', '\r').trimEnd()
            if (joined.isNotBlank()) out += Segment.Text(joined)
            prose.clear()
        }

        for (line in gluedFences(text)) {
            val trimmed = line.trimStart()
            val open = fence
            if (open == null) {
                val marker = fenceOf(trimmed)
                if (marker != null && !(marker[0] == '`' && trimmed.substring(marker.length).contains('`'))) {
                    flushProse()
                    fence = marker
                    fenceIndent = line.length - trimmed.length
                    language = languageOf(trimmed.substring(marker.length))
                    code.clear()
                } else {
                    prose += line
                }
            } else {
                val marker = fenceOf(trimmed)
                if (marker != null && marker[0] == open[0] && marker.length >= open.length &&
                    trimmed.substring(marker.length).isBlank()
                ) {
                    out += Segment.Code(language, code.joinToString("\n"), closed = true)
                    fence = null
                    code.clear()
                } else {
                    code += unindent(line.removeSuffix("\r"), fenceIndent)
                }
            }
        }
        if (fence != null) {
            out += Segment.Code(language, code.joinToString("\n"), closed = false)
        } else {
            flushProse()
        }
        return out
    }

    // The lines of [text], a fence glued to the text before or after it put on a line of its
    // own (split above): followed through the text, so that a closing fence is looked for
    // only inside a block and an opening one only outside.
    private fun gluedFences(text: String): List<String> {
        val out = ArrayList<String>()
        var fence: String? = null
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            val open = fence
            if (open == null) {
                val marker = fenceOf(trimmed)
                if (marker != null) {
                    // a fence of its own (or backticks in a line, which split leaves as text)
                    if (!(marker[0] == '`' && trimmed.substring(marker.length).contains('`'))) fence = marker
                    out += line
                    continue
                }
                val glued = GLUED_OPEN.find(line)?.takeIf { isGluedOpening(it.groupValues[1], it.groupValues[3]) }
                if (glued != null) {
                    // `text```lang`: the text, then the fence on a line of its own
                    out += glued.groupValues[1]
                    out += glued.groupValues[2] + glued.groupValues[3]
                    fence = glued.groupValues[2]
                } else {
                    out += line
                }
            } else {
                val marker = fenceOf(trimmed)
                if (marker != null && marker[0] == open[0] && marker.length >= open.length) {
                    val rest = trimmed.substring(marker.length).trim()
                    if (rest.isNotEmpty() && isSentence(rest)) {
                        // ```That is all.: the closing fence, then the text after it
                        out += line.substring(0, line.length - trimmed.length) + marker
                        out += rest
                        fence = null
                        continue
                    }
                    if (rest.isEmpty()) fence = null
                    out += line
                    continue
                }
                val glued = GLUED_CLOSE.find(line.trimEnd())
                if (glued != null && glued.groupValues[2][0] == open[0] && glued.groupValues[2].length >= open.length) {
                    // `}```: the last line of the code, then the closing fence
                    out += glued.groupValues[1]
                    out += glued.groupValues[2]
                    fence = null
                } else {
                    out += line
                }
            }
        }
        return out
    }

    // A fence at the end of a line of text opens a block: not after an inline code span
    // left open, and with a language (```c), or after a colon (`as follows:```), not for
    // backticks a sentence happens to end with.
    private fun isGluedOpening(text: String, language: String): Boolean {
        if (text.contains("```") || text.count { it == '`' } % 2 != 0) return false
        val last = text.trimEnd().last()
        return language.isNotEmpty() || last == ':' || last == '：'
    }

    // What follows a closing fence on its line is text after the block (```Done., ```这样…,
    // ```That is all), not the info string of a fence inside it (```python, ```js title=a.js).
    private fun isSentence(rest: String): Boolean {
        val first = rest.substringBefore(' ').substringBefore('{')
        if (!LANGUAGE.matches(first)) return true
        return rest.contains(' ') && !rest.contains('=') && !rest.contains('{')
    }

    // a language: lower case letters, digits and + # . - (python, c++, objective-c)
    private val LANGUAGE = Regex("^[a-z0-9_+#.\\-]*$")

    // text, then a fence and perhaps a one-word language (a letter first) at the end of the line
    private val GLUED_OPEN = Regex("^(.*[^`\\s])\\s*(`{3,})((?:[A-Za-z][A-Za-z0-9_+#.\\-]*)?)\\s*$")

    // a line of code, then a closing fence at its end (no other backtick in the line)
    private val GLUED_CLOSE = Regex("^([^`]*\\S)\\s*(`{3,})$")

    // the fence a line (its indentation taken off) starts with: three or more ` or ~
    private fun fenceOf(trimmed: String): String? {
        val c = trimmed.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        var n = 0
        while (n < trimmed.length && trimmed[n] == c) n++
        return if (n >= 3) trimmed.substring(0, n) else null
    }

    // the language of an info string: its first word, lower case (`python {.line-numbers}`)
    private fun languageOf(info: String): String =
        info.trim().substringBefore(' ').substringBefore('{').trim().lowercase()

    private fun unindent(line: String, count: Int): String {
        var n = 0
        while (n < count && n < line.length && line[n] == ' ') n++
        return line.substring(n)
    }

    /**
     * The file name a code block in [language] is highlighted as (TreeSitterEngine finds
     * its grammar by the name: by the file types, or by the extension as the grammar's own
     * name); null for plain text, nothing to highlight.
     */
    fun fileNameOf(language: String): String? {
        val name = language.trim().lowercase()
        if (name.isEmpty() || name in PLAIN) return null
        WHOLE_NAMES[name]?.let { return it }
        return "snippet." + (EXTENSIONS[name] ?: name)
    }

    // languages with nothing to highlight
    private val PLAIN = setOf("text", "txt", "plain", "plaintext", "output", "log", "console-output", "none", "nohighlight")

    // languages that are files of their own name rather than of an extension
    private val WHOLE_NAMES = mapOf(
        "dockerfile" to "Dockerfile", "docker" to "Dockerfile",
        "makefile" to "Makefile", "make" to "Makefile", "mk" to "Makefile",
        "cmake" to "CMakeLists.txt",
    )

    // names a fence gives that are neither an extension nor the name of a grammar
    private val EXTENSIONS = mapOf(
        "shell" to "sh", "console" to "sh", "zsh" to "sh", "shellscript" to "sh", "terminal" to "sh",
        "c++" to "cpp", "cxx" to "cpp", "objective-c" to "m", "objectivec" to "m", "objc" to "m",
        "c#" to "cs", "csharp" to "cs", "f#" to "fs", "fsharp" to "fs",
        "golang" to "go", "yml" to "yaml", "jsonc" to "json", "json5" to "json",
        "py3" to "py", "python3" to "py", "node" to "js", "gradle" to "gradle",
        "kotlin-script" to "kts", "viml" to "vim", "patch" to "diff", "ps1" to "ps1", "powershell" to "ps1",
    )

    /** What read_file gave back as the file's own text: its line numbers off, and the note of the range. */
    data class Unnumbered(val code: String, val firstLine: Int?, val note: String?)

    /**
     * [result] of read_file (each line after its number and a tab, AgentText.numbered, and
     * perhaps `(lines a-b of n)` at the end) as the file's text: the numbers taken off (and
     * the first one kept), the note apart. A result without numbered lines (`(x is empty)`)
     * is left as it is.
     */
    fun unnumbered(result: String): Unnumbered {
        val lines = result.split('\n').toMutableList()
        var note: String? = null
        val last = lines.lastOrNull()
        if (last != null && RANGE_NOTE.matches(last.trim())) {
            note = last.trim().removePrefix("(").removeSuffix(")")
            lines.removeAt(lines.lastIndex)
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        val first = lines.firstOrNull()?.let { NUMBERED.find(it) } ?: return Unnumbered(result, null, null)
        val firstLine = first.groupValues[1].toIntOrNull()
        val code = lines.joinToString("\n") { line ->
            val match = NUMBERED.find(line)
            if (match != null) line.substring(match.range.last + 1) else line
        }
        return Unnumbered(code, firstLine, note)
    }

    private val NUMBERED = Regex("^ *(\\d+)\\t")
    private val RANGE_NOTE = Regex("^\\(lines \\d+-\\d+ of \\d+\\)$")
}
