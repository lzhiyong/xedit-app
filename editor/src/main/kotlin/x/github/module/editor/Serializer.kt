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

package x.github.module.editor

import android.content.Context
import android.graphics.Typeface

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

import x.github.module.editor.util.LineBreakResult
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.TextChange


/**
 * The serializer for ArrayDeque
 */
class ArrayDequeSerializer<T>(
    private val elementSerializer: KSerializer<T>
) : KSerializer<ArrayDeque<T>> {
    private val listSerializer = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun serialize(encoder: Encoder, value: ArrayDeque<T>) {
        encoder.encodeSerializableValue(listSerializer, value.toList())
    }

    override fun deserialize(decoder: Decoder): ArrayDeque<T> {
        return ArrayDeque(decoder.decodeSerializableValue(listSerializer))
    }
}

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

@Serializable
data class SavedState(
    val uri: String,
    val hash: String,
    val modified: Boolean,
    val position: Position,
    val range: Range,
    val layoutWidth: Int,
    val layoutHeight: Int,
    val textSize: Float,
    val fontDescriptor: TypefaceDescriptor,
    val breakResults: MutableList<LineBreakResult>,
    @Serializable(with = ArrayDequeSerializer::class)
    val undoStack: ArrayDeque<List<TextChange>>,
    @Serializable(with = ArrayDequeSerializer::class)
    val redoStack: ArrayDeque<List<TextChange>>,
    val matchResults: MutableList<Range>
    /* val textBuffer: PieceTreeTextBuffer */
)

