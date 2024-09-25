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

public abstract class TextLayout {
    // the text content width and height
    protected open var contentLayoutWidth: Int = 0
    protected open var contentLayoutHeight: Int = 0
    
    // cache the per line break result
    @Volatile
    protected var cacheLineResults = mutableListOf(
        // init the empty line break result
        LineBreakResult(1, 0, 0, 0)
    )
    
    // note this should be run on background thread
    abstract fun measure()
    
    // note this should be run on background thread
    abstract fun relayout(
        isCancelled: () -> Boolean = { false },
        inProgress: (Int) -> Unit = { },
        onCompleted: () -> Unit = { }
    )
    
    // update the layout
    abstract fun update(
        startLine: Int, 
        insertedLinesCnt: Int, 
        deletedLinesCnt: Int
    )
    
    // draw the text content
    abstract fun draw(canvas: Canvas)
    
    fun getLineResult(line: Int) = cacheLineResults.run {
        get(Math.min(line, size) - 1)
    }
    
    fun getReallyLine(line: Int) = cacheLineResults.run {
        get(Math.min(line, size) - 1).line
    }
    
    fun getLineStart(line: Int) = cacheLineResults.run {
        get(Math.min(line, size) - 1).start
    }
    
    fun getLineEnd(line: Int) = cacheLineResults.run {
        get(Math.min(line, size) - 1).end
    }
    
    fun getLineWidth(line: Int) = cacheLineResults.run {
        get(Math.min(line, size) - 1).width
    }
    
    fun getStartIndex(line: Int) = cacheLineResults.startBy(line)
    
    fun getEndIndex(line: Int) = cacheLineResults.endBy(line)
    
    fun getIndexAt(line: Int, column: Int) = cacheLineResults.indexBy(line, column)
    
    fun width() = this.contentLayoutWidth
    
    fun height() = this.contentLayoutHeight
    
    fun recycle() = cacheLineResults.clear()
    
    fun count() = cacheLineResults.size
}

