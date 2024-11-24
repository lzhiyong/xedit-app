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

package io.github.module.treesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative

/**
 * A class that represents a syntax tree.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 */
class TSTree internal constructor(
    private val self: Long,
    private val source: String?,
    val language: TSLanguage
): AutoCloseable {
    init {
        RefCleaner(this, CleanAction(self))
    }
    
    /** The root node of the syntax tree. */
    @get:JvmName("getRootNode")
    val rootNode: TSNode
        @FastNative external get
        
    /** The included ranges that were used to parse the syntax tree. */
    @get:JvmName("includedRanges")
    val includedRanges: List<TSRange>
        @FastNative external get
    /**
     * Get the root node of the syntax tree, but with
     * its position shifted forward by the given offset.
     */
    @FastNative
    @JvmName("rootNodeWithOffset")
    external fun rootNodeWithOffset(offset: UInt, point: TSPoint): TSNode?

    /**
     * Edit the syntax tree to keep it in sync
     * with source code that has been modified.
     * for UTF-16 encoding requires start and end byte * 2
     * for UTF-16 encoding requires start and end point.column * 2
     */
    @FastNative
    external fun edit(edit: TSInputEdit)
    
    @FastNative
    external fun dotGraph(pathname: String)
    
    /**
     * Create a shallow copy of the syntax tree.
     *
     * You need to copy a syntax tree in order to use it on multiple
     * threads or coroutines, as syntax trees are not thread safe.
     */
    fun copy() = TSTree(copy(self), source, language)

    /** Create a new tree cursor starting from the node of the tree. */
    fun walk() = TSTreeCursor(rootNode)
    
    /** Get the source code of the syntax tree, if available. */
    fun text(): CharSequence? = source
    /**
     * Compare an old edited syntax tree to a new
     * syntax tree representing the same document.
     *
     * For this to work correctly, this tree must have been
     * edited such that its ranges match up to the new tree.
     *
     * @return A list of ranges whose syntactic structure has changed.
     */
    external fun changedRanges(newTree: TSTree): List<TSRange>

    override fun toString() = "TSTree(id=0x${self.toString(16)}, language=$language)"

    override fun close() = delete(self)

    private class CleanAction(private val tree: Long) : Runnable {
        override fun run() = delete(tree)
    }

    private companion object {
        @JvmStatic
        @CriticalNative
        private external fun copy(tree: Long): Long

        @JvmStatic
        @CriticalNative
        private external fun delete(tree: Long)
    }
}

