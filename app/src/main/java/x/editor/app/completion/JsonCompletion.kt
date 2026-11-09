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

/**
 * Completion in the JSON files edited on the sheets (packages.json, a language server, the
 * formatting rules, snippets, keymap.json, a theme): plain computation, see SheetCompletion.
 *
 *  - [JsonScanner]: reads JSONC (comments, trailing commas, a text being typed: a string
 *    left open at the end of its line) and knows, at any point, the objects and arrays it
 *    is in, by their keys ([JsonScanner.Frame.path]: `packages/[]/abi`), and whether a key
 *    or a value comes there;
 *  - [contextAt]: what the cursor is in: a key or a value, in a string or not, what of it
 *    is typed ([Context.prefix]), the keys the object has already;
 *  - [JsonSchema]: what may be written where: the keys of an object at a path (`*` for any
 *    key, `[]` for the items of an array) and their shape, the values of a key; learned from
 *    a text too (a template, the file as shipped).
 */
internal object JsonCompletion {

    enum class Position { KEY, VALUE, NONE }

    /**
     * What the cursor is in. [path]: the object or array it is in (its keys from the top);
     * [position]: a key or a value comes there; [key]: whose value it is (`[]` in an array);
     * [inString]: it is in a string (`"ver|`); [prefix]: what is typed before it of the word
     * at the cursor, and [suffix] of it after the cursor; [siblings]: the keys the object it
     * is in has already (not the one being typed); [closingQuote]: in a string, whether the
     * string is closed after the word (`"ver|sion"`), [colonAfter]: and a `:` comes after it.
     */
    data class Context(
        val path: List<String>,
        val position: Position,
        val key: String?,
        val inString: Boolean,
        val prefix: String,
        val suffix: String,
        val siblings: Set<String>,
        val closingQuote: Boolean,
        val colonAfter: Boolean,
    )

    /** What is part of a word typed in a JSON file: keys such as `arm64-v8a`, `keyword.return`, `@string`, `#61afef`. */
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$' || c == '-' || c == '.' || c == '@' || c == '#'

    // the rest of the text read after the cursor, for the keys of its object: no more than this
    private const val LOOK_AHEAD = 64 * 1024

    /** What the cursor at [offset] of [text] is in (see [Context]). */
    fun contextAt(text: CharSequence, offset: Int): Context {
        val cursor = offset.coerceIn(0, text.length)
        var start = cursor
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = cursor
        while (end < text.length && isWordChar(text[end])) end++
        val prefix = text.subSequence(start, cursor).toString()
        val suffix = text.subSequence(cursor, end).toString()

        val scanner = JsonScanner(text)
        // up to the word typed: what comes there; in a string, through it
        scanner.run(start)
        if (scanner.inString) scanner.run(cursor)
        val inString = scanner.inString
        val frame = scanner.top
        val position = when {
            // in a comment: nothing to offer
            scanner.inComment -> Position.NONE
            frame == null -> Position.NONE
            inString && scanner.stringIsKey -> Position.KEY
            inString && scanner.stringIsValue -> Position.VALUE
            inString -> Position.NONE
            frame.isObject && frame.state == JsonScanner.EXPECT_KEY -> Position.KEY
            frame.state == JsonScanner.EXPECT_VALUE -> Position.VALUE
            else -> Position.NONE
        }
        val key = when {
            frame == null -> null
            frame.isObject -> frame.key
            else -> JsonSchema.ITEMS
        }
        val path = frame?.path.orEmpty()

        // after the word: whether the string is closed there, and a `:` follows
        var closingQuote = false
        var colonAfter = false
        if (inString && end < text.length && text[end] == '"') {
            closingQuote = true
            var i = end + 1
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            colonAfter = i < text.length && text[i] == ':'
        }

        // the keys of its object, before the cursor and after it (not the one being typed)
        val siblings = LinkedHashSet<String>()
        if (frame != null && frame.isObject) {
            if (inString && position == Position.KEY) scanner.ignoreNextKeyOf = frame
            scanner.run(minOf(text.length, cursor + LOOK_AHEAD))
            siblings += frame.keys
            if (position == Position.KEY && !inString) siblings -= (prefix + suffix)
        }
        return Context(path, position, key, inString, prefix, suffix, siblings, closingQuote, colonAfter)
    }
}

/**
 * Reads JSONC from its start, as far as it is told ([run]), and knows where it is: the
 * objects and arrays it is in ([stack], each with its [Frame.path]), whether a key or a
 * value comes next in the one it is in ([Frame.state]), and whether it is in a string.
 * What it meets is said to [onKey] and [onValue] (for [JsonSchema.learn]).
 *
 * It is lenient, as the files are typed: comments, trailing commas, a string left open at
 * the end of its line (closed there), a word that is not quoted.
 */
internal class JsonScanner(private val text: CharSequence) {

    class Frame(val isObject: Boolean, val path: List<String>) {
        var state = if (isObject) EXPECT_KEY else EXPECT_VALUE
        // the key whose value comes (or came) last, in an object
        var key: String? = null
        val keys = LinkedHashSet<String>()
    }

    /** The shape of a value, by its first char. */
    enum class Shape { STRING, NUMBER, BOOLEAN, NULL, OBJECT, ARRAY, OTHER }

    val stack = ArrayList<Frame>()
    val top: Frame? get() = stack.lastOrNull()

    /** A key met: in the object at [path]. */
    var onKey: ((path: List<String>, key: String) -> Unit)? = null

    /** A value met: of [key] in the object at [path] ([JsonSchema.ITEMS] in an array); its [shape], and a string's text. */
    var onValue: ((path: List<String>, key: String, shape: Shape, string: String?) -> Unit)? = null

    /** Its next key read is not one of its keys (it is the one being typed). */
    var ignoreNextKeyOf: Frame? = null

    var inString = false
        private set

    /** Whether it is in a comment (where it was told to stop). */
    val inComment: Boolean get() = lineComment || blockComment
    // the string being read is a key, or a value
    var stringIsKey = false
        private set
    var stringIsValue = false
        private set

    private var pos = 0
    private var escape = false
    private var lineComment = false
    private var blockComment = false
    private val string = StringBuilder()
    // the top level: a value comes, or came
    private var rootState = EXPECT_VALUE

    /** Reads on to [to] (exclusive). */
    fun run(to: Int) {
        val limit = to.coerceAtMost(text.length)
        while (pos < limit) {
            val c = text[pos]
            if (lineComment) {
                if (c == '\n') lineComment = false
                pos++
                continue
            }
            if (blockComment) {
                if (c == '*' && pos + 1 < text.length && text[pos + 1] == '/') {
                    blockComment = false
                    pos += 2
                } else {
                    pos++
                }
                continue
            }
            if (inString) {
                when {
                    escape -> {
                        escape = false
                        append(c)
                    }
                    c == '\\' -> escape = true
                    // closed, or left open at the end of its line (being typed)
                    c == '"' || c == '\n' -> endString()
                    else -> append(c)
                }
                pos++
                continue
            }
            when (c) {
                '/' -> {
                    val next = if (pos + 1 < text.length) text[pos + 1] else ' '
                    when (next) {
                        '/' -> { lineComment = true; pos += 2 }
                        '*' -> { blockComment = true; pos += 2 }
                        else -> pos++
                    }
                    continue
                }
                '"' -> {
                    val frame = top
                    inString = true
                    escape = false
                    string.setLength(0)
                    stringIsKey = frame != null && frame.isObject && frame.state == EXPECT_KEY
                    stringIsValue = !stringIsKey && isValuePosition()
                }
                ':' -> top?.let { if (it.isObject && it.state == AFTER_KEY) it.state = EXPECT_VALUE }
                ',' -> top?.let { it.state = if (it.isObject) EXPECT_KEY else EXPECT_VALUE }
                '{', '[' -> {
                    val isObject = c == '{'
                    val path = childPath()
                    valueMet(if (isObject) Shape.OBJECT else Shape.ARRAY, null)
                    stack += Frame(isObject, path)
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ' ', '\t', '\n', '\r' -> Unit
                else -> {
                    // a word not quoted: a number, true, false, null (or a key typed so)
                    val from = pos
                    while (pos + 1 < limit && isLiteral(text[pos + 1])) pos++
                    if (isValuePosition()) {
                        val word = text.subSequence(from, pos + 1).toString()
                        val shape = when {
                            word == "true" || word == "false" -> Shape.BOOLEAN
                            word == "null" -> Shape.NULL
                            c.isDigit() || c == '-' || c == '+' || c == '.' -> Shape.NUMBER
                            else -> Shape.OTHER
                        }
                        valueMet(shape, null)
                    }
                }
            }
            pos++
        }
    }

    private fun isLiteral(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '+' || c == '.' || c == '$'

    private fun append(c: Char) {
        if (string.length < MAX_STRING) string.append(c)
    }

    private fun isValuePosition(): Boolean {
        val frame = top ?: return rootState == EXPECT_VALUE
        return frame.state == EXPECT_VALUE
    }

    // the path of an object or array that starts here
    private fun childPath(): List<String> {
        val frame = top ?: return emptyList()
        return frame.path + (if (frame.isObject) frame.key ?: "?" else JsonSchema.ITEMS)
    }

    // a value starts here (a string, when it ends): its key told, and the next is a comma
    private fun valueMet(shape: Shape, string: String?) {
        val frame = top
        if (frame == null) {
            rootState = AFTER_VALUE
            return
        }
        if (frame.state != EXPECT_VALUE) return
        onValue?.invoke(frame.path, if (frame.isObject) frame.key ?: "?" else JsonSchema.ITEMS, shape, string)
        frame.state = AFTER_VALUE
    }

    private fun endString() {
        inString = false
        val frame = top
        val content = string.toString()
        if (stringIsKey && frame != null) {
            frame.key = content
            if (ignoreNextKeyOf === frame) {
                ignoreNextKeyOf = null
            } else {
                frame.keys += content
                onKey?.invoke(frame.path, content)
            }
            frame.state = AFTER_KEY
        } else if (stringIsValue) {
            valueMet(Shape.STRING, content)
        }
        stringIsKey = false
        stringIsValue = false
    }

    companion object {
        const val EXPECT_KEY = 0
        const val AFTER_KEY = 1
        const val EXPECT_VALUE = 2
        const val AFTER_VALUE = 3

        // a string longer than this is kept cut (no key or value offered is that long)
        private const val MAX_STRING = 256
    }
}

/**
 * What may be written where in a JSON file: for an object at a path, its keys (each with
 * the shape of its value, and a word on what it is); for a key, its values. A path is
 * written as its keys joined by `/`: `packages/[]/abi` (an item of an array is `[]`, the
 * top level the empty path); a segment `*` stands for any one key (`captures`, then `*`:
 * any capture). (Not written joined here: in Kotlin a slash and a star open a comment,
 * even inside one.)
 */
internal class JsonSchema {

    /** The shape of a key's value: what is put in after it (`""`, `{}`, `[]`) when it is taken. */
    enum class Shape { STRING, NUMBER, BOOLEAN, OBJECT, ARRAY, ANY }

    class Key(val name: String, val shape: Shape, val detail: String?)

    /** A value of a key: [raw] ones (true, 5001) are put in as they are, the others as a string. */
    class Value(val text: String, val detail: String?, val raw: Boolean)

    private val keyMap = LinkedHashMap<String, LinkedHashMap<String, Key>>()
    private val valueMap = LinkedHashMap<String, LinkedHashMap<String, Value>>()

    /** The keys of the objects at [path]. */
    fun keys(path: String, vararg list: Pair<String, Shape>, detail: String? = null): JsonSchema {
        val map = keyMap.getOrPut(path) { LinkedHashMap() }
        for ((name, shape) in list) {
            val old = map[name]
            // what is said first stays; a shape or a word learned later fills in what it lacked
            map[name] = if (old == null) Key(name, shape, detail)
                else Key(name, if (old.shape == Shape.ANY) shape else old.shape, old.detail ?: detail)
        }
        return this
    }

    /** A key of the objects at [path] with a word on it. */
    fun key(path: String, name: String, shape: Shape, detail: String? = null): JsonSchema = keys(path, name to shape, detail = detail)

    /** The values of [key] in the objects at [path] (`[]`: the items of the array at [path]). */
    fun values(path: String, key: String, vararg list: String, detail: String? = null, raw: Boolean = false): JsonSchema {
        val map = valueMap.getOrPut(valueKey(path, key)) { LinkedHashMap() }
        for (text in list) if (!map.containsKey(text)) map[text] = Value(text, detail, raw)
        return this
    }

    /** The keys that may be at [path], those of the paths that match it first ([path] itself before `*`). */
    fun keysAt(path: List<String>): List<Key> {
        val found = LinkedHashMap<String, Key>()
        for ((pattern, _) in sorted(keyMap.keys)) {
            if (!matches(pattern, path)) continue
            for ((name, key) in keyMap.getValue(pattern)) if (!found.containsKey(name)) found[name] = key
        }
        return found.values.toList()
    }

    /** The key [name] at [path], if the schema knows it. */
    fun keyAt(path: List<String>, name: String?): Key? {
        if (name == null) return null
        for ((pattern, _) in sorted(keyMap.keys)) {
            if (!matches(pattern, path)) continue
            keyMap.getValue(pattern)[name]?.let { return it }
        }
        return null
    }

    /** The values that may be of [key] at [path]. */
    fun valuesAt(path: List<String>, key: String?): List<Value> {
        if (key == null) return emptyList()
        val found = LinkedHashMap<String, Value>()
        for ((pattern, map) in valueMap) {
            val split = pattern.lastIndexOf(SEPARATOR)
            val keyPattern = pattern.substring(split + 1)
            if (keyPattern != key && keyPattern != ANY) continue
            if (!matches(pattern.substring(0, split), path)) continue
            for ((text, value) in map) if (!found.containsKey(text)) found[text] = value
        }
        return found.values.toList()
    }

    /**
     * Learns what [text] (a template, the file as shipped: comments are fine) has: its keys
     * where they are, with the shape of their values, and the strings given to them (no
     * longer than [MAX_LEARNED_VALUE], not a placeholder `{name}`). [general]: the path a
     * learned one is kept under (`captures/comment` → `captures` and `*`, any capture), as
     * the schema says it.
     */
    fun learn(text: CharSequence, general: (List<String>) -> String = { it.joinToString("/") }): JsonSchema {
        val scanner = JsonScanner(text)
        scanner.onKey = { path, key -> keys(general(path), key to Shape.ANY) }
        scanner.onValue = { path, key, shape, string ->
            val where = general(path)
            if (key != ITEMS) {
                keys(where, key to when (shape) {
                    JsonScanner.Shape.STRING -> Shape.STRING
                    JsonScanner.Shape.NUMBER -> Shape.NUMBER
                    JsonScanner.Shape.BOOLEAN -> Shape.BOOLEAN
                    JsonScanner.Shape.OBJECT -> Shape.OBJECT
                    JsonScanner.Shape.ARRAY -> Shape.ARRAY
                    else -> Shape.ANY
                })
            }
            if (string != null && string.isNotBlank() && string.length <= MAX_LEARNED_VALUE &&
                !(string.startsWith("{") && string.endsWith("}"))
            ) {
                values(where, key, string)
            }
        }
        scanner.run(text.length)
        return this
    }

    // the patterns, those without `*` first (what is said of a path itself before what is said of any)
    private fun sorted(patterns: Collection<String>): List<Pair<String, Int>> =
        patterns.map { it to it.count { c -> c == '*' } }.sortedBy { it.second }

    private fun matches(pattern: String, path: List<String>): Boolean {
        val parts = if (pattern.isEmpty()) emptyList() else pattern.split('/')
        if (parts.size != path.size) return false
        for (i in parts.indices) if (parts[i] != ANY && parts[i] != path[i]) return false
        return true
    }

    private fun valueKey(path: String, key: String) = path + SEPARATOR + key

    companion object {
        /** The items of an array, in a path. */
        const val ITEMS = "[]"

        /** Any one key, in a path (or as the key of [values]). */
        const val ANY = "*"

        // between the path and the key of the values
        private const val SEPARATOR = '\u0000'

        // a string longer than this, learned from a text, is not offered as a value
        const val MAX_LEARNED_VALUE = 48
    }
}
