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

package io.github.module.editor.view

import android.graphics.Canvas
import io.github.module.editor.util.*

public class WordwrapLayout(val editor: EditorView) : TextLayout() {
    
    override var contentLayoutHeight: Int = 0
        get() = cacheLineResults.size * editor.getLineHeight()
        
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
    
    // note this should be run on background thread
    override fun relayout(
        isCancelled: () -> Boolean,
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit
    ) {       
        val maxWidth = editor.run {
            (getWidth() - getStartSpacing() - spacingWidth * 4/*getEndSpacing()*/).toFloat()
        }       
        contentLayoutWidth = maxWidth.toInt()       
        
        val count: Int = cacheLineResults.size
        var index: Int = 0
        var line: Int = 1
        while (line <= editor.getLineCount() && !isCancelled()) {           
            breakLine(line, editor.getLine(line), maxWidth).forEach { result ->
                if (index < count) {
                    cacheLineResults.set(index, result)
                } else {
                    cacheLineResults.add(result)
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
                cacheLineResults.removeAt(i)
            }
        }
        
        // check the task status and call onCompleted 
        // only if it is not cancelled
        if (!isCancelled()) {
            // finished callback
            onCompleted.invoke()      
        }
    }
    
    // note this should be run on background thread
    override fun measure() {
        // nothing to do for word wrap
        // since the layout max width is constant value
    }
    
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) {
        val maxWidth = editor.run {
            (getWidth() - getStartSpacing() - getEndSpacing()).toFloat()
        }
        
        if (maxWidth.toInt() != contentLayoutWidth) {
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
                index < cacheLineResults.size &&
                cacheLineResults[index].line == i + 1
            ) {
                cacheLineResults.removeAt(index)
            }
        }
        
        // modify current line
        var results = breakLine(startLine, editor.getLine(startLine), maxWidth)
        index = getStartIndex(startLine)
        cacheLineResults.addAll(index, results)
        index += results.size
        while (
            index < cacheLineResults.size &&
            cacheLineResults[index].line == startLine
        ) {
            cacheLineResults.removeAt(index)
        }
        
        // insert some lines
        for (j in startLine..(startLine + insertedLinesCnt - 1)) {                        
            // append at last index of prev line
            results = breakLine(j + 1, editor.getLine(j + 1), maxWidth)
            cacheLineResults.addAll(index, results)
            index += results.size           
        }
        
        val changedLinesCnt = insertedLinesCnt - deletedLinesCnt
        if (changedLinesCnt != 0) {
            for (k in index..cacheLineResults.size - 1) {
                cacheLineResults[k].line += changedLinesCnt
            }
        }
    }
}

