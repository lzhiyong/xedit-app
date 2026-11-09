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
import androidx.annotation.WorkerThread
import x.editor.app.lsp.LocalFiles
import java.io.File

/** A tool that cannot do what it was asked: [message] goes back to the model as its result. */
class ToolException(message: String) : Exception(message)

/**
 * The files the agent works on: those under [root] (the project of the file shown), read
 * and written by path or through the folder the user granted (LocalFiles). Paths the model
 * gives are relative to the root (or absolute, inside it); one that leads out of it is
 * refused. Only to be read, the files the user attached to messages ([attachments], by
 * their absolute paths: [resolveForRead]).
 */
class Workspace(private val context: Context, root: File) {

    val root: File = root.absoluteFile.normalize()

    /** Where the files the user attached are kept (AgentAttachments.dir). */
    val attachments: File = AgentAttachments.dir(context).absoluteFile.normalize()

    enum class Kind { MISSING, FILE, DIRECTORY }

    /** [path] as a file of the workspace. */
    fun resolve(path: String): File = resolveIn(root, path)

    /** [path] as a file to read: of the workspace, or (absolute) of what the user attached. */
    fun resolveForRead(path: String): File {
        val given = path.trim()
        if (given.startsWith("/")) {
            val file = File(given).absoluteFile.normalize()
            if (isInside(attachments, file)) return file
        }
        return resolve(path)
    }

    fun isInside(file: File): Boolean = isInside(root, file)

    /** [file] as the model is to name it: relative to the root (a file the user attached: its absolute path). */
    fun relative(file: File): String {
        val normal = file.absoluteFile.normalize()
        if (!isInside(root, normal)) return normal.path
        return normal.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
    }

    /** What is in [directory] (names, and whether each is a directory); null if it cannot be read. */
    @WorkerThread
    fun list(directory: File): List<Pair<String, Boolean>>? = LocalFiles.list(context, directory)?.entries

    @WorkerThread
    fun kindOf(file: File): Kind {
        if (file == root) return Kind.DIRECTORY
        val parent = file.parentFile ?: return Kind.MISSING
        val entry = list(parent)?.firstOrNull { it.first == file.name } ?: return Kind.MISSING
        return if (entry.second) Kind.DIRECTORY else Kind.FILE
    }

    /** The text of [file]; throws ToolException if it is not there, is a directory, is too large or cannot be read. */
    @WorkerThread
    fun read(file: File, maxBytes: Long = MAX_READ_BYTES): String {
        LocalFiles.readText(context, file, maxBytes)?.let { return it }
        throw when (kindOf(file)) {
            Kind.MISSING -> ToolException("${relative(file)} does not exist")
            Kind.DIRECTORY -> ToolException("${relative(file)} is a directory: use list_files")
            Kind.FILE -> ToolException("${relative(file)} cannot be read, or is larger than ${maxBytes / 1024} KB")
        }
    }

    /** Writes [text] to [file] (made if missing, with its directories). */
    @WorkerThread
    fun write(file: File, text: String) {
        try {
            LocalFiles.writeText(context, file, text)
        } catch (e: Exception) {
            throw ToolException("cannot write ${relative(file)}: ${e.message ?: e}")
        }
    }

    companion object {
        const val MAX_READ_BYTES = 2L * 1024 * 1024

        /** [path] (relative to [root], or absolute) as a file under [root]; throws ToolException if it leads out of it. */
        fun resolveIn(root: File, path: String): File {
            val base = root.absoluteFile.normalize()
            val given = path.trim().ifEmpty { "." }
            val file = (if (given.startsWith("/")) File(given) else File(base, given)).absoluteFile.normalize()
            if (!isInside(base, file)) throw ToolException("$given is outside the workspace (${base.path}): only files under it can be used")
            return file
        }

        /** Whether [file] is [root] or under it (`/a/bc` is not under `/a/b`). */
        fun isInside(root: File, file: File): Boolean {
            val base = root.absoluteFile.normalize()
            val normal = file.absoluteFile.normalize()
            return normal == base || base.path == "/" || normal.path.startsWith(base.path + File.separator)
        }
    }
}
