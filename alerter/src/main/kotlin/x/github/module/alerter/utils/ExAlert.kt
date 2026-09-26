/*
 * Copyright © 2024 Github Lzhiyong
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
 
package x.github.module.alerter.utils

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import androidx.annotation.DimenRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import x.github.module.alerter.Alert

fun Alert.getDimenPixelSize(@DimenRes id: Int) = resources.getDimensionPixelSize(id)

@RequiresApi(Build.VERSION_CODES.P)
fun Alert.notchHeight() = rootWindowInsets?.displayCutout?.safeInsetTop ?: 0

fun Context.getRippleDrawable(): Drawable? {
	val typedValue = TypedValue()
	theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
	val imageResId = typedValue.resourceId
	return ContextCompat.getDrawable(this, imageResId)
}
