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

import androidx.annotation.WorkerThread
import x.github.module.editor.util.LineBreakResult


public class UnwrappedLayout(host: TextLayout.Host) : TextLayout(host) {
    
    override var desiredWidth: Int = 0
    
    override var desiredHeight: Int = 0
        get() = host.getLineCount() * host.getLineHeight()
    
    // nothing to do for non-word-wrap
    private fun breakLine(line: Int): LineBreakResult {
        val text: String = host.getLine(line)
        return LineBreakResult(line, 0, text.length, host.measureText(text))
    }
    
    @WorkerThread
    override fun relayout(
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        isCancelled: () -> Boolean
    ) {
        val count: Int = lineBreaks.size
        var index: Int = 0
        var line: Int = 1
        while (line <= host.getLineCount() && !isCancelled()) {
            if (index < count) {
                lineBreaks.set(index, breakLine(line))
            } else {
                lineBreaks.add(breakLine(line))
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
                lineBreaks.removeAt(i)
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
        desiredWidth = lineBreaks.maxBy{ it.width }.width
    }
    
    @WorkerThread
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) {
        // modify current line
        lineBreaks.set(startLine - 1, breakLine(startLine))
        
        // remove some lines
        for (i in (startLine + deletedLinesCnt - 1) downTo startLine) {
            lineBreaks.removeAt(i)
        }
              
        // insert some lines
        for (j in startLine..(startLine + insertedLinesCnt - 1)) {
            lineBreaks.add(j, breakLine(j + 1))
        }
        
        if (insertedLinesCnt - deletedLinesCnt != 0) {
            for (k in (startLine + insertedLinesCnt)..lineBreaks.size - 1) {
                lineBreaks[k].line += insertedLinesCnt - deletedLinesCnt
            }
        }
        
        desiredWidth = lineBreaks.maxBy{ it.width }.width
    }
}

