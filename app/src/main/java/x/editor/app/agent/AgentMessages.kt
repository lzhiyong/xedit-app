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

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the model is told of the chat before the [question]-th thing the user asked (from 0).
 *
 * What the user asked is the only text the user's messages have (what tools returned is not
 * text), but a message may hold more than one: a question after a turn that ended early goes
 * in the same message as what came before it (AgentSession.send). So they are counted by text,
 * and of the message that holds this one, what comes before it stays: not the files attached
 * to it, which come right before its text. Not found (a chat kept by an older version, say):
 * all of it stays.
 */
internal fun messagesBefore(messages: List<Message>, question: Int): List<Message> {
    var seen = 0
    messages.forEachIndexed { index, message ->
        if (message.role != Role.USER) return@forEachIndexed
        message.blocks.forEachIndexed { at, block ->
            if (block is Block.Text) {
                if (seen == question) {
                    val before = messages.subList(0, index)
                    var start = at
                    while (start > 0 && message.blocks[start - 1] is Block.Attachment) start--
                    return if (start == 0) before else before + Message(Role.USER, message.blocks.subList(0, start))
                }
                seen++
            }
        }
    }
    return messages
}

/**
 * How long ago a message was sent, as its row says it ([sentAgo]): just now (under a
 * minute, or a clock set back), so many minutes (under an hour), hours (under a day), days
 * (under a week) ago; beyond that the day it was sent ([Date]: MM-dd this year, yyyy-MM-dd
 * before). The words are the sheet's (AgentSheet.sentTimeLabel).
 */
internal sealed interface SentAgo {
    object JustNow : SentAgo
    data class Minutes(val count: Int) : SentAgo
    data class Hours(val count: Int) : SentAgo
    data class Days(val count: Int) : SentAgo
    data class Date(val text: String) : SentAgo
}

/** How long before [now] a message sent at [time] was (SentAgo), the day in [zone]. */
internal fun sentAgo(time: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): SentAgo {
    val elapsed = now - time
    return when {
        elapsed < MINUTE_MS -> SentAgo.JustNow
        elapsed < HOUR_MS -> SentAgo.Minutes((elapsed / MINUTE_MS).toInt())
        elapsed < DAY_MS -> SentAgo.Hours((elapsed / HOUR_MS).toInt())
        elapsed < WEEK_MS -> SentAgo.Days((elapsed / DAY_MS).toInt())
        else -> {
            val sent = Instant.ofEpochMilli(time).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone)
            val pattern = if (sent.year == today.year) "MM-dd" else "yyyy-MM-dd"
            SentAgo.Date(DateTimeFormatter.ofPattern(pattern).format(sent))
        }
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val WEEK_MS = 7 * DAY_MS
