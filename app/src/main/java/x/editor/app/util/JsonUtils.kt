/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.util

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

import java.io.File
import java.io.InputStream

object JsonUtils {

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    /**
     * Parses the file and hands its text and the parsed element to [block].
     *
     * @throws java.io.IOException if the file cannot be read
     * @throws SerializationException if it is not valid JSON
     */
    inline fun <R> parse(
        jsonFile: File,
        block: (String, JsonElement) -> R
    ): R = parse(jsonFile.inputStream(), block)

    /**
     * Reads [input] to its end as UTF-8, closes it, and hands the text and the
     * parsed element to [block].
     *
     * @throws SerializationException if it is not valid JSON
     */
    inline fun <R> parse(
        input: InputStream,
        block: (String, JsonElement) -> R
    ): R = parse(input.bufferedReader().use { it.readText() }, block)

    /**
     * Hands [jsonString] and the element it parses to, to [block].
     *
     * @throws SerializationException if it is not valid JSON
     */
    @Throws(SerializationException::class)
    inline fun <R> parse(
        jsonString: String,
        block: (String, JsonElement) -> R
    ): R = block(jsonString, json.parseToJsonElement(jsonString))
}
