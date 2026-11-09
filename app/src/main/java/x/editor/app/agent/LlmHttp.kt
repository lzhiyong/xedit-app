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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The HTTP the providers share: one client, a JSON post read back as server-sent events. */
internal object LlmHttp {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val JSON_TYPE = "application/json".toMediaType()

    // a reply may think for a while before its first byte, and between two of them
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Posts [body] to [url] with [headers], and hands each event of the stream it answers
     * with to [onEvent] (on an IO thread). Cancelling the coroutine cancels the call.
     *
     * @throws LlmException if it cannot be reached, or answers with an error
     */
    suspend fun postStream(url: String, headers: Map<String, String>, body: JsonObject, onEvent: (SseParser.Event) -> Unit) {
        // (off the main thread: with images attached it is megabytes of base64)
        val json = withContext(Dispatchers.Default) { body.toString() }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(json.toRequestBody(JSON_TYPE))
        // (not in an apply on the builder: it has a member of that name)
        for ((name, value) in headers) builder.header(name, value)
        val request = builder.build()
        val call = client.newCall(request)
        coroutineScope {
            // cancelled: the call too, which ends the read it is blocked in
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                val text = runCatching { response.body.string() }.getOrDefault("")
                                throw LlmException(errorMessage(response.code, text), response.code)
                            }
                            val source = response.body.source()
                            val parser = SseParser()
                            while (true) {
                                ensureActive()
                                val line = source.readUtf8Line() ?: break
                                parser.feed(line)?.let(onEvent)
                            }
                            parser.finish()?.let(onEvent)
                        }
                    } catch (e: LlmException) {
                        throw e
                    } catch (e: IOException) {
                        ensureActive()
                        throw LlmException(e.message ?: e.toString(), null, e)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    // a short question (the list of models): an answer is quick or it is not coming
    private val quickClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Gets [url] with [headers], and what it answers with as a JSON object. Cancelling the
     * coroutine cancels the call.
     *
     * @throws LlmException if it cannot be reached, answers with an error, or not with JSON
     */
    suspend fun getJson(url: String, headers: Map<String, String>): JsonObject {
        val request = try {
            val builder = Request.Builder().url(url).header("Accept", "application/json").get()
            for ((name, value) in headers) builder.header(name, value)
            builder.build()
        } catch (e: IllegalArgumentException) {
            // not an address OkHttp can go to, or a header it does not take (a key with a line break)
            throw LlmException(e.message ?: e.toString(), null, e)
        }
        val call = quickClient.newCall(request)
        return coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            val text = runCatching { response.body.string() }.getOrDefault("")
                            if (!response.isSuccessful) throw LlmException(errorMessage(response.code, text), response.code)
                            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                                ?: throw LlmException("HTTP ${response.code}: ${text.trim().take(200)}", response.code)
                        }
                    } catch (e: LlmException) {
                        throw e
                    } catch (e: IOException) {
                        ensureActive()
                        throw LlmException(e.message ?: e.toString(), null, e)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** What an error answer says: its `error.message` (both providers), else its text. */
    fun errorMessage(code: Int, text: String): String {
        val said = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val error = root["error"]
            when {
                error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                error != null -> error.jsonPrimitive.contentOrNull
                else -> root["message"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull() ?: text.trim().take(300)
        return if (said.isEmpty()) "HTTP $code" else "HTTP $code: $said"
    }

    /** A JSON object of [text]; an empty one if it is not one (a tool input cut off). */
    fun objectOf(text: String): JsonObject? =
        if (text.isBlank()) JsonObject(emptyMap())
        else runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
}
