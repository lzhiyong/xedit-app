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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class XDividerItemDecoration(
    private val context: Context,
    private val orientation: Int = 1,
    private var divider: Drawable? = null,
    private val drawFirstDivider: Boolean = false,
    private val drawLastDivider: Boolean = true
) : RecyclerView.ItemDecoration() {
       
    init {
        if (divider == null) {
            // default divider style
            val attrs = intArrayOf(android.R.attr.listDivider)
            val a = context.obtainStyledAttributes(attrs)
            divider = a.getDrawable(0)
            a.recycle()
        }
    }
    
    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (orientation == RecyclerView.VERTICAL)
            drawVerticalDivider(c, parent)
        else
            drawHorizontalDivider(c, parent)
    }
    
    private fun drawVerticalDivider(c: Canvas, parent: RecyclerView) {
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight      
        // child count - 1 skip the last item divider
        val childCount = if(drawLastDivider) parent.childCount else parent.childCount - 1
        
        if (childCount > 0 && drawFirstDivider) {            
            // draw the first top divider
            divider!!.setBounds(
                left, 
                parent.getChildAt(0).top - divider!!.intrinsicHeight, 
                right, 
                divider!!.intrinsicHeight
            )
            divider!!.draw(c)
        }
        
        // horizontal divider
        for (i in 0 until childCount) { 
            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            val bottom = top + divider!!.intrinsicHeight
            divider!!.setBounds(left, top, right, bottom)
            divider!!.draw(c)
        }     
    }
    
    private fun drawHorizontalDivider(c: Canvas, parent: RecyclerView) {
        val top = parent.paddingTop
        val bottom = parent.height - parent.paddingBottom
        // child count - 1 skip the last item divider
        val childCount = if(drawLastDivider) parent.childCount else parent.childCount - 1
        
        if (childCount > 0 && drawFirstDivider) {            
            // draw the first left divider
            divider!!.setBounds(
                parent.getChildAt(0).left - divider!!.intrinsicWidth,
                top,
                divider!!.intrinsicWidth,
                bottom
            )
            divider!!.draw(c)
        }
        
        // vertical divider
        for (i in 0 until childCount) { 
            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val left = child.right + params.rightMargin
            val right = left + divider!!.intrinsicWidth
            divider!!.setBounds(left, top, right, bottom)
            divider!!.draw(c)
        }
    }
    
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        super.getItemOffsets(outRect, view, parent, state)        
        if (orientation == RecyclerView.VERTICAL)
            outRect.set(0, 0, 0, divider!!.intrinsicHeight)
        else
            outRect.set(0, 0, divider!!.intrinsicWidth, 0)
    }   
}

