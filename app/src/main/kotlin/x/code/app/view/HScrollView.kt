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
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.HorizontalScrollView

class HScrollView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    
    private var xStart: Float = 0f
    private var yStart: Float = 0f
    
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if(width >= getChildAt(0).width + paddingLeft + paddingRight) {
            return false
        }
        
        when (ev.getAction()) {
            MotionEvent.ACTION_DOWN -> {
                xStart = ev.getX()
                yStart = ev.getY()
            }
            MotionEvent.ACTION_MOVE -> {
                val xEnd = ev.getX()
                val yEnd = ev.getY()
                val xDistance = xEnd - xStart
                val yDistance = yEnd - yStart
                return if (Math.abs(xDistance) > Math.abs(yDistance)) true else false
            }
            MotionEvent.ACTION_UP -> { return false }
        }
        
        return super.onInterceptTouchEvent(ev)
    }
}

