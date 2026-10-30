/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.piecetable

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlin.test.BeforeTest
import org.junit.Test
import org.junit.runner.RunWith
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Only cases that are meaningful on a real device. Everything else lives in src/sharedTest
 *
 * and runs on both the JVM and the device. Two things matter here:
 *   1. memory and timing on a device: opening a large file, random line access, bulk edits;
 *   2. problems that only blow up on an old API level, the typical one being
 *      `MutableList.removeLast()`, which under Kotlin 2.x + JDK 21 resolves to the Java 21
 *      `List.removeLast` and throws NoSuchMethodError below API 35.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AndroidDeviceTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    private companion object {
        const val TAG = "PieceTreeDevice"
    }

    /**
     * These paths reach the places in getLinesContent / appendToNode / createNewPieces that
     * used to call removeLast(). Running them below API 35 is the regression test for it.
     */
    @Test
    fun apiLevelSensitivePathsWork() {
        Log.i(TAG, "running on API ${Build.VERSION.SDK_INT}")

        // the danglingCR branch of getLinesContent
        val mixed = TestSupport.buildBuffer("a\r\nb\nc\rd\r", normalizeEOL = false)
        assertEquals(listOf("a", "b", "c", "d", ""), mixed.getLinesContent())

        // the CRLF branch of appendToNode
        val tree = TestSupport.buildTree("abc\r", normalizeEOL = false)
        tree.insert(tree.getLength(), "\ndef")
        assertEquals("abc\r\ndef", tree.getLinesRawContent())
        assertEquals(2, tree.getLineCount())
        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun openAndEditALargeFileOnDevice() {
        val lineCount = 100_000
        val sb = StringBuilder(lineCount * 48)
        for (i in 0 until lineCount) {
            sb.append("the quick brown fox jumps over the lazy dog ").append(i).append('\n')
        }
        val text = sb.toString()
        Log.i(TAG, "document size: ${text.length} chars")

        var mark = System.currentTimeMillis()
        val buffer = TestSupport.buildBuffer(text)
        Log.i(TAG, "build: ${System.currentTimeMillis() - mark} ms")
        assertEquals(lineCount + 1, buffer.getLineCount())

        val random = Random(2026L)
        mark = System.currentTimeMillis()
        repeat(20_000) {
            val line = random.nextInt(lineCount) + 1
            assertTrue(buffer.getLineContent(line).endsWith("${line - 1}"))
        }
        Log.i(TAG, "20000 random line reads: ${System.currentTimeMillis() - mark} ms")

        mark = System.currentTimeMillis()
        repeat(2000) {
            val line = random.nextInt(lineCount) + 1
            buffer.applyEdits(
                listOf(SingleEditOperation(Range(line, 1, line, 1), "// ")),
                false,
                false
            )
        }
        Log.i(TAG, "2000 edits: ${System.currentTimeMillis() - mark} ms")

        mark = System.currentTimeMillis()
        var total = 0L
        buffer.readPiecesContent { total += it.length }
        Log.i(TAG, "full read back: ${System.currentTimeMillis() - mark} ms, $total chars")
        assertEquals(text.length.toLong() + 2000 * 3, total)

        TestSupport.assertTreeInvariants(buffer.getPieceTree())
    }
}
