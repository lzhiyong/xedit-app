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

import android.content.Context
import x.editor.app.R
import java.net.URI

/**
 * What a provider is called on the screen (the head of the sheet, the model picker): the
 * service at the address it is set up with. A provider is a kind of API (AgentSettings.
 * ProviderKind), and many services speak each: DeepSeek's Anthropic-compatible API is
 * DeepSeek's, its models are not Claude, and "Anthropic (Claude)" in front of them says
 * what is not so.
 */
internal object ProviderNames {

    /** The name of the provider of [kind] at [baseUrl]: the service's ([serviceOf]), or [kind]'s own. */
    fun of(context: Context, kind: AgentSettings.ProviderKind, baseUrl: String): String =
        serviceOf(kind, baseUrl) ?: context.getString(
            if (kind == AgentSettings.ProviderKind.ANTHROPIC) R.string.agent_provider_anthropic else R.string.agent_provider_openai
        )

    /**
     * The service at [baseUrl], for [kind]: by its name if it is a known one (OpenAI,
     * DeepSeek, Moonshot, ...), else the host of the address (api. left out). Null: Anthropic
     * itself, for its own API (its name is the kind's), or an address with no host.
     */
    fun serviceOf(kind: AgentSettings.ProviderKind, baseUrl: String): String? {
        val host = hostOf(baseUrl) ?: return null
        if (kind == AgentSettings.ProviderKind.ANTHROPIC && within(host, "anthropic.com")) return null
        KNOWN.firstOrNull { (domain, _) -> within(host, domain) }?.let { return it.second }
        return host.removePrefix("api.").removePrefix("www.")
    }

    // [host] is [domain] or a part of it (api.deepseek.com of deepseek.com)
    private fun within(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    private fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    // the services known by name: their domain, and what they are called
    private val KNOWN = listOf(
        "openai.com" to "OpenAI",
        "anthropic.com" to "Anthropic",
        "deepseek.com" to "DeepSeek",
        "moonshot.cn" to "Moonshot (Kimi)",
        "moonshot.ai" to "Moonshot (Kimi)",
        "bigmodel.cn" to "Zhipu (GLM)",
        "z.ai" to "Z.ai (GLM)",
        "dashscope.aliyuncs.com" to "Qwen (DashScope)",
        "dashscope-intl.aliyuncs.com" to "Qwen (DashScope)",
        "openrouter.ai" to "OpenRouter",
        "minimaxi.com" to "MiniMax",
        "minimax.io" to "MiniMax",
        "siliconflow.cn" to "SiliconFlow",
        "siliconflow.com" to "SiliconFlow",
        "volces.com" to "Volcengine (Doubao)",
        "x.ai" to "xAI",
        "mistral.ai" to "Mistral",
        "groq.com" to "Groq",
        "together.xyz" to "Together",
        "generativelanguage.googleapis.com" to "Google (Gemini)",
    )
}
