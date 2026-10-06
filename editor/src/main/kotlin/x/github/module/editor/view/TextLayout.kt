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
import x.github.module.editor.util.*

/**
 * The line-break model: maps visual (on-screen) lines to real buffer lines.
 *
 * A layout only measures and caches; it does not draw and does not know EditorView.
 * Everything it needs comes through [Host], and painting is [TextRenderer]'s job.
 */
sealed class TextLayout(protected val host: Host) {

    /** What a layout needs in order to measure text. */
    interface Host : EditorTextSource {
        /** The width available to a wrapped line (view width minus both text indents). */
        val wordwrapWidth: Int
        fun measureText(text: String): Int
        fun getTextWidths(text: String, start: Int = 0, end: Int = text.length): FloatArray
    }

    // cache the per line break result
    @Volatile
    protected var lineBreaks = mutableListOf(
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
    
    fun getLineResult(line: Int) = lineBreaks.run {
        get(Math.min(line, size) - 1)
    }
    
    fun getBufferLine(line: Int) = lineBreaks.run {
        get(Math.min(line, size) - 1).line
    }
    
    fun getLineStart(line: Int) = lineBreaks.run {
        get(Math.min(line, size) - 1).start
    }
    
    fun getLineEnd(line: Int) = lineBreaks.run {
        get(Math.min(line, size) - 1).end
    }
    
    fun getLineWidth(line: Int) = lineBreaks.run {
        get(Math.min(line, size) - 1).width
    }
    
    fun setWidth(width: Int) {
        this.desiredWidth = width
    }
    
    fun setHeight(height: Int) {
        this.desiredHeight = height
    }
    
    fun setBreakResults(
        lineBreaks: MutableList<LineBreakResult>
    ) {
        this.lineBreaks = lineBreaks
    }
    
    fun getBreakResults() = this.lineBreaks
    
    fun getStartIndex(line: Int) = lineBreaks.firstRowOf(line)
    
    fun getEndIndex(line: Int) = lineBreaks.lastRowOf(line)
    
    fun getIndexAt(line: Int, column: Int) = lineBreaks.rowIndexAt(line, column)
    
    fun width() = this.desiredWidth
    
    fun height() = this.desiredHeight
    
    fun recycle() = lineBreaks.clear()
    
    fun count() = lineBreaks.size
}

