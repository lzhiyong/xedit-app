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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * The models a provider has, as it lists them (for the model picker of the AI assistant):
 * Anthropic's `GET /v1/models` (newest first, a page at a time), an OpenAI-compatible
 * API's `GET {address}/models` (OpenAI, DeepSeek, OpenRouter, Ollama, ...). Not every
 * OpenAI-compatible server has the list: then it fails, and the picker still has the
 * models used before and a name to type.
 */
object ModelCatalog {

    /**
     * A model: its API name ([id]), the name it is shown by if the provider gives one, and
     * the tokens it has room for if the list says ([contextWindow]: AgentSettings.contextWindow).
     */
    data class Model(val id: String, val displayName: String? = null, val contextWindow: Int? = null)

    /** Where the list is, for [kind] at [baseUrl]; [afterId]: the page after that model (Anthropic). */
    fun modelsUrl(kind: AgentSettings.ProviderKind, baseUrl: String, afterId: String? = null): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (kind) {
            AgentSettings.ProviderKind.ANTHROPIC -> buildString {
                append(base).append("/v1/models?limit=").append(PAGE_SIZE)
                if (afterId != null) append("&after_id=").append(URLEncoder.encode(afterId, "UTF-8"))
            }
            AgentSettings.ProviderKind.OPENAI -> "$base/models"
        }
    }

    /**
     * The models of one answer: the objects of its `data` with an `id` (both providers),
     * shown by their `display_name` (Anthropic) or `name` (OpenRouter) if they have one
     * that says more than the id, with the tokens they have room for if it says
     * (`max_input_tokens`: Anthropic; `context_length`: OpenRouter and others;
     * `context_window`: some more). The same id twice: once.
     */
    fun parse(answer: JsonObject): List<Model> {
        val data = answer["data"] as? JsonArray ?: return emptyList()
        val seen = HashSet<String>()
        val models = ArrayList<Model>(data.size)
        for (element in data) {
            val item = element as? JsonObject ?: continue
            val id = item.string("id")?.trim().orEmpty()
            if (id.isEmpty() || !seen.add(id)) continue
            val name = (item.string("display_name") ?: item.string("name"))?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals(id, ignoreCase = true) }
            val window = CONTEXT_FIELDS.firstNotNullOfOrNull { field -> item.positive(field) }
            models += Model(id, name, window)
        }
        return models
    }

    /** Whether there is a page after this one (Anthropic's `has_more`), and from which model on. */
    fun nextPageAfter(answer: JsonObject): String? {
        val more = (answer["has_more"] as? JsonPrimitive)?.booleanOrNull ?: false
        return if (more) answer.string("last_id") else null
    }

    /**
     * The models of the provider of [config], in the order to show them: Anthropic's as it
     * lists them (newest first), the others' by name (their order says nothing).
     *
     * @throws LlmException if the list cannot be had
     */
    suspend fun fetch(config: AgentSettings.Config): List<Model> {
        val apiKey = config.apiKey.trim()
        return when (config.kind) {
            AgentSettings.ProviderKind.ANTHROPIC -> {
                val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to AnthropicProvider.API_VERSION)
                val models = ArrayList<Model>()
                var after: String? = null
                for (page in 0 until MAX_PAGES) {
                    val answer = LlmHttp.getJson(modelsUrl(config.kind, config.baseUrl, after), headers)
                    models += parse(answer)
                    after = nextPageAfter(answer) ?: break
                }
                models.distinctBy { it.id }
            }
            AgentSettings.ProviderKind.OPENAI -> {
                val headers = if (apiKey.isNotEmpty()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
                parse(LlmHttp.getJson(modelsUrl(config.kind, config.baseUrl), headers))
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.id })
            }
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    // a count above 0 (a number, or a string of one)
    private fun JsonObject.positive(name: String): Int? =
        (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }?.takeIf { it > 0 }

    private val CONTEXT_FIELDS = listOf("max_input_tokens", "context_length", "context_window")

    private const val PAGE_SIZE = 1000
    private const val MAX_PAGES = 5
}
