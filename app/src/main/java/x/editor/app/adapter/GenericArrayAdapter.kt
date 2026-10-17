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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes


/**
 * An ArrayAdapter whose item views are filled by [bindView], which is called with
 * the adapter as receiver (so `getItem(position)`, `remove(...)` etc. are at hand).
 */
class GenericArrayAdapter<T: Any>(
    context: Context,
    @LayoutRes private val resource: Int,
    private val bindView: ArrayAdapter<T>.(Int, ViewHolder) -> Unit
) : ArrayAdapter<T>(context, resource) {
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {        
        val view: View
        val viewHolder: ViewHolder

        if (convertView == null) {
            // initialize View and ViewHolder
            view = LayoutInflater.from(context).inflate(resource, parent, false)
            viewHolder = ViewHolder(view)
            view.tag = viewHolder
        } else {
            // reusing existing the View and ViewHolder
            view = convertView
            viewHolder = view.tag as ViewHolder
        }
        
        // callback for bind view
        bindView(position, viewHolder)       
        return view
    }
    
    class ViewHolder(val itemView: View) {
        // cache views for reuse and improved performance
        private val cache = ViewCache(itemView)

        fun <V: View> getView(@IdRes viewId: Int): V = cache.get(viewId)
    }
}
