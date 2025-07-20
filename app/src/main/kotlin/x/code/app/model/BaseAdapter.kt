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

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView


/**
 * The data wrapper is used to update the itemView state 
 * like click, long click, selection listeners and change the background etc
 *
 * @param T The type of the items in the list
 * @changed the state of itemView
 */
data class DataWrapper<T : Any>(
    val type : T,
    var changed: Boolean
)

/**
 * A generic DiffUtil.ItemCallback that can be reused for various data types
 *
 * @param T The type of the items in the list
 * @param idSelector A lambda function that returns a unique identifier for an item
 * @param contentComparator A lambda function that compares the content of two items
 */
class BaseDiffUtilCallback<T : Any>(
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
class BaseListAdapter<T: Any>(
    @LayoutRes
    private val layoutId: Int,
    idSelector: (T) -> Any,
    contentComparator: (T, T) -> Boolean
) : ListAdapter<T, BaseListAdapter.ViewHolder>(
    BaseDiffUtilCallback(idSelector, contentComparator)
) {

    // bind view callback
    var onBindView: ((ViewHolder, T) -> Unit)? = null
    // click callback
    var onItemClick: (BaseListAdapter<T>.(ViewHolder, T) -> Unit)? = null
    // long click callback
    var onItemLongClick: (BaseListAdapter<T>.(ViewHolder, T) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // bind view callback
        onBindView?.invoke(holder, getItem(position))

        // on click callback
        holder.itemView.setOnClickListener { v ->
            onItemClick?.invoke(this, holder, getItem(position))
        }

        // on long click callback
        holder.itemView.setOnLongClickListener { v ->
            onItemLongClick?.invoke(this, holder, getItem(position))
            return@setOnLongClickListener true
        }
    }

    // ViewHolder
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Cache views for reuse and improved performance
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


/**
 * A generic RecyclerView.Adapter that can be reused for various data types
 *
 * @param datas the recycler view adapter source data
 * @param layoutId the item layout resource
 */
class BaseAdapter<T: Any>(
    // recyclerView adapter data
    private val datas: List<T>,
    // requers valid layout res id 
    @LayoutRes
    private val layoutId: Int
) : RecyclerView.Adapter<BaseAdapter.ViewHolder>() {
    
    // bind view callback
    var onBindView: ((ViewHolder, T) -> Unit)? = null
    // click callback
    var onItemClick: (BaseAdapter<T>.(ViewHolder, T) -> Unit)? = null
    // long click callback
    var onItemLongClick: (BaseAdapter<T>.(ViewHolder, T) -> Unit)? = null
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // bind vew callback
        onBindView?.invoke(holder, datas[position])
        
        // on click callback
        holder.itemView.setOnClickListener {            
            onItemClick?.invoke(this, holder, datas[position])
        }
        
        // on long click callback
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(this, holder, datas[position])
            return@setOnLongClickListener true
        }
    }
    
    override fun getItemViewType(position: Int) = position
     
    override fun getItemCount() = datas.size
    
    // ViewHolder
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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

