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

sealed class TextLayout {
    // cache the per line break result
    @Volatile
    protected var cacheBreakResults = mutableListOf(
        // init the empty line break result
        LineBreakResult(1, 0, 0, 0)
    )
    
    protected abstract var desiredWidth: Int
    
    protected abstract var desiredHeight: Int
    
    @WorkerThread
    abstract fun measure()
    
    @WorkerThread
    abstract fun relayout(      
        inProgress: (Int) -> Unit = {},
        onCompleted: () -> Unit = {},
        isCancelled: () -> Boolean = {false}
    )
    
    @WorkerThread
    abstract fun update(
        startLine: Int, 
        insertedLinesCnt: Int, 
        deletedLinesCnt: Int
    )
    
    // draw the text content
    @MainThread
    abstract fun draw(canvas: Canvas)
    
    fun getLineResult(line: Int) = cacheBreakResults.run {
        get(Math.min(line, size) - 1)
    }
    
    fun getReallyLine(line: Int) = cacheBreakResults.run {
        get(Math.min(line, size) - 1).line
    }
    
    fun getLineStart(line: Int) = cacheBreakResults.run {
        get(Math.min(line, size) - 1).start
    }
    
    fun getLineEnd(line: Int) = cacheBreakResults.run {
        get(Math.min(line, size) - 1).end
    }
    
    fun getLineWidth(line: Int) = cacheBreakResults.run {
        get(Math.min(line, size) - 1).width
    }
    
    fun setWidth(width: Int) {
        this.desiredWidth = width
    }
    
    fun setHeight(height: Int) {
        this.desiredHeight = height
    }
    
    fun setBreakResults(
        breakResults: MutableList<LineBreakResult>
    ) {
        this.cacheBreakResults = breakResults
    }
    
    fun getBreakResults() = this.cacheBreakResults
    
    fun getStartIndex(line: Int) = cacheBreakResults.startBy(line)
    
    fun getEndIndex(line: Int) = cacheBreakResults.endBy(line)
    
    fun getIndexAt(line: Int, column: Int) = cacheBreakResults.indexBy(line, column)
    
    fun width() = this.desiredWidth
    
    fun height() = this.desiredHeight
    
    fun recycle() = cacheBreakResults.clear()
    
    fun count() = cacheBreakResults.size
}

