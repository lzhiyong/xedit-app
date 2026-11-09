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

package x.editor.app.toolchain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import x.editor.app.config.JsoncSpans

/**
 * Edits of the text of packages.json that keep the rest of it as it is: its comments, its
 * layout, the toolchains around (JsoncSpans, which does it for any list of a JSONC file).
 * A toolchain is taken out of `"packages"` with the comma that goes with it, and its line
 * when nothing else is on it; one is added at its end (New on the toolchain panel); one is
 * found by its id (Edit: the editor goes to it). The text is not parsed and written again
 * (that would lose the comments).
 */
internal object CatalogText {

    /** The list of toolchains in packages.json. */
    const val LIST = "packages"

    /**
     * [text] without the toolchains of id [id] (all of them, switched on or off), the rest as
     * it is; null if it has none. IllegalArgumentException if the text is not a list of
     * toolchains (an object with a `"packages"` list).
     */
    fun removePackage(text: String, id: String): String? {
        var result = text
        var removed = false
        while (true) {
            val spans = packageSpans(result)
            val index = spans.indexOfFirst { idOf(result.substring(it.start, it.end)) == id }
            if (index < 0) break
            result = JsoncSpans.removeAt(result, spans, index)
            removed = true
        }
        return if (removed) result else null
    }

    /** The toolchains of `"packages"`, in order; IllegalArgumentException if there is no such list. */
    internal fun packageSpans(text: String): List<JsoncSpans.Span> = JsoncSpans.elementsOf(text, LIST)

    /**
     * Where the (last) toolchain of id [id] is in [text]: from its `{` to after its `}`;
     * null if there is none, or the text is not a list of toolchains.
     */
    fun spanOf(text: String, id: String): JsoncSpans.Span? =
        runCatching { packageSpans(text) }.getOrNull()
            ?.lastOrNull { idOf(text.substring(it.start, it.end)) == id }

    /**
     * [text] with [entry] (a toolchain, as the template of a new one has it) put at the end
     * of `"packages"`; IllegalArgumentException if there is no such list.
     */
    fun withPackage(text: String, entry: String): JsoncSpans.Appended = JsoncSpans.append(text, LIST, entry)

    // the id of a toolchain's text, if it says one
    private fun idOf(json: String): String? = try {
        ((ToolPackage.json.parseToJsonElement(json) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
    } catch (e: Exception) {
        null
    }
}
