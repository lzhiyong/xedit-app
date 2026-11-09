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

package x.editor.app.adapter

import android.util.SparseArray
import android.view.View
import androidx.annotation.IdRes

/**
 * Finds the child views of an item view by id, each one only once: looking a view
 * up again returns the one found the first time.
 */
class ViewCache(private val root: View) {

    private val views = SparseArray<View>()

    /**
     * @throws IllegalArgumentException if the item layout has no view with this id
     * @throws ClassCastException if that view is not a [V]
     */
    @Suppress("UNCHECKED_CAST")
    fun <V : View> get(@IdRes viewId: Int): V {
        var view = views.get(viewId)
        if (view == null) {
            view = root.findViewById(viewId) ?: throw IllegalArgumentException(
                "The item layout has no view with id 0x${Integer.toHexString(viewId)}"
            )
            views.put(viewId, view)
        }
        return view as V
    }
}
