/*
 * Copyright © 2022 Github Lzhiyong
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

package x.github.module.editor.util

import android.content.Context
import android.graphics.Typeface

import kotlinx.serialization.Serializable

/**
 * A typeface in a form that can be saved: by family name, or by the path of a font
 * file (in the app's assets if the path starts with "assets", on disk otherwise).
 */
@Serializable
data class TypefaceDescriptor(
    val name: String? = null, 
    val path: String? = null, 
    val style: Int = Typeface.NORMAL
) {
    fun toTypeface(context: Context): Typeface {
        return when {
            !path.isNullOrEmpty() -> {
                try {
                     if (path.startsWith("assets"))
                         Typeface.createFromAsset(context.assets, path)                        
                     else
                         Typeface.createFromFile(path)
                } catch (e: Exception) {                
                    if (!name.isNullOrEmpty())
                        Typeface.create(name, style)
                    else 
                        Typeface.defaultFromStyle(style)                   
                }
            }
            !name.isNullOrEmpty() -> Typeface.create(name, style)
            else -> Typeface.defaultFromStyle(style)
        }
    }
}
