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

/*
 * Derived from kotlin-tree-sitter (https://github.com/tree-sitter/kotlin-tree-sitter),
 * Copyright (c) 2024 tree-sitter contributors, under the MIT License: see
 * LICENSE-kotlin-tree-sitter at the root of this module. Reworked from it, and changed since.
 */

package x.github.module.treesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

// The size of the buffers of the batched calls (forEachCapture, forEachMatch); the native
// side takes at most as many (BATCH_INTS in ts_query_cursor.cpp).
@PublishedApi internal const val BATCH_INTS = 1024

// a capture of nativeNextCaptures: pattern, capture, start byte, end byte
@PublishedApi internal const val CAPTURE_INTS = 4

// a capture in a record of nativeNextMatches: capture, start byte, end byte
@PublishedApi internal const val MATCH_CAPTURE_INTS = 3

// the same with its node (withNodes): then context[1..3] and the id, low and high ints
@PublishedApi internal const val MATCH_NODE_CAPTURE_INTS = 8


/**
 * A class that is used for executing a query.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 *
 * @since 0.25.0
 */
class TSQueryCursor internal constructor(
    @PublishedApi internal val query: TSQuery,
    node: TSNode,
    // read natively whenever the cursor advances (tree-sitter calls it then, not at exec)
    private val progressCallback: QueryProgressCallback? = null
) : AutoCloseable {

    // the node the query runs over now: set by reset only
    @PublishedApi
    internal var node: TSNode = node

    private val self: Long = init()
    
    private val cleaner: Cleaner.Cleanable?
    
    private val deleted = AtomicBoolean(false)

    init {
        cleaner = RefCleaner(this, CleanAction(self))

        exec(query.self, node, progressCallback, node.tree.hasText())
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
     * The range of bytes that every node of a match has to lie in, whole.
     *
     * Unlike [byteRange], which lets through the matches that reach into it, this
     * leaves out the matches with a node outside of it, and the cursor does not even
     * go into the nodes outside of it. A query whose patterns can start at any child
     * of a node (a pattern that is a sequence of siblings, `(import)+`) otherwise
     * walks every child of every node the [byteRange] lies in: thousands at the top
     * of a large file.
     *
     * Default: `UInt.MIN_VALUE..UInt.MAX_VALUE`
     *
     * @throws [IllegalArgumentException] If set to an invalid range.
     */
    var containingByteRange: UIntRange = UInt.MIN_VALUE..UInt.MAX_VALUE
        set(value) {
            require(nativeSetContainingByteRange(value.first.toInt(), value.last.toInt())) {
                "Invalid containing byte range: [${value.first}, ${value.last}]"
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
    
    /**
     * Calls [action] with every capture, in the order that they appear, as [captures]
     * finds them: the [pattern][TSQueryMatch.patternIndex] of its match, the capture (an
     * index into [TSQuery.captureNames]) and the bytes of its node.
     *
     * Unlike [captures], no object is made for a capture whose predicates were all
     * decided natively (most of them, see [setText]): they come in batches, as ints.
     * Only a capture Kotlin has to check (a `#match?`, a predicate on text that was not
     * given) comes as a [TSQueryMatch] for [predicate], as before.
     *
     * @param predicate A function that handles custom predicates.
     */
    inline fun forEachCapture(
        noinline predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean = { true },
        action: (patternIndex: Int, captureIndex: Int, startByte: Int, endByte: Int) -> Unit
    ) {
        val buffer = IntArray(BATCH_INTS)
        while (true) {
            val count = nextCaptures(buffer)
            if (count == 0) return
            for (i in 0 until count) {
                val at = i * CAPTURE_INTS
                var pattern = buffer[at]
                if (pattern < 0) {
                    // the last of the batch: its match comes next, to be checked here
                    pattern = pattern and Int.MAX_VALUE
                    if (!pendingCaptureHolds(predicate)) continue
                }
                action(pattern, buffer[at + 1], buffer[at + 2], buffer[at + 3])
            }
        }
    }

    /**
     * Calls [action] with every match, in the order that they were found, as [matches]
     * finds them. The [TSQueryMatchView] is only valid during the call (it is reused).
     *
     * As with [forEachCapture], no object is made for a match whose predicates were all
     * decided natively; only one Kotlin has to check comes as a [TSQueryMatch] for
     * [predicate].
     *
     * @param predicate A function that handles custom predicates.
     * @param withNodes Whether [TSQueryMatchView.node] is going to be called. The records
     *  are then larger, so fewer come in a batch: leave it out if the bytes are enough.
     */
    inline fun forEachMatch(
        noinline predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean = { true },
        withNodes: Boolean = false,
        action: (TSQueryMatchView) -> Unit
    ) {
        val buffer = IntArray(BATCH_INTS)
        val view = TSQueryMatchView(tree)
        val captureInts = if (withNodes) MATCH_NODE_CAPTURE_INTS else MATCH_CAPTURE_INTS
        while (true) {
            val used = nextMatches(buffer, withNodes)
            if (used == 0) return
            var at = 0
            while (at < used) {
                val raw = buffer[at]
                val pattern = raw and Int.MAX_VALUE
                val count = buffer[at + 1]
                if (count < 0) {
                    // too large for the buffer: it comes whole, as an object
                    at += 2
                    val match = pendingMatch(predicate) ?: continue
                    view.of(match, query)
                    action(view)
                    continue
                }
                val next = at + 2 + captureInts * count
                if (raw >= 0 || pendingMatch(predicate) != null) {
                    view.of(pattern, count, buffer, at + 2, captureInts)
                    action(view)
                }
                at = next
            }
        }
    }

    // the tree of the nodes of TSQueryMatchView.node
    @PublishedApi
    internal val tree: TSTree
        get() = node.tree

    // The capture the last batch stopped at, as an object: whether its predicates hold.
    @PublishedApi
    internal fun pendingCaptureHolds(predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean): Boolean {
        val pending = nextCapture(query.captureNames, node.tree) ?: return false
        return if (pending.second.check(predicate) != null) {
            // its other captures are not checked again
            acceptLastMatch()
            true
        } else {
            // nor do they come
            discardLastMatch()
            false
        }
    }

    // The match the last batch stopped at, as an object, if its predicates hold.
    @PublishedApi
    internal fun pendingMatch(predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean): TSQueryMatch? =
        nextMatch(query.captureNames, node.tree)?.check(predicate)

    /**
     * Runs the query again, from the start, over [node] (of this tree or of another):
     * this cursor and its native memory are used again, instead of a new cursor being
     * made (and registered with the cleaner) and closed for each run. For the same query
     * run over many nodes one after the other: each block of a document, each part of it
     * in another language.
     *
     * What the last run had not handed out yet is dropped, and so is the text
     * ([setText]): it was of that run. [byteRange], [containingByteRange], [pointRange],
     * [matchLimit] and [maxStartDepth] stay as they were set.
     *
     * The cursor keeps no node of the last run alive, nor reads any: the tree of the
     * last run may have been closed meanwhile.
     *
     * @throws [IllegalStateException] If the cursor or its query is closed.
     */
    fun reset(node: TSNode) {
        check(!deleted.get()) { "The cursor is closed" }
        check(!query.isClosed) { "The query of the cursor is closed" }
        this.node = node
        exec(query.self, node, progressCallback, node.tree.hasText())
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
    private external fun nativeSetContainingByteRange(start: Int, end: Int): Boolean

    /**
     * The text of the document from [startByte] on, for a tree parsed as UTF-16
     * ([TSInputEncoding.UTF_16LE]): with it, the matches whose text predicates
     * certainly fail (`#eq?`, `#any-of?` with strings, `#lua-match?` anchored at the
     * start) are left out natively, instead of each being handed over and its text
     * read only to be thrown away. A pattern such as `("text" @x (#any-of? @x "TODO"))`
     * matches every word of every comment.
     *
     * Only what the predicates reject is left out; whatever cannot be decided from
     * this text (a node outside of it, a predicate of another kind) comes as before,
     * and is checked in Kotlin as before.
     */
    fun setText(startByte: UInt, text: String) = nativeSetText(startByte.toInt(), text)

    /**
     * The same as [setText] with a String, but read in place: nothing is copied. For a
     * text several cursors are given (the text of a block, for the cursor of each
     * language in it): it is copied once, into [text], instead of once into each cursor.
     *
     * The cursor holds on to the native text until it is run again ([reset]) or closed,
     * whatever happens to [text] meanwhile; but a [TSQueryText.set] changes it in place,
     * so do not set [text] again while this cursor is still being iterated.
     *
     * @throws [IllegalStateException] If [text] is closed.
     */
    fun setText(text: TSQueryText) {
        check(!text.isClosed) { "The text is closed" }
        nativeSetSharedText(text.self)
    }

    @FastNative
    private external fun nativeSetText(startByte: Int, text: String)

    @FastNative
    private external fun nativeSetSharedText(text: Long)

    @FastNative
    private external fun nativeSetPointRange(start: TSPoint, end: TSPoint): Boolean

    @FastNative
    private external fun nextMatch(captureNames: List<String>, tree: TSTree): TSQueryMatch?

    @FastNative
    private external fun nextCapture(
        captureNames: List<String>,
        tree: TSTree
    ): Pair<UInt, TSQueryMatch>?

    // Batched (see forEachCapture, forEachMatch): plain JNI calls, as they may walk far
    // through the tree before their buffer is full (a @FastNative call would hold off the
    // GC of every thread meanwhile); their cost is shared by up to a few hundred captures.
    // (wrapped: the JNI names stay those of private members, whatever Kotlin does with
    // the names of internal ones)
    @PublishedApi
    internal fun nextCaptures(buffer: IntArray): Int = nativeNextCaptures(buffer)

    @PublishedApi
    internal fun nextMatches(buffer: IntArray, withNodes: Boolean): Int = nativeNextMatches(buffer, withNodes)

    private external fun nativeNextCaptures(buffer: IntArray): Int

    private external fun nativeNextMatches(buffer: IntArray, withNodes: Boolean): Int

    @FastNative
    private external fun discardLastMatch()

    @FastNative
    private external fun acceptLastMatch()

    @FastNative
    private external fun exec(
        query: Long, node: TSNode, progressCallback: QueryProgressCallback?, treeHasText: Boolean
    )

    private inline fun TSQueryMatch.check(
        predicate: TSQueryPredicate.(TSQueryMatch) -> Boolean
    ): TSQueryMatch? {
        // without the text, no predicate can be told: they all hold
        if (!node.tree.hasText()) return this
        // all decided natively, and they hold
        if (predicatesDecided) return this
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

