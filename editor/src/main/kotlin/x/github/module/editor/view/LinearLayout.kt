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
import x.github.module.editor.util.LineBreakResult


public class LinearLayout(
    private val editor: EditorView
) : TextLayout() {
    
    override var desiredWidth: Int = 0
    
    override var desiredHeight: Int = 0
        get() = editor.run { getLineCount() * getLineHeight() }
    
    @MainThread
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
    
    @WorkerThread
    override fun relayout(
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        isCancelled: () -> Boolean
    ) {
        val count: Int = cacheBreakResults.size
        var index: Int = 0
        var line: Int = 1
        while (line <= editor.getLineCount() && !isCancelled()) {
            if (index < count) {
                cacheBreakResults.set(index, breakLine(line))
            } else {
                cacheBreakResults.add(breakLine(line))
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
                cacheBreakResults.removeAt(i)
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
    
    @WorkerThread
    override fun measure() {
        // here only compute the layout width
        desiredWidth = cacheBreakResults.maxBy{ it.width }.width
    }
    
    @WorkerThread
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) {
        // modify current line
        cacheBreakResults.set(startLine - 1, breakLine(startLine))
        
        // remove some lines
        for (i in (startLine + deletedLinesCnt - 1) downTo startLine) {
            cacheBreakResults.removeAt(i)
        }
              
        // insert some lines
        for (j in startLine..(startLine + insertedLinesCnt - 1)) {
            cacheBreakResults.add(j, breakLine(j + 1))
        }
        
        if (insertedLinesCnt - deletedLinesCnt != 0) {
            for (k in (startLine + insertedLinesCnt)..cacheBreakResults.size - 1) {
                cacheBreakResults[k].line += insertedLinesCnt - deletedLinesCnt
            }
        }
        
        desiredWidth = cacheBreakResults.maxBy{ it.width }.width
    }
}

