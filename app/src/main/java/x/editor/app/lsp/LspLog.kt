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

package x.editor.app.lsp

import android.content.Context
import android.util.Log
import x.editor.app.console.ConsoleLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What happens with the language servers, step by step: `lsp.log` in the configuration
 * directory (next to servers.json), so that the user can open it in the editor and see
 * why there is no completion. Also in logcat. Written on a thread of its own; when the
 * file gets large it is moved to `lsp.log.1` and started again.
 */
object LspLog {

    private const val TAG = "Lsp"
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "lsp-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        file = File(LanguageServers.configDirectory(context), "lsp.log")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        write("I", message)
        ConsoleLog.append(ConsoleLog.LSP, ConsoleLog.Level.INFO, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        val text = if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}"
        write("W", text)
        ConsoleLog.append(ConsoleLog.LSP, ConsoleLog.Level.WARNING, text)
    }

    private fun write(level: String, message: String) {
        val target = file ?: return
        val line = "${synchronized(time) { time.format(Date()) }} $level $message\n"
        writer.execute {
            try {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_BYTES) target.renameTo(File(target.parentFile, target.name + ".1"))
                target.appendText(line)
            } catch (e: Exception) {
                // no log is no reason to fail
            }
        }
    }
}
