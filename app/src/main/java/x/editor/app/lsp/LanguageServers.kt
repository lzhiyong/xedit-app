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
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The language servers the app knows of. It ships none: they are the user's.
 *
 *  - In `servers.json` of the configuration directory ([configDirectory]):
 *    `xedit/lsp/` on the shared storage when the app may manage it, its external files
 *    directory otherwise. A file with examples, all off, is written there the first time.
 *    Binaries for servers started as processes go in its `bin/` directory, the libraries
 *    they need in `lib/` (see InstalledServers).
 *  - Or registered in code with [register]: the interface for whatever wants to provide
 *    servers of its own. Those come before the ones of the file.
 */
object LanguageServers {

    private const val TAG = "LanguageServers"
    const val CONFIG_FILE = "servers.json"

    private val registered = CopyOnWriteArrayList<LanguageServerSpec>()

    // the file as read last, and when it was changed then
    @Volatile
    private var cached: Pair<Long, List<LanguageServerSpec>>? = null

    /** Adds [spec], in place of one with its id. */
    fun register(spec: LanguageServerSpec) {
        registered.removeAll { it.id == spec.id }
        registered += spec
    }

    /** Removes the server registered with [id]. */
    fun unregister(id: String) {
        registered.removeAll { it.id == id }
    }

    /** Where the user configures servers, and puts the binaries of those the app starts. */
    fun configDirectory(context: Context): File {
        val shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val base = if (shared) File(Environment.getExternalStorageDirectory(), "xedit") else context.getExternalFilesDir(null)
        return File(base ?: context.filesDir, "lsp")
    }

    /** The configuration file of the servers (written with examples if there is none). Not on the main thread. */
    fun configFile(context: Context): File {
        val file = File(configDirectory(context), CONFIG_FILE)
        if (!file.exists()) writeExample(file)
        return file
    }

    /**
     * What the configuration holds, for the log: the file read, and each server with
     * whether it is on and its extensions. Not on the main thread.
     */
    fun describe(context: Context): String {
        val file = configFile(context)
        val servers = registered.map { it to "registered" } + fromFile(context).map { it to "in the file" }
        val list = if (servers.isEmpty()) "no servers" else servers.joinToString("; ") { (spec, where) ->
            "${spec.id} [${if (spec.enabled) "on" else "off"}, $where] .${spec.extensions.joinToString(" .")}"
        }
        return "read ${file.path}: $list"
    }

    /** The servers, those registered first; the file is read again when it changed. Not on the main thread. */
    fun all(context: Context): List<LanguageServerSpec> = registered.toList() + fromFile(context)

    /** The server for a file named [fileName], or null. Not on the main thread. */
    fun specFor(context: Context, fileName: String): LanguageServerSpec? = all(context).firstOrNull { it.handles(fileName) }

    private fun fromFile(context: Context): List<LanguageServerSpec> {
        val file = File(configDirectory(context), CONFIG_FILE)
        if (!file.exists()) writeExample(file)
        val modified = file.lastModified()
        cached?.let { (time, specs) -> if (time == modified) return specs }
        val specs = try {
            val problems = ArrayList<String>()
            LanguageServerSpec.parseAll(Json.parseToJsonElement(file.readText()), problems).also {
                problems.forEach { Log.w(TAG, "$file: $it") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $file", e)
            emptyList()
        }
        cached = modified to specs
        return specs
    }

    // a file to start from: examples of both kinds, off. pyright is started with node and
    // the path of its entry file: socat started by Termux in the background may not have
    // the shebang of `pyright-langserver` (`#!/usr/bin/env node`) rewritten, and fails
    private fun writeExample(file: File) {
        try {
            file.parentFile?.mkdirs()
            File(file.parentFile, "bin").mkdirs()
            File(file.parentFile, "lib").mkdirs()
            file.writeText(EXAMPLE)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write $file", e)
        }
    }

    private val EXAMPLE = """
        {
          "servers": [
            {
              "id": "clangd (Termux)",
              "enabled": false,
              "extensions": ["c", "h", "cc", "cpp", "cxx", "hpp"],
              "connection": {
                "type": "socket",
                "host": "127.0.0.1",
                "port": 5007,
                "termux": {
                  "command": "socat",
                  "arguments": ["TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork", "EXEC:clangd --background-index"]
                }
              }
            },
            {
              "id": "pyright (Termux)",
              "enabled": false,
              "extensions": ["py", "pyi"],
              "initializeTimeout": 120,
              "connection": {
                "type": "socket",
                "host": "127.0.0.1",
                "port": 5008,
                "termux": {
                  "command": "socat",
                  "arguments": [
                    "-d", "-d", "-lf", "/data/data/com.termux/files/home/socat-pyright.log",
                    "TCP-LISTEN:5008,bind=127.0.0.1,reuseaddr,fork",
                    "EXEC:/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/lib/node_modules/pyright/langserver.index.js --stdio"
                  ]
                }
              }
            },
            {
              "id": "lua-language-server (bin/)",
              "enabled": false,
              "extensions": ["lua"],
              "connection": {
                "type": "process",
                "command": ["lua-language-server"]
              }
            }
          ]
        }
    """.trimIndent() + "\n"
}
