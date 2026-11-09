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

import android.content.Context
import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Language servers that format, by the languages they are for, as the app ships them
 * (assets/formatting/servers.jsonc): what the formatting sheet suggests installing for a
 * language that no server formats (FormattingSheet: a tap on a row that says "✗"). Their
 * projects are on GitHub; [Catalog.directory] is where to look for a language not listed.
 * Read once.
 */
object FormatterServers {

    private const val TAG = "FormatterServers"
    private const val ASSET = "formatting/servers.jsonc"

    /**
     * A server to suggest: [name], its project ([url]), the LSP language ids it is for
     * ([languages]), how to install it in Termux ([install]) and the command it runs
     * ([command]), if they are known, and a word on it ([note]).
     */
    class Suggestion(
        val name: String,
        val url: String,
        val languages: Set<String>,
        val install: String?,
        val command: String?,
        private val notes: Map<String, String>,
    ) {
        /** The note in [language] (a locale's language: "zh"), else in English; null: none. */
        fun note(language: String): String? = notes[language] ?: notes["en"] ?: notes.values.firstOrNull()
    }

    /** The servers of the asset, and where to look for others ([directory]; null: not said). */
    class Catalog(val directory: String?, val servers: List<Suggestion>) {
        /** The servers for [languageId], in the order of the asset. */
        fun forLanguage(languageId: String): List<Suggestion> = servers.filter { languageId.lowercase() in it.languages }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    @Volatile
    private var catalog: Catalog? = null

    /** The catalog of the assets, read the first time. Not on the main thread (the first time). */
    fun catalog(context: Context): Catalog = catalog ?: runCatching {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }.getOrElse {
        Log.w(TAG, "cannot read the asset $ASSET", it)
        Catalog(null, emptyList())
    }.also { catalog = it }

    /** [text] (the asset) read: a server without a name, a url or languages is left out. */
    fun parse(text: String): Catalog {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return Catalog(null, emptyList())
        val servers = (root["servers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            fun string(key: String): String? =
                (server[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val name = string("name") ?: return@mapNotNull null
            val url = string("url") ?: return@mapNotNull null
            val languages = (server["languages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
                .toSet()
            if (languages.isEmpty()) return@mapNotNull null
            val notes = when (val note = server["note"]) {
                is JsonObject -> note.mapNotNull { (language, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { language to it }
                }.toMap()
                is JsonPrimitive -> note.contentOrNull?.let { mapOf("en" to it) }.orEmpty()
                else -> emptyMap()
            }
            Suggestion(name, url, languages, string("install"), string("command"), notes)
        }
        val directory = (root["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Catalog(directory, servers)
    }
}
