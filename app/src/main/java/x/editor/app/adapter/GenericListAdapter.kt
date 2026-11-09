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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * A generic DiffUtil.ItemCallback that can be reused for various data types
 *
 * @param T The type of the items in the list
 * @param idSelector A lambda function that returns a unique identifier for an item
 * @param contentComparator A lambda function that compares the content of two items
 */
class GenericDiffCallback<T : Any>(
    private val idSelector: (T) -> Any,
    private val contentComparator: (T, T) -> Boolean
) : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
        return idSelector(oldItem) == idSelector(newItem)
    }

    override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
        return contentComparator(oldItem, newItem)
    }
}

/**
 * A generic ListAdapter that can be reused for various data types
 *
 * @param T The type of the items in the list.
 * @param idSelector A lambda function that returns a unique identifier for an item
 * @param contentComparator A lambda function that compares the content of two items
 * @param layoutId A layout resource id
 */
class GenericListAdapter<T: Any>(
    @LayoutRes
    private val layoutId: Int,
    idSelector: (T) -> Any,
    contentComparator: (T, T) -> Boolean
) : ListAdapter<T, GenericListAdapter.ViewHolder>(
    GenericDiffCallback(idSelector, contentComparator)
) {

    // bind view callback
    var onBindView: ((ViewHolder, T) -> Unit)? = null
    // click callback
    var onItemClick: (GenericListAdapter<T>.(ViewHolder, T) -> Unit)? = null
    // long click callback
    var onItemLongClick: (GenericListAdapter<T>.(ViewHolder, T) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        val holder = ViewHolder(view)

        // Set once per view, and the position is asked for when the click happens:
        // the one known at bind time is wrong as soon as an item before it is
        // inserted or removed.
        view.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(this, holder, getItem(position))
            }
        }

        view.setOnLongClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemLongClick?.invoke(this, holder, getItem(position))
            }
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        onBindView?.invoke(holder, getItem(position))
    }

    // ViewHolder
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // cache views for reuse and improved performance
        private val cache = ViewCache(itemView)

        fun <V: View> getView(@IdRes viewId: Int): V = cache.get(viewId)
    }
}
