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

package x.code.app.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.DecimalFormat
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

public object FileUtils {

    fun unzipFile(zipFile: File, extractDir: File) {
        val zfile = ZipFile(zipFile)
        val zinput = ZipInputStream(FileInputStream(zipFile))
        var entry: ZipEntry? = null

        while (zinput.nextEntry.also { entry = it } != null) {
            val output = File(extractDir, entry!!.name)
            if (output.parentFile != null && !output.parentFile!!.exists()) {
                output.parentFile!!.mkdirs()
            }

            if (!output.exists()) {
                if (entry!!.isDirectory) {
                    output.mkdirs()
                    continue
                } else {
                    output.createNewFile()
                }
            }

            val bis = BufferedInputStream(zfile.getInputStream(entry))
            val bos = BufferedOutputStream(FileOutputStream(output))
            val buffer = ByteArray(1024)
            var len = bis.read(buffer)
            while (len >= 0) {
                bos.write(buffer, 0, len)
                // continue to read
                len = bis.read(buffer)
            }
            bos.close()
            bis.close()
        }
        // close the zip stream
        zfile.close()
    }

    // format document size
    fun formatSize(df: DecimalFormat, size: Long) = when {
        size < 1024 -> size.toString() + "B"
        size < 1048576 -> df.format(size / 1024f) + "KB"
        size < 1073741824 -> df.format(size / 1048576f) + "MB"
        else -> df.format(size / 1073741824f) + "GB"
    }

    // check file crc32
    fun checkCRC32(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    
    fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    
    fun calculateFileHash(path: String) = calculateFileHash(File(path))
    
    fun calculateFileHash(file: File) = calculateFileHash(file.inputStream())
    
    fun calculateFileHash(input: InputStream, algorithm: String = "SHA-256"): String? {
        try {
            val digest = MessageDigest.getInstance(algorithm)
            BufferedInputStream(input).use { bis ->
                val buffer = ByteArray(1024) // Read in 1KB chunks
                var bytesRead: Int
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hashBytes = digest.digest()
            return bytesToHex(hashBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

