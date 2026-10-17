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

package x.editor.app.adapter

/**
 * An item of a list together with a flag for its view, to tell rows apart that
 * need another look (selected, clicked, with another background etc).
 *
 * @param T The type of the items in the list
 * @property item the item itself
 * @property isChanged whether the state of its itemView was changed
 */
data class ItemState<T : Any>(
    val item: T,
    var isChanged: Boolean
)
