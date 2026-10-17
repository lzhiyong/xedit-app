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

/**
 * Loads the children of a node lazily, the first time the node is expanded.
 *
 * Nodes created with `childrenLoaded = false` start in [TreeNode.LoadState.NOT_LOADED]. When
 * such a node is expanded, [TreeAdapter] switches it to [TreeNode.LoadState.LOADING], rebinds
 * its row (so a progress indicator can be shown) and calls [loadChildren]. The loader fetches
 * the children in whatever way it likes (disk, network, database) and reports back through
 * the [callback]. See [TreeAdapter.childLoader] for the complete life cycle.
 *
 * [loadChildren] is called on the main thread and must not block it.
 */
public fun interface ChildLoader<T> {

    /**
     * Starts loading the children of [node] and reports the result to [callback], exactly
     * once. Children must be new nodes without a parent; they may be lazy themselves.
     */
    public fun loadChildren(node: TreeNode<T>, callback: ChildLoadCallback<T>)
}

/**
 * Receives the result of one [ChildLoader.loadChildren] call.
 *
 * - Call exactly one of the methods, once; further calls are ignored.
 * - Both methods may be called from any thread; the result is applied on the main thread.
 * - Results of outdated requests are dropped silently: the node was removed, its children were
 *   reloaded with [TreeAdapter.reloadChildren], or the tree was replaced. It is therefore safe
 *   to let a request finish after the user has moved on.
 */
public interface ChildLoadCallback<T> {

    /** Delivers the loaded [children]; an empty list means the node is empty. */
    public fun onLoaded(children: List<TreeNode<T>>)

    /**
     * Reports that loading failed. The node becomes [TreeNode.LoadState.LOAD_FAILED] and is
     * collapsed; expanding it again retries.
     */
    public fun onFailed(error: Throwable?)
}
