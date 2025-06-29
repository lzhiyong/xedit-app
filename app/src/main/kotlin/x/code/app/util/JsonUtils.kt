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

package x.code.app.util

import kotlinx.serialization.*
import kotlinx.serialization.json.*

import java.io.File
import java.io.InputStream


object JsonUtils {
    
    val json = Json {            
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    
    inline fun <R> parse(
        jsonFile: File,
        block: (String, JsonElement) -> R
    ) = JsonUtils.parse(
        jsonFile.inputStream(),
        block
    )
    
    inline fun <R> parse(
        input: InputStream,
        block: (String, JsonElement) -> R
    ) = JsonUtils.parse(
        input.bufferedReader().use { it.readText() },
        block
    )
    
    @Throws(SerializationException::class)
    inline fun <R> parse(
        jsonString : String, 
        block: (String, JsonElement) -> R
    ): R {
        try {
            val jsonElement = json.parseToJsonElement(jsonString)
        
            return block(jsonString, jsonElement)
        } catch(e: SerializationException) {
            e.printStackTrace()
            throw e
        }
    }
}

