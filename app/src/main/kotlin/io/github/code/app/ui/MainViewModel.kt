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
 
package io.github.code.app.ui
 
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
 
class MainViewModel : ViewModel() {

    // undo state
    private val _stateUndo = MutableStateFlow(false)
    public val stateUndo = _stateUndo.asStateFlow()

    // redo state
    private val _stateRedo = MutableStateFlow(false)
    public val stateRedo = _stateRedo.asStateFlow()

    // text scale state
    private val _stateTextScaled = MutableStateFlow(true)
    public val stateTextScaled = _stateTextScaled.asStateFlow()

    // text changed state
    private val _stateTextChanged = MutableStateFlow(false)
    public val stateTextChanged = _stateTextChanged.asStateFlow()
    
    // text changed callback
    private val _textSharedFlow = MutableSharedFlow<Boolean>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    public val textSharedFlow = _textSharedFlow.asSharedFlow()
    
    /*
    val sharedFlow = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    sharedFlow.tryEmit(InitialState()) // emit the initial value
    val stateFlow = sharedFlow.distinctUntilChanged() // get StateFlow-like behavior
    */
    
    fun setUndoState(state: Boolean) {
        _stateUndo.value = state
    }
    
    fun setRedoState(state: Boolean) {
        _stateRedo.value = state
    }
    
    fun setTextScaledState(state: Boolean) {
        _stateTextScaled.value = state
    }
    
    fun setTextChangedState(state: Boolean) {
        _stateTextChanged.value = state
        
        viewModelScope.launch {
            _textSharedFlow.emit(state)
        }
    }
    
    val exHandler = CoroutineExceptionHandler { _, exception ->
        // throw the exceptions to CrashReport for handle
        throw exception
    }

    // remove jobs
    inline fun removeJobs(jobs: Job?) {
        if(jobs != null) {
            viewModelScope.launch {
                jobs.cancelAndJoin()
            }
        }
    }

    // delay task(similar to the Handler.postDelayed)
    // note this running on main thread
    // dispatcher default is Dispatchers.Main
    public fun postDelayed(
        timeout: Long,
        block: suspend () -> Unit
    ) = viewModelScope.launch {
        delay(timeout)
        block.invoke()
    }

    // running on background thread
    public fun execute(
        dispatcher: CoroutineDispatcher,
        block: suspend CoroutineScope.() -> Unit
    ) = viewModelScope.launch(
        dispatcher + exHandler, 
        CoroutineStart.DEFAULT, 
        block
    )
}

