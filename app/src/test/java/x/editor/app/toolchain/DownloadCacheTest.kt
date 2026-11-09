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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Which unfinished downloads are gone on with, and which are cleared (see DownloadCache). */
class DownloadCacheTest {

    private val url = "https://x.org/clangd-18.1.tar.gz"

    @Test
    fun theSameEntryDownloadsIntoTheSameFile() {
        val name = DownloadCache.downloadName("clangd", "18.1", url, "AB12")
        assertEquals(name, DownloadCache.downloadName("clangd", "18.1", " $url ", "ab12"))
        assertTrue(name.startsWith("clangd-") && name.endsWith(".download"))
    }

    @Test
    fun anyChangeOfIdVersionSourceOrHashIsAnotherFile() {
        val name = DownloadCache.downloadName("clangd", "18.1", url, null)
        assertNotEquals(name, DownloadCache.downloadName("clangd2", "18.1", url, null))
        assertNotEquals(name, DownloadCache.downloadName("clangd", "19.1", url, null))
        assertNotEquals(name, DownloadCache.downloadName("clangd", null, url, null))
        assertNotEquals(name, DownloadCache.downloadName("clangd", "18.1", "https://y.org/clangd.tar.gz", null))
        assertNotEquals(name, DownloadCache.downloadName("clangd", "18.1", url, "ab12"))
    }

    @Test
    fun staleIsWhatNoEntryWantsAndNothingUses() {
        val kept = DownloadCache.downloadName("clangd", "18.1", url, null)
        val old = DownloadCache.downloadName("clangd", "17.0", url, null)
        val running = DownloadCache.downloadName("node", "22", "https://x.org/node.tgz", null)
        val present = listOf(
            kept, old, running,
            "clangd-123456.download",              // named the old way: no entry wants it
            DownloadCache.localCopyName("pyright"),  // left by an app killed while copying
            DownloadCache.contentCopyName("node"),   // copying now
            "notes.txt",                             // not the cache's
        )
        val stale = DownloadCache.stale(
            present,
            wanted = setOf(kept),
            inUse = setOf(running, DownloadCache.contentCopyName("node")),
        )
        assertEquals(listOf(old, "clangd-123456.download", "pyright.local"), stale)
    }

    @Test
    fun anIdIsKeptToOneFileName() {
        assertTrue(DownloadCache.downloadName("../a/b", null, url, null).startsWith(".._a_b-"))
        assertEquals("a_b.local", DownloadCache.localCopyName("a/b"))
    }
}
