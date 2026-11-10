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

package x.github.module.editor.model

import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.StorageStats
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PieceTreeBufferCompactTest {

    private val worthwhile = EditorBuffer.CompactMode.WORTHWHILE
    private val lowMemory = EditorBuffer.CompactMode.LOW_MEMORY

    private fun bufferOf(text: String) = PieceTreeBuffer(PieceTreeTextBufferBuilder().apply { acceptChunk(text) }.build())

    @Test
    fun aFreshTextIsNotCompacted() {
        val buffer = bufferOf("line\n".repeat(10_000))
        assertFalse(buffer.compact(worthwhile))
        assertFalse(buffer.compact(lowMemory))
    }

    @Test
    fun aLittleGarbageWaitsForMemoryPressure() {
        // 200K chars typed and deleted again: below WORTHWHILE's 1M, above LOW_MEMORY's
        val buffer = bufferOf("a".repeat(100_000))
        repeat(200) {
            buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "x".repeat(1000))), false)
            buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1001), null)), false)
        }
        val before = buffer.getText()
        assertFalse(buffer.compact(worthwhile))
        assertTrue(buffer.compact(lowMemory))
        assertEquals(before, buffer.getText())
        val stats = buffer.pieceTree.getStorageStats()
        assertEquals(stats.length.toLong(), stats.storedLength)
        // nothing left to give back
        assertFalse(buffer.compact(lowMemory))
    }

    @Test
    fun muchGarbageIsCompactedAfterASave() {
        val buffer = bufferOf("b".repeat(10_000))
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "y".repeat(2_000_000))), false)
        buffer.applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 2_000_001), null)), false)
        assertTrue(buffer.compact(worthwhile))
        assertEquals("b".repeat(10_000), buffer.getText())
    }

    @Test
    fun theThresholds() {
        fun stats(length: Int, stored: Long, pieces: Int) = StorageStats(length, stored, 0, pieces)
        // half of the text, at least 1M chars
        assertFalse(PieceTreeBuffer.isWorthCompacting(stats(10_000_000, 14_000_000, 200), worthwhile))
        assertTrue(PieceTreeBuffer.isWorthCompacting(stats(10_000_000, 15_000_000, 200), worthwhile))
        assertFalse(PieceTreeBuffer.isWorthCompacting(stats(100, 900_000, 2), worthwhile))
        // fragmented: 20 000 pieces more than the chunks of the text
        assertTrue(PieceTreeBuffer.isWorthCompacting(stats(1000, 1000, 20_001), worthwhile))
        // under pressure: an eighth, at least 64K chars; 5 000 extra pieces
        assertTrue(PieceTreeBuffer.isWorthCompacting(stats(1_000_000, 1_125_000, 20), lowMemory))
        assertFalse(PieceTreeBuffer.isWorthCompacting(stats(100, 60_000, 2), lowMemory))
        assertTrue(PieceTreeBuffer.isWorthCompacting(stats(1000, 1000, 5_001), lowMemory))
    }
}
