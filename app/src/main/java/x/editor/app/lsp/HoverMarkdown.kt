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

package x.editor.app.lsp

/**
 * The Markdown of a hover (or a signature's documentation) as text and what to show where
 * in it: headings, bold, italic, `code`, fenced code blocks (their keywords, strings,
 * comments, numbers, types and calls marked), links, lists, rules. Plain computation: the
 * marks are made spans by HoverSpans. Enough of Markdown for what servers send (clangd,
 * pyright), not all of it.
 */
object HoverMarkdown {

    enum class Style { HEADING, BOLD, ITALIC, CODE, CODE_BLOCK, LINK, RULE, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION }

    /** [style] from [start] to [end] of the text. */
    data class Mark(val start: Int, val end: Int, val style: Style)

    data class Rendered(val text: String, val marks: List<Mark>)

    fun render(markdown: String): Rendered {
        val out = StringBuilder()
        val marks = ArrayList<Mark>()
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        var blank = false
        fun newline() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // a fenced code block, to its closing fence (or the end)
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.substring(0, 3)
                val language = trimmed.drop(3).trim()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                i++
                if (code.isEmpty()) continue
                newline()
                if (blank && out.isNotEmpty()) out.append('\n')
                val start = out.length
                out.append(code.joinToString("\n"))
                marks += Mark(start, out.length, Style.CODE_BLOCK)
                marks += codeTokens(out, start, out.length, language)
                out.append('\n')
                blank = false
                continue
            }
            if (trimmed.isEmpty()) {
                blank = true
                i++
                continue
            }
            newline()
            // one blank line between blocks at most
            if (blank && out.isNotEmpty()) out.append('\n')
            blank = false
            when {
                // a rule
                RULE.matches(trimmed) -> {
                    val start = out.length
                    out.append("─".repeat(RULE_WIDTH))
                    marks += Mark(start, out.length, Style.RULE)
                }
                // a heading
                trimmed.startsWith("#") && HEADING.matches(trimmed) -> {
                    val start = out.length
                    inline(trimmed.dropWhile { it == '#' }.trim(), out, marks)
                    marks += Mark(start, out.length, Style.HEADING)
                }
                // an item of a list
                LIST.matches(line) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    out.append("  ".repeat(indent)).append("• ")
                    inline(LIST.matchEntire(line)!!.groupValues[1], out, marks)
                }
                else -> inline(trimmed, out, marks)
            }
            i++
        }
        return Rendered(out.toString().trimEnd(), marks.filter { it.end <= out.toString().trimEnd().length && it.start < it.end })
    }

    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val HEADING = Regex("""^#{1,6}\s+.*""")
    private val LIST = Regex("""^\s*[-*+]\s+(.*)$""")
    private const val RULE_WIDTH = 24

    // One line of text, its inline Markdown made marks: `code`, **bold**, *italic*,
    // _italic_ (not inside a word: snake_case stays), [text](url), \-escapes.
    private fun inline(text: String, out: StringBuilder, marks: MutableList<Mark>) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!<>" -> {
                    out.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val ticks = text.substring(i).takeWhile { it == '`' }.length
                    val close = text.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) {
                        out.append(text, i, i + ticks)
                        i += ticks
                    } else {
                        val start = out.length
                        out.append(text.substring(i + ticks, close).trim())
                        marks += Mark(start, out.length, Style.CODE)
                        i = close + ticks
                    }
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                    val close = text.indexOf("$c$c", i + 2)
                    if (close > i + 2) {
                        val start = out.length
                        inline(text.substring(i + 2, close), out, marks)
                        marks += Mark(start, out.length, Style.BOLD)
                        i = close + 2
                    } else {
                        out.append(c)
                        i++
                    }
                }
                (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) && i + 1 < text.length && text[i + 1] != ' ' -> {
                    var close = text.indexOf(c, i + 1)
                    // _ closes only where a word ends
                    while (c == '_' && close >= 0 && close + 1 < text.length && text[close + 1].isLetterOrDigit()) close = text.indexOf(c, close + 1)
                    if (close > i + 1) {
                        val start = out.length
                        inline(text.substring(i + 1, close), out, marks)
                        marks += Mark(start, out.length, Style.ITALIC)
                        i = close + 1
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    val m = LINK.find(text, i)
                    if (m != null && m.range.first == i) {
                        val start = out.length
                        inline(m.groupValues[1], out, marks)
                        marks += Mark(start, out.length, Style.LINK)
                        i = m.range.last + 1
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]*)\)""")

    // #region code

    private val KEYWORDS = setOf(
        // C, C++
        "auto", "break", "case", "const", "constexpr", "continue", "default", "do", "else", "enum", "extern",
        "for", "goto", "if", "inline", "register", "restrict", "return", "sizeof", "static", "struct", "switch",
        "typedef", "union", "volatile", "while", "class", "namespace", "template", "typename", "public",
        "private", "protected", "virtual", "override", "final", "new", "delete", "this", "using", "operator",
        "noexcept", "nullptr", "true", "false", "try", "catch", "throw", "friend", "explicit", "mutable",
        // Python
        "def", "lambda", "import", "from", "as", "pass", "yield", "with", "in", "is", "not", "and", "or",
        "None", "True", "False", "elif", "except", "finally", "raise", "global", "nonlocal", "async", "await",
        // JavaScript, TypeScript, Java, Kotlin, Rust, Go, Lua
        "function", "var", "let", "val", "fun", "fn", "func", "impl", "trait", "pub", "mut", "match", "loop",
        "interface", "extends", "implements", "package", "export", "type", "object", "when", "local", "then",
        "end", "nil", "null", "undefined", "self", "super", "typeof", "instanceof", "go", "defer", "chan",
        "select", "range", "map", "where", "crate", "mod", "use", "dyn", "ref", "move", "unsafe", "abstract",
        "open", "data", "sealed", "companion", "suspend", "internal", "lateinit", "init", "constructor"
    )
    private val TYPES = setOf(
        "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "bool", "_Bool",
        "size_t", "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t",
        "uint64_t", "uintptr_t", "string", "str", "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
        "usize", "isize", "f32", "f64", "byte", "boolean", "number", "any", "Int", "Long", "String",
        "Boolean", "Unit", "Float", "Double", "list", "dict", "tuple", "set", "bytes", "object"
    )
    private val TOKEN = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*|--[^\n]*)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?[a-zA-Z]*\b|\b0[xX][0-9a-fA-F]+\b)|([A-Za-z_][A-Za-z0-9_]*)"""
    )

    // The marks of the tokens of the code in [text] from [start] to [end]: comments
    // (`//`, `/* */`; `#` in Python and the shell, `--` in Lua and SQL), strings, numbers,
    // keywords, types (also a capitalised name), calls (a name before `(`).
    private fun codeTokens(text: CharSequence, start: Int, end: Int, language: String): List<Mark> {
        val code = text.subSequence(start, end).toString()
        val hashComments = language.lowercase() in setOf("python", "py", "sh", "bash", "shell", "ruby", "rb", "perl", "toml", "yaml", "cmake", "makefile")
        val dashComments = language.lowercase() in setOf("lua", "sql", "haskell")
        val marks = ArrayList<Mark>()
        for (m in TOKEN.findAll(code)) {
            val s = start + m.range.first
            val e = start + m.range.last + 1
            val style = when {
                m.groups[1] != null -> when {
                    m.value.startsWith("#") && !hashComments -> null
                    m.value.startsWith("--") && !dashComments -> null
                    else -> Style.COMMENT
                }
                m.groups[2] != null -> Style.STRING
                m.groups[3] != null -> Style.NUMBER
                else -> {
                    val word = m.value
                    val next = code.substring(m.range.last + 1).trimStart().firstOrNull()
                    when {
                        word in KEYWORDS -> Style.KEYWORD
                        word in TYPES -> Style.TYPE
                        next == '(' -> Style.FUNCTION
                        word.first().isUpperCase() && word.any { it.isLowerCase() } -> Style.TYPE
                        else -> null
                    }
                }
            }
            if (style != null) marks += Mark(s, e, style)
        }
        return marks
    }

    // #endregion
}
