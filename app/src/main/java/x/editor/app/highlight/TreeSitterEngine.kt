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

package x.editor.app.highlight

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.MainThread

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import java.io.File
import java.util.concurrent.Executors

import x.editor.app.treesitter.TreeSitterResources
import x.editor.app.util.DeviceUtils
import x.editor.app.util.JsonUtils
import x.github.module.piecetable.PieceTreeSnapshot
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.common.Range

import x.github.module.treesitter.TSInputEdit
import x.github.module.treesitter.TSLanguage
import x.github.module.treesitter.TSNode
import x.github.module.treesitter.TSParser
import x.github.module.treesitter.TSPoint
import x.github.module.treesitter.TSQuery
import x.github.module.treesitter.TSTree

/**
 * The tree-sitter model class is used to implement syntax highlighting
 * indentation, folding, and other functions
 *
 * How it is used:
 *  1. [loadResources] once (and again after a download): the grammar libraries, the map of
 *     file types and the highlight styles.
 *  2. [prepare] for each document: picks the grammar for its name and parses the
 *     text. Without a grammar for the file, highlighting stays off ([isEnabled]).
 *  3. While the document is open: [applyEdit] for every change, [reparse] when an edit is
 *     complete (it parses in the background, see [onTreeChanged]), [highlightLine] for
 *     every line that is drawn, and [captures] / [isCodeAt] for the structure of the text
 *     (folds, indented blocks, strings and comments around brackets).
 *  4. [release] before the next document, and at the end.
 *
 * Threads: [loadResources] and [prepare] may run on a background thread. They publish what
 * they produced when it is complete, and [prepare] turns highlighting on as its last
 * step. [applyEdit], [reparse], [highlightLine] and [release] belong to the main thread, and
 * must not run while [prepare] does.
 *
 * Parsing again after an edit happens on a thread of its own, from a snapshot of the
 * text and a copy of the tree, so that typing never waits for it: a large file takes
 * a while to parse even incrementally. Meanwhile the tree in place, told about the
 * edits ([applyEdit]), is drawn from; the new one replaces it when it is ready, told
 * about the edits made in the meantime too.
 *
 * Native memory: the parser, the query, the tree and every query cursor hold
 * memory that the garbage collector does not see. They are closed here as soon
 * as they are replaced or no longer needed; nothing is left to a finalizer.
 *
 * @param context the current context
 */
class TreeSitterEngine(private val context: Context) {
    
    // the tree sitter parser
    private var parser: TSParser? = null
    // the tree sitter query
    private var query: TSQuery? = null
    // the queries of folds.scm and indents.scm; null when the grammar has none, or
    // one the grammar does not understand
    // (asked about the lines on screen without walking the whole file, see StructureQuery)
    private var foldsQuery: StructureQuery? = null
    private var indentsQuery: StructureQuery? = null
    // the code blocks of the text (Markdown's), see isInCodeBlock; null when the
    // grammar has none
    private var codeBlockQuery: TSQuery? = null
    // their lines, worked out from the tree when first asked for: each block a
    // first and a last line (1-based) in one Long, in the order of the text
    private var codeBlockLines: LongArray? = null
    // the tree sitter tree
    private var tree: TSTree? = null
    
    // map the file name and file extension to the tree-sitter grammar
    // (replaced as a whole, never changed: it is read while it is loaded again)
    @Volatile
    private var grammarSymbols: Map<String, String> = emptyMap()
    
    // the highlight theme: the style of each capture name of a query (the scope)
    @Volatile
    private var theme: HighlightTheme? = null

    // the name of the grammar of the text (like "rust"): a theme can style the
    // captures of one language apart ("type.rust")
    @Volatile
    private var languageName: String? = null

    /**
     * Whether colors written in hex (`#D4D4D4`) are shown in their own color, over the
     * highlighting (see HexColors). The editor has to style the text again after a
     * change.
     */
    @Volatile
    var colorPreview: Boolean = true

    // the priority of each pattern of the highlights query (`#set! priority`); see
    // highlightLine
    @Volatile
    private var patternPriorities: IntArray = IntArray(0)

    private fun prioritiesOf(query: TSQuery): IntArray =
        IntArray(query.patternCount.toInt()) { index ->
            query.settings(index.toUInt())["priority"]?.toIntOrNull() ?: DEFAULT_PRIORITY
        }

    // the parts of the text in other languages (Markdown in Markdown, code in code
    // blocks, JavaScript in HTML, ...), see TreeSitterInjections
    private val injections = TreeSitterInjections(
        queriesDir = { TreeSitterResources.queriesDir(context) },
        libraries = { libraryFiles },
        fileTypes = { grammarSymbols },
        readPattern = { dir, grammar, kind -> readQueryPattern(dir, grammar, kind) }
    )

    // the buffer the tree was parsed from last: the injected parts read their text from it
    @Volatile
    private var parsedBuffer: PieceTreeTextBuffer? = null

    // the grammar libraries found when the resources were loaded, by file name
    @Volatile
    private var libraryFiles: Map<String, File> = emptyMap()
    
    /**
     * Whether there is a syntax tree to highlight with. Can only be turned on
     * while there is one: between a successful [prepare] and the next [release].
     */
    @Volatile
    public var isEnabled: Boolean = false
        set(value) {
            field = value && tree != null
        }
    
    /**
     * Initialize the tree sitter for current programming language
     *
     * Whatever was there for another document is released first. If the file has
     * no grammar, or its grammar or query cannot be loaded, highlighting stays
     * off; that is logged, not thrown.
     *     
     * @param fileName the name of document file 
     * @param textBuffer contents of the text editor
     */
    fun prepare(fileName: String, textBuffer: PieceTreeTextBuffer) {
        release()

        // the symbol name `tree_sitter_c`, `tree_sitter_cpp` etc (see TreeSitterLookup
        // for the variants each step takes); each step that fails says so in the log
        val symbol = findGrammarSymbol(fileName) ?: run {
            Log.i(TAG, "$fileName: no file type in filetypes.json (${grammarSymbols.size} entries)")
            return
        }
        val library = TreeSitterLookup.findLibrary(symbol, libraryFiles) ?: run {
            Log.w(
                TAG,
                "$fileName: no library for $symbol among ${libraryFiles.size} in ${grammarDir().path}, " +
                    "tried ${TreeSitterLookup.libraryNames(symbol)}"
            )
            return
        }

        var newParser: TSParser? = null
        var newQuery: TSQuery? = null
        var newFoldsQuery: StructureQuery? = null
        var newIndentsQuery: StructureQuery? = null
        try {
            val language = TSLanguage(library.path, symbol)
            // the query files directory: files/treesitter/queries; the folder of the
            // grammar is named as the grammar names itself, or as its symbol says
            val queryDir = TreeSitterResources.queriesDir(context)
            val names = TreeSitterLookup.queryNames(language.name, symbol)
            val grammarName = TreeSitterLookup.findQueryName(queryDir, names) ?: run {
                Log.w(TAG, "$fileName: no highlights.scm in ${queryDir.path} for any of $names")
                return
            }
            val pattern = readQueryPattern(queryDir, grammarName, QUERY_HIGHLIGHTS) ?: run {
                Log.w(TAG, "$fileName: the queries of $grammarName are empty")
                return
            }

            newParser = TSParser(language)
            newQuery = TSQuery(language, pattern)
            // not needed for highlighting: without them, the structure of the text
            // is worked out from its indentation (see TreeSitterStructure)
            newFoldsQuery = loadStructureQuery(language, queryDir, grammarName, QUERY_FOLDS, FOLD_CAPTURES)
            newIndentsQuery = loadStructureQuery(language, queryDir, grammarName, QUERY_INDENTS, INDENT_CAPTURES)
            this.codeBlockQuery = loadCodeBlockQuery(language)
            // initialize the tree-sitter
            this.parser = newParser
            this.query = newQuery
            this.foldsQuery = newFoldsQuery
            this.indentsQuery = newIndentsQuery
            this.languageName = grammarName
            this.patternPriorities = prioritiesOf(newQuery)
            injections.setUp(language, grammarName)
            // first time parse the oldTree is null
            parse(null, textBuffer)
            // the languages injected in it, loaded now rather than when first drawn
            this.tree?.let { injections.preload(it) }
            // now enable the tree-sitter
            this.isEnabled = true
        } catch (e: IllegalArgumentException) {
            // A library that is not a grammar (or one of another ABI version), or a
            // query the grammar does not understand (TSQueryError). Both come from
            // downloaded files: no highlighting, but no crash either.
            Log.e(TAG, "cannot set up tree-sitter for $fileName", e)
            this.parser = null
            this.query = null
            this.foldsQuery = null
            this.indentsQuery = null
            codeBlockQuery?.close()
            codeBlockQuery = null
            injections.release()
            newIndentsQuery?.close()
            newFoldsQuery?.close()
            newQuery?.close()
            newParser?.close()
        }
    }

    // The query of a kind that is not needed for highlighting. One that is missing
    // or that the grammar does not understand is logged and left out.
    // [usedCaptures]: the captures TreeSitterStructure reads; the patterns without any
    // are left out (see StructureQuery).
    private fun loadStructureQuery(
        language: TSLanguage, dir: File, grammarName: String, kind: String, usedCaptures: Set<String>
    ): StructureQuery? {
        val pattern = readQueryPattern(dir, grammarName, kind) ?: return null
        return try {
            StructureQuery.create(language, pattern, usedCaptures)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot use $kind.scm of $grammarName", e)
            null
        }
    }
    
    /**
     * Release the memory allocated by the native layer
     */ 
    fun release() {
        // now disable the tree-sitter
        this.isEnabled = false
        // a parse under way belongs to what goes now: its result is thrown away
        generation++
        // what is asked for of blocks is of no use any more
        synchronized(requests) {
            for (request in requests) releaseSnapshotLocked(request.snapshot)
            requests.clear()
            latestSnapshot?.let { releaseSnapshotLocked(it) }
            latestSnapshot = null
        }
        // and a block being found waits for nothing that is closed now
        synchronized(parseLock) { synchronized(highlightLock) { releaseLocked() } }
    }

    // release, while no parse can be running
    private fun releaseLocked() {
        // free up the memory
        query?.close()
        query = null

        foldsQuery?.close()
        foldsQuery = null

        indentsQuery?.close()
        indentsQuery = null

        codeBlockQuery?.close()
        codeBlockQuery = null
        codeBlockLines = null

        tree?.close()
        tree = null
        codeLookupTop = null
        codeLookupTree = null

        parser?.close()
        parser = null

        injections.release()
        injectionsKey = null
        prefilters.clear()
        parsedBuffer = null
        store.clear()
        editLog.clear()
        structureResults.clear()
        structurePending.clear()
    }
    
    // #region parsing

    // Held while a parser parses, and while the parser is replaced or closed: the
    // parses after edits run on PARSE_EXECUTOR, prepare on a thread of the caller's,
    // release on any.
    private val parseLock = Any()

    // Goes up with every release: a parse started before belongs to a parser, a tree
    // and a text that are gone, and what it produces is thrown away.
    @Volatile
    private var generation = 0

    // Main thread: the generation a parse after an edit is running for (another number
    // while none is), whether the text was edited again meanwhile, and the edits made
    // meanwhile, which the new tree is told about so that it matches the text of now.
    private var parsingGeneration = -1
    private var reparseAgain = false
    private val editsDuringParse = ArrayList<TSInputEdit>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called on the main thread when a parse after an edit has put a new tree in
     * place, with the parts of the text whose styles may have changed (chars, see
     * worked out on the parse thread): those are to be styled again, the rest is as it was drawn. The
     * structure of the text is to be worked out again.
     */
    @Volatile
    var onTreeChanged: ((List<IntRange>) -> Unit)? = null

    // The bytes the edits since the tree in place was parsed touched, in the
    // coordinates of the text of now, as one span (start, end; empty while there are
    // none): what is styled again besides tree-sitter's changed ranges, which leave
    // out an edit that kept the structure (a letter typed into a name) although a
    // predicate may style the new text otherwise (ALL_CAPS as a constant).
    private var editedStart = UInt.MAX_VALUE
    private var editedEnd = 0U

    /**
     * Parses the text again after an edit, in the background. Until the new tree is
     * there, the old one, told about the edits by [applyEdit], is drawn from; then
     * [onTreeChanged] is called. An edit made while a parse is running has another one
     * follow it, from the new tree.
     *
     * @param textBuffer the text, as it is after the edit
     */
    @MainThread
    fun reparse(textBuffer: PieceTreeTextBuffer) {
        val tree = this.tree ?: return
        if (parsingGeneration == generation) {
            // after the one that is running
            reparseAgain = true
            return
        }
        val parseGeneration = generation
        parsingGeneration = parseGeneration
        reparseAgain = false
        editsDuringParse.clear()
        parsedBuffer = textBuffer

        // What the parse works on, fixed now: the text as it is (a snapshot costs a
        // walk over the pieces, no copy of the text) and a copy of the tree (shares
        // its nodes; trees must not be used on two threads at once).
        val snapshot = textBuffer.createSnapshot()
        val oldTree = tree.copy()
        // the span the edits so far touched, styled again whatever the parse says
        val editedSpan = if (editedStart <= editedEnd) editedStart..editedEnd else null
        PARSE_EXECUTOR.execute {
            var newTree: TSTree? = null
            var changedBytes: List<UIntRange>? = null
            var structureRoots: List<Pair<StructureQuery, StructureQuery.NodeCaptures>> = emptyList()
            try {
                synchronized(parseLock) {
                    val parser = this.parser
                    if (parseGeneration == generation && parser != null) {
                        val parsed = parser.parse(oldTree) { byteIndex, _ -> readSnapshot(snapshot, byteIndex) }
                        newTree = parsed
                        // The structure at the root (all the children at the top of the
                        // file are walked through, see StructureQuery), here rather than
                        // on the main thread at the next frame. The tree is not used
                        // anywhere else yet; the queries are not closed while the lock is
                        // held. Its predicates read the text it was parsed from.
                        parsed.textReader = TSTree.TextReader { start, end ->
                            val from = start.coerceIn(0, snapshot.length)
                            snapshot.subSequence(from, end.coerceIn(from, snapshot.length))
                        }
                        structureRoots = listOfNotNull(foldsQuery, indentsQuery).mapNotNull { structure ->
                            structure.rootCaptures(parsed)?.let { structure to it }
                        }
                        // What to style again, here rather than on the main thread: comparing
                        // the trees of a large file walks much of both (sqlite3.c: 25 ms on a
                        // desktop), and so does looking for the injected parts in what changed.
                        changedBytes = try {
                            val bytes = ArrayList<UIntRange>()
                            for (range in oldTree.changedRanges(parsed)) bytes += range.startByte..range.endByte
                            if (editedSpan != null) bytes += editedSpan
                            bytes += injections.contentsTouching(parsed, bytes)
                            bytes
                        } catch (e: Exception) {
                            Log.w(TAG, "cannot compare the trees", e)
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cannot parse the text again", e)
            } finally {
                oldTree.close()
            }
            val result = newTree
            val roots = structureRoots
            val changed = changedBytes
            mainHandler.post { onReparsed(parseGeneration, result, textBuffer, roots, changed) }
        }
    }

    // A parse after an edit is done: its tree replaces the one in place.
    @MainThread
    private fun onReparsed(
        parseGeneration: Int, newTree: TSTree?, textBuffer: PieceTreeTextBuffer,
        structureRoots: List<Pair<StructureQuery, StructureQuery.NodeCaptures>> = emptyList(),
        changedBytes: List<UIntRange>? = null
    ) {
        if (parseGeneration != generation) {
            // released meanwhile, or set up for another text
            newTree?.close()
            return
        }
        parsingGeneration = -1
        if (newTree == null) {
            // it failed (logged): the old tree stays, and is parsed again with the next edit;
            // the blocks waiting for it are found with it meanwhile
            store.stopAwaitingParse()
            editsDuringParse.clear()
            reparseAgain = false
            return
        }
        // the edits made while it was parsing
        val editsMeanwhile = editsDuringParse.map { edit ->
            StructureQuery.CharEdit(
                (edit.startByte / 2U).toInt(), (edit.oldEndByte / 2U).toInt(), (edit.newEndByte / 2U).toInt()
            )
        }
        for (edit in editsDuringParse) newTree.edit(edit)
        editsDuringParse.clear()
        // What to style again: worked out on the parse thread, in the text it parsed;
        // moved along with the edits made meanwhile, whose own span is styled again too.
        val changed = ArrayList<IntRange>()
        if (changedBytes == null) {
            changed += 0..Int.MAX_VALUE
        } else {
            for (range in changedBytes) {
                var from = (range.first / 2U).toInt()
                var to = (range.last / 2U).toInt()
                for (edit in editsMeanwhile) {
                    val delta = edit.newEnd - edit.oldEnd
                    from = CaptureStore.movedStart(from, edit.start, edit.oldEnd, delta)
                    to = CaptureStore.movedEnd(to, edit.start, edit.oldEnd, edit.newEnd, delta)
                }
                changed += from..maxOf(from, to)
            }
        }
        for (edit in editsMeanwhile) changed += edit.start..edit.newEnd
        install(newTree, textBuffer)
        // the edits made meanwhile are not parsed yet: styled again after the next parse too
        for (edit in editsMeanwhile) {
            editedStart = minOf(editedStart, edit.start.toUInt() * 2U)
            editedEnd = maxOf(editedEnd, edit.newEnd.toUInt() * 2U)
        }
        // the blocks of what changed are found again, with the new tree; drawn as they
        // are until then (see CaptureStore)
        for (range in changed) store.markStale(range.first, range.last)
        // The structure at the root, worked out with the parse, moved along with the
        // edits made meanwhile (as the tree was): while typing goes on, a parse is
        // mostly overtaken by an edit, and working the root out again on the main
        // thread would stall the next frame. The parse that follows (reparseAgain) puts
        // the root of the text of now in place.
        val lineage = treeGen
        for ((structure, roots) in structureRoots) {
            // (a query of what was released meanwhile is not in place any more)
            if (structure === foldsQuery || structure === indentsQuery) {
                HIGHLIGHT_EXECUTOR.execute {
                    synchronized(highlightLock) { structure.seed(roots, editsMeanwhile, lineage) }
                }
            }
        }
        onTreeChanged?.invoke(changed)
        if (reparseAgain) {
            reparseAgain = false
            reparse(textBuffer)
        }
    }

    /**
     * Parses the whole text, now, on the calling thread, and puts the tree in place
     * (closing the one that was). Used by [prepare]; after edits, see [reparse].
     *
     * @param oldTree old TSTree this can be null for the first initialization of TSTree
     * @param textBuffer contents of the text editor
     * @return the new TSTree
     * @throws IllegalStateException if there is no parser ([prepare] did not succeed)
     */
    fun parse(oldTree: TSTree?, textBuffer: PieceTreeTextBuffer): TSTree {
        val newTree = synchronized(parseLock) {
            val parser = checkNotNull(this.parser) { "TreeSitterEngine.parse() without a parser: call prepare() first" }
            val length = textBuffer.length
            // The parser asks for the text where it needs it, and is answered from the
            // piece tree directly: the rest of the piece the offset lies in, at most
            // PARSE_CHUNK_MAX_CHARS of it. Nothing copies the whole text.
            parser.parse(oldTree) { byteIndex, _ ->
                // UTF-16: two bytes a char
                val offset = (byteIndex / 2U).toInt()
                if (offset >= length) {
                    // the end of the document
                    NO_BYTES
                } else {
                    utf16le(readChunk(offset, length, PARSE_CHUNK_MAX_CHARS, textBuffer::getNearestChunk) { textBuffer[it] })
                }
            }
        }
        install(newTree, textBuffer)
        return newTree
    }

    // [newTree], of the text of [textBuffer], becomes the tree in place.
    private fun install(newTree: TSTree, textBuffer: PieceTreeTextBuffer) {
        // it is of the text with every edit so far
        editedStart = UInt.MAX_VALUE
        editedEnd = 0U
        // The tree keeps no copy of the text: the predicates of the queries (and
        // TSNode.text) read it from the buffer, which the tree stays in step with
        // (every edit is reported to it, see applyEdit).
        newTree.textReader = TSTree.TextReader { start, end ->
            val size = textBuffer.length
            val from = start.coerceIn(0, size)
            textBuffer.subSequence(from, end.coerceIn(from, size))
        }

        // The injected parts are parsed again, and the blocks of what changed found
        // again, with the new tree, in the background (see findBlock). The blocks of
        // what did not change are as they were: the caller marks the others stale.
        parsedBuffer = textBuffer
        treeGen++
        // the code blocks are looked for again when they are drawn
        codeBlockLines = null

        val previousTree = this.tree
        this.tree = newTree
        // Its nodes that are still in use are shared with the new tree and stay
        // alive; the rest is of no use any more. Freeing that is not quick (sqlite3.c:
        // 15 ms on a desktop): on the parse thread, which is done with it.
        if (previousTree !== newTree && previousTree != null) {
            PARSE_EXECUTOR.execute { previousTree.close() }
        }
    }

    // The text from the byte [byteIndex] on, for a parse after an edit: from the
    // snapshot, in smaller chunks than a full parse reads (it reads a little here
    // and there, around the edits).
    private fun readSnapshot(snapshot: PieceTreeSnapshot, byteIndex: UInt): ByteArray {
        val offset = (byteIndex / 2U).toInt()
        val length = snapshot.length
        if (offset >= length) return NO_BYTES
        return utf16le(readChunk(offset, length, REPARSE_CHUNK_MAX_CHARS, snapshot::getNearestChunk) { snapshot[it] })
    }

    // #endregion

    /**
     * Query text to find specific patterns in source code
     * tree-sitter provides a simple pattern-matching language for this purpose
     *
     * @param text the current line of text in the editor
     * @param lineStart the starting index of the current line of text in textBuffer
     * @param startOffset the starting index position of the query in the current line of text
     * @param endOffset the ending index position of the query in the current line of text
     * @return the line with its spans; without spans if there is no syntax tree
     */
    @MainThread
    fun highlightLine(text: String, lineStart: Int, startOffset: Int, endOffset: Int): SpannableString {
        val spannable = SpannableString(text)
        if (this.query == null || this.tree == null || this.theme == null) return spannable
        val language = languageName

        val length = endOffset - startOffset
        if (length <= 0) return spannable

        // As Neovim lays highlights over each other: the captures are taken in the order
        // of the text (as its iter_captures does), each laid over what is there with the
        // priority of its pattern (100, or `#set! priority`). On a character, a capture
        // takes the place of one of lower or equal priority: of equal ones the later, so
        // a node inside another wins over it, and of the captures of one node the last
        // pattern. An attribute a capture does not set comes from those below it: an
        // italic comment stays italic under a capture that only colors.
        val foreground = IntArray(length)
        val foregroundRank = LongArray(length) { -1L }
        val background = IntArray(length)
        val backgroundRank = LongArray(length) { -1L }
        val boldRank = LongArray(length) { -1L }
        val italicRank = LongArray(length) { -1L }
        val underlineRank = LongArray(length) { -1L }
        val strikethroughRank = LongArray(length) { -1L }

        // The captures come from the blocks the row lies in (see CaptureBlock): one
        // query for every BLOCK_CHARS chars, kept, rather than one for every row. A
        // block holds its captures cut to its own chars, in the order of its query, so
        // that on each char only the captures of one block are compared, in their order.
        val rowStart = lineStart + startOffset
        val rowEnd = lineStart + endOffset
        // what no block has yet is asked for, in the background (see stylesReady); a
        // stale block is drawn from meanwhile
        if (!store.isCovered(rowStart, rowEnd)) requestBlocks(rowStart, rowEnd)
        for (block in store.blocksIn(rowStart, rowEnd)) {
            val starts = block.starts
            val ends = block.ends
            val ranks = block.ranks
            val styles = block.styles
            // a block that was cut keeps its captures: only those in its chars count
            val blockStart = maxOf(block.start, rowStart)
            val blockEnd = minOf(block.end, rowEnd)
            for (j in 0 until block.count) {
                val from = maxOf(starts[j], blockStart)
                val to = minOf(ends[j], blockEnd)
                if (to <= from) continue
                val rank = ranks[j]
                val style = styles[j]
                val color = style.foreground
                val fill = style.background
                for (i in (from - rowStart) until (to - rowStart)) {
                    // (a later capture has a higher rank than an earlier one of equal priority)
                    if (color != null && rank >= foregroundRank[i]) {
                        foreground[i] = color
                        foregroundRank[i] = rank
                    }
                    if (fill != null && rank >= backgroundRank[i]) {
                        background[i] = fill
                        backgroundRank[i] = rank
                    }
                    if (style.bold && rank >= boldRank[i]) boldRank[i] = rank
                    if (style.italic && rank >= italicRank[i]) italicRank[i] = rank
                    if (style.underline && rank >= underlineRank[i]) underlineRank[i] = rank
                    if (style.strikethrough && rank >= strikethroughRank[i]) strikethroughRank[i] = rank
                }
            }
        }

        // Colors written in hex, in their own color, over everything else: the text on
        // a background of the color, black or white to be read on it.
        if (colorPreview) {
            for (match in HexColors.find(text, startOffset, endOffset, HexColors.isAlphaLast(language))) {
                val textColor = match.textColor
                val from = maxOf(match.start, startOffset) - startOffset
                val to = minOf(match.end, endOffset) - startOffset
                for (i in from until to) {
                    foreground[i] = textColor
                    foregroundRank[i] = Long.MAX_VALUE
                    background[i] = match.color
                    backgroundRank[i] = Long.MAX_VALUE
                }
            }
        }

        // the spans: one set for each run of characters styled alike
        fun sameStyle(a: Int, b: Int): Boolean =
            (foregroundRank[a] >= 0) == (foregroundRank[b] >= 0) &&
                (foregroundRank[a] < 0 || foreground[a] == foreground[b]) &&
                (backgroundRank[a] >= 0) == (backgroundRank[b] >= 0) &&
                (backgroundRank[a] < 0 || background[a] == background[b]) &&
                (boldRank[a] >= 0) == (boldRank[b] >= 0) &&
                (italicRank[a] >= 0) == (italicRank[b] >= 0) &&
                (underlineRank[a] >= 0) == (underlineRank[b] >= 0) &&
                (strikethroughRank[a] >= 0) == (strikethroughRank[b] >= 0)

        var runStart = 0
        while (runStart < length) {
            var runEnd = runStart + 1
            while (runEnd < length && sameStyle(runStart, runEnd)) runEnd++
            val style = HighlightStyle(
                foreground = if (foregroundRank[runStart] >= 0) foreground[runStart] else null,
                background = if (backgroundRank[runStart] >= 0) background[runStart] else null,
                bold = boldRank[runStart] >= 0,
                italic = italicRank[runStart] >= 0,
                strikethrough = strikethroughRank[runStart] >= 0,
                underline = underlineRank[runStart] >= 0
            )
            applyStyle(spannable, style, startOffset + runStart, startOffset + runEnd)
            runStart = runEnd
        }

        // return the spannable string
        return spannable
    }

    // #region capture blocks

    /**
     * The highlight captures of the chars [start, end) of the document: of its own
     * language, then of the parts in other languages (injections), in the order the
     * queries found them, each with its rank (priority, then place in that order) and
     * style, cut to [start, end).
     *
     * Rows are styled from these rather than with a query each. Dragging the scrollbar
     * thumb brings a screen of new rows on every frame: a query per row walked down the
     * tree, looked for the injected parts and evaluated the predicates of the matches
     * that reach over several rows again for each of them. A block is one query for all
     * of its rows, and it is kept: scrolling back and forth styles from it again.
     */
    // what rows are styled from, on the main thread, see CaptureStore
    private val store = CaptureStore(BLOCK_CHARS, MAX_CAPTURE_BLOCKS)

    /**
     * Called on the main thread when blocks found in the background are in: the chars
     * of the range are to be styled again (their lines: EditorView.invalidateStyles).
     */
    @Volatile
    var onStylesReady: ((IntRange) -> Unit)? = null

    // Main thread: goes up with every edit; the edits since, with the version each made,
    // to move a block found in the text of before along to the text of now.
    private var editVersion = 0L
    private val editLog = ArrayDeque<Pair<Long, StructureQuery.CharEdit>>()

    // goes up with every tree put in place
    @Volatile
    private var treeGen = 0
    private var requestSequence = 0L

    // What blocks are found in: a copy of the tree and a snapshot of the text, as they
    // were when they were asked for, used on HIGHLIGHT_EXECUTOR while the main thread
    // goes on with its own tree. Closed when the last request is done with it.
    private class Snapshot(
        val tree: TSTree,
        val text: PieceTreeSnapshot,
        val version: Long,
        val treeGen: Int,
        val generation: Int
    ) {
        // guarded by `requests`
        var refs = 1
    }

    // a block to be found, with what it is found with
    private class Request(
        val start: Int,
        val end: Int,
        val sequence: Long,
        val snapshot: Snapshot,
        val query: TSQuery,
        val theme: HighlightTheme,
        val priorities: IntArray,
        val language: String?
    )

    // The snapshot of now, made when a block is asked for after an edit; read by the
    // worker, which leaves out what was asked about a text that has been asked about since.
    @Volatile
    private var latestSnapshot: Snapshot? = null

    // the blocks asked for, the latest last; whether the worker is at them (guarded by it)
    private val requests = ArrayDeque<Request>()
    private var workerRunning = false

    // held while a block is found, and while what it is found with is closed (release)
    private val highlightLock = Any()

    // what the injected parts were last looked for in (version, tree); worker thread
    private var injectionsKey: Pair<Long, Int>? = null

    /**
     * Whether the chars [start, end) can be styled as they are: in blocks found with the
     * text and the tree of now. If not, what is missing is asked for, and [onStylesReady]
     * follows; meanwhile [highlightLine] styles them from what there is (stale blocks:
     * the colors from before an edit, moved along with it), or not at all.
     */
    @MainThread
    fun stylesReady(start: Int, end: Int): Boolean {
        if (!isEnabled || query == null || tree == null || end <= start) return true
        if (store.isCovered(start, end)) return true
        requestBlocks(start, end)
        return false
    }

    // Asks for the blocks of what [start, end) is missing, from the latest first.
    @MainThread
    private fun requestBlocks(start: Int, end: Int) {
        val query = this.query ?: return
        val theme = this.theme ?: return
        val tree = this.tree ?: return
        val buffer = parsedBuffer ?: return
        val ranges = store.toRequest(start, end) { ++requestSequence }
        if (ranges.isEmpty()) return
        val snapshot = snapshotOfNow(tree, buffer)
        val priorities = patternPriorities
        val language = languageName
        synchronized(requests) {
            for (range in ranges) {
                snapshot.refs++
                requests.addLast(
                    Request(range[0].toInt(), range[1].toInt(), range[2], snapshot, query, theme, priorities, language)
                )
            }
            // A fast scroll asks for more than can be found: the oldest requests are of
            // rows that have gone by; they are asked for again if they come back.
            while (requests.size > MAX_REQUESTS) {
                val old = requests.removeFirst()
                releaseSnapshotLocked(old.snapshot)
                store.dropped(old.sequence)
            }
            if (!workerRunning) {
                workerRunning = true
                HIGHLIGHT_EXECUTOR.execute { findBlocks() }
            }
        }
    }

    // the snapshot of the text and the tree of now: the one made last, if they did not
    // change since, or a new one
    @MainThread
    private fun snapshotOfNow(tree: TSTree, buffer: PieceTreeTextBuffer): Snapshot {
        latestSnapshot?.let {
            if (it.version == editVersion && it.treeGen == treeGen && it.generation == generation) return it
        }
        // a snapshot costs a walk over the pieces, a copy of the tree shares its nodes
        val text = buffer.createSnapshot()
        val copy = tree.copy()
        copy.textReader = TSTree.TextReader { start, end ->
            val from = start.coerceIn(0, text.length)
            text.subSequence(from, end.coerceIn(from, text.length))
        }
        val snapshot = Snapshot(copy, text, editVersion, treeGen, generation)
        synchronized(requests) {
            latestSnapshot?.let { releaseSnapshotLocked(it) }
            latestSnapshot = snapshot
        }
        return snapshot
    }

    // One holder of [snapshot] fewer; the last one closes its tree, on the worker (it
    // can be the last holder of much of an old tree). With `requests` held.
    private fun releaseSnapshotLocked(snapshot: Snapshot) {
        if (--snapshot.refs == 0) HIGHLIGHT_EXECUTOR.execute { snapshot.tree.close() }
    }

    // The worker: finds the blocks asked for, the latest first, and hands them to the
    // main thread.
    private fun findBlocks() {
        while (true) {
            val request = synchronized(requests) {
                requests.removeLastOrNull() ?: run {
                    workerRunning = false
                    return
                }
            }
            var block: CaptureStore.Block? = null
            try {
                synchronized(highlightLock) {
                    val snapshot = request.snapshot
                    // not of what was released meanwhile, nor of a text that was asked
                    // about again since (that request comes too)
                    if (snapshot.generation == generation && snapshot === latestSnapshot) {
                        block = findBlock(request)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cannot find the captures of a block", e)
            } finally {
                synchronized(requests) { releaseSnapshotLocked(request.snapshot) }
            }
            val found = block
            mainHandler.post { onBlockFound(request, found) }
        }
    }

    /**
     * The highlight captures of the chars of [request]: of the language of the document,
     * then of the parts in other languages (injections), in the order the queries found
     * them, each with its rank (priority, then place in that order) and style, cut to the
     * chars of the block. Worker thread, with the tree and the text of the request.
     */
    private fun findBlock(request: Request): CaptureStore.Block {
        val snapshot = request.snapshot
        val start = request.start
        val end = request.end
        // the bytes of the block: offset * 2 for UTF-16 encoding
        val bytes = UIntRange(start.toUInt() * 2U, end.toUInt() * 2U)

        var count = 0
        var starts = IntArray(64)
        var ends = IntArray(64)
        var ranks = LongArray(64)
        var styles = arrayOfNulls<HighlightStyle>(64)
        // the rank of the next capture: its priority, then its place in the order
        var sequence = 0L
        // The text of the block, for the cursors: the matches whose predicates fail
        // on it are left out natively (TSQueryCursor.setText). The words of a comment
        // are each a match of the comment grammar's TODO, NOTE, ... patterns, and all
        // but a few fail.
        val length = snapshot.text.length
        val textFrom = minOf(start, length)
        val text = snapshot.text.subSequence(textFrom, minOf(end, length)).toString()

        // The captures of [layerQuery] over the tree of [root], in the order of the text,
        // with the predicates of Neovim (lua-match? and others, see QueryPredicates).
        fun collect(layerQuery: TSQuery, root: TSNode, priorities: IntArray, layerLanguage: String?) {
            // A cursor per call, closed at the end: it holds native memory.
            val cursor = layerQuery(root)
            try {
                cursor.byteRange = bytes
                cursor.setText(bytes.first, text)
                for ((captureIndex, match) in cursor.captures { match -> QueryPredicates.evaluate(this, match) }) {
                    val capture = match.captures.getOrNull(captureIndex.toInt()) ?: continue
                    val pattern = match.patternIndex.toInt()
                    val priority = priorities.getOrElse(pattern) { DEFAULT_PRIORITY }
                    val rank = (priority.toLong() shl SEQUENCE_BITS) or sequence++

                    val from = maxOf((capture.node.startByte / 2U).toInt(), start)
                    val to = minOf((capture.node.endByte / 2U).toInt(), end)
                    if (to <= from) continue

                    val style = request.theme.styleFor(capture.name, layerLanguage) ?: continue
                    if (count == starts.size) {
                        val size = count * 2
                        starts = starts.copyOf(size)
                        ends = ends.copyOf(size)
                        ranks = ranks.copyOf(size)
                        styles = styles.copyOf(size)
                    }
                    starts[count] = from
                    ends[count] = to
                    ranks[count] = rank
                    styles[count] = style
                    count++
                }
            } finally {
                cursor.close()
            }
        }

        // the text in the language of the document
        collect(request.query, queryRoot(snapshot.tree, bytes.first, bytes.last), request.priorities, request.language)

        // then the parts of it in other languages, over it: they come later, so of
        // equal priority they win, as the trees of injected languages do in Neovim.
        // They are parsed again for each text (version) and tree.
        val key = snapshot.version to snapshot.treeGen
        if (injectionsKey != key) {
            injections.invalidate()
            injectionsKey = key
        }
        val injected = injections.treesIn(
            snapshot.tree, bytes, keep = { layer, region -> mayCapture(layer, region, snapshot.text, request.theme) }
        ) { byteIndex -> readSnapshot(snapshot.text, byteIndex) }
        for ((layer, layerTree) in injected) {
            collect(layer.highlights, layerTree.rootNode, layer.priorities, layer.name)
        }

        @Suppress("UNCHECKED_CAST")
        return CaptureStore.Block(
            start, end, count, starts, ends, ranks,
            (if (count == styles.size) styles else styles.copyOf(count)) as Array<HighlightStyle>,
            request.sequence
        )
    }

    // What the patterns of each injected language need of a text to give a styled capture,
    // for the theme it was worked out for (worker thread; see InjectionPrefilter)
    private val prefilters = java.util.IdentityHashMap<TreeSitterInjections.Layer, Pair<HighlightTheme, InjectionPrefilter?>>()

    // Whether [region] may give any styled capture in [layer]: if not, it is not parsed
    // (most comments: no TODO, no #123, no URL). Worker thread.
    private fun mayCapture(
        layer: TreeSitterInjections.Layer,
        region: TreeSitterInjections.Region,
        text: PieceTreeSnapshot,
        theme: HighlightTheme
    ): Boolean {
        // a part made of several ranges is parsed as one: its text is not one piece
        if (region.ranges.size != 1) return true
        val cached = prefilters[layer]
        val prefilter = if (cached != null && cached.first === theme) {
            cached.second
        } else {
            InjectionPrefilter.of(layer, theme).also { prefilters[layer] = theme to it }
        }
        prefilter ?: return true
        val from = (region.startByte / 2U).toInt().coerceIn(0, text.length)
        val to = (region.endByte / 2U).toInt().coerceIn(from, text.length)
        return prefilter.mayCapture(text.subSequence(from, to))
    }

    // A block is found (null: left out). It is moved along with the edits made since
    // it was asked for, and stale if they touched it or another tree is in place now.
    @MainThread
    private fun onBlockFound(request: Request, found: CaptureStore.Block?) {
        if (request.snapshot.generation != generation) return
        if (found == null) {
            store.dropped(request.sequence)
            return
        }
        var stale = request.snapshot.treeGen != treeGen
        var awaitsParse = false
        val since = request.snapshot.version
        if (editVersion != since) {
            // the edits since must all be known, or the block cannot be placed
            val oldest = editLog.firstOrNull()
            if (oldest == null || oldest.first > since + 1) {
                store.dropped(request.sequence)
                return
            }
            for ((version, edit) in editLog) {
                if (version <= since) continue
                if (CaptureStore.move(found, edit.start, edit.oldEnd, edit.newEnd)) awaitsParse = true
            }
        }
        found.stale = stale || awaitsParse
        found.awaitsParse = awaitsParse
        store.put(found)
        if (found.end > found.start) onStylesReady?.invoke(found.start until found.end)
    }

    // the blocks are of another theme: found again, drawn as they are meanwhile. Any thread.
    private fun invalidateCaptures() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            store.markAllStale()
        } else {
            mainHandler.post { store.markAllStale() }
        }
    }

    // #endregion

    // #region code blocks

    /**
     * Whether [bufferLine] (1-based) is a line of code in a code block: of a fenced
     * code block of Markdown the lines between the fences (not the fences), of an
     * indented one all of its lines. False without a tree, or for a grammar that
     * has no code blocks.
     *
     * Asked while drawing, for each row on screen: the blocks are found once after
     * each parse, a line is then a binary search.
     */
    @MainThread
    fun isInCodeBlock(bufferLine: Int): Boolean {
        if (!isEnabled || codeBlockQuery == null) return false
        val blocks = codeBlockLines ?: findCodeBlocks().also { codeBlockLines = it }
        // the last block that starts at or before the line
        var low = 0
        var high = blocks.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if ((blocks[middle] ushr 32).toInt() <= bufferLine) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found >= 0 && blocks[found].toInt() >= bufferLine
    }

    // The lines of the code blocks in the tree, in order.
    private fun findCodeBlocks(): LongArray {
        val query = codeBlockQuery ?: return NO_BLOCKS
        val tree = this.tree ?: return NO_BLOCKS
        val buffer = parsedBuffer
        val blocks = ArrayList<Long>()
        val cursor = query(tree.rootNode)
        try {
            for (match in cursor.matches()) {
                for (capture in match.captures) {
                    val node = capture.node
                    // rows are 0-based, columns in bytes (two a char, UTF-16)
                    val first = node.startPoint.row.toInt() + 1
                    val endRow = node.endPoint.row.toInt() + 1
                    val endColumn = (node.endPoint.column / 2U).toInt()
                    // The content of a fenced block ends where the line of the closing
                    // fence starts (or, in a list or a quote, after the indentation
                    // of that line): that line is not code.
                    val last = when {
                        endRow <= first -> first
                        endColumn == 0 -> endRow - 1
                        buffer != null && isBlankBefore(buffer, (node.endByte / 2U).toInt(), endColumn) -> endRow - 1
                        else -> endRow
                    }
                    blocks += (first.toLong() shl 32) or last.toLong()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot find the code blocks", e)
        } finally {
            cursor.close()
        }
        if (blocks.isEmpty()) return NO_BLOCKS
        val result = blocks.toLongArray()
        result.sort()
        return result
    }

    // whether the [count] chars before [end] are all white space
    private fun isBlankBefore(buffer: PieceTreeTextBuffer, end: Int, count: Int): Boolean {
        val start = (end - count).coerceAtLeast(0)
        val stop = end.coerceIn(start, buffer.length)
        return buffer.subSequence(start, stop).isBlank()
    }

    // The query of the code blocks of [language]: the node types of CODE_BLOCK_NODES
    // it has. Null for a grammar with none of them (all but Markdown).
    private fun loadCodeBlockQuery(language: TSLanguage): TSQuery? {
        val pattern = CODE_BLOCK_NODES
            .filter { language.symbolForName(it, true).toInt() != 0 }
            .joinToString("\n") { "($it) @block" }
        if (pattern.isEmpty()) return null
        return try {
            TSQuery(language, pattern)
        } catch (e: Exception) {
            Log.w(TAG, "cannot look for code blocks", e)
            null
        }
    }

    // #endregion

    /**
     * Where a query over the bytes [start, end] of [tree] starts: the top-level node
     * they lie in (a function, a declaration), rather than the root. The cursor of a
     * query restricted to a range still walks past every top-level node before it,
     * one by one: in a large file (sqlite3.c has thousands of them) that is most of
     * the work of styling a line. Matches of patterns rooted in that node, and in the
     * nodes inside it, are all there are for bytes inside it, but for the patterns
     * rooted at the root itself; those only ever reach into its first child (a
     * shebang: `(program . (comment) @keyword.directive)`), where the root is used.
     */
    private fun queryRoot(tree: TSTree, start: UInt, end: UInt): TSNode {
        val root = tree.rootNode
        val top = root.firstChildForByte(start) ?: return root
        // in between top-level nodes, or across several: from the root
        if (top.startByte > start || top.endByte < end) return root
        if (top.startByte <= root.startByte) return root
        return top
    }

    // the top-level node isCodeAt looked in last, and the tree it is of
    private var codeLookupTop: TSNode? = null
    private var codeLookupTree: TSTree? = null

    /**
     * Whether the char at [offset] is code: not in a string, a comment, a character
     * literal and the like. For the matching of brackets, which asks about every
     * bracket on hundreds of lines: one look at the node there, not a query.
     *
     * The smallest node at the char decides. A token of the grammar (an anonymous
     * node: `(`, `{`, `${`) is code, also inside an interpolation in a string; a named
     * node is not if it is a string or a comment of some kind (string_content,
     * line_comment, char_literal, regex, ...). True without a tree.
     */
    @MainThread
    fun isCodeAt(offset: Int): Boolean {
        if (!isEnabled) return true
        val tree = this.tree ?: return true
        val start = Math.max(0, offset).toUInt() * 2U
        val end = start + 2U
        // lines are asked about one after the other: mostly in the same top-level node
        var top = if (codeLookupTree === tree) codeLookupTop else null
        if (top == null || top.startByte > start || top.endByte < end) {
            val root = tree.rootNode
            top = root.firstChildForByte(start)?.takeIf { it.startByte <= start && it.endByte >= end } ?: root
            codeLookupTop = top
            codeLookupTree = tree
        }
        val node = top.descendant(start, end) ?: return true
        if (!node.isNamed) return true
        val type = node.type
        return NOT_CODE_NODES.none { type.contains(it) }
    }

    // #region structure, found in the background

    /**
     * Called on the main thread when structure captures asked for by [capturesToDraw] are
     * there: the structure is to be asked for again (EditorView.invalidateStructure).
     */
    @Volatile
    var onStructureReady: (() -> Unit)? = null

    // what capturesToDraw found, by what was asked (kind, chars, text, tree); main thread
    private val structureResults = object : LinkedHashMap<String, List<SyntaxCapture>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SyntaxCapture>>): Boolean =
            size > MAX_STRUCTURE_RESULTS
    }
    private val structurePending = HashSet<String>()

    // the edits the structure queries have followed (worker thread)
    private var structureVersion = 0L

    /**
     * As [captures] for FOLDS and INDENTS, for what is drawn: found on the worker thread,
     * with a snapshot of the text and the tree of now. Null while that is under way;
     * [onStructureReady] follows. The structure queries keep what they found of a tree,
     * and follow the edits, there (see StructureQuery): the main thread never waits for a
     * query, nor for the thousands of children at the top of a large file.
     */
    @MainThread
    fun capturesToDraw(kind: QueryKind, startOffset: Int, endOffset: Int): List<SyntaxCapture>? {
        if (!isEnabled || kind == QueryKind.HIGHLIGHTS) return captures(kind, startOffset, endOffset)
        val structure = structureOf(kind) ?: return emptyList()
        val tree = this.tree ?: return emptyList()
        val buffer = parsedBuffer ?: return emptyList()
        val start = Math.max(0, startOffset)
        val end = Math.max(startOffset, endOffset)
        val key = "$kind:$start:$end:$editVersion:$treeGen:$generation"
        structureResults[key]?.let { return it }
        if (!structurePending.add(key)) return null
        val snapshot = snapshotOfNow(tree, buffer)
        synchronized(requests) { snapshot.refs++ }
        HIGHLIGHT_EXECUTOR.execute {
            var result: List<SyntaxCapture>? = null
            try {
                synchronized(highlightLock) {
                    // not of what was released, nor of a text edited again since (that
                    // is asked about again)
                    if (snapshot.generation == generation && structureVersion == snapshot.version) {
                        result = structure.captures(snapshot.tree, snapshot.treeGen, start, end)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "cannot find the structure of the lines", e)
            } finally {
                synchronized(requests) { releaseSnapshotLocked(snapshot) }
            }
            val found = result
            mainHandler.post {
                structurePending.remove(key)
                if (found != null && snapshot.generation == generation) {
                    structureResults[key] = found
                    onStructureReady?.invoke()
                }
            }
        }
        return null
    }

    // #endregion

    /** The queries [captures] can run. */
    enum class QueryKind { HIGHLIGHTS, FOLDS, INDENTS }

    private fun structureOf(kind: QueryKind): StructureQuery? = when (kind) {
        QueryKind.HIGHLIGHTS -> null
        QueryKind.FOLDS -> foldsQuery
        QueryKind.INDENTS -> indentsQuery
    }

    /**
     * Whether there is a syntax tree, and a query of [kind] to run on it. Without a
     * folds.scm or an indents.scm for the grammar, there is none of that kind.
     */
    @MainThread
    fun hasQuery(kind: QueryKind): Boolean = isEnabled &&
        (if (kind == QueryKind.HIGHLIGHTS) query != null else structureOf(kind) != null)

    /**
     * The captures of the query of [kind] whose nodes reach into the text between
     * [startOffset] and [endOffset] (chars, end exclusive), in the order the query
     * finds them. A node that starts before the range or ends after it is reported
     * whole: a fold that starts above the screen, a string the range lies in.
     *
     * Empty without a syntax tree or without that query.
     */
    @MainThread
    fun captures(kind: QueryKind, startOffset: Int, endOffset: Int): List<SyntaxCapture> {
        if (!isEnabled) return emptyList()
        val tree = this.tree ?: return emptyList()
        // folds and indents: the captures of the nodes that start in the range, or
        // before it and reach into it, without walking the whole file (StructureQuery)
        if (kind != QueryKind.HIGHLIGHTS) {
            val structure = structureOf(kind) ?: return emptyList()
            val buffer = parsedBuffer ?: return emptyList()
            // On the thread the structure queries are used on (see capturesToDraw), after
            // what was asked of it before: waited for. Not for drawing: a tap on a fold
            // marker, fold all.
            val snapshot = snapshotOfNow(tree, buffer)
            synchronized(requests) { snapshot.refs++ }
            return try {
                HIGHLIGHT_EXECUTOR.submit(java.util.concurrent.Callable {
                    synchronized(highlightLock) {
                        if (snapshot.generation == generation) {
                            structure.captures(
                                snapshot.tree, snapshot.treeGen, Math.max(0, startOffset), Math.max(startOffset, endOffset)
                            )
                        } else {
                            emptyList()
                        }
                    }
                }).get()
            } catch (e: Exception) {
                Log.e(TAG, "cannot find the structure of the lines", e)
                emptyList()
            } finally {
                synchronized(requests) { releaseSnapshotLocked(snapshot) }
            }
        }
        val query = this.query ?: return emptyList()

        val result = ArrayList<SyntaxCapture>()
        // offset * 2 for UTF-16 encoding
        val bytes = UIntRange(
            Math.max(0, startOffset).toUInt() * 2U,
            Math.max(startOffset, endOffset).toUInt() * 2U
        )
        // a cursor per call, closed at the end: it holds native memory
        val cursor = query(queryRoot(tree, bytes.first, bytes.last))
        try {
            cursor.byteRange = bytes
            for (match in cursor.matches { match -> QueryPredicates.evaluate(this, match) }) {
                for (capture in match.captures) {
                    result.add(
                        SyntaxCapture(
                            capture.name,
                            (capture.node.startByte / 2U).toInt(),
                            (capture.node.endByte / 2U).toInt()
                        )
                    )
                }
            }
        } finally {
            cursor.close()
        }
        return result
    }

    private fun applyStyle(spannable: SpannableString, style: HighlightStyle, start: Int, end: Int) {
        var typeface = Typeface.NORMAL
        // bold
        if (style.bold) {
            typeface = typeface or Typeface.BOLD
        }

        // italic
        if (style.italic) {
            typeface = typeface or Typeface.ITALIC
        }

        // typeface
        if (typeface != Typeface.NORMAL) {
            spannable.setSpan(StyleSpan(typeface), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        // strikethrough
        if (style.strikethrough) {
            spannable.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        // underline
        if (style.underline) {
            spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        // background color
        style.background?.let {
            spannable.setSpan(BackgroundColorSpan(it), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        // foreground color
        style.foreground?.let {
            spannable.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }
    
    /**
     * When the text changes, the syntax tree is updated synchronously
     * note that this method must be run on the main thread
     *
     * Does nothing if there is no syntax tree.
     *
     * @param range the range where the text changes
     * @param rangeOffset the starting offset of the text change range
     * @param insertedTextLength the amount of text to insert
     * @param deletedTextLength the amount of text to delete
     * @param finalLineNumber final row position after text insertion and deletion
     * @param finalColumn final column position after text insertion and deletion
     */
    @MainThread
    fun applyEdit(
        range: Range,
        rangeOffset: Int,
        insertedTextLength: Int,
        deletedTextLength: Int,
        finalLineNumber: Int,
        finalColumn: Int
    ) {
        val tree = this.tree ?: return
        // offset * 2U for utf-16 encoding
        val tsInput = TSInputEdit(
            startByte = rangeOffset.toUInt() * 2U,
            oldEndByte = (rangeOffset + deletedTextLength).toUInt() * 2U,
            newEndByte = (rangeOffset + insertedTextLength).toUInt() * 2U,
            startPoint = TSPoint(
                (range.startLine - 1).toUInt(),
                (range.startColumn - 1).toUInt() * 2U
            ),
            oldEndPoint = TSPoint(
                (range.endLine - 1).toUInt(),
                (range.endColumn - 1).toUInt() * 2U
            ),
            newEndPoint = TSPoint(
                (finalLineNumber - 1).toUInt(),
                (finalColumn - 1).toUInt() * 2U
            )
        )
        tree.edit(tsInput)
        // the span the edits touched: what was touched before, moved along by this
        // edit, and what this one touched
        val editStart = tsInput.startByte
        val oldEnd = tsInput.oldEndByte
        val newEnd = tsInput.newEndByte
        if (editedStart > editedEnd) {
            editedStart = editStart
            editedEnd = newEnd
        } else {
            editedEnd = when {
                // after this edit: moves with the text
                editedEnd >= oldEnd -> editedEnd - oldEnd + newEnd
                // in what this edit replaced
                editedEnd > editStart -> newEnd
                else -> editedEnd
            }
            editedStart = minOf(editedStart, editStart)
            editedEnd = maxOf(editedEnd, newEnd)
        }
        // a parse running in the background works on the text before this edit: its
        // tree is told about it too, when it is put in place
        if (parsingGeneration == generation) editsDuringParse += tsInput
        // The code blocks were found in the text before the edit; they are found again
        // in the edited tree when they are drawn (their places moved). The injected parts
        // are looked for again by the worker, for the text of the blocks it finds.
        codeBlockLines = null
        // its nodes are where they were before the edit
        codeLookupTop = null
        // The structure kept of the tree follows the edit (see StructureQuery.onEdit):
        // thrown away, the root (thousands of children in a large file) would be asked
        // again on the main thread at the next frame, at every key press.
        val charEdit = StructureQuery.CharEdit(
            rangeOffset, rangeOffset + deletedTextLength, rangeOffset + insertedTextLength
        )
        // The blocks follow it: the rows around it are styled from them at once, the
        // colors of before moved along, until the parse is done (see CaptureStore).
        store.onEdit(charEdit.start, charEdit.oldEnd, charEdit.newEnd)
        editVersion++
        editLog.addLast(editVersion to charEdit)
        while (editLog.size > MAX_EDIT_LOG) editLog.removeFirst()
        // the structure kept of the tree follows it too, on the thread it is used on, in
        // the order of the edits (see capturesToDraw)
        val folds = foldsQuery
        val indents = indentsQuery
        val version = editVersion
        HIGHLIGHT_EXECUTOR.execute {
            synchronized(highlightLock) {
                folds?.onEdit(charEdit)
                indents?.onEdit(charEdit)
                structureVersion = version
            }
        }
    }
    
    // The text from [offset] for the parser: the rest of the piece it lies in, at most
    // [maxChars] (only that much is copied). The two halves of a surrogate pair stay
    // together: a high surrogate at the end of a chunk takes the low one along, or the
    // parser would ask for the same offset again.
    private inline fun readChunk(
        offset: Int, length: Int, maxChars: Int,
        nearestChunk: (Int, Int) -> String, charAt: (Int) -> Char
    ): String {
        var chunk = nearestChunk(offset, maxChars)
        if (chunk.isEmpty()) {
            // (cannot happen below the length; a char at a time then)
            chunk = charAt(offset).toString()
        }
        val end = offset + chunk.length
        if (Character.isHighSurrogate(chunk[chunk.length - 1]) && end < length) {
            chunk += charAt(end)
        }
        return chunk
    }

    // The UTF-16LE bytes of [text], char by char: what the parser reads. Faster than
    // a charset encoder, and a lone surrogate stays itself (no "?" in its place).
    private fun utf16le(text: String): ByteArray {
        val bytes = ByteArray(text.length * 2)
        for (i in text.indices) {
            val code = text[i].code
            bytes[2 * i] = code.toByte()
            bytes[2 * i + 1] = (code shr 8).toByte()
        }
        return bytes
    }

    /**
     * Dynamic loading the tree-sitter language libraries and config files
     *
     * Everything is read from `files/treesitter` (see TreeSitterResources): the
     * libraries of this device from `<arch>` (in folders below it too), the file
     * types from `config/filetypes.json`, the theme from `themes/<name>.json`.
     * These files are downloaded, so one that is missing or broken is logged and
     * skipped (what was loaded before stays).
     *
     * @param archName the architecture of the device, see DeviceUtils.getArchName
     * @param styleName the name of the highlight theme
     */
    fun loadResources(archName: String, styleName: String) {
        val libraries = HashMap<String, File>()
        grammarDir().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .forEach { library ->
                if (TreeSitterResources.loadLibrary(library)) {
                    libraries[library.name] = library
                }
            }
        libraryFiles = libraries

        loadConfig(TreeSitterResources.fileTypesFile(context)) { loadFileTypes(it) }
        loadHighlightStyle(styleName)
    }

    /**
     * Loads the highlight theme [styleName] (`themes/<styleName>.json`) in
     * place of the one loaded before, keeping the libraries and file types. Not on
     * the main thread (it may read a file); the editor has to be told to style the
     * text again.
     */
    fun loadHighlightStyle(styleName: String) {
        HighlightThemes.get(context, styleName)?.let {
            theme = it
            // the captures were styled with the theme before
            invalidateCaptures()
        }
    }

    private inline fun loadConfig(file: File, parse: (File) -> Unit) {
        if (!file.exists()) return
        try {
            parse(file)
        } catch (e: Exception) {
            // IOException, SerializationException (not JSON), IllegalArgumentException
            // (not the expected shape, or not a color)
            Log.e(TAG, "cannot read ${file.path}", e)
        }
    }
    
    /**
     * Mapping file types to tree-sitter language name
     * for example `.c` => `tree_sitter_c` `.kt` => `tree_sitter_kotlin`
     *
     * The file is an object of symbol names, each with the array of the file
     * names and extensions that use it. It replaces the mapping loaded before.
     *
     * @param types the file suffix types json config file
     * @throws SerializationException if the file is not JSON
     * @throws IllegalArgumentException if it does not have that shape
     */
    @Throws(SerializationException::class)
    fun loadFileTypes(types: File) {
        grammarSymbols = TreeSitterLookup.parseFileTypes(types)
    }
    
    /**
     * Get the tree-sitter language from a grammar library
     *
     * @param path the path of the library, like `.../lib/aarch64/libtree_sitter_c.so`
     * @param symbol the grammar symbol name, like `tree_sitter_c`
     * @return TSLanguage, or null if either argument is null
     * @throws IllegalArgumentException if the library is not a usable grammar
     */
    fun loadLanguage(path: String?, symbol: String?): TSLanguage? {
        return if (path != null && symbol != null) TSLanguage(path, symbol) else null
    }
    
    /**
     * Get the grammar symbol name, like `tree_sitter_c`, `tree_sitter_cpp`, for a file
     *
     * @param filename source file name like `hello.c`, `test.cpp`, `foo.kt`, `Makefile` etc
     * @return the symbol name, or null if no grammar is mapped to the file
     */
    fun findGrammarSymbol(filename: String): String? =
        TreeSitterLookup.symbolFor(filename, grammarSymbols)
    
    /**
     * Get the tree-sitter query s-expression pattern
     *
     * @param dir the query files directory, files/treesitter/queries
     * @param name grammar name like `c`, `cpp`, `kotlin` etc
     * @param kinds the query types like `highlights`, 'locals', 'indents', 'fold' etc
     * @return the patterns of all kinds, one after the other; null if there is none
     */
    fun readQueryPattern(dir: File, name: String, kinds: List<String>): String? {
        val buffer = StringBuilder()
        kinds.forEach { kind ->
            readQueryPattern(dir, name, kind)?.let { buffer.append(it) }
        }      
        return if (buffer.isNotEmpty()) buffer.toString() else null
    }
    
    /**
     * Get the tree-sitter query s-expression pattern
     *
     * A query file can build on the one of another grammar, like c++ inherits c,
     * with a comment `; inherits: c`. The inherited patterns come first.
     *
     * @param dir the query files directory, default /data/data/package_name/files/query
     * @param name grammar name like `c`, `cpp`, `kotlin` etc
     * @param kind the query type like `highlights`, 'locals', 'indents', 'fold' etc
     * @return s-expression pattern string literal, null if the grammar has no such file
     */
    fun readQueryPattern(dir: File, name: String, kind: String): String? =
        TreeSitterUtils.resolvePattern(name) { grammar ->
            File(dir, "$grammar/$kind.scm").takeIf { it.isFile }?.readText()
        }

    // where the grammar libraries of this device are: files/treesitter/<arch>
    private fun grammarDir(): File = TreeSitterResources.languagesDir(context)

    private companion object {
        const val TAG = "TreeSitterEngine"

        const val QUERY_HIGHLIGHTS = "highlights"

        // the priority of a pattern that does not set one, as in Neovim
        const val DEFAULT_PRIORITY = 100

        // the bits of a rank below the priority: the place of the capture in the order
        const val SEQUENCE_BITS = 40

        // The chars of the document a capture block covers (see CaptureBlock), and how
        // many blocks are kept. Some 50 lines of code: a few blocks a screen. A block is
        // styled in one go, which the frame budget cannot cut short: the smaller it is,
        // the shorter the longest frame of a fast scroll.
        const val BLOCK_CHARS = 2 * 1024
        const val MAX_CAPTURE_BLOCKS = 96
        // the blocks waiting to be found at most: a fast scroll asks for more
        const val MAX_REQUESTS = 12
        // the edits remembered, to move a block found before them along
        const val MAX_EDIT_LOG = 512
        // the structure answers kept (a few windows of lines, of folds and of indents)
        const val MAX_STRUCTURE_RESULTS = 16
        const val QUERY_FOLDS = "folds"
        const val QUERY_INDENTS = "indents"

        // the captures of folds.scm and indents.scm that TreeSitterStructure reads
        val FOLD_CAPTURES = setOf("fold")
        val INDENT_CAPTURES = setOf("indent.begin", "indent")

        // The most the parser gets at once, in chars: the rest of a piece, cut here.
        // A full parse reads it all in order; an incremental one only a few chars
        // around each edit, and each answer is copied (to bytes, through JNI).
        const val PARSE_CHUNK_MAX_CHARS = 16 * 1024

        // the same for a parse after an edit, which reads a little here and there
        const val REPARSE_CHUNK_MAX_CHARS = 2 * 1024

        // Where the parses after edits run: one at a time, for all the editors. A
        // daemon thread, so that it never keeps the process alive.
        val PARSE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tree-sitter-parse").apply { isDaemon = true }
        }

        // Where the highlight captures of blocks are found (see findBlock): one at a
        // time, for all the editors, off the main thread, beside the parses.
        val HIGHLIGHT_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tree-sitter-highlight").apply { isDaemon = true }
        }

        val NO_BYTES = ByteArray(0)

        // The node types whose lines are code (tree-sitter-markdown): the content of
        // a fenced code block (its lines between the fences), an indented code block.
        val CODE_BLOCK_NODES = listOf("code_fence_content", "indented_code_block")

        val NO_BLOCKS = LongArray(0)

        // named nodes whose text is not code, by a part of their type
        val NOT_CODE_NODES = listOf("string", "comment", "char", "regex", "heredoc")
    }
}
