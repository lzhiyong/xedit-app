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

package x.editor.app.session

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The files the editing sessions are kept in, one per document, inside
 * [directory]. A session is addressed by a key, which is the uri of its document.
 *
 * The file name is the SHA-256 of the key: always 64 characters, whatever the
 * length of the uri (a file name may have at most 255 bytes, a uri can have more).
 * The content is what EditorService writes, JSON.
 *
 * Only java.io is used, and nothing is cached: the directory is the state.
 */
class SessionFiles(private val directory: File) {

    /** The file the session for [key] is written to. It may not exist. */
    fun fileFor(key: String): File = File(directory, nameFor(key) + EXTENSION)

    /** The existing file with the session for [key], or null if there is none. */
    fun find(key: String): File? = fileFor(key).takeIf { it.isFile }

    fun exists(key: String): Boolean = find(key) != null

    /**
     * The stored session for [key], or null if there is none.
     *
     * @throws IOException if the file exists but cannot be read
     */
    @Throws(IOException::class)
    fun read(key: String): ByteArray? = find(key)?.readBytes()

    /**
     * Stores [bytes] as the session for [key], replacing the one that was there.
     * The file is written under another name first and then moved in place, so a
     * write that is interrupted leaves the previous session intact, not half a file.
     *
     * @throws IOException if the directory cannot be created or the file not written
     */
    @Throws(IOException::class)
    fun write(key: String, bytes: ByteArray) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create directory ${directory.path}")
        }

        val target = fileFor(key)
        val temp = File(directory, target.name + TEMP_SUFFIX)
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                // some file systems do not rename over an existing file
                target.delete()
                if (!temp.renameTo(target)) {
                    throw IOException("Cannot move ${temp.path} to ${target.path}")
                }
            }
        } finally {
            temp.delete()
        }
    }

    /** Removes the session for [key]. @return whether there was one */
    fun delete(key: String): Boolean = fileFor(key).delete()

    companion object {
        const val EXTENSION = ".json"
        private const val TEMP_SUFFIX = ".tmp"

        /** The file name (without extension) for [key]: its SHA-256 in hex. */
        fun nameFor(key: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
