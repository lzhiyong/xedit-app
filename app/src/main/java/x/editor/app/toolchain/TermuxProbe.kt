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

package x.editor.app.toolchain

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import x.editor.app.lsp.Termux
import x.editor.app.lsp.TermuxCommand
import java.io.IOException

/**
 * Which commands Termux has. X Editor installs nothing in Termux: you install its packages
 * there yourself (`pkg install clang`), and this finds what is there, by asking Termux to
 * run `command -v` for each command (in the background, through RUN_COMMAND).
 *
 * The answer is kept ([last]) until the next check: the toolchain sheet shows it, and checks
 * again when it opens.
 */
object TermuxProbe {

    /** What Termux had at the last check, and when; null if it was not asked yet. */
    @Volatile
    var last: Pair<Set<String>, Long>? = null
        private set

    private const val TIMEOUT_MILLIS = 20_000L

    /** The line that prints those of [commands] Termux has, one a line. */
    internal fun script(commands: Collection<String>): String =
        "for c in " + commands.distinct().joinToString(" ") { ToolchainRules.quote(it) } +
            "; do command -v \"\$c\" >/dev/null 2>&1 && echo \"\$c\"; done; true"

    /** The commands [stdout] (of [script]) says Termux has, of [asked]. */
    internal fun parse(stdout: String, asked: Collection<String>): Set<String> =
        stdout.lineSequence().map { it.trim() }.filter { it in asked }.toSet()

    /**
     * Asks Termux which of [commands] it has; [done] gets them on the main thread, or null
     * if Termux could not be asked (not installed, not allowed, no answer in time).
     */
    fun check(context: Context, commands: Collection<String>, done: (Set<String>?) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (commands.isEmpty()) {
            main.post { done(emptySet()) }
            return
        }
        if (!Termux.isInstalled(app) || !Termux.hasPermission(app)) {
            main.post { done(null) }
            return
        }
        var answered = false
        val action = "${app.packageName}.TERMUX_PROBE.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    app.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    // gone already
                }
                if (answered) return
                answered = true
                val bundle = intent.getBundleExtra("result")
                val found = parse(bundle?.getString("stdout").orEmpty(), commands)
                last = found to System.currentTimeMillis()
                done(found)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            app, action.hashCode(), Intent(action).setPackage(app.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            Termux.run(app, TermuxCommand("bash", listOf("-lc", script(commands)), null), pending)
        } catch (e: IOException) {
            try {
                app.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
            }
            answered = true
            main.post { done(null) }
            return
        }
        // Termux may not answer (it was stopped, allow-external-apps is off)
        main.postDelayed({
            if (answered) return@postDelayed
            answered = true
            try {
                app.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
            }
            done(null)
        }, TIMEOUT_MILLIS)
    }
}
