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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/**
 * A generic RecyclerView.Adapter that can be reused for various data types
 *
 * The adapter shows [items] as it is: change the list, then tell the adapter with
 * the matching notify call (notifyItemInserted, notifyItemRemoved, ...).
 *
 * @param items the recycler view adapter source data
 * @param layoutId the item layout resource
 */
class GenericAdapter<T: Any>(
    // recyclerView adapter data
    private val items: List<T>,
    // requires valid layout res id
    @LayoutRes
    private val layoutId: Int
) : RecyclerView.Adapter<GenericAdapter.ViewHolder>() {
    
    // bind view callback
    var onBindView: ((ViewHolder, T) -> Unit)? = null
    // click callback
    var onItemClick: (GenericAdapter<T>.(ViewHolder, T) -> Unit)? = null
    // long click callback
    var onItemLongClick: (GenericAdapter<T>.(ViewHolder, T) -> Unit)? = null
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        val holder = ViewHolder(view)

        // see GenericListAdapter.onCreateViewHolder
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(this, holder, items[position])
            }
        }

        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemLongClick?.invoke(this, holder, items[position])
            }
            true
        }
        return holder
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        onBindView?.invoke(holder, items[position])
    }
     
    override fun getItemCount() = items.size
    
    // ViewHolder
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // cache views for reuse and improved performance
        private val cache = ViewCache(itemView)

        fun <V: View> getView(@IdRes viewId: Int): V = cache.get(viewId)
    }
}
