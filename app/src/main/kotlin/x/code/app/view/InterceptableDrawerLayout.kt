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

package x.code.app.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] which allows intercepting the touch events by its children.
 *
 * @author Akash Yadav
 */
open class InterceptableDrawerLayout : DrawerLayout {
    
    constructor(context: Context) : this(context, null)
    
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    
    constructor(
        context: Context, attrs: AttributeSet?, defStyle: Int
    ) : super(context, attrs, defStyle) 
    
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val childView = findChildView(this, Rect(), event.x, event.y)
        
        if (childView != null) {
            return false
        }
        return super.onInterceptTouchEvent(event)
    }
    
    fun findChildView(
        parent: ViewGroup, 
        rect: Rect = Rect(),  
        x: Float = 0f, 
        y: Float = 0f
    ): View? {
        val n = parent.childCount
        if (parent === this && n <= 1) {
            return null
        }

        var start = 0
        if (parent === this) {
            start = 1
        }

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
            
            val view = findChildView(child, rect, x - rect.left, y - rect.top)
            if (view != null) {
                return view
            }
        }
        return null
    }
}

