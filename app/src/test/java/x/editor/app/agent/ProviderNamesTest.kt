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

import x.editor.app.agent.AgentSettings.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderNamesTest {

    @Test
    fun anthropicItselfIsTheKindsName() {
        assertNull(ProviderNames.serviceOf(ProviderKind.ANTHROPIC, "https://api.anthropic.com"))
        assertNull(ProviderNames.serviceOf(ProviderKind.ANTHROPIC, "https://API.Anthropic.com/"))
    }

    @Test
    fun anotherServiceSpeakingAnthropicsApiIsThatService() {
        assertEquals("DeepSeek", ProviderNames.serviceOf(ProviderKind.ANTHROPIC, "https://api.deepseek.com/anthropic"))
        assertEquals("Moonshot (Kimi)", ProviderNames.serviceOf(ProviderKind.ANTHROPIC, "https://api.moonshot.cn/anthropic"))
        assertEquals("Zhipu (GLM)", ProviderNames.serviceOf(ProviderKind.ANTHROPIC, "https://open.bigmodel.cn/api/anthropic"))
    }

    @Test
    fun theOpenAiCompatibleOnes() {
        assertEquals("OpenAI", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://api.openai.com/v1"))
        assertEquals("DeepSeek", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://api.deepseek.com"))
        assertEquals("Qwen (DashScope)", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertEquals("OpenRouter", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://openrouter.ai/api/v1"))
    }

    @Test
    fun anUnknownOneIsItsHost() {
        assertEquals("example.com", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://api.example.com/v1"))
        assertEquals("localhost", ProviderNames.serviceOf(ProviderKind.OPENAI, "http://localhost:11434/v1"))
        assertEquals("192.168.1.5", ProviderNames.serviceOf(ProviderKind.OPENAI, "http://192.168.1.5:1234/v1"))
        // a domain that only ends like a known one is not it
        assertEquals("notdeepseek.com", ProviderNames.serviceOf(ProviderKind.OPENAI, "https://notdeepseek.com"))
    }

    @Test
    fun noHostNoName() {
        assertNull(ProviderNames.serviceOf(ProviderKind.OPENAI, "api.deepseek.com"))
        assertNull(ProviderNames.serviceOf(ProviderKind.OPENAI, ""))
        assertNull(ProviderNames.serviceOf(ProviderKind.OPENAI, "::not a url"))
    }
}
