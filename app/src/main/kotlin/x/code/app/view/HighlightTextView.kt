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

package x.code.app.view

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
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.text.StaticLayout
import android.text.TextPaint

import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.annotation.MainThread

import x.code.app.model.MainViewModel
import x.code.app.model.Span
import x.code.app.model.TreeSitter

import x.github.module.editor.util.sumOf
import x.github.module.editor.view.EditorView
import x.github.module.editor.SavedState
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Range
import x.github.module.treesitter.*

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*


class HighlightTextView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : EditorView(context, attrs, defStyleAttr) {
      
    // task cache buffer
    private val tasksDeque = ArrayDeque<Job>()
    
    private val lifecycleScope by obtainViewLifecycleScope()
    
    public val treeSitter by lazy { TreeSitter(context) }
        
    private val viewModel by lazy {
        findViewTreeViewModelStoreOwner()!!.run {
            ViewModelProvider(this).get(MainViewModel::class.java)
        }
    }
    
    init {
        // hardware accelerated
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }    
    
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        // cancel the previous job
        tasksDeque.removeFirstOrNull()?.let {
            lifecycleScope.launch { it.cancelAndJoin() }
        }      
        return super.onScaleBegin(detector)
    }
    
    @ExperimentalCoroutinesApi
    override fun onScaleEnd(detector: ScaleGestureDetector) {        
        viewModel.setTextScaled(false)
        tasksDeque.addLast(
            lifecycleScope.launch(Dispatchers.Default.limitedParallelism(1)) {
                // start text relayout
                scaleCallback(cancelled = { !isActive }) {
                    // scale finished
                    viewModel.setTextScaled(true)
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
        lifecycleScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            textLayout.measure()
        }
        
        // update the abstract syntax tree 
        // this must be running on main thread
        with(treeSitter) TreeSitter@ { 
            if (isEnabled) {
                this@TreeSitter.edit(
                    range,
                    rangeOffset, 
                    insertedTextLength,
                    deletedTextLength,
                    finalLineNumber,
                    finalColumn
                )
            }
        }            
    }

    override fun afterTextChanged(
        changes: List<ContentChange>,
        lastLineNumber: Int,
        lastColumn: Int
    ) {
        // perform text changed callback
        viewModel.setTextChanged(true)
        
        // reparse the abstract syntax tree        
        with(treeSitter) {
            if (isEnabled) parse(pieceTreeBuffer)
        }
               
        // update text and cursor state
        super.afterTextChanged(
            changes, lastLineNumber, lastColumn
        )
    }
    
    override fun onMergedBefore() {
        // to change the redo state
        viewModel.setCanRedo(false)
    }
    
    override fun onMergedAfter() {
        // to change the undo state 
        viewModel.setCanUndo(true)
    }
    
    override fun undo(): Boolean {
        if (isEditable() && stack.canUndo()) {
            super.undo()
            viewModel.setCanUndo(stack.canUndo())
            viewModel.setCanRedo(stack.canRedo())
        }
        return viewModel.canUndo.value
    }
    
    override fun redo(): Boolean {
        if (isEditable() && stack.canRedo()) {
            super.redo()
            viewModel.setCanRedo(stack.canRedo())
            viewModel.setCanUndo(stack.canUndo())
        }
        return viewModel.canRedo.value
    }
    
    /**
     *
     */
    override fun drawText(
        canvas: Canvas,    // the canvas for draw
        text: String,        // the current line text
        line: Int,            // the current line number
        startOffset: Int,    // visible start column
        endOffset: Int,     // visible end column
        xPaint: Float,      // draw the starting x coordinate
        yPaint: Float,      // draw the starting y coordinate
    ) {
        if (text.length > 0 && treeSitter.isEnabled) {
            // draw the spannable string
            // for performance reasons, StaticLayout is not used to directly draw spans
            val lineStart = getLineStart(line)
            val spannable = treeSitter.query(text, lineStart, startOffset, endOffset)
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
    
    override fun restoreState(
        savedState: SavedState, 
        textBuffer: PieceTreeTextBuffer
    ) {
        // here request restores the scroll state
        scrollTo(0, 0)
        // now restore the editor states
        super.restoreState(savedState, textBuffer)
        // discard the old node display datas
        recycleRenderNode()
        invalidate()
    } 
}

