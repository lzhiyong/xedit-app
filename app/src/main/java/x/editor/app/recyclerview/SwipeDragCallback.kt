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

package x.editor.app.recyclerview

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class SwipeDragCallback(
    dragDirs: Int, // ItemTouchHelper.UP or ItemTouchHelper.DOWN 
    swipeDirs: Int // ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
) : ItemTouchHelper.SimpleCallback(dragDirs, swipeDirs) { 
    
    /** An item was swiped away; called with its adapter position. */
    var onItemSwiped: ((Int) -> Unit)? = null
    
    /** An item is dragged over another; called with both adapter positions. */
    var onItemMoved: ((Int, Int) -> Boolean)? = null
    
    /** A swipe or drag of the item starts. */
    var onGestureStarted: ((RecyclerView.ViewHolder) -> Unit)? = null
    
    /** The swipe or drag of the item is over, and its view is back to normal. */
    var onGestureEnded: ((RecyclerView.ViewHolder) -> Unit)? = null
    
    /** Draw behind the item that is being swiped; the float is how far it moved. */
    var onDrawSwipe: ((Canvas, RecyclerView.ViewHolder, Float) -> Unit)? = null
    
    override fun onMove(
        recyclerView: RecyclerView,
        holder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = holder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
            return false
        }
        // update the adapter data
        return onItemMoved?.invoke(from, to) ?: false
    }
    
    override fun onSwiped(
        viewHolder: RecyclerView.ViewHolder,
        direction: Int
    ) {
        val position = viewHolder.bindingAdapterPosition
        // the item is already gone from the adapter, e.g. the list was replaced
        // while the swipe animation was running
        if (position == RecyclerView.NO_POSITION) return
        // update the adapter data
        onItemSwiped?.invoke(position)
    }
    
    override fun onSelectedChanged(
        viewHolder: RecyclerView.ViewHolder?,
        actionState: Int
    ) {       
        if(
            viewHolder != null && 
            actionState != ItemTouchHelper.ACTION_STATE_IDLE                    
        ) {
            onGestureStarted?.invoke(viewHolder)
        }
        super.onSelectedChanged(viewHolder, actionState)
    }
    
    override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ) {       
        onGestureEnded?.invoke(viewHolder)
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
            onDrawSwipe?.invoke(canvas, viewHolder, dX)
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
