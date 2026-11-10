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

package x.editor.app.view

import android.view.View

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope

import kotlin.reflect.KProperty

/**
 * A coroutine scope that lives as long as the view is attached to its window:
 *
 * ```
 * private val scope by obtainViewLifecycleScope()
 * ```
 *
 * The lifecycle is resumed while the view is attached and destroyed, for good,
 * when it is detached: a view that is attached again does not get a new scope.
 */
fun View.obtainViewLifecycleScope() = ViewLifecycleDelegate(this)

class ViewLifecycleDelegate(
    private val view: View
) : LifecycleOwner, View.OnAttachStateChangeListener {

    private val lifecycleRegistry = LifecycleRegistry(this)
    
    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        view.addOnAttachStateChangeListener(this)
        // created for a view that is attached already: there will be no callback
        if (view.isAttachedToWindow) {
            onViewAttachedToWindow(view)
        }
    }
    
    override val lifecycle: Lifecycle = lifecycleRegistry

    override fun onViewAttachedToWindow(v: View) {
        // a destroyed lifecycle cannot be started again
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onViewDetachedFromWindow(v: View) {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        view.removeOnAttachStateChangeListener(this)
    }

    operator fun getValue(view: View, property: KProperty<*>): LifecycleCoroutineScope {
        return this.lifecycleScope
    }
}
