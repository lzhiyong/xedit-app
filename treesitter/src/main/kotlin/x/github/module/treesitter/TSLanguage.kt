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
 * A class that defines how to parse a particular language.
 *
 * When a [Language] is generated by the Tree-sitter CLI, it is assigned
 * an ABI [version] number that corresponds to the current CLI version.
 *
 * @constructor Create a new instance from the given language pointer.
 * @param language A pointer to a `TSLanguage` cast to [Long].
 * @throws [IllegalArgumentException] If the pointer is invalid or the [version] is incompatible.
 */
class TSLanguage internal constructor(val self: Long) {
    /**
     * Get the tree-sitter language by grammar function name
     * which are usually named in a standard format
     * like tree_sitter_c, tree_sitter_cpp, tree_sitter_kotlin and so on.
     * see the ts_language.h for supported languages
     * 
     * @name grammar name like `tree_sitter_c`, `tree_sitter_cpp` etc
     *
     * #### Example
     *
     * ```kotlin
     * val language = TSLanguage("tree_sitter_c")
     * ```
     */
    constructor(name: String): this(resolve(name)) {
        /** the prefix length of tree_sitter_xxx is 12 */
        this.name = name.substring(12).lowercase()
    }
    
    /** language name like `c`, `cpp`, `kotlin` etc */
    private var name: String = "txt"
    
    init {
        if (self <= 0L) {
            throw IllegalArgumentException("Invalid language: $self")
        }
        checkVersion()
    }
    
    /** The ABI version number for this language. */
    @get:JvmName("getVersion")
    val version: UInt
        @FastNative external get
    
    /** The number of distinct node types in this language. */
    @get:JvmName("getSymbolCount")
    val symbolCount: UInt
        @FastNative external get
      
    @FastNative
    @Throws(IllegalArgumentException::class)
    private external fun checkVersion()
    
    /** The number of valid states in this language. */
    @get:JvmName("getStateCount")
    val stateCount: UInt
        @FastNative external get

    /** The number of distinct field names in this language. */
    @get:JvmName("getFieldCount")
    val fieldCount: UInt
        @FastNative external get
    
    fun getName() = this.name
    
    /**
     * Get another reference to the language.
     *
     * @since 0.24.0
     */
    fun copy() = TSLanguage(copy(self))
    
    /** Get the node type for the given numerical ID. */
    @FastNative
    @JvmName("symbolName")
    external fun symbolName(symbol: UShort): String?
    
    /** Get the numerical ID for the given node type. */
    @FastNative
    @JvmName("symbolForName")
    external fun symbolForName(name: String, isNamed: Boolean): UShort
    
    /** Get the symbol type for the given symbol ID. */
    @FastNative
    @JvmName("symbolType")
    external fun symbolType(symbol: UShort): TSSymbolType
    
    /**
     * Check if the node for the given numerical ID is named
     *
     * @see [Node.isNamed]
     */
    @FastNative
    @JvmName("isNamed")
    external fun isNamed(symbol: UShort): Boolean

    /** Check if the node for the given numerical ID is visible. */
    @FastNative
    @JvmName("isVisible")
    external fun isVisible(symbol: UShort): Boolean
    
    /**
     * Check if the node for the given numerical ID is a supertype.
     *
     * @since 0.24.0
     */
    @FastNative
    @JvmName("isSupertype")
    external fun isSupertype(symbol: UShort): Boolean
    
    /** Get the field name for the given numerical id. */
    @FastNative
    @JvmName("fieldNameForId")
    external fun fieldNameForId(id: UShort): String?

    /** Get the numerical ID for the given field name. */
    @FastNative
    @JvmName("fieldIdForName")
    external fun fieldIdForName(name: String): UShort
    
    /**
     * Get the next parse state.
     *
     * Combine this with [lookaheadIterator] to generate
     * completion suggestions or valid symbols in error nodes.
     *
     * #### Example
     *
     * ```kotlin
     * language.nextState(node.parseState, node.grammarSymbol)
     * ```
     */
    @FastNative
    @JvmName("nextState")
    external fun nextState(state: UShort, symbol: UShort): UShort

    /**
     * Create a new [lookahead iterator][LookaheadIterator] for the given parse state.
     *
     * @throws [IllegalArgumentException] If the state is invalid for this language.
     */
    @JvmName("lookaheadIterator")
    @Throws(IllegalArgumentException::class)
    fun lookaheadIterator(state: UShort) = TSLookaheadIterator(this, state)
    
    /**
     * Create a new [Query] from a string containing one or more S-expression
     * [patterns](https://tree-sitter.github.io/tree-sitter/using-parsers#query-syntax).
     *
     * @throws [QueryError] If any error occurred while creating the query.
     */
    @Throws(TSQueryError::class)
    fun query(pattern: String) = TSQuery(this, pattern)
    
    override operator fun equals(other: Any?) =
        this === other || (other is TSLanguage && self == other.self)

    override fun hashCode() = self.hashCode()

    override fun toString() = "TSLanguage(id=0x${self.toString(16)}, version=$version)"
    
    private companion object {
        /** note here requires load the native library */
        init {
            System.loadLibrary("android-tree-sitter")
        }
        
        @JvmStatic
        @CriticalNative
        private external fun copy(language: Long): Long
        
        @JvmStatic
        @FastNative
        private external fun resolve(name: String): Long
    }
}

