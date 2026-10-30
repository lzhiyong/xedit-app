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

package x.editor.app.util

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.DecimalFormat
import java.util.zip.CRC32
import java.util.zip.ZipFile

public object FileUtils {

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Extracts [zipFile] into [extractDir], replacing files that already exist.
     *
     * @throws IOException if the archive cannot be read, a file cannot be written,
     *   or an entry would end up outside of [extractDir] (a name such as
     *   `../../x`); nothing is written for such an entry
     */
    @Throws(IOException::class)
    fun unzipFile(zipFile: File, extractDir: File) {
        val root = extractDir.canonicalFile
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                val output = File(root, entry.name).canonicalFile
                if (output != root && !output.path.startsWith(root.path + File.separator)) {
                    throw IOException("Zip entry outside of the target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    ensureDirectory(output)
                    continue
                }

                output.parentFile?.let { ensureDirectory(it) }
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Cannot create directory ${dir.path}")
        }
    }

    // format document size
    fun formatFileSize(df: DecimalFormat, size: Long) = when {
        size < 1024 -> size.toString() + "B"
        size < 1048576 -> df.format(size / 1024f) + "KB"
        size < 1073741824 -> df.format(size / 1048576f) + "MB"
        else -> df.format(size / 1073741824f) + "GB"
    }

    // check file crc32
    fun computeCrc32(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    /** Lower case hex, two digits per byte. */
    fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX_DIGITS[value ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(chars)
    }

    /** The hash of [text] in UTF-8, as lower case hex. */
    fun hashString(text: String, algorithm: String = "SHA-256"): String =
        bytesToHex(MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)))

    fun computeFileHash(path: String) = computeFileHash(File(path))

    /** The SHA-256 of the file as lower case hex, or null if it cannot be read. */
    fun computeFileHash(file: File): String? {
        val input = try {
            file.inputStream()
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
        return computeStreamHash(input)
    }

    /**
     * The hash of everything left in [input] as lower case hex, or null if reading
     * fails or the algorithm does not exist. Closes [input].
     */
    fun computeStreamHash(input: InputStream, algorithm: String = "SHA-256"): String? {
        try {
            // use {} first: the stream is closed also when the algorithm is unknown
            return BufferedInputStream(input).use { bis ->
                val digest = MessageDigest.getInstance(algorithm)
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                bytesToHex(digest.digest())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
