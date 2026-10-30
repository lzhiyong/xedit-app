/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state the editor view and the activity share: the view writes it (see
 * CodeEditorView), the activity shows it (menu, progress bar, search).
 */
class EditorViewModel : ViewModel() {

    // undo state
    private val _canUndo = MutableStateFlow(false)
    public val canUndo = _canUndo.asStateFlow()

    // redo state
    private val _canRedo = MutableStateFlow(false)
    public val canRedo = _canRedo.asStateFlow()

    // text scale state: false while the text is laid out again in the background
    private val _isLayoutReady = MutableStateFlow(true)
    public val isLayoutReady = _isLayoutReady.asStateFlow()

    // text changed state: true while the text has changes that are not saved
    private val _isModified = MutableStateFlow(false)
    public val isModified = _isModified.asStateFlow()
    
    // text changed callback: unlike isModified it reports every change, also
    // when the value stays the same; only the latest one is kept
    private val _textChangeEvents = MutableSharedFlow<Boolean>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    public val textChangeEvents = _textChangeEvents.asSharedFlow()
    
    // search flow: every request is delivered, also the same text twice
    private val _searchRequests = MutableSharedFlow<String>() 
    public val searchRequests = _searchRequests.asSharedFlow()
    
    // throw the exceptions to CrashReport for handle
    val rethrowingExceptionHandler = CoroutineExceptionHandler { _, e -> throw e }
    
    fun setCanUndo(value: Boolean) {
        _canUndo.value = value
    }
    
    fun setCanRedo(value: Boolean) {
        _canRedo.value = value
    }
    
    fun setLayoutReady(value: Boolean) {
        _isLayoutReady.value = value
    }
    
    fun setModified(value: Boolean) {
        _isModified.value = value
        // never suspends: the flow drops the oldest value instead
        _textChangeEvents.tryEmit(value)
    }
    
    fun requestSearch(value: String) {      
        viewModelScope.launch {
            _searchRequests.emit(value)
        }
    }
}
