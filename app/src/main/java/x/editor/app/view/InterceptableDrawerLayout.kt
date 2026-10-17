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

package x.editor.app.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] which allows intercepting the touch events by its children.
 *
 * A touch that lands on a child which can scroll sideways is left to that child,
 * instead of being taken as the start of a drawer swipe.
 *
 * @author Akash Yadav
 */
open class InterceptableDrawerLayout : DrawerLayout {

    // Each constructor calls the matching one of DrawerLayout, so that its default
    // style (R.attr.drawerLayoutStyle) applies when no style is passed.
    constructor(context: Context) : super(context)
    
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    
    constructor(
        context: Context, attrs: AttributeSet?, defStyle: Int
    ) : super(context, attrs, defStyle)

    // reused for every touch event; only touched on the main thread
    private val hitRect = Rect()
    
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (findHorizontallyScrollableChild(this, hitRect, event.x, event.y) != null) {
            return false
        }
        return super.onInterceptTouchEvent(event)
    }
    
    /**
     * The topmost-in-tree view under the point ([x], [y]) that can scroll
     * horizontally, searched depth first in [parent]; null if there is none.
     *
     * The coordinates are relative to [parent]. For the drawer layout itself the
     * first child (the content) is skipped: only the drawers are searched.
     *
     * @param rect scratch space, overwritten
     */
    fun findHorizontallyScrollableChild(
        parent: ViewGroup, 
        rect: Rect = Rect(),  
        x: Float = 0f, 
        y: Float = 0f
    ): View? {
        val n = parent.childCount
        if (parent === this && n <= 1) {
            return null
        }

        val start = if (parent === this) 1 else 0

        for (i in start until n) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) {
                continue
            }

            child.getHitRect(rect)
            if (!rect.contains(x.toInt(), y.toInt())) {
                continue
            }
            
            if (
                child.canScrollHorizontally(-1) || // left
                child.canScrollHorizontally(1) // right
            ) {
                return child
            }

            if (child !is ViewGroup) {
                continue
            }

            // the offsets are read before the recursion overwrites rect
            val view = findHorizontallyScrollableChild(child, rect, x - rect.left, y - rect.top)
            if (view != null) {
                return view
            }
        }
        return null
    }
}
