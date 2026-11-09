/*
 * Copyright © 2026 Github Lzhiyong
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
package x.github.module.treeview

import android.view.View

/**
 * Called when the row of a node is clicked. Registered with [TreeAdapter.onNodeClickListener].
 *
 * Runs before the adapter's own click handling ([TreeAdapter.toggleOnClick]), so [node] still
 * has its previous expansion state.
 */
public fun interface OnNodeClickListener<T> {

    /**
     * @param node the node of the clicked row.
     * @param position the adapter position of the row.
     * @param view the clicked item view, e.g. to anchor a popup.
     */
    public fun onNodeClick(node: TreeNode<T>, position: Int, view: View)
}

/**
 * Called when the row of a node is long-clicked. Registered with
 * [TreeAdapter.onNodeLongClickListener].
 */
public fun interface OnNodeLongClickListener<T> {

    /**
     * @param node the node of the long-clicked row.
     * @param position the adapter position of the row.
     * @param view the long-clicked item view, e.g. to anchor a popup or context menu.
     * @return  if the long click was consumed; this also triggers haptic feedback.
     */
    public fun onNodeLongClick(node: TreeNode<T>, position: Int, view: View): Boolean
}
