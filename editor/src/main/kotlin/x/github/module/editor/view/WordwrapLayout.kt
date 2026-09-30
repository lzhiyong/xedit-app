/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.editor.view

import android.graphics.Canvas
import androidx.annotation.WorkerThread
import androidx.annotation.MainThread
import x.github.module.editor.util.*


public class WordwrapLayout(
    private val editor: EditorView
) : TextLayout() {
    
    override var desiredWidth: Int = 0
    
    override var desiredHeight: Int = 0
        get() = cacheBreakResults.size * editor.getLineHeight()
    
    @MainThread
    override fun draw(canvas: Canvas) {
        // hardware accelerated
        if (canvas.isHardwareAccelerated) {
            editor.onWordwrapHardwareDraw(canvas)
        } else {
            editor.onWordwrapSoftwareDraw(canvas)
        }
    }
    
    private fun breakLine(
        lineNumber: Int,
        text: String, 
        maxWidth: Float
    ): List<LineBreakResult> {
        // measure per line width
        val widths = editor.getTextWidths(text)
        val lineBreaks = breakText(text, widths, maxWidth)
        val lineResults = mutableListOf<LineBreakResult>()
        var start: Int = 0
        var end: Int = 0
        var width: Int = 0
        for (i in 0..lineBreaks.size - 1) {
            end = lineBreaks[i]
            width = Math.ceil(widths.sumOf(start, end).toDouble()).toInt()
            lineResults.add(LineBreakResult(lineNumber, start, end, width))
            start = end
        }
              
        return lineResults
    }
    
    @WorkerThread
    override fun relayout(
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        isCancelled: () -> Boolean
    ) {       
        val maxWidth = editor.run {
            (getWidth() - getStartSpacing() - spacingWidth * 4/*getEndSpacing()*/).toFloat()
        }       
        desiredWidth = maxWidth.toInt()       
        
        val count: Int = cacheBreakResults.size
        var index: Int = 0
        var line: Int = 1
        while (line <= editor.getLineCount() && !isCancelled()) {           
            breakLine(line, editor.getLine(line), maxWidth).forEach { result ->
                if (index < count) {
                    cacheBreakResults.set(index, result)
                } else {
                    cacheBreakResults.add(result)
                }
                // continue to next position
                index++
            }
            // continue to next line
            line++
        }
        
        // when the number of line break decreases
        // remove the redundant items
        for (i in count - 1 downTo index) {
            if (!isCancelled()) {
                cacheBreakResults.removeAt(i)
            }
        }
        
        // check the task status and call onCompleted 
        // only when if it is not cancelled
        if (!isCancelled()) {
            // finished callback
            onCompleted.invoke()      
        }
    }
    
    @WorkerThread
    override fun measure() {
        // nothing to do for word wrap
        // since the layout max width is constant value
    }
    
    @WorkerThread
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) {
        val maxWidth = editor.run {
            (getWidth() - getStartSpacing() - getEndSpacing()).toFloat()
        }
        
        if (maxWidth.toInt() != desiredWidth) {
            // here needs to re-layout
            // when the max width of layout has been changed
            // there maybe lag for large text
            return relayout()
        }
        
        // remove some lines
        var index: Int = 0
        for (i in (startLine + deletedLinesCnt - 1) downTo startLine) {
            index = getStartIndex(i + 1)
            while (
                index < cacheBreakResults.size &&
                cacheBreakResults[index].line == i + 1
            ) {
                cacheBreakResults.removeAt(index)
            }
        }
        
        // modify current line
        var results = breakLine(startLine, editor.getLine(startLine), maxWidth)
        index = getStartIndex(startLine)
        cacheBreakResults.addAll(index, results)
        index += results.size
        while (
            index < cacheBreakResults.size &&
            cacheBreakResults[index].line == startLine
        ) {
            cacheBreakResults.removeAt(index)
        }
        
        // insert some lines
        for (j in startLine..(startLine + insertedLinesCnt - 1)) {                        
            // append at last index of prev line
            results = breakLine(j + 1, editor.getLine(j + 1), maxWidth)
            cacheBreakResults.addAll(index, results)
            index += results.size           
        }
        
        val changedLinesCnt = insertedLinesCnt - deletedLinesCnt
        if (changedLinesCnt != 0) {
            for (k in index..cacheBreakResults.size - 1) {
                cacheBreakResults[k].line += changedLinesCnt
            }
        }
    }
}

