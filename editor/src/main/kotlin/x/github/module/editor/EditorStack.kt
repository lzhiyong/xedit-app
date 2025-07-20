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

package x.github.module.editor

import kotlinx.serialization.Serializable
import x.github.module.piecetable.common.TextChange


@Serializable
public class EditorStack {

    // undo stack
    @Serializable(with = ArrayDequeSerializer::class)
    private var undoStack: ArrayDeque<List<TextChange>>

    // redo stack
    @Serializable(with = ArrayDequeSerializer::class)
    private var redoStack: ArrayDeque<List<TextChange>>

    init {
        undoStack = ArrayDeque<List<TextChange>>()
        redoStack = ArrayDeque<List<TextChange>>()
    }
    
    fun setUndoStack(undoStack: ArrayDeque<List<TextChange>>) {
        val recycle = this.undoStack
        this.undoStack = undoStack
        // free up the memory
        recycle.clear()
    }
    
    fun setRedoStack(redoStack: ArrayDeque<List<TextChange>>) {
        val recycle = this.redoStack
        this.redoStack = redoStack
        // free up the memory
        recycle.clear()
    }
    
    fun getUndoStack() = this.undoStack
    
    fun getRedoStack() = this.redoStack

    fun push(changes: List<TextChange>) {
        // add the changes
        undoStack.addFirst(changes)
    }

    fun undo(): List<TextChange> {
        // pop the top element from undo stack
        val changes = this.undoStack.removeFirst()
        // then add the element to redo stack
        this.redoStack.addFirst(changes)
        return changes
    }

    fun redo(): List<TextChange> {
        // pop the top element from redo stack
        val changes = this.redoStack.removeFirst()
        // then add the element to undo stack
        this.undoStack.addFirst(changes)
        return changes
    }

    fun canUndo() = this.undoStack.size > 0

    fun canRedo() = this.redoStack.size > 0

    fun clearUndo() {
        this.undoStack.clear()
    }

    fun clearRedo() {
        this.redoStack.clear()
    }
    
    fun clear() {
        clearUndo()
        clearRedo()
    }
    
    override fun toString(): String {
        return "undo_stack: ${undoStack} -> redo_stack: ${redoStack}"
    }
}

