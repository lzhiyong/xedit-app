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
 * length of the uri. Older versions used the hex of the whole uri instead, which
 * is twice as long as the uri and passes the 255 bytes a file name may have for a
 * uri of about 125 characters; the session of such a document could never be
 * written. Files with the old names are still found, and are replaced by one with
 * the new name the next time the session is written.
 *
 * Only java.io is used, and nothing is cached: the directory is the state.
 */
class SessionFiles(private val directory: File) {

    /** The file the session for [key] is written to. It may not exist. */
    fun fileFor(key: String): File = File(directory, nameFor(key) + EXTENSION)

    /** The file an older version wrote the session for [key] to. It may not exist. */
    fun legacyFileFor(key: String): File = File(directory, legacyNameFor(key) + EXTENSION)

    /** The existing file with the session for [key], or null if there is none. */
    fun find(key: String): File? {
        val file = fileFor(key)
        if (file.isFile) return file
        val legacy = legacyFileFor(key)
        // exists() fails quietly (false) for a name that is too long
        return if (legacy.isFile) legacy else null
    }

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

        legacyFileFor(key).delete()
    }

    /** Removes the session for [key]. @return whether there was one */
    fun delete(key: String): Boolean {
        val deletedCurrent = fileFor(key).delete()
        val deletedLegacy = legacyFileFor(key).delete()
        return deletedCurrent || deletedLegacy
    }

    companion object {
        const val EXTENSION = ".cbor"
        private const val TEMP_SUFFIX = ".tmp"

        /** The file name (without extension) for [key]: its SHA-256 in hex. */
        fun nameFor(key: String): String =
            hex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

        /** The file name (without extension) older versions used: the key itself in hex. */
        fun legacyNameFor(key: String): String = hex(key.toByteArray(Charsets.UTF_8))

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}
