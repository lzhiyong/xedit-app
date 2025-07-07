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
import java.lang.ref.Cleaner

/**
 * A function to retrieve a chunk of text at a given byte offset and point.
 *
 * The function should return `null` to indicate the end of the document.
 */
typealias ParseCallback = (byte: UInt, point: TSPoint) -> ByteArray

/**
 * A function that logs parsing results.
 *
 * The first argument is the log type and the second argument is the message.
 */
typealias LogFunction = (type: TSLogType, message: String) -> Unit

/**
 * A class that is used to produce a [syntax tree][Tree] from source code.
 *
 * @constructor Create a new instance with a certain [language], or `null` if empty.
 */
class TSParser(language: TSLanguage) : AutoCloseable {
    
    private val cleaner: Cleaner.Cleanable?
    
    private val self: Long = init()
     
    /**
     * The language that the parser will use for parsing.
     *
     * Parsing cannot be performed while the language is `null`.
     */
    var language: TSLanguage? = null
        @FastNative external set
        
    init {       
        this.language = language       
        cleaner = RefCleaner(this, CleanAction(self))
    }
    
    /**
     * The ranges of text that the parser will include when parsing.
     *
     * By default, the parser will always include entire documents.
     * Setting this property allows you to parse only a _portion_ of a
     * document but still return a syntax tree whose ranges match up with
     * the document as a whole. You can also pass multiple disjoint ranges.
     *
     * @throws [IllegalArgumentException] If the ranges overlap or are not in ascending order.
     */
    @set:Throws(IllegalArgumentException::class)
    @set:JvmName("setIncludedRanges")
    var includedRanges: List<TSRange> = emptyList()
        external set
    
    /**
     * The maximum duration in microseconds that parsing
     * should be allowed to take before halting.
     */
    @set:JvmName("setTimeoutMicros")
    var timeoutMicros: ULong = 0UL
        @FastNative external set
       
    /** set and get the parser cancelled flag */
    @set:JvmName("setCancelled")
    var isCancelled: Boolean = false
        @FastNative external set
    
    /**
     * The logger that the parser will use during parsing.
     *
     * #### Example
     *
     * ```
     * import android.util.Log;
     *
     * parser.logger = { type, msg ->
     *     Log.d("${type.name}", msg)
     * }
     * ```
     */
    @set:JvmName("setLogger")
    @get:Deprecated("The logger can't be called directly.", level = DeprecationLevel.HIDDEN)
    var logger: LogFunction? = null
        @FastNative external set
    
    /**
     * Parse a source code string and create a syntax tree.
     *
     * If you have already parsed an earlier version of this document and the document
     * has since been edited, pass the previous syntax tree to [oldTree] so that the
     * unchanged parts of it can be reused. This will save time and memory. For this
     * to work correctly, you must have already edited the old syntax tree using the
     * [Tree.edit] method in a way that exactly matches the source code changes.
     *
     * @throws [IllegalStateException]
     *  If the parser does not have a [language] assigned or
     *  if parsing was cancelled due to a [timeout][timeoutMicros].
     */
    @Throws(IllegalStateException::class)
    fun parse(oldTree: TSTree?, source: String) = parse(
        oldTree, TSInputEncoding.UTF16, source.toByteArray(Charsets.UTF_16LE)
    )
    
    @Throws(IllegalStateException::class)
    external fun parse(oldTree: TSTree?, encoding: TSInputEncoding, bytes: ByteArray): TSTree
    
    /**
     * Parse source code from a callback and create a syntax tree.
     *
     * If you have already parsed an earlier version of this document and the document
     * has since been edited, pass the previous syntax tree to [oldTree] so that the
     * unchanged parts of it can be reused. This will save time and memory. For this
     * to work correctly, you must have already edited the old syntax tree using the
     * [Tree.edit] method in a way that exactly matches the source code changes.
     *
     * @throws [IllegalStateException]
     *  If the parser does not have a [language] assigned or
     *  if parsing was cancelled due to a [timeout][timeoutMicros].
     */
    @Throws(IllegalStateException::class)
    fun parse(oldTree: TSTree?, callback: ParseCallback) = parse(oldTree, TSInputEncoding.UTF16, callback)
     
    @Throws(IllegalStateException::class)
    external fun parse(oldTree: TSTree?, encoding: TSInputEncoding, callback: ParseCallback): TSTree
    
    /**
     * Instruct the parser to start the next [parse] from the beginning.
     *
     * If the parser previously failed because of a [timeout][timeoutMicros],
     * then by default, it will resume where it left off. If you don't
     * want to resume, and instead intend to use this parser to parse
     * some other document, you must call this method first.
     */
    @FastNative
    external fun reset()
    
    external fun dotGraphs(pathname: String)

    override fun toString() = "TSParser(language=$language)"

    override fun close() {
        cleaner?.let { it.clean() } ?: run { delete(self) }
    }

    private class CleanAction(private val parser: Long) : Runnable {
        override fun run() = delete(parser)
    }
    
    private companion object {        
        @JvmStatic
        @CriticalNative
        private external fun init(): Long

        @JvmStatic
        @FastNative
        private external fun delete(parser: Long)
    }
}

