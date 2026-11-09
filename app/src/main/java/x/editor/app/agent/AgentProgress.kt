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

/** How a turn ended (ChatItem.Progress); [RUNNING]: it goes on. */
enum class ProgressState { RUNNING, DONE, STOPPED, INCOMPLETE }

/**
 * What the agent is doing now, in a turn that goes on: what the status of the turn says
 * (ChatItem.Progress, AgentSheet puts it in words).
 */
sealed interface TurnActivity {
    /** Waiting for the model, or it thinks and says nothing of it. */
    data object Thinking : TurnActivity

    /** The model thinks, and says this of it ([AgentProgress.headline] of what it said). */
    data class Thought(val text: String) : TurnActivity

    /** The model's answer streams in. */
    data object Answering : TurnActivity

    /** The model is asking for the tool [tool] (its input still coming). */
    data class Preparing(val tool: String) : TurnActivity

    /** A call of [tool] on [summary] waits for the user to allow it. */
    data class Waiting(val tool: String, val summary: String) : TurnActivity

    /** A call of [tool] on [summary] runs. */
    data class Running(val tool: String, val summary: String) : TurnActivity

    /** The older part of the conversation is being summarized (AgentSession.compact). */
    data object Compacting : TurnActivity
}

/** The status of a turn put in words: pure (what AgentSheet shows, AgentSession keeps). */
internal object AgentProgress {

    /**
     * What a part of the model's thinking ([thinking], as it streams in) says it is doing:
     * the last heading in it (a summary of Anthropic's is in parts, each under a line of
     * bold: **Checking the build**), else its last paragraph; one line of it, without
     * Markdown, at most [MAX_HEADLINE] characters. Null: nothing yet.
     */
    fun headline(thinking: String): String? {
        val text = thinking.trim()
        if (text.isEmpty()) return null
        val lines = text.lines()
        for (index in lines.indices.reversed()) {
            val heading = headingOf(lines[index].trim()) ?: continue
            return oneLine(heading)
        }
        val paragraph = text.split(BLANK_LINE).lastOrNull { it.isNotBlank() } ?: return null
        return oneLine(paragraph)
    }

    // what [line] says as a heading (# Title, **Title**, __Title__); null: it is not one
    private fun headingOf(line: String): String? {
        HASH_HEADING.matchEntire(line)?.let { return it.groupValues[1] }
        BOLD_HEADING.matchEntire(line)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
        return null
    }

    // [text] on one line, its Markdown marks left out; null if nothing is left
    private fun oneLine(text: String): String? {
        val plain = text
            .replace(EMPHASIS, "")
            .replace(LIST_MARK, "")
            .replace(WHITESPACE, " ")
            .trim()
            .trimEnd(':', '：')
            .trim()
        if (plain.isEmpty()) return null
        return if (plain.length <= MAX_HEADLINE) plain else plain.take(MAX_HEADLINE - 1).trimEnd() + "…"
    }

    /**
     * How long [millis] is, as the status of a turn says it: 42s, 6m 18s, 1h 02m (the
     * seconds and minutes after the first part two digits each, so it does not jump about).
     */
    fun elapsedText(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) / 1000L)
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m ${two(seconds % 60)}s"
        return "${minutes / 60}h ${two(minutes % 60)}m"
    }

    private fun two(value: Long) = value.toString().padStart(2, '0')

    /** How long until [millis] (of a turn going on) reaches its next whole second. */
    fun untilNextSecond(millis: Long): Long = 1000L - (millis.coerceAtLeast(0L) % 1000L)

    /** At most this many characters of a headline. */
    const val MAX_HEADLINE = 160

    private val BLANK_LINE = Regex("\\n\\s*\\n")
    private val HASH_HEADING = Regex("#{1,6}\\s+(.+?)\\s*#*")
    private val BOLD_HEADING = Regex("\\*\\*([^*]+)\\*\\*:?|__([^_]+)__:?")
    private val EMPHASIS = Regex("\\*\\*|__|`")
    private val LIST_MARK = Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val WHITESPACE = Regex("\\s+")
}
