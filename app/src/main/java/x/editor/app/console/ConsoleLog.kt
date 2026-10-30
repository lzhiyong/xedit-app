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

package x.editor.app.console

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the console shows in its output: lines by channel (X Editor, the language servers,
 * each server's own log, each build: one channel per project or file, see BuildRunner),
 * the last [MAX_ENTRIES] of each. Any thread may append;
 * listeners hear of it on the main thread, in order. See ConsoleSheet.
 */
object ConsoleLog {

    enum class Level { DEBUG, INFO, WARNING, ERROR }

    /**
     * One line (or a few lines) of a channel, with when it came and how serious it is.
     * [heading]: a line of a build with its time (when it started, when it ended): shown at
     * the left edge, the build's other lines indented under it (ConsoleSheet).
     */
    data class Entry(val time: Long, val level: Level, val text: String, val heading: Boolean = false)

    /** Told on the main thread of every entry appended, and of channels cleared. */
    interface Listener {
        fun onAppended(channel: String, entry: Entry)
        fun onCleared(channel: String) {}
    }

    /** The app itself: what went wrong with a document, ... */
    const val APP = "X Editor"

    /** What the app does with the language servers (LspLog). */
    const val LSP = "Language servers"

    /**
     * What the builds of a project (or of a file) print: one channel each, so that builds
     * of different projects running side by side do not mix their lines (BuildRunner).
     */
    fun buildChannel(label: String) = BUILD_PREFIX + label

    /** Whether [channel] is that of a build: its lines are shown without their times (the build's start and end have them). */
    fun isBuildChannel(channel: String) = channel.startsWith(BUILD_PREFIX)

    /** How far a build's lines are indented under its start and end lines (the headings). */
    const val BUILD_INDENT = "    "

    /**
     * [entry] as a build's channel shows it: a heading as it is, any other entry with each
     * of its lines indented by [BUILD_INDENT] (blank lines left blank). As copied too.
     */
    fun buildText(entry: Entry): String =
        if (entry.heading) entry.text
        else entry.text.lines().joinToString("\n") { if (it.isBlank()) "" else BUILD_INDENT + it }

    private const val BUILD_PREFIX = "Build: "

    /** A server's own log: what it sends (window/logMessage, window/showMessage). */
    fun serverChannel(serverId: String) = "LSP: $serverId"

    private const val MAX_ENTRIES = 3000

    private val lock = Any()
    private val channels = LinkedHashMap<String, ArrayDeque<Entry>>().apply {
        put(APP, ArrayDeque())
        put(LSP, ArrayDeque())
    }
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun append(channel: String, level: Level, text: String, heading: Boolean = false) {
        val entry = Entry(System.currentTimeMillis(), level, text.trimEnd(), heading)
        synchronized(lock) {
            val entries = channels.getOrPut(channel) { ArrayDeque() }
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        if (listeners.isNotEmpty()) main.post { for (listener in listeners) listener.onAppended(channel, entry) }
    }

    /** The channels, in the order they first had something (the two of the app first). */
    fun channels(): List<String> = synchronized(lock) { channels.keys.toList() }

    fun entries(channel: String): List<Entry> = synchronized(lock) { channels[channel]?.toList().orEmpty() }

    fun clear(channel: String) {
        synchronized(lock) { channels[channel]?.clear() }
        main.post { for (listener in listeners) listener.onCleared(channel) }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }
}
