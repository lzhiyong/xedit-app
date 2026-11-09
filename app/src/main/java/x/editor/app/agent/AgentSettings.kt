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
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * How the agent reaches its model, and what it may do without asking. Each kind of provider
 * keeps its own address, model and key, so that going from one to the other and back loses
 * nothing. The API keys are kept encrypted, with a key of the Android Keystore that never
 * leaves it ([SecretStore]); a backup restored elsewhere cannot read them (they are asked for
 * again).
 */
object AgentSettings {

    /**
     * [defaultContextWindow]: the tokens a model of it is taken to have room for when nothing
     * says how many (contextWindow): Claude's usual window; for the others, what most of
     * today's models have at least.
     */
    enum class ProviderKind(val key: String, val defaultBaseUrl: String, val defaultModel: String, val defaultContextWindow: Int) {
        /** Anthropic's Messages API (Claude) */
        ANTHROPIC("anthropic", AnthropicProvider.DEFAULT_BASE_URL, "claude-sonnet-5-5", 200_000),

        /** any OpenAI-compatible chat completions API */
        OPENAI("openai", OpenAiProvider.DEFAULT_BASE_URL, "", 128_000);

        /** The levels of thinking this provider offers ([ThinkingLevel.DEFAULT] first). */
        val thinkingLevels: List<ThinkingLevel>
            get() = if (this == ANTHROPIC) ThinkingLevel.ANTHROPIC else ThinkingLevel.OPENAI

        /** [level] if this provider offers it, else [ThinkingLevel.DEFAULT]. */
        fun offered(level: ThinkingLevel): ThinkingLevel = if (level in thinkingLevels) level else ThinkingLevel.DEFAULT
    }

    /** What runs without being asked: reading always; [AUTO_EDITS] file changes too; [AUTO_ALL] commands too. */
    enum class Approval(val key: String) { ASK("ask"), AUTO_EDITS("auto_edits"), AUTO_ALL("auto_all") }

    data class Config(
        val kind: ProviderKind,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val maxTokens: Int,
        val approval: Approval,
        /** How much the model thinks (kept for each provider, as its model is). */
        val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
        /**
         * The tokens the model has room for, as the user set it for this provider; 0: not
         * set, found out (contextWindow).
         */
        val contextWindow: Int = 0,
    ) {
        /** Whether a request can be made with it: a model, and a key (a local OpenAI-compatible server may need none). */
        val isUsable: Boolean
            get() = model.isNotBlank() && baseUrl.isNotBlank() && (apiKey.isNotBlank() || kind == ProviderKind.OPENAI)

        fun provider(): LlmProvider = when (kind) {
            ProviderKind.ANTHROPIC -> AnthropicProvider(baseUrl.trim(), apiKey.trim(), model.trim(), kind.offered(thinking))
            ProviderKind.OPENAI -> OpenAiProvider(baseUrl.trim(), apiKey.trim(), model.trim(), thinking = kind.offered(thinking))
        }

        /** Whether a call of [risk] needs the user's yes. */
        fun asks(risk: ToolRisk): Boolean = when (risk) {
            ToolRisk.READ -> false
            ToolRisk.WRITE -> approval == Approval.ASK
            ToolRisk.COMMAND -> approval != Approval.AUTO_ALL
        }
    }

    const val DEFAULT_MAX_TOKENS = 8192

    private const val PREFS = "agent"
    private const val KIND = "provider"
    private const val MAX_TOKENS = "max_tokens"
    private const val APPROVAL = "approval"
    private const val THINKING = "thinking"
    private const val CONTEXT_WINDOW = "context_window"
    private const val LEARNED_WINDOW = "learned_context_window"

    /**
     * What is set up, but for the API key: only whether one is kept, not what it is. The
     * screen asks for this (AgentSheet: the head of the sheet, at every turn and at every
     * model picked), because reading the key is work for the Android Keystore, in another
     * process, and that is too slow to do while the user is tapping.
     */
    data class Summary(
        val kind: ProviderKind,
        val baseUrl: String,
        val model: String,
        val hasKey: Boolean,
        val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
    ) {
        /**
         * As [Config.isUsable], but it only knows that a key is kept, not that it can still
         * be read (a backup restored on another device cannot: the request then says so).
         */
        val isUsable: Boolean
            get() = model.isNotBlank() && baseUrl.isNotBlank() && (hasKey || kind == ProviderKind.OPENAI)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // the provider chosen, or [kind] if one is named
    private fun kindOf(prefs: SharedPreferences, kind: ProviderKind?): ProviderKind =
        kind ?: ProviderKind.entries.firstOrNull { it.key == prefs.getString(KIND, null) } ?: ProviderKind.ANTHROPIC

    /** What is set up for the provider chosen (or for [kind]), without reading the key. */
    fun summary(context: Context, kind: ProviderKind? = null): Summary {
        val prefs = prefs(context)
        val chosen = kindOf(prefs, kind)
        return Summary(
            kind = chosen,
            baseUrl = prefs.getString("${chosen.key}.base_url", null) ?: chosen.defaultBaseUrl,
            model = prefs.getString("${chosen.key}.model", null) ?: chosen.defaultModel,
            hasKey = !prefs.getString("${chosen.key}.api_key", null).isNullOrBlank(),
            thinking = thinkingOf(prefs, chosen),
        )
    }

    // the level of thinking kept for [kind] (one it offers)
    private fun thinkingOf(prefs: SharedPreferences, kind: ProviderKind): ThinkingLevel =
        kind.offered(ThinkingLevel.of(prefs.getString("${kind.key}.$THINKING", null)))

    /** The settings, of the provider chosen (or of [kind]); the key is read (the Keystore). */
    fun load(context: Context, kind: ProviderKind? = null): Config {
        val prefs = prefs(context)
        val chosen = kindOf(prefs, kind)
        val key = prefs.getString("${chosen.key}.api_key", null)?.let { SecretStore.decrypt(it) }.orEmpty()
        return Config(
            kind = chosen,
            baseUrl = prefs.getString("${chosen.key}.base_url", null) ?: chosen.defaultBaseUrl,
            model = prefs.getString("${chosen.key}.model", null) ?: chosen.defaultModel,
            apiKey = key,
            maxTokens = prefs.getInt(MAX_TOKENS, DEFAULT_MAX_TOKENS),
            approval = Approval.entries.firstOrNull { it.key == prefs.getString(APPROVAL, null) } ?: Approval.ASK,
            thinking = thinkingOf(prefs, chosen),
            contextWindow = prefs.getInt("${chosen.key}.$CONTEXT_WINDOW", 0).coerceAtLeast(0),
        )
    }

    fun save(context: Context, config: Config) {
        val encrypted = if (config.apiKey.isBlank()) null else SecretStore.encrypt(config.apiKey.trim())
        val recent = withRecent(recentModels(context, config.kind), config.model.trim())
        prefs(context).edit()
            .putString("${config.kind.key}.$RECENT", recent.joinToString("\n"))
            .putString(KIND, config.kind.key)
            .putString("${config.kind.key}.base_url", config.baseUrl.trim())
            .putString("${config.kind.key}.model", config.model.trim())
            .putString("${config.kind.key}.api_key", encrypted)
            .putString("${config.kind.key}.$THINKING", config.kind.offered(config.thinking).key)
            .putInt("${config.kind.key}.$CONTEXT_WINDOW", config.contextWindow.coerceAtLeast(0))
            .putInt(MAX_TOKENS, config.maxTokens)
            .putString(APPROVAL, config.approval.key)
            .apply()
    }

    // #region how much the model has room for

    /**
     * The tokens the model of [config] has room for, in what it is sent and what it writes
     * (AgentContext keeps a conversation within it): what the user set ([Config.contextWindow]);
     * else what a request found out (one refused as too long for it said: [learnContextWindow]);
     * else what the provider's list of models says of it (ModelCatalog); else the provider's
     * [ProviderKind.defaultContextWindow]. Never more than what was found out.
     */
    fun contextWindow(context: Context, config: Config): Int {
        val prefs = prefs(context)
        val model = config.model.trim()
        val learned = prefs.getInt(learnedKey(config.kind, model), 0).takeIf { it > 0 }
        val listed = cachedModels(context, config.kind, config.baseUrl)?.firstOrNull { it.id == model }?.contextWindow
        val chosen = config.contextWindow.takeIf { it > 0 } ?: learned ?: listed?.takeIf { it > 0 } ?: config.kind.defaultContextWindow
        return if (learned != null) minOf(chosen, learned) else chosen
    }

    /** [window] is what the model of [config] has room for, as a request refused as too long for it said. */
    fun learnContextWindow(context: Context, config: Config, window: Int) {
        if (window <= 0) return
        prefs(context).edit().putInt(learnedKey(config.kind, config.model.trim()), window).apply()
    }

    private fun learnedKey(kind: ProviderKind, model: String) = "${kind.key}.$LEARNED_WINDOW.$model"

    // #endregion

    // #region choosing the model (the model picker of AgentSheet)

    private const val RECENT = "recent_models"
    private const val MODELS = "models"
    private const val MODELS_FROM = "models_from"
    private const val MAX_RECENT = 8

    /** The models of [kind] used last (saved with it), the last one first. */
    fun recentModels(context: Context, kind: ProviderKind): List<String> =
        prefs(context).getString("${kind.key}.$RECENT", null).orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    // [model] in front of [recent], once, the list no longer than MAX_RECENT
    internal fun withRecent(recent: List<String>, model: String): List<String> =
        if (model.isEmpty()) recent.take(MAX_RECENT)
        else (listOf(model) + recent.filter { it != model }).take(MAX_RECENT)

    /**
     * Makes [model] of [kind] the one the assistant asks (and [kind] the provider): the rest
     * of that provider's settings as they are. From the next request on (a turn going on
     * keeps the model it started with).
     */
    fun selectModel(context: Context, kind: ProviderKind, model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        // only these: the key is not read and written again (nor lost if it cannot be read)
        prefs(context).edit()
            .putString(KIND, kind.key)
            .putString("${kind.key}.model", trimmed)
            .putString("${kind.key}.$RECENT", withRecent(recentModels(context, kind), trimmed).joinToString("\n"))
            .apply()
    }

    /**
     * The models [kind] listed last at [baseUrl] (ModelCatalog), to show at once while the
     * list is asked for again; null if it never did there (another address: another list).
     */
    fun cachedModels(context: Context, kind: ProviderKind, baseUrl: String): List<ModelCatalog.Model>? {
        val prefs = prefs(context)
        if (prefs.getString("${kind.key}.$MODELS_FROM", null) != baseUrl.trim()) return null
        val text = prefs.getString("${kind.key}.$MODELS", null) ?: return null
        // a line each: the id, its name, its context window (the last two may not be there)
        return text.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            val id = parts[0].trim()
            if (id.isEmpty()) null
            else ModelCatalog.Model(
                id,
                parts.getOrNull(1)?.trim()?.ifEmpty { null },
                parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    fun cacheModels(context: Context, kind: ProviderKind, baseUrl: String, models: List<ModelCatalog.Model>) {
        val text = models.joinToString("\n") { model ->
            // (no tab nor line break in what is kept per line)
            val name = model.displayName?.replace(Regex("[\t\n\r]"), " ").orEmpty()
            val window = model.contextWindow?.takeIf { it > 0 }
            when {
                window != null -> "${model.id}\t$name\t$window"
                name.isEmpty() -> model.id
                else -> "${model.id}\t$name"
            }
        }
        prefs(context).edit()
            .putString("${kind.key}.$MODELS_FROM", baseUrl.trim())
            .putString("${kind.key}.$MODELS", text)
            .apply()
    }

    // #endregion
}

/** Strings encrypted with an AES key of the Android Keystore (AES-GCM; the IV before the text, Base64). */
internal object SecretStore {

    private const val ALIAS = "xedit.agent.secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    // The key, looked up once and kept for as long as the app runs: opening the keystore and
    // asking it for the key is a call to another process, far too slow to do on the main
    // thread every time the settings are read (the head of the sheet reads them at every
    // model picked and at both ends of every turn). It is only a handle: the key itself never
    // leaves the keystore, which still does the encrypting. If it stops working (the user
    // cleared the keystore) it is dropped, and looked up again on the next call.
    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: found().also { cached = it }
    }

    private fun found(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** [plain] encrypted; null if the keystore cannot (it is then not kept). */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }.onFailure { cached = null }.getOrNull()

    /** What [encoded] was; null if it cannot be read (another device's, a key gone). */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.onFailure { cached = null }.getOrNull()
}
