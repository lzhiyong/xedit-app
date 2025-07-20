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

import android.graphics.Canvas
import android.graphics.Color
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SimpleItemCallback(
    dragDirs: Int, // ItemTouchHelper.UP or ItemTouchHelper.DOWN 
    swipeDirs: Int // ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
) : ItemTouchHelper.SimpleCallback(dragDirs, swipeDirs) { 
    
    var swipeCallback: ((Int) -> Unit)? = null
    
    var moveCallback: ((Int, Int) -> Boolean)? = null
    
    var stateCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    
    var clearCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    
    var drawCallback: ((Canvas, RecyclerView.ViewHolder, Float) -> Unit)? = null
    
    override fun onMove(
        recyclerView: RecyclerView,
        holder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        // update the adapter data
        return moveCallback?.invoke(
             holder.getAdapterPosition(), 
             target.getAdapterPosition()
        ) ?: false
    }
    
    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int
    ) {
        // update the adapter data
        swipeCallback?.invoke(viewHolder.getAdapterPosition())
    }
    
    override fun onSelectedChanged(
        viewHolder: RecyclerView.ViewHolder?,
        actionState: Int
    ) {       
        if(
            viewHolder != null && 
            actionState != ItemTouchHelper.ACTION_STATE_IDLE                    
        ) {
            stateCallback?.invoke(viewHolder)
        }
        super.onSelectedChanged(viewHolder, actionState)
    }
    
    override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ) {       
        clearCallback?.invoke(viewHolder)
        super.clearView(recyclerView, viewHolder)
    }
    
    override fun getSwipeThreshold(
        viewHolder: RecyclerView.ViewHolder
    ) = 0.5f
    
    
    override fun onChildDraw(
        canvas: Canvas, 
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder, 
        dX: Float, 
        dY: Float, 
        actionState: Int, 
        isCurrentlyActive: Boolean
    ) {
        if(actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            // draw for swipe item 
            drawCallback?.invoke(canvas, viewHolder, dX)
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}

