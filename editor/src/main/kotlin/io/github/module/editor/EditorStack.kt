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
 
 package io.github.module.editor
 
 import io.github.module.piecetable.common.TextChange
 
 import java.util.ArrayDeque
 
 class EditorStack(capacity: Int = 1000) {
     
     // undo stack
     private val undoStack: ArrayDeque<List<TextChange>?>
     // redo stack
     private val redoStack: ArrayDeque<List<TextChange>?>
     
     init {
         undoStack = ArrayDeque<List<TextChange>?>(capacity)
         redoStack = ArrayDeque<List<TextChange>?>(capacity)
     }
     
     fun push(changes: List<TextChange>?) {
         // add changes
         undoStack.push(changes)
     }
     
     fun undo(): List<TextChange>? {
        // pop the top element from undo stack
        val changes = this.undoStack.poll()
        // then add the element to redo stack
        this.redoStack.push(changes)
        
        return changes
     }
     
     fun redo(): List<TextChange>? {
        // pop the top element from redo stack
        val changes = this.redoStack.poll()
        // then add the element to undo stack
        this.undoStack.push(changes)
        
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
 }
 
 