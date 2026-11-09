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
 * Supplies the exact horizontal content width to [TreeLayoutManager].
 *
 * The layout manager can only measure rows that are currently laid out, so on its own it
 * doesn't know about wide rows that are scrolled out of view. A provider that knows the
 * width of every visible row (for example [TreeAdapter] with a [RowWidthCalculator]) makes
 * the horizontal scroll range exact.
 *
 * Implementations are queried on every layout pass, so they should be cheap (cached).
 */
public interface ContentWidthProvider {

    /**
     * Returns the width in pixels of the widest visible row, or [UNKNOWN] if the width
     * can't be determined. In the latter case the layout manager falls back to measuring rows.
     */
    public fun getContentWidth(): Int

    public companion object {
        /** Returned by [getContentWidth] when the width isn't known. */
        public const val UNKNOWN: Int = -1
    }
}
