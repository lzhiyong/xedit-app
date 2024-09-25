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

package io.github.code.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface

import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.StaticLayout
import android.text.TextPaint

import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.annotation.MainThread

import io.github.code.app.common.ColorScheme
import io.github.code.app.ui.MainViewModel
import io.github.module.editor.util.sumOf
import io.github.module.editor.view.EditorView

import io.github.module.piecetable.common.ContentChange
import io.github.module.piecetable.common.Range

import io.github.module.treesitter.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*


class HighlightTextView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : EditorView(context, attrs, defStyleAttr) {
    
    // mainViewModel object from BaseActivity
    private lateinit var viewModel: MainViewModel
    
    // the tree-sitter parser and query
    private lateinit var parser: TSParser
    private lateinit var tree: TSTree
    private lateinit var query: TSQuery
        
    // enable syntax highlight flag
    private var treeSitterEnable = false
    // task cache buffer
    private val tasksDeque = ArrayDeque<Job>()
    // the logging tag
    private val TAG = this::class.simpleName
    
    init {
        // hardware accelerated
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // get the viewModel instance
        // note this has been initialized in BaseActivity
        // viewModel === mainViewModel
        viewModel = findViewTreeViewModelStoreOwner()!!.run {
            ViewModelProvider(this).get(MainViewModel::class.java)
        }        
    }
    
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        // cancel the privious job
        viewModel.removeJobs(tasksDeque.removeFirstOrNull())        
        return super.onScaleBegin(detector)
    }
    
    @ExperimentalCoroutinesApi
    override fun onScaleEnd(detector: ScaleGestureDetector) {        
        viewModel.setTextScaledState(false)
        tasksDeque.addLast(
            viewModel.execute(Dispatchers.Default.limitedParallelism(1)) {
                // start text relayout
                scaleCallback(cancelled = { !isActive }) {
                    // scale finished
                    viewModel.setTextScaledState(true)
                    // the job has finished, no longer needed
                    tasksDeque.removeFirstOrNull()
                }
            }
        )
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }
    
    @ExperimentalCoroutinesApi
    override fun onTextChanged(
        range: Range,
        rangeOffset: Int,
        insertedLinesCnt: Int,
        insertedTextLength: Int,
        deletedLinesCnt: Int,
        deletedTextLength: Int,
        finalLineNumber: Int,
        finalColumn: Int
    ) {
        // running on main thread
        super.onTextChanged(
            range,
            rangeOffset,
            insertedLinesCnt,
            insertedTextLength,
            deletedLinesCnt,
            deletedTextLength, 
            finalLineNumber,
            finalColumn
        )
        
        // update the text layout width and height
        // this running on background thread
        viewModel.execute(Dispatchers.Default.limitedParallelism(1)) {
            textLayout.measure()
        }
        
        // update the syntax tree 
        // note here must be running on main thread
        if (treeSitterEnable) {           
            treeSitterEdit(
                range,
                rangeOffset, 
                insertedTextLength,
                deletedTextLength,
                finalLineNumber,
                finalColumn
            )
        }
    }

    override fun afterTextChanged(
        changes: List<ContentChange>,
        lastLineNumber: Int,
        lastColumn: Int
    ) {
        // perform text changed callback
        viewModel.setTextChangedState(true)
        
        if (treeSitterEnable) {
            // reparse the syntax tree
            tree = treeSitterParse(tree)
        }

        // update text and cursor state
        super.afterTextChanged(
            changes, lastLineNumber, lastColumn
        )
    }
    
    override fun onMergedBefore() {
        // to change the redo state
        viewModel.setRedoState(false)
    }
    
    override fun onMergedAfter() {
        // to change the undo state 
        viewModel.setUndoState(true)
    }
    
    override fun undo(): Boolean {
        if (isEditable() && editorStack.canUndo()) {
            super.undo()
            viewModel.setUndoState(editorStack.canUndo())
            viewModel.setRedoState(editorStack.canRedo())
        }
        return viewModel.stateUndo.value
    }
    
    override fun redo(): Boolean {
        if (isEditable() && editorStack.canRedo()) {
            super.redo()
            viewModel.setRedoState(editorStack.canRedo())
            viewModel.setUndoState(editorStack.canUndo())
        }
        return viewModel.stateRedo.value
    }
    
    override fun drawText(
        canvas: Canvas, // the canvas for draw
        text: String, // the current line text
        line: Int,  // the current line number
        startOffset: Int, // visible start column
        endOffset: Int, // visible end column
        xPaint: Float, // draw the starting x coordinate
        yPaint: Float, // draw the starting y coordinate
    ) {
        if (text.length > 0 && treeSitterEnable) {
            // draw the spannable string
            // for performance reasons, StaticLayout is not used to directly draw spans
            val spannable = treeSitterQuery(text, line, startOffset, endOffset)
            val widths = getTextWidths(text, startOffset, endOffset)
            
            var xStart = xPaint
            var xEnd = xPaint
            var start = startOffset
            var end = startOffset
            
            while (start < endOffset) {
                end = spannable.nextSpanTransition(start, endOffset, CharacterStyle::class.java)                
                xEnd = xStart + widths.sumOf(start, end, startOffset)
                
                // background color
                spannable.getSpans(start, end, BackgroundColorSpan::class.java).firstOrNull()?.let {
                    textPaint.color = it.backgroundColor
                    canvas.drawRect(
                        xStart,
                        (line - 1) * getLineHeight(),
                        xEnd,
                        line * getLineHeight(),
                    )
                }
                
                // underline
                spannable.getSpans(start, end, UnderlineSpan::class.java).firstOrNull()?.let {
                    //textPaint.color = underlineColor
                    canvas.drawLine(xStart, yPaint, xEnd, yPaint, textPaint)
                }
                
                // foreground color
                spannable.getSpans(start, end, ForegroundColorSpan::class.java).firstOrNull()?.let {
                    textPaint.color = it.foregroundColor
                }
                
                // typeface
                spannable.getSpans(start, end, StyleSpan::class.java).firstOrNull()?.let {               
                    when (it.style) {
                        Typeface.BOLD_ITALIC -> {
                            textPaint.setFakeBoldText(true)
                            textPaint.setTextSkewX(-0.2f)
                        }
                        Typeface.BOLD -> textPaint.setFakeBoldText(true)
                        Typeface.ITALIC -> textPaint.setTextSkewX(-0.2f)
                        else -> { /* Typeface.NORMAL nothing to do */ }                    
                    }
                }
                
                // draw the content text
                canvas.drawText(text, start, end, xStart, yPaint, textPaint)
                // restore the paint state
                textPaint.setColor(defaultPaintColor)
                textPaint.setFakeBoldText(false)
                textPaint.setTextSkewX(0f)
                
                xStart = xEnd
                start = end
            }
        } else {
            // no highlight for the text
            super.drawText(canvas, text, line, startOffset, endOffset, xPaint, yPaint)
        }
    }

    /**
     * split the text buffer
     * the file size >= the buffer length
     * for utf8 encoding not contains the emoji
     * the file size equals text buffer length
     */
    private fun splitBuffer(
        length: Int = getLength(),
        blockSize: Int = 1024 * 1024 * 2 // 2MB
    ): List<UInt> {
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

        // add the lastest offset
        return indexs.apply{ add(length.toUInt()) }
    }
   
    @Synchronized
    public fun treeSitterConfig(
        language: TSLanguage,
        pattern: String,
        enable: Boolean
    ) {        
        treeSitterEnable = enable
        if (treeSitterEnable) {
            val source = pieceTreeBuffer.getSnapshotContent()
            // inital tree sitter parser
            parser = TSParser(language)            
            // initial the syntax tree
            tree = treeSitterParse(null)
            // tree sitter query
            query = TSQuery(language, pattern)           
        }               
    }

    // tree sitter parse
    private fun treeSitterParse(
        oldTree: TSTree? = null,
        table: List<UInt> = splitBuffer()
    ): TSTree {
        // index of the indexs
        var index: Int = 0 
        return parser.parse(oldTree) { byteIndex, point ->            
            // for UTF-16 encoding requires byteIndex / 2U
            if (
                index < table.size && 
                byteIndex / 2U != 0U && 
                byteIndex / 2U >= table[index]
            ) {
                index++
            }

            if (index < table.size && byteIndex / 2U < table[index]) {
                return@parse pieceTreeBuffer
                    .substring((byteIndex / 2U).toInt(), table[index].toInt())
                    .toByteArray(Charsets.UTF_16LE)
            } else {
                return@parse String().toByteArray(Charsets.UTF_16LE)
            }
        }
    }
    
    private fun treeSitterQuery(
        text: String,
        lineNumber: Int,
        startOffset: Int,
        endOffset: Int
    ): SpannableString {
        val spannable = SpannableString(text)
        val lineStart = getLineStart(lineNumber)
        var prevNode = tree.rootNode
        query.byteRange = UIntRange(
            (lineStart + startOffset).toUInt() * 2U,
            (lineStart + endOffset).toUInt() * 2U
        )
        
        query.matches(tree.rootNode).forEach { match ->            
            val node = match.captures[0].node
            if (node.startByte < prevNode.endByte && prevNode != tree.rootNode) 
                return@forEach // equivalent to continue
            else 
                prevNode = node
                        
            var startByte = (node.startByte / 2U).toInt() - lineStart
            var endByte = (node.endByte / 2U).toInt() - lineStart
            
            if (endByte <= startOffset || startByte >= endOffset) {
                return@forEach // equivalent to continue
            }
            
            if (startByte < startOffset) {
                startByte = startOffset
            }
            
            if (endByte > endOffset) {
                endByte = endOffset
            }
            
            ColorScheme.tokenStyle[match.captures[0].name]?.let {
                // set typeface
                if (it.typeface != Typeface.NORMAL) {
                    spannable.setSpan(StyleSpan(it.typeface), startByte, endByte, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }

                // set underline
                if (it.underline) {
                    spannable.setSpan(UnderlineSpan(), startByte, endByte, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }

                // set foreground color
                spannable.setSpan(ForegroundColorSpan(it.color), startByte, endByte, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        
        return spannable
    }

    // edit the syntax tree
    @MainThread
    private fun treeSitterEdit(
        range: Range,
        rangeOffset: Int,
        insertedTextLength: Int,
        deletedTextLength: Int,
        finalLineNumber: Int,
        finalColumn: Int
    ) {        
        // edit the syntax tree must in main thread
        tree.edit(TSInputEdit(
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
        ))
    }
}

