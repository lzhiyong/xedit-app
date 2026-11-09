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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import x.editor.app.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A file the user attached to a message of the AI assistant, as it is kept in the app's own
 * files (AgentAttachments.dir, a folder of its own: [id]) and given to the model:
 * - [Kind.IMAGE]: made no larger than the model takes well (AgentAttachments.MAX_IMAGE_EDGE),
 *   a JPEG or a PNG, sent as an image;
 * - [Kind.TEXT]: its text, in [text];
 * - [Kind.ARCHIVE]: a zip (jar, apk, ...), a tar or a gzip, unpacked into [unpacked]: [text]
 *   says what is in it, and holds the text of what fits;
 * - [Kind.FILE]: anything else, named only ([text]).
 * The model can read the copy, and what was unpacked, with its tools by their paths (read
 * only: Workspace.resolveForRead), and use them with run_command.
 */
data class AgentAttachment(
    val kind: Kind,
    /** Its name as picked. */
    val name: String,
    /** Its size as picked, in bytes (-1: not known). */
    val size: Long,
    /** The type of the copy kept: of an image, of what it was made into. */
    val mediaType: String,
    /** The copy kept, absolute. */
    val path: String,
    /** Of an archive: the folder it was unpacked into, absolute; null otherwise. */
    val unpacked: String? = null,
    /** What the model is told of it (of all but an image), with its name and path. */
    val text: String = "",
    /** Of a text: how many lines the whole file has; -1 otherwise (or not known: kept before). */
    val lines: Int = -1,
) {
    enum class Kind { IMAGE, TEXT, ARCHIVE, FILE }

    /** The folder it is kept in, under AgentAttachments.dir. */
    val id: String get() = File(path).parentFile?.name.orEmpty()
}

/** An attachment that cannot be made: [message] says why, to the user. */
class AttachmentException(message: String) : IOException(message)

/** Attachments made from what the user picked, kept, and read again. */
object AgentAttachments {

    /** Where they are kept: a folder each. */
    fun dir(context: Context): File = File(context.filesDir, "agent/attachments").apply { if (!isDirectory) mkdirs() }

    /**
     * The file of [uri] (picked by the user) copied into a folder of its own and made into
     * an attachment.
     *
     * @throws AttachmentException if it is too large or cannot be read
     */
    @WorkerThread
    fun prepare(context: Context, uri: Uri): AgentAttachment {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val shown = safeName(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file")
        if (size > MAX_BYTES) throw AttachmentException(context.getString(R.string.agent_attachment_too_large, shown, sizeText(MAX_BYTES)))
        val folder = File(dir(context), UUID.randomUUID().toString().replace("-", "").take(20))
        folder.mkdirs()
        try {
            val copy = File(folder, shown)
            val copied = (resolver.openInputStream(uri) ?: throw AttachmentException(context.getString(R.string.agent_attachment_unreadable, shown)))
                .use { input -> copyAtMost(input, copy, MAX_BYTES) }
            if (copied > MAX_BYTES) throw AttachmentException(context.getString(R.string.agent_attachment_too_large, shown, sizeText(MAX_BYTES)))
            val type = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: typeOfName(shown)
            return made(folder, copy, shown, if (size >= 0) size else copied, type)
        } catch (e: AttachmentException) {
            folder.deleteRecursively()
            throw e
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw AttachmentException(context.getString(R.string.agent_attachment_unreadable, shown))
        }
    }

    /** [attachment] gone from the device (one picked and then removed, or of a chat deleted). */
    @WorkerThread
    fun delete(context: Context, attachment: AgentAttachment) {
        val folder = File(attachment.path).parentFile ?: return
        if (folder.parentFile?.absolutePath == dir(context).absolutePath) folder.deleteRecursively()
    }

    /** Every attachment gone (every chat deleted). */
    @WorkerThread
    fun deleteAll(context: Context) {
        dir(context).deleteRecursively()
    }

    /** The image of [attachment] as base64, to send; null if it is no longer there. */
    @WorkerThread
    fun base64Of(attachment: AgentAttachment): String? = runCatching {
        val file = File(attachment.path)
        if (!file.isFile) null else Base64.getEncoder().encodeToString(file.readBytes())
    }.getOrNull()

    /** The image at [path], made [px] on its shorter side (no larger), for a thumbnail; null if it cannot be read. */
    @WorkerThread
    fun thumbnail(path: String, px: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        // then down to [px] on its shorter side (what a sample of two left over: up to twice it)
        val shorter = minOf(decoded.width, decoded.height)
        if (shorter <= px) return decoded
        val scale = px.toFloat() / shorter
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        scaled
    }.getOrNull()

    /** What the model is told of an image before the image itself: its name and where it is kept. */
    fun imageLabel(attachment: AgentAttachment): String =
        "<attachment name=\"${attribute(attachment.name)}\" path=\"${attribute(attachment.path)}\" size=\"${sizeText(attachment.size)}\">An image, the one that follows.</attachment>"

    /** What the model is told of an image that is no longer kept (its folder deleted). */
    fun missingImageText(attachment: AgentAttachment): String =
        "<attachment name=\"${attribute(attachment.name)}\">An image the user attached, no longer on this device.</attachment>"

    /** What the model is told of an attachment that is not an image. */
    fun textOf(attachment: AgentAttachment): String =
        attachment.text.ifEmpty { "<attachment name=\"${attribute(attachment.name)}\" path=\"${attribute(attachment.path)}\"></attachment>" }

    /** [bytes] as people read it: 980 B, 12.3 KB, 4.5 MB. */
    fun sizeText(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    // #region what it is made into

    private fun made(folder: File, copy: File, name: String, size: Long, type: String): AgentAttachment {
        val head = readHead(copy, HEAD_BYTES)
        if (isImage(type, head)) image(folder, copy, name, size)?.let { return it }
        archiveFormatOf(name, head)?.let { format -> return archive(folder, copy, name, size, type, format) }
        if (looksLikeText(head)) return text(copy, name, size, type)
        return file(copy, name, size, type, unpackable = isOtherArchive(name))
    }

    // Made no larger than MAX_IMAGE_EDGE on its long side, turned as its EXIF says; a PNG if
    // it has transparency and stays small enough, a JPEG otherwise. Null if it cannot be decoded.
    private fun image(folder: File, copy: File, name: String, size: Long): AgentAttachment? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(copy.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turned = turnedByExif(decoded, copy)
        val longest = max(turned.width, turned.height)
        val bitmap = if (longest > MAX_IMAGE_EDGE) {
            val scale = MAX_IMAGE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(turned, (turned.width * scale).roundToInt().coerceAtLeast(1), (turned.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            turned
        }
        var out: File? = null
        if (bitmap.hasAlpha()) {
            val png = File(folder, "$IMAGE_NAME.png")
            FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (png.length() <= MAX_PNG_BYTES) out = png else png.delete()
        }
        if (out == null) {
            // (transparency flattened onto white: a JPEG has none)
            val opaque = if (bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { canvas ->
                    Canvas(canvas).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            } else {
                bitmap
            }
            val jpeg = File(folder, "$IMAGE_NAME.jpg")
            FileOutputStream(jpeg).use { opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            out = jpeg
        }
        if (out.absolutePath != copy.absolutePath) copy.delete()
        val type = if (out.name.endsWith(".png")) "image/png" else "image/jpeg"
        return AgentAttachment(AgentAttachment.Kind.IMAGE, name, size, type, out.absolutePath)
    }

    private fun turnedByExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun text(copy: File, name: String, size: Long, type: String): AgentAttachment {
        val (content, cut) = readText(copy, MAX_TEXT_BYTES)
        val shown = if (content.length > MAX_TEXT_CHARS) content.take(MAX_TEXT_CHARS) else content
        val out = StringBuilder()
        out.append(openTag(name, copy, size)).append('\n')
        out.append(shown)
        if (!shown.endsWith('\n')) out.append('\n')
        if (cut || shown.length < content.length) {
            out.append("(Cut short here: the whole file can be read with read_file at the path above.)\n")
        }
        out.append("</attachment>")
        return AgentAttachment(
            AgentAttachment.Kind.TEXT, name, size, type, copy.absolutePath,
            text = out.toString(), lines = countLines(copy),
        )
    }

    private fun file(copy: File, name: String, size: Long, type: String, unpackable: Boolean): AgentAttachment {
        val what = if (unpackable) {
            "An archive in a format that is not unpacked here: what it holds is not shown. run_command can unpack it at the path above, if a tool for it is installed."
        } else {
            "A binary file ($type): what it holds is not shown. run_command can use it at the path above (copy it into the workspace, look into it with a tool)."
        }
        val text = openTag(name, copy, size) + "\n" + what + "\n</attachment>"
        return AgentAttachment(AgentAttachment.Kind.FILE, name, size, type, copy.absolutePath, text = text)
    }

    // the tag an attachment's text opens with: its name, where it is kept, how large it is
    private fun openTag(name: String, copy: File, size: Long, unpacked: File? = null): String {
        val out = StringBuilder("<attachment name=\"").append(attribute(name))
            .append("\" path=\"").append(attribute(copy.absolutePath))
            .append("\" size=\"").append(sizeText(size)).append('"')
        if (unpacked != null) out.append(" unpacked_to=\"").append(attribute(unpacked.absolutePath)).append('"')
        return out.append('>').toString()
    }

    private fun attribute(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    // #endregion

    // #region archives

    private enum class ArchiveFormat { ZIP, TAR, GZIP }

    private class Entry(val path: String, val size: Long, val directory: Boolean)

    // What is unpacked: every entry within MAX_ENTRIES and MAX_UNPACKED_BYTES, none outside [into]
    private class Unpacking(val into: File) {
        val entries = ArrayList<Entry>()
        var bytes = 0L
        var stopped = false
        private val base = into.canonicalPath + File.separator

        // [path] as it is shown: no leading / or ./
        fun clean(path: String): String {
            var out = path.replace('\\', '/').trimStart('/')
            while (out.startsWith("./")) out = out.substring(2).trimStart('/')
            return out
        }

        // where [path] goes, if it stays within [into]
        fun target(path: String): File? {
            val clean = clean(path)
            if (clean.isEmpty() || clean == ".") return null
            val file = File(into, clean).canonicalFile
            return file.takeIf { it.path.startsWith(base) }
        }

        fun full(): Boolean {
            if (entries.size >= MAX_ENTRIES || bytes >= MAX_UNPACKED_BYTES) stopped = true
            return stopped
        }

        // [input] written to [path], within what is left; false if it did not all fit
        fun write(path: String, input: InputStream, declared: Long): Boolean {
            // (a path that leads out: read through, not kept)
            val file = target(path) ?: return true.also { AgentAttachments.skip(input, Long.MAX_VALUE) }
            file.parentFile?.mkdirs()
            val written = FileOutputStream(file).use { copyLimited(input, it, MAX_UNPACKED_BYTES - bytes) }
            bytes += written
            entries += Entry(clean(path), if (declared >= 0) declared else written, directory = false)
            if (bytes >= MAX_UNPACKED_BYTES) {
                stopped = true
                return false
            }
            return true
        }

        fun directory(path: String) {
            val file = target(path) ?: return
            file.mkdirs()
            entries += Entry(clean(path).trimEnd('/') + "/", 0, directory = true)
        }
    }

    private fun archive(folder: File, copy: File, name: String, size: Long, type: String, format: ArchiveFormat): AgentAttachment {
        val into = File(folder, UNPACKED)
        into.mkdirs()
        val unpacking = Unpacking(into)
        val ok = runCatching {
            when (format) {
                ArchiveFormat.ZIP -> unzip(copy, unpacking)
                ArchiveFormat.TAR -> FileInputStream(copy).use { untar(it, unpacking) }
                ArchiveFormat.GZIP -> BufferedInputStream(GZIPInputStream(FileInputStream(copy))).use { input ->
                    input.mark(TAR_HEADER + 1)
                    val header = ByteArray(TAR_HEADER)
                    val read = readFully(input, header)
                    input.reset()
                    if (read == TAR_HEADER && isTar(header)) {
                        untar(input, unpacking)
                    } else {
                        unpacking.write(gunzippedName(name), input, -1)
                    }
                }
            }
        }.isSuccess
        if (!ok && unpacking.entries.isEmpty()) {
            into.deleteRecursively()
            return file(copy, name, size, type, unpackable = false)
        }
        val out = StringBuilder()
        out.append(openTag(name, copy, size, into)).append('\n')
        val files = unpacking.entries.filter { !it.directory }
        out.append("An archive, unpacked to the folder above: ").append(files.size).append(" files")
        if (unpacking.stopped || !ok) out.append(" (not all of it: it is too large, or could not all be read)")
        out.append(".\n")
        unpacking.entries.take(MAX_LISTED).forEach { entry ->
            out.append(entry.path)
            if (!entry.directory) out.append("  (").append(sizeText(entry.size)).append(')')
            out.append('\n')
        }
        if (unpacking.entries.size > MAX_LISTED) out.append("(").append(unpacking.entries.size - MAX_LISTED).append(" more not listed: list_files the folder above)\n")
        // the text of the smallest files first, shallow ones before deep ones, as much as fits
        var budget = MAX_ARCHIVE_TEXT_CHARS
        var left = 0
        val candidates = files.filter { it.size in 1..MAX_ARCHIVE_FILE_BYTES }
            .sortedWith(compareBy<Entry>({ it.path.count { c -> c == '/' } }, { it.path }))
        for (entry in candidates) {
            val file = unpacking.target(entry.path) ?: continue
            if (!looksLikeText(readHead(file, HEAD_BYTES))) continue
            val (content, _) = readText(file, MAX_ARCHIVE_FILE_BYTES)
            if (content.length > budget) {
                left++
                continue
            }
            budget -= content.length
            out.append("\n<file path=\"").append(attribute(entry.path)).append("\">\n").append(content)
            if (!content.endsWith('\n')) out.append('\n')
            out.append("</file>\n")
        }
        if (left > 0 || files.size > candidates.size) {
            out.append("\n(The text of the other files is not shown here: read_file them in the folder above.)\n")
        }
        out.append("</attachment>")
        return AgentAttachment(AgentAttachment.Kind.ARCHIVE, name, size, type, copy.absolutePath, into.absolutePath, out.toString())
    }

    // a zip, its names as UTF-8 or (written on Windows in China) GB18030
    private fun unzip(copy: File, unpacking: Unpacking) {
        // (a name that is not UTF-8: IllegalArgumentException, or ZipException, as it is read)
        val zip = try {
            ZipFile(copy, Charsets.UTF_8).also { zip ->
                try {
                    zip.entries().toList().forEach { it.name }
                } catch (e: Exception) {
                    zip.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            ZipFile(copy, Charset.forName("GB18030"))
        }
        zip.use {
            for (entry in it.entries()) {
                if (unpacking.full()) break
                if (entry.isDirectory) {
                    unpacking.directory(entry.name)
                } else {
                    val fits = it.getInputStream(entry).use { input -> unpacking.write(entry.name, input, entry.size) }
                    if (!fits) break
                }
            }
        }
    }

    // A tar: 512-byte headers, each followed by its file in 512-byte blocks. Long names as
    // GNU (`L`) or POSIX (`x`, its path) write them; links and the like are left out.
    private fun untar(input: InputStream, unpacking: Unpacking) {
        val header = ByteArray(TAR_HEADER)
        var longName: String? = null
        while (!unpacking.full()) {
            if (readFully(input, header) < TAR_HEADER) return
            if (header.all { it.toInt() == 0 }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = string(header, 345, 155)
            val own = string(header, 0, 100)
            val path = longName ?: if (prefix.isNotEmpty() && isUstar(header)) "$prefix/$own" else own
            longName = null
            val padded = (size + TAR_HEADER - 1) / TAR_HEADER * TAR_HEADER
            when (type) {
                'L' -> {
                    longName = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')
                    skip(input, padded - size)
                }
                'x' -> {
                    val pax = String(readBytes(input, size), Charsets.UTF_8)
                    longName = pax.lineSequence().mapNotNull { line ->
                        line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=")
                    }.firstOrNull()
                    skip(input, padded - size)
                }
                '5' -> {
                    unpacking.directory(path)
                    skip(input, padded)
                }
                '0', '\u0000', '7' -> {
                    val fits = unpacking.write(path, LimitedInputStream(input, size), size)
                    if (!fits) return
                    skip(input, padded - size)
                }
                else -> skip(input, padded)
            }
        }
    }

    private class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        // what was not read of it (the rest of a file too large to keep) read through
        override fun close() {
            AgentAttachments.skip(input, left)
            left = 0
        }
    }

    private fun isTar(header: ByteArray) = isUstar(header) || (header[156].toInt().toChar() in "0\u000057Lx" && octalOk(header))

    private fun isUstar(header: ByteArray) = header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar"

    // the size field of an old tar's header is octal digits (spaces, a NUL at the end)
    private fun octalOk(header: ByteArray): Boolean {
        val field = String(header, 124, 12, Charsets.US_ASCII).trim(' ', '\u0000')
        return field.isNotEmpty() && field.all { it in '0'..'7' }
    }

    private fun octal(header: ByteArray, at: Int, length: Int): Long {
        val field = String(header, at, length, Charsets.US_ASCII).trim(' ', '\u0000')
        return field.toLongOrNull(8) ?: 0L
    }

    private fun string(header: ByteArray, at: Int, length: Int): String {
        var end = at
        while (end < at + length && header[end].toInt() != 0) end++
        return String(header, at, end - at, Charsets.UTF_8)
    }

    private fun gunzippedName(name: String): String = when {
        name.endsWith(".gz", ignoreCase = true) -> name.dropLast(3)
        else -> "$name.out"
    }.ifEmpty { "file" }

    private fun archiveFormatOf(name: String, head: ByteArray): ArchiveFormat? = when {
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2].toInt() == 3 && head[3].toInt() == 4) || (head[2].toInt() == 5 && head[3].toInt() == 6)) -> ArchiveFormat.ZIP
        head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> ArchiveFormat.GZIP
        head.size >= TAR_HEADER && (isUstar(head) || (name.endsWith(".tar", ignoreCase = true) && isTar(head))) -> ArchiveFormat.TAR
        else -> null
    }

    private fun isOtherArchive(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return OTHER_ARCHIVES.any { lower.endsWith(it) }
    }

    // #endregion

    // #region bytes and text

    private fun isImage(type: String, head: ByteArray): Boolean {
        if (type == "image/svg+xml") return false
        if (type.startsWith("image/")) return true
        if (head.size < 12) return false
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        val png = head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte()
        val gif = String(head, 0, 3, Charsets.US_ASCII) == "GIF"
        val webp = String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        val bmp = head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte()
        return jpeg || png || gif || webp || bmp
    }

    // No NUL byte, and next to no other control characters (tabs, line ends, form feeds and
    // escapes aside), in its first bytes
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        var control = 0
        for (b in head) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) control++
        }
        return control * 100 <= head.size
    }

    // How many lines [file] has, all of it (not only what the model is given): its line feeds,
    // and one more for a last line that does not end in one (as an editor counts them). The
    // bytes are counted, not decoded: a line feed is that one byte in UTF-8 and GB18030.
    private fun countLines(file: File): Int {
        var feeds = 0L
        var last = -1
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (i in 0 until read) if (buffer[i] == '\n'.code.toByte()) feeds++
                if (read > 0) last = buffer[read - 1].toInt()
            }
        }
        if (last < 0) return 0
        val lines = if (last == '\n'.code) feeds else feeds + 1
        return lines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // The text of [file], its first [maxBytes] at most: as UTF-8 (a BOM dropped), or, if it
    // is not that, GB18030. And whether it was cut short.
    private fun readText(file: File, maxBytes: Long): Pair<String, Boolean> {
        val bytes = FileInputStream(file).use { readBytes(it, maxBytes + 1) }
        val cut = bytes.size > maxBytes
        val kept = if (cut) bytes.copyOf(maxBytes.toInt()) else bytes
        val text = utf8(kept, cut) ?: String(kept, Charset.forName("GB18030"))
        return text.removePrefix("\uFEFF") to cut
    }

    // [bytes] as UTF-8, null if they are not that; cut short, a character split at the end
    // is let go
    private fun utf8(bytes: ByteArray, cut: Boolean): String? {
        for (drop in 0..(if (cut) 3 else 0)) {
            if (drop > bytes.size) break
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.size - drop))
                    .toString()
            } catch (e: CharacterCodingException) {
                // one byte fewer, in case a character was split
            }
        }
        return null
    }

    private fun readHead(file: File, count: Int): ByteArray = runCatching {
        FileInputStream(file).use { readBytes(it, count.toLong()) }
    }.getOrDefault(ByteArray(0))

    private fun readBytes(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var left = max
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) break
            at += n
        }
        return at
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(BUFFER)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    // [input] to [file], [max] + 1 bytes at most (one more: to know it was larger); how many
    private fun copyAtMost(input: InputStream, file: File, max: Long): Long =
        FileOutputStream(file).use { copyLimited(input, it, max + 1) }

    private fun copyLimited(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (total < max) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), max - total).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    private fun typeOfName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    // a name to keep it under: no folders in it, nothing a file system refuses
    private fun safeName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f\"*:<>?|]"), "_").trim().trimStart('.')
        return clean.take(MAX_NAME).ifEmpty { "file" }
    }

    // #endregion

    /** The most a file picked may be. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /** The most files one message takes. */
    const val MAX_PER_MESSAGE = 20

    /** The longest side of an image as sent (what the models take without making it smaller). */
    const val MAX_IMAGE_EDGE = 1568

    private const val MAX_PNG_BYTES = 3_500_000L
    private const val JPEG_QUALITY = 85
    private const val IMAGE_NAME = "image"
    private const val UNPACKED = ".unpacked"

    private const val MAX_TEXT_BYTES = 400L * 1024
    private const val MAX_TEXT_CHARS = 100_000
    private const val MAX_ENTRIES = 5_000
    private const val MAX_UNPACKED_BYTES = 200L * 1024 * 1024
    private const val MAX_LISTED = 400
    private const val MAX_ARCHIVE_TEXT_CHARS = 60_000
    private const val MAX_ARCHIVE_FILE_BYTES = 32L * 1024

    private const val HEAD_BYTES = 8 * 1024
    private const val TAR_HEADER = 512
    private const val BUFFER = 64 * 1024
    private const val MAX_NAME = 120

    private val OTHER_ARCHIVES = listOf(".7z", ".rar", ".xz", ".bz2", ".zst", ".lz", ".lzma", ".tbz2", ".txz")
}
