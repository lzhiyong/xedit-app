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

package x.code.app.model

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.text.TextUtils

import androidx.annotation.MainThread

import kotlinx.serialization.*
import kotlinx.serialization.json.*

import java.io.File
import java.io.InputStream

import x.code.app.util.JsonUtils
import x.github.module.document.DocumentFile
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.PieceTreeTextBuffer

import x.github.module.treesitter.TSInputEdit
import x.github.module.treesitter.TSLanguage
import x.github.module.treesitter.TSParser
import x.github.module.treesitter.TSQuery
import x.github.module.treesitter.TSTree
import x.github.module.treesitter.TSNode
import x.github.module.treesitter.TSPoint


/**
 * The tree-sitter model class is used to implement syntax highlighting
 * indentation, folding, and other functions
 *
 * @context the current context
 */
class TreeSitter(private val context: Context) {
    
    // the tree sitter parser
    private lateinit var tsParser: TSParser
    // the tree sitter query
    private lateinit var tsQuery: TSQuery
    // the tree sitter tree
    private lateinit var tsTree: TSTree    
    
    // map the file name and file extension to the tree-sitter grammar
    private val fileTypeMap by lazy { mutableMapOf<String, String>() }
    
    // map the scope name to the span type
    private val spanTypeMap by lazy { mutableMapOf<String, Span>() }
    
    public var isEnabled: Boolean = false
        set(value) {
            if(this::tsTree.isInitialized) {
                field = value
            }
        }
    
    /**
     * Initialize the tree sitter for current programming language
     *     
     * @fileName the name of document file 
     * @textBuffer contents of the text editor
     * @return
     */
    fun init(fileName: String, textBuffer: PieceTreeTextBuffer) {
        val language = getLanguage(fileName)                               
        // the query files directory, default is /data/data/package_name/files/query
        val queryDir = File(context.getFilesDir(), "query")
        val pattern = language?.run {
            getPattern(queryDir, getName(), "highlights")
        } ?: null        
        // initialize the tree-sitter
        if (language != null && pattern != null) {
            this.tsParser = TSParser(language)
            this.tsQuery = TSQuery(language, pattern)
            // first time parse the oldTree is null
            this.tsTree = parse(null, textBuffer)           
            // now enable the tree-sitter
            this.isEnabled = true
        }
    }
    
    /**
     * Release the memory allocated by the native layer
     * @return
     */ 
    fun recycle() {
        // now disable the tree-sitter
        this.isEnabled = false
        
        // free up the memory
        if(this::tsParser.isInitialized) {
            tsParser.close()
        }
        
        if(this::tsQuery.isInitialized) {
            tsQuery.close()
        }
        
        if(this::tsTree.isInitialized) {
            tsTree.close()
        }
    }
    
    /**
     * Due to android JNI restrictions, an OOM exception will occur when we pass a large text
     * therefore, the text buffer needs to be split into appropriate sizes
     * in most cases, for UTF-8 encoded files, the file size is equivalent to the text buffer length
     * note that the emoji is not included here
     *
     * @length the text buffer length
     * @blockSize split block size
     * @return the list saves the start index position of each block
     */
    fun splitBuffer(length: Int, blockSize: Int): List<UInt> {
        val indexs = mutableListOf<UInt>()
        val count = when {
            blockSize < 1024 * 12 -> 0 // 12kb
            else -> (length / blockSize).toInt()
        }

        if (count > 1) {
            for (i in 1..count - 1) {
                indexs.add((blockSize * i).toUInt())
            }
        }

        // add the text buffer length
        return indexs.apply{ add(length.toUInt()) }
    }
    
    /**
     * Parse the text buffer to get the abstract syntax tree
     * you must ensure that TSTree has been initialized before calling this method
     * this method is a convenience method of psrse(oldTree, textBuffer) method
     * for the same file, oldTree is equivalent to the current tsTree
     *
     * @textBuffer the piece tree text buffer
     * @return the new TSTree
     */
    fun parse(textBuffer: PieceTreeTextBuffer) = parse(tsTree, textBuffer)
    
    /**
     * Parse the text buffer to get the abstract syntax tree
     * you must ensure that TSParser has been initialized before calling this method
     * here we use utf-16 encoding to process unicode
     *
     * @oldTree old TSTree this can be null for the first initialization of TSTree
     * @textBuffer contents of the text editor
     * @return the new TSTree
     */
    fun parse(oldTree: TSTree?, textBuffer: PieceTreeTextBuffer): TSTree {        
        // 1024 * 1024 * 2 = 2MB
        if(textBuffer.length > 2097152) {
            // index of the indexs
            var index: Int = 0
            // default block size 1024 * 1024 = 1048576
            val table = splitBuffer(textBuffer.length, 1048576)
            // parse callback
            tsTree = tsParser.parse(oldTree) { byteIndex, point ->            
                // for UTF-16 encoding requires byteIndex / 2U
                if (
                    index < table.size && 
                    byteIndex / 2U != 0U && 
                    byteIndex / 2U >= table[index]
                ) {
                    index++
                }

                if (index < table.size && byteIndex / 2U < table[index]) {
                    return@parse textBuffer
                        .substring((byteIndex / 2U).toInt(), table[index].toInt())
                        .toByteArray(Charsets.UTF_16LE)
                } else {
                    return@parse String().toByteArray(Charsets.UTF_16LE)
                }
            }
        } else {
            // parse string, note the string using utf16 encoding in native
            tsTree = tsParser.parse(oldTree, textBuffer.toString())
        }
        // return the new TSTree
        return tsTree
    }
    
    /**
     * Query text to find specific patterns in source code
     * tree-sitter provides a simple pattern-matching language for this purpose
     * note that the query predicate only for parse string not parse callback
     *
     * @text the current line of text in the editor
     * @lineStart the starting index of the current line of text in textBuffer
     * @startOffset the starting index position of the query in the current line of text
     * @endOffset the ending index position of the query in the current line of text
     * @return spannable string
     */
    fun query(text: String, lineStart: Int, startOffset: Int, endOffset: Int): SpannableString {
        val spannable = SpannableString(text)       
        
        var spanMark: Any? = null
        var markStart: Int = 0
        var markEnd: Int = 0
        
        var prevNode: TSNode? = null
        var prevResult: Boolean = false
        var prevIndex: UInt = 0U
        // offset * 2 for UTF-16 encoding
        tsQuery.byteRange = UIntRange(
            (lineStart + startOffset).toUInt() * 2U,
            (lineStart + endOffset).toUInt() * 2U
        )
        
        tsQuery.matches(tsTree.rootNode).forEach { match ->          
            for (capture in match.captures) {
                if (
                    prevNode == capture.node && 
                    prevResult == match.predicateResult &&
                    prevIndex != match.patternIndex
                ) {
                    continue
                }
            
                prevNode = capture.node
                prevResult = match.predicateResult
                prevIndex = match.patternIndex
        
                var start = (capture.node.startByte / 2U).toInt() - lineStart
                var end = (capture.node.endByte / 2U).toInt() - lineStart
                // check offset boundary
                if (end <= startOffset || start >= endOffset) {
                    continue
                } 
                // reset offset boundary         
                if (start < startOffset) start = startOffset
                if (end > endOffset) end = endOffset
                
                spanTypeMap[capture.name]?.let { span ->              
                    // remove previous span, which was attached markup object
                    spannable.getSpans(start, end, CharacterStyle::class.java).forEach { markup ->                       
                        val spanStart = spannable.getSpanStart(markup)
                        val spanEnd = spannable.getSpanEnd(markup)                        
                        // handle the string interpolate identifier
                        // like println("$test -> ${ foo() }"), $test and ${ foo() }
                        if (start > spanStart && end < spanEnd) {                           
                            spanMark = markup                            
                            markStart = spanStart
                            markEnd = spanEnd                                                                 
                        }                                           
                        spannable.removeSpan(markup)
                    }
                    
                    var typeface = Typeface.NORMAL                  
                    // bold
                    if (span.bold) {
                        typeface = typeface or Typeface.BOLD
                    }
                                      
                    // italic
                    if (span.italic) {
                        typeface = typeface or Typeface.ITALIC
                    }
                    
                    // typeface
                    if (typeface != Typeface.NORMAL) {
                        spannable.setSpan(StyleSpan(typeface), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    
                    // strikethrough
                    if (span.underline) {
                        spannable.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }

                    // underline
                    if (span.underline) {
                        spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    
                    // background color
                    span.bg?.let {
                        spannable.setSpan(BackgroundColorSpan(it), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                                      
                    // foreground color
                    span.fg?.let {
                        spannable.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                    
                    // handle the string interpolate identifier
                    if (spanMark != null && start >= markEnd) {                        
                        val spArray = spannable.getSpans(markStart, markEnd, CharacterStyle::class.java).apply {
                            sortBy { spannable.getSpanStart(it) }
                        }
                        
                        var start = spannable.getSpanStart(spArray[0])
                        var end = spannable.getSpanEnd(spArray[0])                                               
                        if (start != markStart) {                            
                            spannable.setSpan(
                                ForegroundColorSpan((spanMark as ForegroundColorSpan).getForegroundColor()), 
                                markStart, 
                                start, 
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                            )
                        }
                        
                        for (i in 1..spArray.size - 1) {
                            start = spannable.getSpanStart(spArray[i])
                            // prev span end index => next span start index
                            if (end != start) {
                                spannable.setSpan(
                                    ForegroundColorSpan((spanMark as ForegroundColorSpan).getForegroundColor()), 
                                    end, 
                                    start, 
                                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                                )
                            }
                            end = spannable.getSpanEnd(spArray[i])
                        }
                        
                        if (end != markEnd) {
                            spannable.setSpan(
                                ForegroundColorSpan((spanMark as ForegroundColorSpan).getForegroundColor()), 
                                end, 
                                markEnd, 
                                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                            )
                        }
                        // reset span mark to null
                        spanMark = null
                    }              
                }
            }
        }
        // return the spannable string
        return spannable
    }
    
    /**
     * When the text changes, the syntax tree is updated synchronously
     * note that this method must be run on the main thread
     *
     * @range the range where the text changes
     * @rangeOffset the starting offset of the text change range
     * @insertedTextLength the amount of text to insert
     * @deletedTextLength the amount of text to delete
     * @finalLineNumber final row position after text insertion and deletion
     * @finalColumn final column position after text insertion and deletion
     */
    @MainThread
    fun edit(
        range: Range,
        rangeOffset: Int,
        insertedTextLength: Int,
        deletedTextLength: Int,
        finalLineNumber: Int,
        finalColumn: Int
    ) {
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
        tsTree.edit(tsInput)
    }
    
    /**
     * Dynamic loading the tree-sitter language libraries and config files
     *
     * @libs the tree-sitter grammar libraries directory default /data/data/package_name/files/lib
     * @types the file suffix types json config file
     * @styles the syntax highlight styles json config file
     * @return
     */
    fun load(archName: String, styleName: String) {
        File(context.getFilesDir(), "lib/$archName").listFiles()?.forEach {
            System.load(it.absolutePath)
        }
        
        File(context.getFilesDir(), "config/filetypes.json").also { file ->
            if (file.exists()) {
                parseFileTypes(file)
            }
        }
        
        File(context.getFilesDir(), "theme/$styleName.json").also { file ->
            if (file.exists()) {
                parseSpanTypes(file)
            }
        }
    }
    
    /**
     * Mapping file types to tree-sitter language name
     * for example `.c` => `tree_sitter_c` `.kt` => `tree_sitter_kotlin`
     *
     * @types the file suffix types json config file
     * @return 
     */
    @Throws(SerializationException::class)
    fun parseFileTypes(types: File) {       
        JsonUtils.parse(types.inputStream()) { _, element ->
            element.jsonObject?.forEach { entry ->
                // Map.Entry<String, JsonElement>           
                entry.value.jsonArray?.forEach {
                    fileTypeMap.put(it.jsonPrimitive.content, entry.key)
                }
            }
        }
    }
    
    /**
     * Mapping the syntax highlight styles to span types
     *
     * @styles the syntax highlight styles json config file
     * @return
     */
    @Throws(SerializationException::class)
    fun parseSpanTypes(styles: File) {                
        JsonUtils.parse(styles.inputStream()) { _, element ->
            element.jsonObject["highlights"]?.jsonObject?.forEach {                
                if (it.value is JsonObject) {
                    val span = Span(null, null)
                    it.value.jsonObject["fg"]?.let {
                        // foreground color
                        span.fg = Color.parseColor(it.jsonPrimitive.content)
                    }
               
                    it.value.jsonObject["bg"]?.let {
                        // background color
                        span.bg = Color.parseColor(it.jsonPrimitive.content)
                    }
               
                    it.value.jsonObject["bold"]?.let {
                        // bold style
                        span.bold = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["italic"]?.let {
                        // italic style
                        span.italic = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["strikethrough"]?.let {
                        // strikethrough style
                        span.strikethrough = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["underline"]?.let {
                        // underline style
                        span.underline = it.jsonPrimitive.boolean
                    }
                    
                    // add the span
                    spanTypeMap.put(it.key, span)  
                } else if (it.value !is JsonNull) {
                    // foreground color and add span
                    spanTypeMap.put(it.key, Span(Color.parseColor(it.value.jsonPrimitive.content)))  
                }                     
            }
        }
    }
    
    /**
     * Get the tree-sitter language via file suffix or full name
     *
     * @filename source file name like `hello.c`, `test.cpp`, `foo.kt` etc
     * @return TSLanguage
     */
    fun getLanguage(filename: String): TSLanguage? {
        val index = filename.lastIndexOf(".")
        return if (index >= 0) {
            // file extension name
            val extension = filename.substring(index)
            // get the grammar name via the file extension name
            // like `tree_sitter_c`, `tree_sitter_cpp` etc
            val name = fileTypeMap[extension]
            if (name != null) TSLanguage(name) else null
        } else {
            // get the grammar name via the file full name
            val name = fileTypeMap[filename]
            if (name != null) TSLanguage(name) else null
        }
    }
    
    /**
     * Get the tree-sitter query s-expression pattern
     *
     * @dir the query files directory, default /data/data/package_name/files/queries
     * @name grammar name like `c`, `cpp`, `kotlin` etc
     * @kind the query type like `highlights`, 'locals', 'indents', 'fold' etc
     * @return s-expression pattern string literal
     */
    fun getPattern(dir: File, name: String, kinds: List<String>): String? {
        var buffer = StringBuilder()
        kinds.forEach { kind ->
            getPattern(dir, name, kind)?.let { buffer.append(it) }
        }      
        return if (buffer.length > 0) buffer.toString() else null
    }
    
    /**
     * Get the tree-sitter query s-expression pattern
     *
     * @dir the query files directory, default /data/data/package_name/files/queries
     * @name grammar name like `c`, `cpp`, `kotlin` etc
     * @kind the query type like `highlights`, 'locals', 'indents', 'fold' etc
     * @return s-expression pattern string literal
     */
    fun getPattern(dir: File, name: String, kind: String): String? {
        var pattern: String? = null     
        val queryFile = File(dir, "${name}/${kind}.scm")
        if (queryFile.exists()) {
            // super class pattern, like c++ inherits c
            val source = queryFile.readText()
            Regex("; inherits: (\\w+)").find(source)?.let {
                // super grammar name
                val superName = it.value.substring(it.value.indexOf(":") + 1).trim()
                val superFile = File(dir, "${superName}/${kind}.scm")
                if (superFile.exists()) {
                    // (super + current) s-expression pattern
                    pattern = superFile.readText() + source
                }
            } ?: run {
                // the s-expression query pattern        
                pattern = source
            }
        }
        // return the s-expression string
        return pattern
    }
}

