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

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelCatalogTest {

    private fun json(text: String) = LlmHttp.json.parseToJsonElement(text).jsonObject

    @Test
    fun anthropicListWithDisplayNames() {
        val answer = json(
            """{"data":[
                {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"},
                {"type":"model","id":"claude-haiku-5-5","display_name":"claude-haiku-5-5"}
               ],"has_more":true,"last_id":"claude-haiku-5-5"}"""
        )
        assertEquals(
            listOf(ModelCatalog.Model("claude-opus-5-5", "Claude Opus 5.5"), ModelCatalog.Model("claude-haiku-5-5")),
            ModelCatalog.parse(answer),
        )
        assertEquals("claude-haiku-5-5", ModelCatalog.nextPageAfter(answer))
    }

    @Test
    fun openAiListSkipsBadEntriesAndDuplicates() {
        val answer = json(
            """{"object":"list","data":[
                {"id":"gpt-x","object":"model"},
                {"id":"  "},
                {"object":"model"},
                "nonsense",
                {"id":"gpt-x"},
                {"id":"vendor/model","name":"Vendor: Model"}
               ]}"""
        )
        assertEquals(
            listOf(ModelCatalog.Model("gpt-x"), ModelCatalog.Model("vendor/model", "Vendor: Model")),
            ModelCatalog.parse(answer),
        )
        assertNull(ModelCatalog.nextPageAfter(answer))
    }

    @Test
    fun theContextWindowIfTheListSaysIt() {
        val answer = json(
            """{"data":[
                {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_input_tokens":1000000,"max_tokens":128000},
                {"id":"vendor/model","name":"Vendor: Model","context_length":131072},
                {"id":"other","context_window":"32768"},
                {"id":"none","max_input_tokens":0},
                {"id":"unknown","max_input_tokens":null}
               ]}"""
        )
        assertEquals(listOf(1_000_000, 131_072, 32_768, null, null), ModelCatalog.parse(answer).map { it.contextWindow })
    }

    @Test
    fun noDataIsNoModels() {
        assertEquals(emptyList(), ModelCatalog.parse(json("""{"error":{"message":"no"}}""")))
    }

    @Test
    fun urls() {
        assertEquals(
            "https://api.anthropic.com/v1/models?limit=1000",
            ModelCatalog.modelsUrl(AgentSettings.ProviderKind.ANTHROPIC, "https://api.anthropic.com/"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models?limit=1000&after_id=a%2Fb",
            ModelCatalog.modelsUrl(AgentSettings.ProviderKind.ANTHROPIC, "https://api.anthropic.com", "a/b"),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1/models",
            ModelCatalog.modelsUrl(AgentSettings.ProviderKind.OPENAI, " http://127.0.0.1:11434/v1/ "),
        )
    }

    @Test
    fun recentModels() {
        assertEquals(listOf("b", "a", "c"), AgentSettings.withRecent(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("new", "a"), AgentSettings.withRecent(listOf("a"), "new"))
        assertEquals(listOf("a"), AgentSettings.withRecent(listOf("a"), ""))
        val many = (1..10).map { "m$it" }
        assertEquals(listOf("x") + many.take(7), AgentSettings.withRecent(many, "x"))
    }
}
