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

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * What a package to install comes as, and how it is unpacked into a directory: a zip, a
 * tar (also gzipped), a Debian package (`.deb`: its `data.tar`, gzipped or not), or a
 * single file (a binary, a script). The kind is told by the first bytes, not the name.
 *
 * Unpacking keeps what a toolchain needs: the executable bits of a tar (and of what is
 * a binary or a script in a zip), its symbolic links, and nothing outside the target
 * directory (`../x` and absolute names are refused). [strip] leading path parts are taken
 * off every name, as `tar --strip-components` does (`clangd_18/bin/clangd` with 1:
 * `bin/clangd`). A Termux package's `data/data/com.termux/files/usr/` is taken off too.
 *
 * Written to a [Sink], so that it can be tested without a device.
 */
object Archives {

    enum class Format {
        ZIP, TAR, TAR_GZ, DEB, ELF, SCRIPT,
        /** Known, but not unpacked here: XZ, Zstandard, bzip2 (and a `.deb` of them). */
        UNSUPPORTED,
        UNKNOWN
    }

    /** Where what is unpacked goes. Paths are relative, with `/`, already checked. */
    interface Sink {
        fun directory(path: String)
        fun file(path: String, input: InputStream, executable: Boolean)
        fun symlink(path: String, target: String)
        /** [path] is the same file as [target], written before (a hard link of a tar). */
        fun hardlink(path: String, target: String)
    }

    /** The kind of what starts with [head] (its first bytes, 512 at least for a tar). */
    fun detect(head: ByteArray): Format {
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        return when {
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> Format.ZIP
            at(0, 0x1F, 0x8B) -> Format.TAR_GZ
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> Format.UNSUPPORTED
            at(0, 0x28, 0xB5, 0x2F, 0xFD) -> Format.UNSUPPORTED
            at(0, 0x42, 0x5A, 0x68) -> Format.UNSUPPORTED
            at(0, 0x21, 0x3C, 0x61, 0x72, 0x63, 0x68, 0x3E, 0x0A) -> Format.DEB
            at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> Format.TAR
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> Format.ELF
            at(0, 0x23, 0x21) -> Format.SCRIPT
            else -> Format.UNKNOWN
        }
    }

    /** The kind of [file]. */
    fun detect(file: File): Format = file.inputStream().use { input ->
        val head = ByteArray(512)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        detect(head.copyOf(read))
    }

    /**
     * Unpacks [archive] (of [format], see [detect]) into [sink], [strip] path parts off
     * each name. A single file (a binary, a script) goes to `bin/<[singleName]>`. Returns
     * the paths written (files and links). Throws IOException for what cannot be read, a
     * name outside the target, or a format that is not unpacked here.
     */
    @Throws(IOException::class)
    fun extract(archive: File, format: Format, sink: Sink, strip: Int = 0, singleName: String = archive.name): List<String> {
        val written = ArrayList<String>()
        when (format) {
            Format.ZIP -> archive.inputStream().buffered().use { unzip(it, sink, strip, written) }
            Format.TAR -> archive.inputStream().buffered().use { untar(it, sink, strip, written) }
            Format.TAR_GZ -> GZIPInputStream(archive.inputStream().buffered()).use { untar(BufferedInputStream(it), sink, strip, written) }
            Format.DEB -> archive.inputStream().buffered().use { undeb(it, sink, strip, written) }
            Format.ELF, Format.SCRIPT -> {
                val path = "bin/" + safeName(singleName)
                sink.directory("bin")
                archive.inputStream().use { sink.file(path, it, executable = true) }
                written += path
            }
            Format.UNSUPPORTED -> throw IOException("XZ, Zstandard and bzip2 archives are not unpacked here: use a .tar.gz or a .zip")
            Format.UNKNOWN -> throw IOException("not an archive this can unpack (zip, tar, tar.gz, deb), nor a binary or a script")
        }
        return written
    }

    // #region layout

    /** How an archive goes into usr: [strip] parts off its names, [into] a directory under usr (`""`: usr itself). */
    data class Layout(val strip: Int, val into: String)

    // what is at the top of a prefix
    private val PREFIX_DIRS = setOf("bin", "sbin", "lib", "lib64", "libexec", "include", "etc", "share", "opt", "var", "man")

    /**
     * The layout of an archive of [names] (its files, as unpacked with nothing stripped) for
     * package [id], with what is given of it ([strip], [into]; null: worked out):
     *
     *  - strip: the top directory every name is in is taken off, while there is one (and it
     *    is not `bin`, `lib`, …): `clangd_18.1/bin/clangd` gives `bin/clangd`;
     *  - into: usr itself for what is laid out as a prefix (only `bin/`, `lib/`, `share/`, …
     *    at the top, `bin/` or `lib/` among them: a Termux package, a single program), else
     *    `opt/<id>`, a directory of its own (its `bin/` linked into usr/bin).
     */
    fun layout(names: List<String>, id: String, strip: Int? = null, into: String? = null): Layout {
        val parts = names.map { it.split('/').filter(String::isNotEmpty) }.filter { it.isNotEmpty() }
        var s = strip ?: 0
        if (strip == null) {
            while (s < 4) {
                val tops = parts.map { it.getOrNull(s) }
                val top = tops.firstOrNull() ?: break
                // every name in the same directory, below which there is more
                if (tops.any { it != top } || parts.any { it.size <= s + 1 } || top in PREFIX_DIRS) break
                s++
            }
        }
        val tops = parts.mapNotNull { it.getOrNull(s) }.toSet()
        val prefixLike = tops.isNotEmpty() && tops.all { it in PREFIX_DIRS } && ("bin" in tops || "lib" in tops)
        return Layout(s, into ?: if (prefixLike) "" else "opt/$id")
    }

    /** The names of [archive] (its files and links, nothing stripped): for [layout]. */
    @Throws(IOException::class)
    fun names(archive: File, format: Format, singleName: String = archive.name): List<String> =
        extract(archive, format, object : Sink {
            override fun directory(path: String) = Unit
            override fun file(path: String, input: InputStream, executable: Boolean) = Unit
            override fun symlink(path: String, target: String) = Unit
            override fun hardlink(path: String, target: String) = Unit
        }, 0, singleName)

    // #endregion

    // #region paths

    // Termux's prefix in its packages: what is under it is under our usr
    private val TERMUX_PREFIX = Regex("""^(\./)?data/data/com\.termux/files/usr/?""")

    /**
     * [name] (of an entry) with [strip] leading parts taken off, checked: null if nothing
     * is left of it (a directory stripped away). Throws for a name that would go outside.
     */
    @Throws(IOException::class)
    fun relativePath(name: String, strip: Int): String? {
        var path = name.replace('\\', '/').removePrefix("./")
        path = TERMUX_PREFIX.replace(path, "")
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." } || name.startsWith("/")) throw IOException("an entry outside of the target: $name")
        val kept = parts.drop(strip)
        return if (kept.isEmpty()) null else kept.joinToString("/")
    }

    // a link that points out of the target is not written
    private fun linkStaysInside(path: String, target: String): Boolean {
        if (target.startsWith("/")) return false
        val parts = path.split('/').dropLast(1).toMutableList()
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return false else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return true
    }

    private fun safeName(name: String): String =
        name.substringAfterLast('/').replace(Regex("""[^\w.+\-]"""), "_").ifEmpty { "tool" }

    // what of a zip is to run: in a bin or libexec directory, a library, or a binary or a
    // script by its first bytes (a zip keeps no modes that Java sees)
    private fun looksExecutable(path: String, head: ByteArray): Boolean =
        path.split('/').dropLast(1).any { it == "bin" || it == "libexec" || it == "sbin" } ||
            path.endsWith(".so") || path.contains(".so.") ||
            detect(head).let { it == Format.ELF || it == Format.SCRIPT }

    // #endregion

    // #region zip

    private fun unzip(input: InputStream, sink: Sink, strip: Int, written: MutableList<String>) {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            val path = relativePath(entry.name, strip) ?: continue
            if (entry.isDirectory) {
                sink.directory(path)
                continue
            }
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(sink::directory)
            val stream = BufferedInputStream(Uncloseable(zip), 4096)
            stream.mark(64)
            val head = ByteArray(64)
            val n = readFully(stream, head)
            stream.reset()
            sink.file(path, stream, looksExecutable(path, head.copyOf(n)))
            written += path
        }
    }

    // #endregion

    // #region tar

    private const val BLOCK = 512

    private fun untar(input: InputStream, sink: Sink, strip: Int, written: MutableList<String>) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (readFully(input, header) < BLOCK) break
            // two empty blocks end it
            if (header.all { it.toInt() == 0 }) break
            val type = header[156].toInt().toChar()
            val size = octal(header, 124, 12)
            val name = paxPath ?: longName ?: tarName(header)
            val link = paxLink ?: longLink ?: string(header, 157, 100)
            when (type) {
                // GNU long names and links: the next entry's
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                // pax: the next entry's path and link, others let be
                'x' -> {
                    val pax = parsePax(readString(input, size))
                    paxPath = pax["path"]
                    paxLink = pax["linkpath"]
                    continue
                }
                'g' -> { skip(input, padded(size)); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null
            val path = relativePath(name, strip)
            when (type) {
                '5' -> path?.let(sink::directory)
                '2' -> if (path != null && linkStaysInside(path, link)) {
                    path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(sink::directory)
                    sink.symlink(path, link)
                    written += path
                }
                // a hard link: a copy of what it links to, if that was written
                '1' -> if (path != null) {
                    val target = relativePath(link, strip)
                    if (target != null && target in written) {
                        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(sink::directory)
                        sink.hardlink(path, target)
                        written += path
                    }
                }
                '0', '\u0000', '7' -> {
                    if (path != null) {
                        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(sink::directory)
                        val mode = octal(header, 100, 8)
                        val body = Bounded(input, size)
                        sink.file(path, body, executable = mode and 0b001_001_001L != 0L)
                        body.drain()
                        written += path
                        skip(input, padded(size) - size)
                        continue
                    }
                }
            }
            skip(input, padded(size))
        }
    }

    private fun tarName(header: ByteArray): String {
        val name = string(header, 0, 100)
        val magic = string(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) string(header, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun string(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end].toInt() != 0) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    // an octal field (or the base-256 of GNU tar for large sizes)
    private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
        if (bytes[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in offset + 1 until offset + length) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }
        val text = string(bytes, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun readString(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        readFully(input, bytes)
        skip(input, padded(size) - size)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    // `<length> key=value\n` records
    internal fun parsePax(text: String): Map<String, String> {
        val values = HashMap<String, String>()
        for (line in text.split('\n')) {
            val record = line.substringAfter(' ', "")
            val equals = record.indexOf('=')
            if (equals > 0) values[record.substring(0, equals)] = record.substring(equals + 1)
        }
        return values
    }

    // #endregion

    // #region deb

    // a Debian package: an `ar` archive whose data.tar(.gz) holds the files
    private fun undeb(input: InputStream, sink: Sink, strip: Int, written: MutableList<String>) {
        val magic = ByteArray(8)
        readFully(input, magic)
        val header = ByteArray(60)
        while (readFully(input, header) == 60) {
            val name = String(header, 0, 16, Charsets.US_ASCII).trim().removeSuffix("/")
            val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
            if (name.startsWith("data.tar")) {
                val body = Bounded(input, size)
                when {
                    name == "data.tar" -> untar(body, sink, strip, written)
                    name.endsWith(".gz") -> untar(BufferedInputStream(GZIPInputStream(body)), sink, strip, written)
                    else -> throw IOException("$name in the .deb is not unpacked here (XZ, Zstandard): use a .tar.gz")
                }
                return
            }
            skip(input, size + (size and 1))
        }
        throw IOException("no data.tar in the .deb")
    }

    // #endregion

    // #region streams

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("the archive ends early")
            left -= n
        }
    }

    // at most [size] bytes of [input], and not closing it
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun skip(n: Long): Long {
            val skipped = super.skip(minOf(n, left))
            left -= skipped
            return skipped
        }
        override fun available(): Int = minOf(super.available().toLong(), left).toInt()
        override fun close() = Unit
        override fun markSupported() = false
        fun drain() {
            val buffer = ByteArray(8192)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    // a stream the sink may close without closing the archive under it
    private class Uncloseable(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    // #endregion
}
