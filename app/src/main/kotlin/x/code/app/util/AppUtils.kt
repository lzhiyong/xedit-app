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

package x.code.app.util

import android.content.Context
import android.util.TypedValue

object AppUtils {
    fun resolveAttr(context: Context, id: Int): Int {
        val typedValue = TypedValue()
        context.getTheme().resolveAttribute(id, typedValue, true)
        return typedValue.resourceId
    }
    
    fun getStatusBarHeight(context: Context) = context.getResources().run {
        getDimensionPixelSize(getIdentifier("status_bar_height", "dimen", "android"))
    }
    
    fun getActionBarHeight(context: Context): Int {
        val tv = TypedValue()
        return if(context.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, context.resources.getDisplayMetrics())
        } else {
            0
        }
    }
}

