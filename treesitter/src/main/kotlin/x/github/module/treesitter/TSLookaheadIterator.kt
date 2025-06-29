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

package x.github.module.treesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative

/**
 * A class that is used to look up valid symbols in a specific parse state.
 *
 * Lookahead iterators can be useful to generate suggestions and improve syntax
 * error diagnostics. To get symbols valid in an `ERROR` node, use the lookahead
 * iterator on its first leaf node state. For `MISSING` nodes, a lookahead
 * iterator created on the previous non-extra leaf node may be appropriate.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 */
class TSLookaheadIterator @Throws(IllegalArgumentException::class) internal constructor(
    language: TSLanguage,
    private val state: UShort
) : AbstractIterator<TSLookaheadIterator.Symbol>(), AutoCloseable {
    private val self: Long = init(language.self, state).takeIf { it > 0L }
        ?: throw IllegalArgumentException("State $state is not valid for $language")

    init {
        RefCleaner(this, CleanAction(self))
    }

    /** The current language of the lookahead iterator. */
    val language: TSLanguage
        @FastNative external get

    /**
     * The current symbol ID.
     *
     * The ID of the `ERROR` symbol is equal to `UShort.MAX_VALUE`.
     */
    @get:JvmName("getCurrentSymbol")
    val currentSymbol: UShort
        @FastNative external get

    /**
     * The current symbol name.
     *
     * Newly created lookahead iterators will contain the `ERROR` symbol.
     */
    val currentSymbolName: String
        @FastNative external get

    /** Iterate over the symbol IDs. */
    fun symbols(): Sequence<UShort> {
        reset(state, null)
        return sequence {
            while (nativeNext())
                yield(currentSymbol)
        }
    }

    /** Iterate over the symbol names. */
    fun symbolNames(): Sequence<String> {
        reset(state, null)
        return sequence {
            while (nativeNext())
                yield(currentSymbolName)
        }
    }
    
    /** Advance the lookahead iterator to the next symbol. */
    override fun next() = super.next()
   
    override fun computeNext() = if (nativeNext()) {
        setNext(Symbol(currentSymbol, currentSymbolName))
    } else {
        done()
    }

    operator fun iterator() = apply { reset(state, null) }

    override fun close() = delete(self)
    
    /** A class that pairs a symbol ID with its name. */
    @JvmRecord
    data class Symbol(val id: UShort, val name: String)

    private class CleanAction(private val lookahead: Long) : Runnable {
        override fun run() = delete(lookahead)
    }
    
    /**
     * Reset the lookahead iterator the given [state] and, optionally, another [language].
     *
     * @return `true` if the iterator was reset successfully or `false` if it failed.
     */
    @FastNative
    @JvmName("reset")
    external fun reset(state: UShort, language: TSLanguage?): Boolean
    
    @FastNative
    private external fun nativeNext(): Boolean

    private companion object {
        @JvmStatic
        @JvmName("init")
        @CriticalNative
        private external fun init(language: Long, state: UShort): Long

        @JvmStatic
        @CriticalNative
        private external fun delete(lookahead: Long)
    }
}

