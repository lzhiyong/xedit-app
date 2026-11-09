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

/**
 * The placeholders of the templates of the developer tools (assets/templates, see
 * Templates), one way for all of them: `{{name}}`.
 *
 *  - A placeholder that stands for a JSON value is written in quotes, `"{{tabSize}}"`, so
 *    that the template is still JSONC (highlighted, completed, parsed as it is); it is
 *    replaced quotes and all by the value as JSON (`4`, `true`, `"clangd"`; empty: taken
 *    out, as an extension that is not known in `["{{extension}}"]`): [values].
 *  - A placeholder in a comment, or inside a string, is written bare and replaced by plain
 *    text: [text]. The comments that explain a value's placeholder write it bare, so they
 *    are left as they are when only [values] has it.
 *
 * [values] are put in first, then [text]. Pure Kotlin: no Android in it.
 */
internal object TemplateText {

    /** The placeholder of [key] as written for a value: `"{{key}}"`, quotes and all. */
    fun quoted(key: String): String = "\"{{$key}}\""

    /** The placeholder of [key] as written in a comment or a string: `{{key}}`. */
    fun bare(key: String): String = "{{$key}}"

    /**
     * [template] with its placeholders put in: each of [values] (its JSON, as it is to be
     * written; empty: the placeholder taken out) for `"{{key}}"`, then each of [text] for
     * `{{key}}`.
     */
    fun fill(template: String, values: Map<String, String> = emptyMap(), text: Map<String, String> = emptyMap()): String {
        var result = template
        for ((key, json) in values) result = result.replace(quoted(key), json)
        for ((key, plain) in text) result = result.replace(bare(key), plain)
        return result
    }

    /** [value] as a JSON string, quoted and escaped. */
    fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
