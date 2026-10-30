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

package x.github.module.piecetable.common

/**
 * A growable list of primitive ints.
 *
 * The piece tree keeps one line-start offset per line of every buffer. Storing those in a
 * `MutableList<Int>` costs roughly 20 bytes per entry (boxed Integer + backing array slot),
 * which is the single largest memory consumer on big files. This class stores them in a plain
 * `IntArray` (4 bytes per entry) while keeping the same `size` / `get` / `set` surface, so the
 * call sites read exactly like before.
 *
 * Not thread safe, just like the rest of the piece tree.
 */
class IntList {

    private var data: IntArray
    private var length: Int

    constructor(initialCapacity: Int = 16) {
        this.data = IntArray(if (initialCapacity > 0) initialCapacity else 1)
        this.length = 0
    }

    private constructor(data: IntArray, length: Int) {
        this.data = data
        this.length = length
    }

    val size: Int
        get() = this.length

    fun isEmpty() = this.length == 0

    fun isNotEmpty() = this.length > 0

    operator fun get(index: Int): Int {
        if (index < 0 || index >= this.length) {
            throw IndexOutOfBoundsException("index: $index, size: ${this.length}")
        }
        return this.data[index]
    }

    operator fun set(index: Int, value: Int) {
        if (index < 0 || index >= this.length) {
            throw IndexOutOfBoundsException("index: $index, size: ${this.length}")
        }
        this.data[index] = value
    }

    fun add(value: Int) {
        ensureCapacity(this.length + 1)
        this.data[this.length++] = value
    }

    /** Append `other[fromIndex until other.size]`, optionally shifting every value by `delta`. */
    fun addAll(other: IntList, fromIndex: Int = 0, delta: Int = 0) {
        if (fromIndex >= other.length) {
            return
        }
        val count = other.length - fromIndex
        ensureCapacity(this.length + count)
        if (delta == 0) {
            System.arraycopy(other.data, fromIndex, this.data, this.length, count)
            this.length += count
        } else {
            for (i in 0 until count) {
                this.data[this.length + i] = other.data[fromIndex + i] + delta
            }
            this.length += count
        }
    }

    /** Add `delta` to every element in place. */
    fun shiftAll(delta: Int) {
        if (delta == 0) {
            return
        }
        for (i in 0 until this.length) {
            this.data[i] += delta
        }
    }

    fun removeLast(): Int {
        if (this.length == 0) {
            throw NoSuchElementException("IntList is empty")
        }
        return this.data[--this.length]
    }

    fun last(): Int = get(this.length - 1)

    fun clear() {
        this.length = 0
    }

    fun toIntArray(): IntArray = this.data.copyOf(this.length)

    fun copy(): IntList = IntList(this.data.copyOf(this.length), this.length)

    private fun ensureCapacity(required: Int) {
        if (required <= this.data.size) {
            return
        }
        var capacity = this.data.size + (this.data.size shr 1) + 1
        if (capacity < required) {
            capacity = required
        }
        this.data = this.data.copyOf(capacity)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntList || other.length != this.length) return false
        for (i in 0 until this.length) {
            if (this.data[i] != other.data[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until this.length) {
            result = result * 31 + this.data[i]
        }
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder(this.length * 4 + 2)
        sb.append('[')
        for (i in 0 until this.length) {
            if (i > 0) sb.append(", ")
            sb.append(this.data[i])
        }
        sb.append(']')
        return sb.toString()
    }

    companion object {
        fun of(vararg values: Int): IntList = IntList(values.copyOf(), values.size)

        fun fromIntArray(values: IntArray): IntList = IntList(values.copyOf(), values.size)
    }
}
