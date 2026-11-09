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

/**
 * Server-sent events, a line at a time (as both providers stream their replies): `event:`
 * and `data:` lines up to a blank line make an event; `data:` lines of one event are joined
 * by newlines; comments (`:`) and other fields are let be.
 */
class SseParser {

    /** An event: its [type] (null: none given) and [data]. */
    data class Event(val type: String?, val data: String)

    private var type: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** [line] (without its line end) read; the event it ends, if it ends one. */
    fun feed(line: String): Event? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** The stream ended: the event not yet ended by a blank line, if any. */
    fun finish(): Event? = flush()

    private fun flush(): Event? {
        val event = if (hasData) Event(type, data.toString()) else null
        type = null
        data.setLength(0)
        hasData = false
        return event
    }
}
