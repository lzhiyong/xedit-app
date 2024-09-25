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

package io.github.code.app.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

import io.github.code.app.R

class AppDrawerLayout : DrawerLayout {
    
    constructor(context: Context) : this(context, null)
    
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    
    constructor(
        context: Context, attrs: AttributeSet?, defStyle: Int
    ) : super(context, attrs, defStyle) 
    
    init {
        addDrawerListener(object: SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
               val gravity = (drawerView.layoutParams as LayoutParams).gravity
               val view = findViewById<View>(R.id.app_bar_main) ?: return
               val offset = (drawerView.width * slideOffset) * 0.2f
               view.translationX = (if (gravity == GravityCompat.START) 1 else -1) * offset
            }
        })
    }
    
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
            if (child.visibility == View.GONE) {
                continue
            }

            child.getHitRect(rect)
            if (rect.contains(x.toInt(), y.toInt())) {
                if (child.canScrollHorizontally(-1) || child.canScrollVertically(-1)) {
                    return child
                } else if (child is ViewGroup) {
                    findChildView(child, rect, x - rect.left, y - rect.top)?.let {
                        return@findChildView it
                    }
                }
            }
        }
        return null
    }
    
}

