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

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packages unpacked as a toolchain needs them (see Archives). */
class ArchivesTest {

    // what a sink was told: files (their text and whether executable), links, directories
    private class Recorder : Archives.Sink {
        val files = LinkedHashMap<String, Pair<String, Boolean>>()
        val links = LinkedHashMap<String, String>()
        val dirs = LinkedHashSet<String>()
        override fun directory(path: String) { dirs += path }
        override fun file(path: String, input: InputStream, executable: Boolean) { files[path] = input.readBytes().toString(Charsets.UTF_8) to executable }
        override fun symlink(path: String, target: String) { links[path] = target }
        override fun hardlink(path: String, target: String) { files[path] = files.getValue(target) }
    }

    // a tar of [entries]: (name, type, mode, content or link target)
    private fun tar(vararg entries: List<Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(block: ByteArray, offset: Int, length: Int, text: String) {
            val bytes = text.toByteArray()
            System.arraycopy(bytes, 0, block, offset, minOf(bytes.size, length))
        }
        fun header(name: String, type: Char, mode: Int, size: Int, link: String = "") {
            val block = ByteArray(512)
            field(block, 0, 100, name)
            field(block, 100, 8, "%07o".format(mode))
            field(block, 124, 12, "%011o".format(size))
            block[156] = type.code.toByte()
            field(block, 157, 100, link)
            field(block, 257, 6, "ustar")
            out.write(block)
        }
        fun body(bytes: ByteArray) {
            out.write(bytes)
            val pad = (512 - bytes.size % 512) % 512
            out.write(ByteArray(pad))
        }
        for (e in entries) {
            val name = e[0] as String
            val type = e[1] as Char
            val mode = e[2] as Int
            val data = e[3] as String
            when (type) {
                '2', '1' -> header(name, type, mode, 0, data)
                '5' -> header(name, type, mode, 0)
                'L' -> { header("././@LongLink", 'L', 0, data.length + 1); body((data + "\u0000").toByteArray()) }
                else -> { header(name, type, mode, data.length); body(data.toByteArray()) }
            }
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun temp(bytes: ByteArray): File = Files.createTempFile("archive", ".bin").toFile().apply { writeBytes(bytes); deleteOnExit() }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    @Test
    fun aTarGzWithModesLinksAndAStrippedTop() {
        val bytes = gzip(tar(
            listOf("clangd_18/", '5', 0b111_101_101, ""),
            listOf("clangd_18/bin/", '5', 0b111_101_101, ""),
            listOf("clangd_18/bin/clangd", '0', 0b111_101_101, "ELF..."),
            listOf("clangd_18/lib/libc++.so.1", '0', 0b110_100_100, "lib"),
            listOf("clangd_18/lib/libc++.so", '2', 0b111_111_111, "libc++.so.1"),
            listOf("clangd_18/lib/hard", '1', 0b110_100_100, "clangd_18/lib/libc++.so.1"),
            listOf("clangd_18/lib/escape", '2', 0b111_111_111, "../../../etc/passwd"),
            listOf("clangd_18/README", '0', 0b110_100_100, "read me"),
        ))
        val file = temp(bytes)
        assertEquals(Archives.Format.TAR_GZ, Archives.detect(file))
        val sink = Recorder()
        val written = Archives.extract(file, Archives.Format.TAR_GZ, sink, strip = 1)
        assertEquals("ELF..." to true, sink.files["bin/clangd"])
        assertEquals("lib" to false, sink.files["lib/libc++.so.1"])
        assertEquals("libc++.so.1", sink.links["lib/libc++.so"])
        assertEquals("lib" to false, sink.files["lib/hard"])
        // a link out of the target is not written
        assertFalse("lib/escape" in sink.links)
        assertTrue("README" in written)
        assertTrue("bin" in sink.dirs)
    }

    @Test
    fun longNamesAndTermuxsPrefix() {
        val long = "data/data/com.termux/files/usr/share/" + "x".repeat(120) + "/file.txt"
        val bytes = tar(
            listOf("", 'L', 0, long),
            listOf("ignored", '0', 0b110_100_100, "long"),
            listOf("./data/data/com.termux/files/usr/bin/tool", '0', 0b111_101_101, "tool"),
        )
        val file = temp(bytes)
        assertEquals(Archives.Format.TAR, Archives.detect(file))
        val sink = Recorder()
        Archives.extract(file, Archives.Format.TAR, sink)
        assertEquals("long" to false, sink.files["share/" + "x".repeat(120) + "/file.txt"])
        assertEquals("tool" to true, sink.files["bin/tool"])
    }

    @Test
    fun aZipMarksItsProgramsExecutable() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in listOf("lua-ls/bin/lua-language-server" to "x", "lua-ls/main.lua" to "print()", "lua-ls/run.sh" to "#!/bin/sh\necho")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        val file = temp(out.toByteArray())
        assertEquals(Archives.Format.ZIP, Archives.detect(file))
        val sink = Recorder()
        Archives.extract(file, Archives.Format.ZIP, sink, strip = 1)
        assertEquals("x" to true, sink.files["bin/lua-language-server"])
        assertEquals("print()" to false, sink.files["main.lua"])
        assertEquals("#!/bin/sh\necho" to true, sink.files["run.sh"])
    }

    @Test
    fun aSingleBinaryGoesToBin() {
        val file = temp(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        assertEquals(Archives.Format.ELF, Archives.detect(file))
        val sink = Recorder()
        assertEquals(listOf("bin/rust-analyzer"), Archives.extract(file, Archives.Format.ELF, sink, singleName = "rust-analyzer"))
        assertTrue(sink.files.getValue("bin/rust-analyzer").second)
    }

    @Test
    fun namesOutsideAreRefusedAndUnsupportedFormatsSaid() {
        assertFailsWith<IOException> { Archives.relativePath("../../x", 0) }
        assertFailsWith<IOException> { Archives.relativePath("/etc/passwd", 0) }
        assertNull(Archives.relativePath("top/", 1))
        assertEquals("a/b", Archives.relativePath("./top/a/b", 1))
        val xz = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
        assertEquals(Archives.Format.UNSUPPORTED, Archives.detect(xz))
        assertFailsWith<IOException> { Archives.extract(temp(xz), Archives.Format.UNSUPPORTED, Recorder()) }
        assertEquals(Archives.Format.UNKNOWN, Archives.detect("hello".toByteArray()))
    }

    @Test
    fun aDebsDataTar() {
        val data = gzip(tar(listOf("./data/data/com.termux/files/usr/bin/hello", '0', 0b111_101_101, "hi")))
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray())
        fun member(name: String, body: ByteArray) {
            out.write(name.padEnd(16).toByteArray())
            out.write("0".padEnd(12).toByteArray() + "0".padEnd(6).toByteArray() + "0".padEnd(6).toByteArray() + "100644".padEnd(8).toByteArray())
            out.write(body.size.toString().padEnd(10).toByteArray())
            out.write("`\n".toByteArray())
            out.write(body)
            if (body.size % 2 == 1) out.write('\n'.code)
        }
        member("debian-binary", "2.0\n".toByteArray())
        member("control.tar.gz", gzip(tar()))
        member("data.tar.gz", data)
        val file = temp(out.toByteArray())
        assertEquals(Archives.Format.DEB, Archives.detect(file))
        val sink = Recorder()
        Archives.extract(file, Archives.Format.DEB, sink)
        assertEquals("hi" to true, sink.files["bin/hello"])
    }

    @Test
    fun theLayoutIsWorkedOut() {
        // a release with a top directory, laid out as a prefix: the top taken off, into usr
        assertEquals(Archives.Layout(1, ""), Archives.layout(listOf("clangd_18.1/bin/clangd", "clangd_18.1/lib/clang/18/x.h"), "clangd"))
        // a program with its files: a directory of its own
        assertEquals(Archives.Layout(1, "opt/lua-ls"),
            Archives.layout(listOf("lua-ls/bin/lua-language-server", "lua-ls/main.lua", "lua-ls/script/a.lua"), "lua-ls"))
        // nested tops taken off one after the other
        assertEquals(Archives.Layout(2, ""), Archives.layout(listOf("a/b/bin/x", "a/b/share/y"), "x"))
        // a Termux package (its prefix already taken off), a single program
        assertEquals(Archives.Layout(0, ""), Archives.layout(listOf("bin/clang", "lib/libLLVM.so", "share/man/x.1"), "clang"))
        assertEquals(Archives.Layout(0, ""), Archives.layout(listOf("bin/rust-analyzer"), "ra"))
        // one file at the top: nothing to take off
        assertEquals(Archives.Layout(0, "opt/one"), Archives.layout(listOf("tool.jar"), "one"))
        // what is given wins
        assertEquals(Archives.Layout(0, "opt/mine"), Archives.layout(listOf("top/bin/x"), "x", strip = 0, into = "opt/mine"))
    }

    @Test
    fun aPackageListAsDpkgKeepsIt() {
        val pkg = ToolPackage(id = "clangd", source = "https://x.org/c.tgz")
        val installed = InstalledPackage(pkg, listOf("opt/clangd/bin/clangd", "opt/clangd/lib/libc.so"), mapOf("clangd" to "opt/clangd/bin/clangd"), 1)
        assertEquals(
            listOf(
                "/u", "/u/bin", "/u/opt", "/u/opt/clangd", "/u/opt/clangd/bin", "/u/opt/clangd/lib",
                "/u/opt/clangd/bin/clangd", "/u/opt/clangd/lib/libc.so", "/u/bin/clangd",
            ),
            ToolchainInstaller.packageList(File("/u"), installed).trim().lines()
        )
    }
}
