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
 
package x.editor.app.model

import android.content.Context
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.LayoutRes


class BaseArrayAdapter<T: Any>(
    content: Context,
    @LayoutRes private val resource: Int,
    private val bindView: ArrayAdapter<T>.(Int, ViewHolder) -> Unit
) : ArrayAdapter<T>(content, resource) {
    
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
        return@getView view
    }
    
    class ViewHolder(val itemView: View) {
        // cache views for reuse and improved performance
        val sparseArray = SparseArray<View>()
        
        @Suppress("UNCHECKED_CAST") 
        fun <V: View> getView(viewId: Int): V {
            var view = sparseArray.get(viewId)            
            if(view == null) {
                view = itemView.findViewById(viewId)
                sparseArray.put(viewId, view)
            }
            return view as V
        }
    }
}

