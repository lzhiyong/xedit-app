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
 
package x.code.app.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
 
class MainViewModel : ViewModel() {

    // undo state
    private val _canUndo = MutableStateFlow(false)
    public val canUndo = _canUndo.asStateFlow()

    // redo state
    private val _canRedo = MutableStateFlow(false)
    public val canRedo = _canRedo.asStateFlow()

    // text scale state
    private val _isTextScaled = MutableStateFlow(true)
    public val isTextScaled = _isTextScaled.asStateFlow()

    // text changed state
    private val _isTextChanged = MutableStateFlow(false)
    public val isTextChanged = _isTextChanged.asStateFlow()
    
    // text changed callback
    private val _textSharedFlow = MutableSharedFlow<Boolean>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    public val textSharedFlow = _textSharedFlow.asSharedFlow()
    
    // throw the exceptions to CrashReport for handle
    val exHandler = CoroutineExceptionHandler { _, e -> throw e }
    
    /*
    val sharedFlow = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    sharedFlow.tryEmit(InitialState()) // emit the initial value
    val stateFlow = sharedFlow.distinctUntilChanged() // get StateFlow-like behavior
    */
    
    fun setCanUndo(value: Boolean) {
        _canUndo.value = value
    }
    
    fun setCanRedo(value: Boolean) {
        _canRedo.value = value
    }
    
    fun setTextScaled(value: Boolean) {
        _isTextScaled.value = value
    }
    
    fun setTextChanged(value: Boolean) {
        _isTextChanged.value = value
        
        viewModelScope.launch {
            _textSharedFlow.emit(value)
        }
    }
}

