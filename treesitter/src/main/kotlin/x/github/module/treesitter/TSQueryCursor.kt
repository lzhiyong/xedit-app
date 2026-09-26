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
import java.util.concurrent.atomic.AtomicBoolean


/**
 * A class that is used for executing a query.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 *
 * @since 0.25.0
 */
class TSQueryCursor internal constructor(
    private val query: TSQuery,
    private val node: TSNode,
    progressCallback: QueryProgressCallback? = null
) : AutoCloseable {

    private val self: Long = init()
    
    private val cleaner: Cleaner.Cleanable?
    
    private val deleted = AtomicBoolean(false)

    init {
        cleaner = RefCleaner(this, CleanAction(self))

        exec(query.self, node, progressCallback)
    }
    
    /**
     * The maximum number of in-progress matches.
     *
     * Default: `UInt.MAX_VALUE`
     *
     * @throws [IllegalArgumentException] If the match limit is set to `0`.
     */
    @get:JvmName("getMatchLimit")
    @set:JvmName("setMatchLimit")
    @set:Throws(IllegalArgumentException::class)
    var matchLimit: UInt
        @FastNative external get

        @FastNative external set
    
    /**
     * The maximum start depth for the query.
     *
     * This prevents cursors from exploring children nodes at a certain depth.
     * Note that if a pattern includes many children, then they will still be checked.
     *
     * Default: `UInt.MAX_VALUE`
     */
    @get:JvmName("getMaxStartDepth")
    @set:JvmName("setMaxStartDepth")
    var maxStartDepth: UInt = UInt.MAX_VALUE
        @FastNative external set
        
    
    /**
     * Check if the query exceeded its maximum number of
     * in-progress matches during its last execution.
     *
     * @see matchLimit
     */
    @get:JvmName("didExceedMatchLimit")
    val didExceedMatchLimit: Boolean
        @FastNative external get
        
    /**
     * The range of bytes in which the query will be executed.
     *
     * The query cursor will return matches that intersect with
     * the given range. This means that a match may be returned
     * even if some of its captures fall outside the specified range,
     * as long as at least part of the match overlaps with the range.
     *
     * Default: `UInt.MIN_VALUE..UInt.MAX_VALUE`
     *
     * @throws [IllegalArgumentException] If set to an invalid range.
     */
    var byteRange: UIntRange = UInt.MIN_VALUE..UInt.MAX_VALUE
        set(value) {
            require(nativeSetByteRange(value.first.toInt(), value.last.toInt())) {
                "Invalid byte range: [${value.first}, ${value.last}]"
            }
            field = value
        }
    
    /**
     * The range of points in which the query will be executed.
     *
     * The query cursor will return matches that intersect with
     * the given range. This means that a match may be returned
     * even if some of its captures fall outside the specified range,
     * as long as at least part of the match overlaps with the range.
     *
     * Default: `Point.MIN..Point.MAX`
     */
    var pointRange: ClosedRange<TSPoint> = TSPoint.MIN..TSPoint.MAX
        set(value) {
            require(nativeSetPointRange(value.start, value.endInclusive)) {
                "Invalid point range: [${value.start}, ${value.endInclusive}]"
            }
            field = value
        }
        
    /**
     * Iterate over all the matches in the order that they were found.
     *
     * #### Example
     *
     * ```kotlin
     * query(tree.rootNode).matches {
     *      if (name != "ieq?") return@matches true
     *      val node = it[(args[0] as QueryPredicateArg.Capture).value].first()
     *      val value = (args[1] as QueryPredicateArg.Literal).value
     *      value.equals(node.text()?.toString(), ignoreCase = true)
     *  }
     * ```
     *
     * @param predicate A function that handles custom predicates.
     */
    @JvmOverloads
    fun matches(
        predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean = { true }
    ) = sequence<TSQueryMatch> {
        var match = nextMatch(query.captureNames, node.tree)
        while (match != null) {
            val result = match.check(predicate)
            if (result != null) yield(result)
            match = nextMatch(query.captureNames, node.tree)
        }
    }
    
    /**
     * Iterate over all the individual captures in the order that they appear.
     *
     * This is useful if you don't care about _which_ pattern matched.
     *
     * @param predicate A function that handles custom predicates.
     */
    @JvmOverloads
    fun captures(
        predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean = { true }
    ) = sequence<Pair<UInt, TSQueryMatch>> {
        var capture = nextCapture(query.captureNames, node.tree)
        while (capture != null) {
            val index = capture.first
            val match = capture.second.check(predicate)
            if (match != null) yield(index to match)
            capture = nextCapture(query.captureNames, node.tree)
        }
    }
    
    override fun toString() = "TSQueryCursor(query=$query, node=$node)"

    override fun close() {
        if (deleted.compareAndSet(false, true)) {
            if (cleaner != null)
                cleaner.clean()
            else 
                delete(self)
        }
    }

    @FastNative
    private external fun nativeSetByteRange(start: Int, end: Int): Boolean

    @FastNative
    private external fun nativeSetPointRange(start: TSPoint, end: TSPoint): Boolean

    @FastNative
    private external fun nextMatch(captureNames: List<String>, tree: TSTree): TSQueryMatch?

    @FastNative
    private external fun nextCapture(
        captureNames: List<String>,
        tree: TSTree
    ): Pair<UInt, TSQueryMatch>?

    @FastNative
    private external fun exec(query: Long, node: TSNode, progressCallback: QueryProgressCallback?)

    private inline fun TSQueryMatch.check(
        predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean
    ): TSQueryMatch? {
        if (node.tree.text() == null) return this
        val result = query.predicates[patternIndex.toInt()].all {
            if (it !is TSQueryPredicate.Generic) it(this) else predicate(it, this)
        }
        return if (result) this else null
    }
    
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
    }
}

