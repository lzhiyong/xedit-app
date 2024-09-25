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
 
package io.github.code.app.common

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.recyclerview.widget.RecyclerView

class BaseAdapter(
    // recyclerView datas
    var datas: MutableList<*>? = null,
    // requers valid layout id 
    var layout: Int = 0
) : RecyclerView.Adapter<BaseAdapter.ViewHolder>() {
    
    // bind view callback
    lateinit var onBindView: (ViewHolder, MutableList<*>, Int) -> Unit
    // click callback
    var onItemClick: (BaseAdapter.(View, MutableList<*>, Int) -> Unit)? = null
    // long click callback
    var onItemLongClick: (BaseAdapter.(View, MutableList<*>, Int) -> Unit)? = null
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // bind vew callback
        onBindView.invoke(holder, datas!!, position)
        
        // on click callback
        holder.itemView.setOnClickListener { v ->
            onItemClick?.invoke(this, v, datas!!, position)
        }
        
        // on long click callback
        holder.itemView.setOnLongClickListener { v ->
            onItemLongClick?.invoke(this, v, datas!!, position)
            return@setOnLongClickListener true
        }
    }
    
    override fun getItemViewType(position: Int) = position
     
    override fun getItemCount() = datas!!.size
     
     
    // ViewHolder
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        
        val sparseArray = SparseArray<View>()
        
        fun <T: View> getView(viewId: Int): T {
            var view = sparseArray.get(viewId)
            if(view == null) {
                view = itemView.findViewById(viewId)
                sparseArray.put(viewId, view)
            }
            return view as T
        }
    }
    
}

