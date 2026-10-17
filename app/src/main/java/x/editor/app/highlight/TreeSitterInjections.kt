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

package x.editor.app.highlight

import android.util.Log

import java.io.File

import x.github.module.treesitter.TSLanguage
import x.github.module.treesitter.TSNode
import x.github.module.treesitter.TSParser
import x.github.module.treesitter.TSQuery
import x.github.module.treesitter.TSRange
import x.github.module.treesitter.TSTree

/**
 * Language injections, as Neovim does them: parts of a document in another
 * language, parsed by the grammar of that language and highlighted by its queries.
 * Markdown is two grammars (its blocks, and `markdown_inline` for the text in
 * them) and the code blocks in it are in the language their fence names; HTML has
 * JavaScript and CSS in it, many languages regular expressions and comments.
 *
 * Which parts, in which language, the `injections.scm` of the host grammar says:
 *
 *  - `@injection.content` is the part; without `#set! injection.include-children`,
 *    the nodes inside it are not part of it (the `> ` of a code block in a quote);
 *  - its language is the text of `@injection.language` (the info string of a
 *    fence), or `#set! injection.language "name"`, or `injection.self` (the host's).
 *
 * The language is found by its name, an alias of it (`js`, `sh`, `c++`, ...), or as a
 * file extension (filetypes.json). Each part is parsed on its own, with the parser of
 * its language restricted to its ranges: its tree is in the coordinates of the
 * document. With `#set! injection.combined`, all the parts of a pattern in one
 * language are one region, parsed together (the HTML blocks of Markdown).
 *
 * The parts are found in a window of the tree around what is drawn, once after each
 * edit, and again when the screen moves out of it: the drawn bytes and a margin (a
 * pattern such as that of a code block captures nodes on other lines than the one
 * drawn, the info string on the fence; a query restricted to a range returns the
 * matches that reach into it, whole). With `injection.combined` patterns, whose
 * parts are parsed together, the window is widened to the top-level nodes its ends
 * lie in, so that what is combined does not depend on where the screen is. Not the
 * whole tree: in a large file (every comment and macro of sqlite3.c) that took
 * seconds after every keystroke. Only the parts that are drawn are parsed, and their
 * trees are kept until the document changes.
 *
 * The window is kept small on purpose: dragging the scrollbar thumb through a large
 * file moves the screen out of it on every frame. Widened to the top-level nodes of
 * sqlite3.c (a `#if` block or a function of hundreds of KB), finding the parts again
 * took several times longer than drawing the frame.
 *
 * Simplifications: an injection does not inject again (no Markdown in a code block
 * of Markdown), and `#offset!` is not applied.
 *
 * Main thread, except [preload].
 */
internal class TreeSitterInjections(
    private val queriesDir: () -> File,
    private val libraries: () -> Map<String, File>,
    private val fileTypes: () -> Map<String, String>,
    // the patterns of the query of a kind (`highlights`, `injections`) of a grammar
    private val readPattern: (dir: File, grammar: String, kind: String) -> String?
) {

    /** A language that can be injected: its parser, and its highlights query. */
    class Layer(
        val name: String,
        val parser: TSParser,
        val highlights: TSQuery,
        val priorities: IntArray,
        // the source of [highlights], for what its patterns need (see InjectionPrefilter)
        val source: String
    ) {
        fun close() {
            highlights.close()
            parser.close()
        }
    }

    /** A part of the document in [language]: its [ranges], in order. */
    data class Region(val language: String, val ranges: List<TSRange>) {
        val startByte: UInt get() = ranges.first().startByte
        val endByte: UInt get() = ranges.last().endByte
    }

    // the injections query of the host grammar; null: it injects nothing
    private var query: TSQuery? = null
    private var hostName: String? = null
    // whether a pattern of it combines its parts (`#set! injection.combined`): then
    // the window is widened to whole top-level nodes, see windowFor
    private var hasCombined = false

    // the languages looked for so far, by the name the queries use (null: not there)
    private val layers = HashMap<String, Layer?>()

    // the parts of the tree parsed last in [regionWindow] (bytes), by their start;
    // null until they are looked for
    private var regions: List<Region>? = null
    private var regionWindow: UIntRange = UIntRange.EMPTY

    // the trees of the parts parsed since the document changed last; the oldest
    // that was used goes first when there are too many
    private val trees = object : LinkedHashMap<Region, TSTree>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Region, TSTree>): Boolean {
            if (size <= MAX_TREES) return false
            eldest.value.close()
            return true
        }
    }

    /** The injections of the host grammar [hostName] ([language]); none if it has no injections.scm. */
    @Synchronized
    fun setUp(language: TSLanguage, hostName: String) {
        release()
        this.hostName = hostName
        val pattern = readPattern(queriesDir(), hostName, "injections") ?: return
        val query = try {
            TSQuery(language, pattern)
        } catch (e: Exception) {
            // a query the grammar does not understand: no injections, but no crash
            Log.w(TAG, "cannot use the injections of $hostName", e)
            null
        }
        this.query = query
        hasCombined = query != null && (0 until query.patternCount.toInt()).any { index ->
            query.settings(index.toUInt()).containsKey("injection.combined")
        }
    }

    /** The document changed: its parts are looked for again, and parsed again. */
    @Synchronized
    fun invalidate() {
        regions = null
        regionWindow = UIntRange.EMPTY
        trees.values.forEach { it.close() }
        trees.clear()
    }

    @Synchronized
    fun release() {
        invalidate()
        layers.values.forEach { it?.close() }
        layers.clear()
        query?.close()
        query = null
        hostName = null
        hasCombined = false
    }

    /**
     * Loads the languages injected anywhere in [host] now, before they are drawn.
     * Not on the main thread: loading a grammar and its query takes a while.
     */
    @Synchronized
    fun preload(host: TSTree) {
        // the whole tree, once, in the background: the window of the first drawing
        // is looked at again then
        collect(host, UInt.MIN_VALUE..UInt.MAX_VALUE).mapTo(HashSet()) { it.language }.forEach { layer(it) }
    }

    /**
     * The parts in [host] that lie in the bytes [range], each with its language and
     * its tree; parsed now if they were not since the last change. [read] gives the
     * text from a byte offset of the document (an empty array at its end).
     */
    @Synchronized
    fun treesIn(
        host: TSTree,
        range: UIntRange,
        // whether a region is parsed at all: one whose text cannot give any capture is not
        keep: (Layer, Region) -> Boolean = { _, _ -> true },
        read: (UInt) -> ByteArray
    ): List<Pair<Layer, TSTree>> {
        if (query == null) return emptyList()
        val result = ArrayList<Pair<Layer, TSTree>>()
        for (region in regionsOf(host, range)) {
            // by their start: the rest begins after the range
            if (region.startByte >= range.last) break
            if (region.endByte <= range.first) continue
            if (region.ranges.none { it.endByte > range.first && it.startByte < range.last }) continue
            val layer = layer(region.language) ?: continue
            if (!keep(layer, region)) continue
            val tree = trees[region] ?: parse(layer, region, host, read)?.also { trees[region] = it }
            if (tree != null) result += layer to tree
        }
        return result
    }

    /**
     * The bytes of the injected parts of [host] whose patterns reach into any of
     * [ranges], whole: the code block a fence belongs to, a comment, a script. After
     * an edit their trees are parsed again from scratch, so everything in them may be
     * styled otherwise, not only what changed in the host.
     */
    @Synchronized
    fun contentsTouching(host: TSTree, ranges: List<UIntRange>): List<UIntRange> {
        val query = query ?: return emptyList()
        if (ranges.isEmpty()) return emptyList()
        // overlapping ranges once
        val merged = ArrayList<UIntRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        // Changed ranges that large (a comment opened above the rest of a file) are
        // styled again whole anyway; looking for every injected part in them would
        // be the slow whole-tree search this window was made to avoid.
        var total = 0UL
        for (range in merged) total += (range.last - range.first).toULong()
        if (total > MAX_TOUCHED_BYTES) return emptyList()

        val result = ArrayList<UIntRange>()
        for (range in merged) {
            val cursor = query(host.rootNode)
            try {
                cursor.byteRange = range
                for (match in cursor.matches { match -> QueryPredicates.evaluate(this, match) }) {
                    for (node in match["injection.content"].ifEmpty { match["content"] }) {
                        result += node.startByte..node.endByte
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot look for the injected parts of an edit", e)
            } finally {
                cursor.close()
            }
        }
        return result
    }

    // The parts of [host] that reach into [range]: those found for a window around it,
    // looked for again when the range is not inside the window any more.
    private fun regionsOf(host: TSTree, range: UIntRange): List<Region> {
        val known = regions
        if (known != null && range.first >= regionWindow.first && range.last <= regionWindow.last) return known
        val window = windowFor(host, range)
        return collect(host, window).sortedBy { it.startByte }.also {
            regions = it
            regionWindow = window
        }
    }

    // The window to look for parts in around [range]. The matches that reach into it
    // come whole (a query restricted to a range returns them so), which is all separate
    // parts need: the range itself. With combined parts, the range and a margin (so that
    // scrolling a little does not look again); combined parts are parsed together, so the window is then widened to
    // the top-level nodes its ends lie in, for what is combined not to change with
    // every step of scrolling; not wider than MAX_WINDOW_BYTES beyond the margin: a
    // document that is one top-level node (the sections of Markdown) is looked at
    // around the range only.
    private fun windowFor(host: TSTree, range: UIntRange): UIntRange {
        // Without combined parts, each part is parsed alone: the parts that reach into the
        // range are the same whatever the window, and the query is not run over a margin
        // around it. Its cost goes with the text it looks at: the margin made it look at
        // five times the text of a block, for every block a fast scroll brought.
        if (!hasCombined) return range
        val first = if (range.first > WINDOW_MARGIN_BYTES) range.first - WINDOW_MARGIN_BYTES else 0U
        val last = if (range.last < UInt.MAX_VALUE - WINDOW_MARGIN_BYTES) range.last + WINDOW_MARGIN_BYTES else UInt.MAX_VALUE
        val root = host.rootNode
        var start = first
        root.firstChildForByte(first)?.let { node ->
            if (node.startByte < first && first - node.startByte <= MAX_WINDOW_BYTES) start = node.startByte
        }
        var end = last
        root.firstChildForByte(last)?.let { node ->
            if (node.startByte <= last && node.endByte > last && node.endByte - last <= MAX_WINDOW_BYTES) end = node.endByte
        }
        return start..end
    }

    // The parts of [host] in the bytes [window], as the injections query says. The
    // parts of a pattern with injection.combined are one part per language, with all
    // their ranges.
    private fun collect(host: TSTree, window: UIntRange): List<Region> {
        val query = query ?: return emptyList()
        val separate = ArrayList<Region>()
        val combined = LinkedHashMap<Pair<UInt, String>, MutableList<TSRange>>()
        val cursor = query(host.rootNode)
        try {
            if (window.first != UInt.MIN_VALUE || window.last != UInt.MAX_VALUE) {
                cursor.byteRange = window
                // The text of the window, for the predicates to be decided natively
                // (TSQueryCursor.setText): without it every match came to Kotlin, every
                // macro body and comment of the window (three patterns for each), and
                // their predicates read the text back through the tree. That was most of
                // the time a block of a large C file took to be highlighted.
                val start = window.first / 2U
                val text = host.textReader?.read(start.toInt(), (window.last / 2U).coerceAtMost(Int.MAX_VALUE.toUInt()).toInt())
                if (text != null) cursor.setText(start * 2U, text.toString())
            }
            for (match in cursor.matches { match -> QueryPredicates.evaluate(this, match) }) {
                val settings = query.settings(match.patternIndex)
                val language = when {
                    settings.containsKey("injection.self") -> hostName
                    else -> settings["injection.language"]
                        ?: (match["injection.language"].firstOrNull() ?: match["language"].firstOrNull())
                            ?.text()?.toString()
                }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                val includeChildren = settings.containsKey("injection.include-children")
                val contents = match["injection.content"].ifEmpty { match["content"] }
                for (node in contents) {
                    val ranges = rangesOf(node, includeChildren)
                    if (ranges.isEmpty()) continue
                    if (settings.containsKey("injection.combined")) {
                        combined.getOrPut(match.patternIndex to language) { ArrayList() } += ranges
                    } else {
                        separate += Region(language, ranges)
                    }
                }
            }
        } finally {
            cursor.close()
        }
        // the ranges of a combined part in order, and without overlaps (included
        // ranges have to be)
        for ((key, ranges) in combined) {
            val ordered = ArrayList<TSRange>()
            for (range in ranges.sortedBy { it.startByte }) {
                val last = ordered.lastOrNull()
                if (last == null || range.startByte >= last.endByte) ordered += range
            }
            separate += Region(key.second, ordered)
        }
        return separate
    }

    // The ranges of [node]: all of it, or what lies between its named children.
    //
    // Only the named ones, as Neovim (and tree-sitter-highlight) do: the anonymous
    // children are tokens of the host grammar, and for Markdown they are every
    // punctuation character in a code block, an inline or a table cell (`(`, `.`,
    // `"`, `/`, `` ` ``, ...). Leaving those out fed the injected parser the text
    // without its punctuation: `CharArray(64 * 1024)` came out as `CharArray64 1024`,
    // `// comment` lost its `//`, and no code span could start without its backtick.
    private fun rangesOf(node: TSNode, includeChildren: Boolean): List<TSRange> {
        if (node.startByte >= node.endByte) return emptyList()
        if (includeChildren) return listOf(TSRange(node.startPoint, node.endPoint, node.startByte, node.endByte))
        val ranges = ArrayList<TSRange>()
        var startPoint = node.startPoint
        var startByte = node.startByte
        val count = node.namedChildCount
        var index = 0U
        while (index < count) {
            val child = node.namedChild(index++) ?: continue
            if (child.startByte > startByte) {
                ranges += TSRange(startPoint, child.startPoint, startByte, child.startByte)
            }
            if (child.endByte > startByte) {
                startPoint = child.endPoint
                startByte = child.endByte
            }
        }
        if (node.endByte > startByte) {
            ranges += TSRange(startPoint, node.endPoint, startByte, node.endByte)
        }
        return ranges
    }

    private fun parse(layer: Layer, region: Region, host: TSTree, read: (UInt) -> ByteArray): TSTree? =
        try {
            layer.parser.includedRanges = region.ranges
            layer.parser.parse(null) { byteIndex, _ -> read(byteIndex) }.also {
                // its predicates read the text of the document, as those of the host
                it.textReader = host.textReader
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot parse a part in ${region.language}", e)
            null
        }

    // #region languages

    private fun layer(name: String): Layer? {
        if (layers.containsKey(name)) return layers[name]
        val layer = loadLayer(name)
        layers[name] = layer
        if (layer == null) Log.i(TAG, "no grammar for the injected language $name")
        return layer
    }

    private fun loadLayer(name: String): Layer? {
        val libraries = libraries()
        for (symbol in symbolsFor(name)) {
            val library = TreeSitterLookup.findLibrary(symbol, libraries) ?: continue
            var parser: TSParser? = null
            try {
                val language = TSLanguage(library.path, symbol)
                val dir = queriesDir()
                val grammar = TreeSitterLookup.findQueryName(dir, TreeSitterLookup.queryNames(language.name, symbol))
                    ?: continue
                val pattern = readPattern(dir, grammar, "highlights") ?: continue
                parser = TSParser(language)
                val highlights = TSQuery(language, pattern)
                val priorities = IntArray(highlights.patternCount.toInt()) { index ->
                    highlights.settings(index.toUInt())["priority"]?.toIntOrNull() ?: DEFAULT_PRIORITY
                }
                return Layer(grammar, parser, highlights, priorities, pattern)
            } catch (e: Exception) {
                parser?.close()
                Log.w(TAG, "cannot load the injected language $name ($symbol)", e)
            }
        }
        return null
    }

    // The symbols a language name may stand for: the name itself, its alias, the
    // grammar of the file extension it is (`kt`, `py`).
    private fun symbolsFor(name: String): List<String> {
        val types = fileTypes()
        return listOfNotNull(
            ALIASES[name]?.let { TreeSitterLookup.symbolOf(it) },
            TreeSitterLookup.symbolOf(name),
            TreeSitterLookup.symbolFor("x.$name", types)
        ).distinct()
    }

    // #endregion

    private companion object {
        const val TAG = "TreeSitterInjections"
        const val DEFAULT_PRIORITY = 100

        // the trees of parts kept at once (those on the screen, and a few more)
        const val MAX_TREES = 128

        // the margin around what is drawn that parts are looked for in, and how far
        // beyond it a top-level node may reach and still be taken whole (bytes, two a
        // char). The margin is some 100 lines of code: scrolling that far looks again,
        // which costs little; a larger one costs every frame of a fast scroll.
        const val WINDOW_MARGIN_BYTES = 8_192U
        const val MAX_WINDOW_BYTES = 1_048_576U

        // how much changed text the injected parts are looked for in, see contentsTouching
        const val MAX_TOUCHED_BYTES = 524_288UL

        // names in code fences and injections, and the grammar each stands for
        val ALIASES = mapOf(
            "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "node" to "javascript",
            "ts" to "typescript", "mts" to "typescript",
            "py" to "python", "python3" to "python", "py3" to "python",
            "sh" to "bash", "shell" to "bash", "zsh" to "bash", "console" to "bash", "shellscript" to "bash",
            "c++" to "cpp", "cxx" to "cpp", "cc" to "cpp", "hpp" to "cpp", "h" to "c",
            "cs" to "c_sharp", "csharp" to "c_sharp", "c#" to "c_sharp",
            "rb" to "ruby", "rs" to "rust", "kt" to "kotlin", "kts" to "kotlin",
            "golang" to "go", "yml" to "yaml", "md" to "markdown",
            "svg" to "xml", "xsd" to "xml", "plist" to "xml", "jsonc" to "json",
            "makefile" to "make", "mk" to "make", "gradle" to "groovy",
            "objective-c" to "objc", "objectivec" to "objc", "viml" to "vim",
            "tex" to "latex", "patch" to "diff", "hs" to "haskell", "ex" to "elixir", "exs" to "elixir",
            "clj" to "clojure", "pl" to "perl", "ps1" to "powershell", "dockerfile" to "dockerfile"
        )
    }
}
