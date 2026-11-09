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

package x.github.module.treesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A text for the predicates of query cursors ([TSQueryCursor.setText]), copied into native
 * memory once and read there in place by any number of cursors.
 *
 * [TSQueryCursor.setText] with a String copies the text into each cursor it is given to;
 * the text of a block that several cursors run over (the language of the document, then
 * each part in another language) was copied once for each of them. Set this once instead
 * and give it to all of them. Its buffer is used again by the next [set]: no allocation
 * once it is large enough, so keep one and set it for each block.
 *
 * A cursor holds on to the native text it was given until it is run again
 * ([TSQueryCursor.reset]) or closed, so [close] never frees a text a cursor still reads.
 * But [set] changes the text in place: do not set it while a cursor given it is still
 * being iterated, nor from another thread than the one running its cursors.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 */
class TSQueryText : AutoCloseable {

    // a std::shared_ptr<QueryText> * (ts_query_cursor.cpp)
    internal val self: Long = init()

    private val cleaner: Cleaner.Cleanable? = RefCleaner(this, CleanAction(self))

    private val deleted = AtomicBoolean(false)

    /** The byte of the document the text starts at (UTF-16: char index × 2). */
    var startByte: UInt = 0U
        private set

    /** The number of chars of the text. */
    var length: Int = 0
        private set

    /**
     * The text of the document from [startByte] on: [text], for a tree parsed as
     * UTF-16 ([TSInputEncoding.UTF_16LE]).
     */
    fun set(startByte: UInt, text: String) = set(startByte, text, 0, text.length)

    /**
     * The text of the document from [startByte] on: the chars [start, end) of [text]
     * (copied from it directly, without a substring).
     *
     * @throws [IndexOutOfBoundsException] If [start, end) is not a range of [text].
     */
    fun set(startByte: UInt, text: String, start: Int, end: Int) {
        check(!deleted.get()) { "The text is closed" }
        if (start < 0 || end > text.length || start > end) {
            throw IndexOutOfBoundsException("start: $start, end: $end, length: ${text.length}")
        }
        nativeSet(self, startByte.toInt(), text, start, end - start)
        this.startByte = startByte
        this.length = end - start
    }

    override fun toString() = "TSQueryText(startByte=$startByte, length=$length)"

    override fun close() {
        if (deleted.compareAndSet(false, true)) {
            if (cleaner != null)
                cleaner.clean()
            else
                delete(self)
        }
    }

    // whether it was closed (TSQueryCursor.setText refuses it then)
    internal val isClosed: Boolean
        get() = deleted.get()

    private class CleanAction(private val ptr: Long) : Runnable {
        override fun run() = delete(ptr)
    }

    private companion object {
        @JvmStatic
        @CriticalNative
        private external fun init(): Long

        @JvmStatic
        @CriticalNative
        private external fun delete(self: Long)

        @JvmStatic
        @FastNative
        private external fun nativeSet(self: Long, startByte: Int, text: String, start: Int, length: Int)
    }
}
