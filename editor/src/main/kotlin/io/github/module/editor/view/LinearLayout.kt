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
import io.github.module.editor.util.LineBreakResult

public class LinearLayout(val editor: EditorView) : TextLayout() {
    
    override var contentLayoutHeight: Int = 0
        get() = editor.run {
            getLineCount() * getLineHeight()
        }
    
    override fun draw(canvas: Canvas) {
        // hardware accelerated
        if (canvas.isHardwareAccelerated) {
            editor.onHardwareDraw(canvas)
        } else {
            editor.onSoftwareDraw(canvas)
        }
    }
    
    // nothing to do for non-word-wrap
    private fun breakLine(line: Int) = editor.run {
        val text: String = getLine(line)
        LineBreakResult(line, 0, text.length, measureText(text))
    }
    
    // note this should be run on background thread
    override fun relayout(
        isCancelled: () -> Boolean,
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit
    ) {
        val count: Int = cacheLineResults.size
        var index: Int = 0
        var line: Int = 1
        while (line <= editor.getLineCount() && !isCancelled()) {
            if (index < count) {
                cacheLineResults.set(index, breakLine(line))
            } else {
                cacheLineResults.add(breakLine(line))
            }
            // continue to next position
            index++
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
            // re-compute the layout width
            this.measure()
            // finished callback
            onCompleted.invoke()      
        }
    }
    
    // note this should be run on background thread
    override fun measure() {
        // here only compute the layout width
        contentLayoutWidth = cacheLineResults.maxBy{ it.width }.width
    }
    
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) {
        // modify current line
        cacheLineResults.set(startLine - 1, breakLine(startLine))
        
        // remove some lines
        for (i in (startLine + deletedLinesCnt - 1) downTo startLine) {
            cacheLineResults.removeAt(i)
        }
              
        // insert some lines
        for (j in startLine..(startLine + insertedLinesCnt - 1)) {
            cacheLineResults.add(j, breakLine(j + 1))
        }
        
        if (insertedLinesCnt - deletedLinesCnt != 0) {
            for (k in (startLine + insertedLinesCnt)..cacheLineResults.size - 1) {
                cacheLineResults[k].line += insertedLinesCnt - deletedLinesCnt
            }
        }
        
        contentLayoutWidth = cacheLineResults.maxBy{ it.width }.width
    }
}

