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

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max

/**
 * A [RecyclerView.LayoutManager] for tree views: a single column of fixed-height rows whose
 * content may be wider than the viewport. It scrolls both vertically and horizontally inside
 * one RecyclerView, without nesting scroll containers.
 *
 * ## Design
 *
 * **Coordinate model.** The scroll position is two absolute offsets, `verticalOffset` and
 * `horizontalOffset`. Since every row has the same height, the visible row range follows
 * directly from `verticalOffset`: layout costs O(visible rows) and large scroll deltas (fast
 * flings) never leave gaps. Horizontal scrolling never changes which rows are visible, so it
 * only translates the attached children.
 *
 * **Content width** (the horizontal scroll range) is the width of the widest row. It comes from:
 * 1. a [ContentWidthProvider]: [contentWidthProvider], or else the adapter if it implements
 *    the interface (such as [TreeAdapter] with a [RowWidthCalculator]). This value is exact;
 * 2. the natural width of the rows measured so far. This value is reset when the data
 *    structure changes (expand, collapse, insert, remove) and only grows while scrolling, so
 *    vertical scrolling never makes the horizontal position jump.
 *
 * The larger of the two is used, so an underestimating provider never clips a row.
 *
 * **Row measurement.** A row is first measured with an UNSPECIFIED width to get its natural
 * width. If [stretchRowWidth] is on, it is measured again with an exact width of
 * `max(contentWidth, viewportWidth)`, so that row backgrounds and dividers span the whole
 * scrollable area. Rows are re-measured only when that target width changes.
 *
 * **Axis lock.** See [isAxisLockEnabled]. RecyclerView consults [canScrollHorizontally] and
 * [canScrollVertically] for every touch event and when it computes the fling velocity, so
 * returning `false` for the locked-out axis suppresses both dragging and flinging along it,
 * without subclassing RecyclerView.
 *
 * ## Requirements and limitations
 * - The RecyclerView needs an exact size (`match_parent` or fixed); auto-measure is off.
 * - All rows have the same height, see [rowHeight].
 * - Predictive item animations aren't supported; regular add/remove/move animations run.
 * - Right-to-left layouts aren't supported; rows always start at the left edge.
 */
public class TreeLayoutManager @JvmOverloads public constructor(
    rowHeight: Int = 0,
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    private companion object {
        const val UNSET = -1
        const val INVALID_OFFSET = Int.MIN_VALUE

        const val KEY_VERTICAL_OFFSET = "tree_layout_manager:vertical_offset"
        const val KEY_HORIZONTAL_OFFSET = "tree_layout_manager:horizontal_offset"

        const val AXIS_NONE = 0
        const val AXIS_HORIZONTAL = 1
        const val AXIS_VERTICAL = 2
    }

    /**
     * Height of every row in pixels, including item decoration insets and vertical margins.
     * A value <= 0 means: measure the first item once and use its height.
     */
    public var rowHeight: Int = rowHeight
        set(value) {
            if (field == value) return
            field = value
            resolvedRowHeight = value.coerceAtLeast(0)
            requestLayout()
        }

    /**
     * Source of the exact content width. When `null`, the adapter is used if it implements
     * [ContentWidthProvider]; otherwise the width is derived from measured rows only.
     */
    public var contentWidthProvider: ContentWidthProvider? = null
        set(value) {
            field = value
            invalidateContentWidth()
        }

    /**
     * When `true` (default), every row is stretched to `max(contentWidth, viewportWidth)`,
     * so that selection backgrounds and dividers cover the whole row while scrolled sideways.
     * When `false`, each row keeps its natural width.
     */
    public var stretchRowWidth: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * When `true` (default), each drag is locked to one axis: as soon as the finger has moved
     * beyond the touch slop, the dominant direction wins and the gesture, including its fling,
     * scrolls only along that axis. When `false`, diagonal scrolling is possible.
     */
    public var isAxisLockEnabled: Boolean = true
        set(value) {
            field = value
            lockedAxis = AXIS_NONE
        }

    /** Current content width in pixels, i.e. the horizontal scroll range. */
    public var contentWidth: Int = 0
        private set

    // Row height actually used for layout: `rowHeight`, or the measured height of item 0.
    private var resolvedRowHeight: Int = rowHeight.coerceAtLeast(0)

    // Absolute scroll position of the viewport's top-left corner within the content.
    private var verticalOffset = 0
    private var horizontalOffset = 0

    // Widest natural row width measured since the data structure last changed.
    private var measuredMaxWidth = 0
    private var isContentWidthDirty = true

    // Whether the provider returned a known width during the last layout pass.
    private var isProvidedWidthExact = false

    private var pendingScrollPosition = RecyclerView.NO_POSITION
    private var pendingScrollOffset = INVALID_OFFSET

    private var recyclerView: RecyclerView? = null
    private val decorInsets = Rect()

    // Axis lock state, driven by AxisLockTouchListener.
    private var lockedAxis = AXIS_NONE
    private var touchSlop = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val axisLockTouchListener = AxisLockTouchListener()

    private val horizontalSpace: Int get() = width - paddingLeft - paddingRight
    private val verticalSpace: Int get() = height - paddingTop - paddingBottom
    private val rowWidth: Int get() = max(contentWidth, horizontalSpace)
    private val maxHorizontalOffset: Int get() = (contentWidth - horizontalSpace).coerceAtLeast(0)
    private val isHorizontallyScrollable: Boolean get() = contentWidth > horizontalSpace

    private val effectiveContentWidthProvider: ContentWidthProvider?
        get() = contentWidthProvider ?: recyclerView?.adapter as? ContentWidthProvider

    /**
     * Recomputes the content width on the next layout pass. Call it when row widths changed
     * without an adapter notification (text size, indentation, ...). [TreeAdapter] calls it
     * from [TreeAdapter.invalidateRowWidths].
     */
    public fun invalidateContentWidth() {
        isContentWidthDirty = true
        requestLayout()
    }

    /** Adapter position of the first (topmost) attached row, or [RecyclerView.NO_POSITION]. */
    public fun findFirstVisibleItemPosition(): Int =
        getChildAt(0)?.let { getPosition(it) } ?: RecyclerView.NO_POSITION

    /** Adapter position of the last (bottommost) attached row, or [RecyclerView.NO_POSITION]. */
    public fun findLastVisibleItemPosition(): Int =
        getChildAt(childCount - 1)?.let { getPosition(it) } ?: RecyclerView.NO_POSITION

    /**
     * Scrolls so that the top of the row at [position] is [offset] pixels below the top of the
     * viewport (after padding). The horizontal position is kept.
     */
    public fun scrollToPositionWithOffset(position: Int, offset: Int) {
        pendingScrollPosition = position
        pendingScrollOffset = offset
        requestLayout()
    }

    // region Layout

    override fun isAutoMeasureEnabled(): Boolean = false

    override fun supportsPredictiveItemAnimations(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            resetScrollState()
            return
        }
        if (state.isPreLayout) return

        // Full relayout: every row is re-added and re-measured by fill().
        detachAndScrapAttachedViews(recycler)

        if (resolvedRowHeight <= 0) {
            resolveRowHeight(recycler)
            if (resolvedRowHeight <= 0) return
        }

        updateContentWidthFromProvider()
        applyPendingScroll(state)
        verticalOffset = verticalOffset.coerceIn(0, maxVerticalOffset(state))

        fill(recycler, state)

        // The content may have become narrower (e.g. after a collapse): pull the horizontal
        // offset back into range and shift the freshly laid out rows accordingly.
        val maxOffset = maxHorizontalOffset
        if (horizontalOffset > maxOffset) {
            val shift = horizontalOffset - maxOffset
            horizontalOffset = maxOffset
            offsetChildrenHorizontal(shift)
        }
    }

    private fun resetScrollState() {
        verticalOffset = 0
        horizontalOffset = 0
        contentWidth = 0
        measuredMaxWidth = 0
    }

    private fun updateContentWidthFromProvider() {
        if (isContentWidthDirty) {
            measuredMaxWidth = 0
            contentWidth = 0
            isContentWidthDirty = false
        }
        val provided = effectiveContentWidthProvider?.getContentWidth() ?: ContentWidthProvider.UNKNOWN
        isProvidedWidthExact = provided >= 0
        if (isProvidedWidthExact) contentWidth = max(contentWidth, provided)
    }

    private fun applyPendingScroll(state: RecyclerView.State) {
        val position = pendingScrollPosition
        val offset = pendingScrollOffset
        pendingScrollPosition = RecyclerView.NO_POSITION
        pendingScrollOffset = INVALID_OFFSET
        if (position == RecyclerView.NO_POSITION || position >= state.itemCount) return

        val rowTop = position * resolvedRowHeight
        val rowBottom = rowTop + resolvedRowHeight
        verticalOffset = when {
            offset != INVALID_OFFSET -> rowTop - offset
            // Like LinearLayoutManager: scroll as little as possible to show the whole row.
            rowTop < verticalOffset -> rowTop
            rowBottom > verticalOffset + verticalSpace -> rowBottom - verticalSpace
            else -> verticalOffset
        }
    }

    private fun maxVerticalOffset(state: RecyclerView.State): Int =
        (state.itemCount * resolvedRowHeight - verticalSpace).coerceAtLeast(0)

    private fun resolveRowHeight(recycler: RecyclerView.Recycler) {
        val view = recycler.getViewForPosition(0)
        addView(view)
        measureRow(view)
        val lp = view.layoutParams as LayoutParams
        resolvedRowHeight = getDecoratedMeasuredHeight(view) + lp.topMargin + lp.bottomMargin
        detachAndScrapView(view, recycler)
    }

    /**
     * Brings the attached rows in line with the current offsets:
     * 1. recycles rows that left the visible range,
     * 2. adds rows that entered it (attached children always stay sorted by position),
     * 3. grows the content width if a newly measured row is wider,
     * 4. measures and lays out rows whose target width changed (new rows included).
     *
     * Rows that were only translated by scrolling are left untouched.
     */
    private fun fill(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        val itemCount = state.itemCount
        if (itemCount == 0 || resolvedRowHeight <= 0) {
            removeAndRecycleAllViews(recycler)
            return
        }

        val lastIndex = itemCount - 1
        val firstRow = (verticalOffset / resolvedRowHeight).coerceIn(0, lastIndex)
        val lastRow = ((verticalOffset + verticalSpace - 1).coerceAtLeast(0) / resolvedRowHeight)
            .coerceIn(firstRow, lastIndex)

        // 1. Recycle rows outside [firstRow, lastRow]. The remaining ones stay contiguous.
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            if (position < firstRow || position > lastRow) removeAndRecycleView(child, recycler)
        }

        // 2. Add missing rows: those above the attached block go to the front (in order),
        //    those below it go to the end. With no attached rows, everything is "above".
        val attachedFirst = getChildAt(0)?.let { getPosition(it) } ?: (lastRow + 1)
        val attachedLast = getChildAt(childCount - 1)?.let { getPosition(it) } ?: lastRow
        for (position in firstRow until attachedFirst) {
            addRow(recycler, position, index = position - firstRow)
        }
        for (position in attachedLast + 1..lastRow) {
            addRow(recycler, position, index = UNSET)
        }

        // 3. While scrolling the content width only grows; see the class documentation.
        contentWidth = max(contentWidth, measuredMaxWidth)

        // 4. Measure and lay out rows whose target width differs from their current one.
        val targetRowWidth = rowWidth
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            val lp = child.layoutParams as LayoutParams
            val width = if (stretchRowWidth) targetRowWidth else lp.naturalWidth
            if (lp.laidOutWidth == width) continue
            if (stretchRowWidth) measureRow(child, width)
            layoutRow(child, getPosition(child), width)
            lp.laidOutWidth = width
        }
    }

    private fun addRow(recycler: RecyclerView.Recycler, position: Int, index: Int) {
        val view = recycler.getViewForPosition(position)
        if (index == UNSET) addView(view) else addView(view, index)

        measureRow(view)
        val lp = view.layoutParams as LayoutParams
        lp.naturalWidth = getDecoratedMeasuredWidth(view) + lp.leftMargin + lp.rightMargin
        lp.laidOutWidth = UNSET // Forces measure/layout in fill() step 4.
        measuredMaxWidth = max(measuredMaxWidth, lp.naturalWidth)
    }

    /**
     * Measures a row. With [exactWidth] == [UNSET] the width is the row's natural width
     * (UNSPECIFIED spec, unless the item declares a fixed width); otherwise [exactWidth] is the
     * total width including decoration insets and margins. The height always matches the row
     * height once it is known.
     *
     * RecyclerView's own `measureChildWithMargins` isn't used because it caps WRAP_CONTENT
     * children at the parent width when horizontal scrolling is unavailable.
     */
    private fun measureRow(view: View, exactWidth: Int = UNSET) {
        calculateItemDecorationsForChild(view, decorInsets)
        val lp = view.layoutParams as LayoutParams
        val horizontalExtra = decorInsets.left + decorInsets.right + lp.leftMargin + lp.rightMargin
        val verticalExtra = decorInsets.top + decorInsets.bottom + lp.topMargin + lp.bottomMargin

        val widthSpec = when {
            exactWidth != UNSET -> exactly(exactWidth - horizontalExtra)
            lp.width >= 0 -> exactly(lp.width)
            else -> unspecified()
        }
        val heightSpec = when {
            resolvedRowHeight > 0 -> exactly(resolvedRowHeight - verticalExtra)
            lp.height >= 0 -> exactly(lp.height)
            else -> unspecified()
        }
        view.measure(widthSpec, heightSpec)
    }

    private fun layoutRow(view: View, position: Int, width: Int) {
        val left = paddingLeft - horizontalOffset
        val top = paddingTop + position * resolvedRowHeight - verticalOffset
        layoutDecoratedWithMargins(view, left, top, left + width, top + resolvedRowHeight)
    }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)

    private fun unspecified(): Int =
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    // endregion

    // region Scrolling

    // RecyclerView calls these for every touch event and when computing the fling velocity.
    // Returning false for the locked-out axis disables both dragging and flinging along it.
    override fun canScrollHorizontally(): Boolean =
        isHorizontallyScrollable && lockedAxis != AXIS_VERTICAL

    override fun canScrollVertically(): Boolean = lockedAxis != AXIS_HORIZONTAL

    override fun scrollHorizontallyBy(
        dx: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (childCount == 0 || dx == 0) return 0
        val consumed = (horizontalOffset + dx).coerceIn(0, maxHorizontalOffset) - horizontalOffset
        if (consumed != 0) {
            horizontalOffset += consumed
            // The set of visible rows doesn't depend on the horizontal offset.
            offsetChildrenHorizontal(-consumed)
        }
        return consumed
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (childCount == 0 || dy == 0) return 0
        val consumed = (verticalOffset + dy).coerceIn(0, maxVerticalOffset(state)) - verticalOffset
        if (consumed != 0) {
            verticalOffset += consumed
            offsetChildrenVertical(-consumed)
            fill(recycler, state)
        }
        return consumed
    }

    /** Scrolls the minimum distance needed to show the whole row at [position]. */
    override fun scrollToPosition(position: Int) {
        pendingScrollPosition = position
        pendingScrollOffset = INVALID_OFFSET
        requestLayout()
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        position: Int,
    ) {
        if (position !in 0 until state.itemCount) return
        val scroller = object : LinearSmoothScroller(recyclerView.context) {
            // Only scroll vertically; keep the current horizontal position.
            override fun calculateDxToMakeVisible(view: View, snapPreference: Int): Int = 0
        }
        scroller.targetPosition = position
        startSmoothScroll(scroller)
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val firstPosition = findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        return PointF(0f, if (targetPosition < firstPosition) -1f else 1f)
    }

    override fun findViewByPosition(position: Int): View? {
        // Attached rows are contiguous and sorted, so the index can be computed directly.
        val firstPosition = findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        val index = position - firstPosition
        if (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child != null && getPosition(child) == position) return child
        }
        return super.findViewByPosition(position)
    }

    // Scroll bars and View.canScrollHorizontally(direction) rely on these values.
    override fun computeHorizontalScrollOffset(state: RecyclerView.State): Int = horizontalOffset
    override fun computeHorizontalScrollExtent(state: RecyclerView.State): Int = horizontalSpace
    override fun computeHorizontalScrollRange(state: RecyclerView.State): Int = rowWidth

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = verticalOffset
    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int = verticalSpace
    override fun computeVerticalScrollRange(state: RecyclerView.State): Int =
        state.itemCount * resolvedRowHeight

    // endregion

    // region Adapter changes

    // Structural changes (expand, collapse, insert, remove) can make the content narrower,
    // so the width is recomputed from scratch on the next layout pass.
    override fun onItemsChanged(recyclerView: RecyclerView) {
        isContentWidthDirty = true
    }

    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        isContentWidthDirty = true
    }

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        isContentWidthDirty = true
    }

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) {
        isContentWidthDirty = true
    }

    // An updated row may have become narrower. With an exact provider the width can safely be
    // recomputed. Without one it can't: the width would shrink to the visible rows only and
    // frequent updates (selection, highlighting) would make the horizontal position jump.
    override fun onItemsUpdated(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        if (isProvidedWidthExact) isContentWidthDirty = true
    }

    override fun onAdapterChanged(
        oldAdapter: RecyclerView.Adapter<*>?,
        newAdapter: RecyclerView.Adapter<*>?,
    ) {
        removeAllViews()
        verticalOffset = 0
        horizontalOffset = 0
        isContentWidthDirty = true
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.addOnItemTouchListener(axisLockTouchListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        view.removeOnItemTouchListener(axisLockTouchListener)
        recyclerView = null
        lockedAxis = AXIS_NONE
    }

    // endregion

    // region Axis lock

    /**
     * Observes touch events without ever intercepting them. RecyclerView dispatches each event
     * to its item touch listeners before handling it, so the axis gets locked before
     * RecyclerView decides whether the touch slop is exceeded and dragging starts.
     */
    private inner class AxisLockTouchListener : RecyclerView.SimpleOnItemTouchListener() {

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (isAxisLockEnabled) handleTouchEvent(rv, e)
            return false
        }
    }

    // May see the same event twice (from RecyclerView's onInterceptTouchEvent and
    // onTouchEvent); every branch below is idempotent.
    private fun handleTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lockedAxis = AXIS_NONE
                activePointerId = e.getPointerId(0)
                gestureStartX = e.x
                gestureStartY = e.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // The new pointer becomes the active one, as in RecyclerView.
                val index = e.actionIndex
                activePointerId = e.getPointerId(index)
                gestureStartX = e.getX(index)
                gestureStartY = e.getY(index)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = e.actionIndex
                if (e.getPointerId(index) == activePointerId) {
                    val newIndex = if (index == 0) 1 else 0
                    activePointerId = e.getPointerId(newIndex)
                    gestureStartX = e.getX(newIndex)
                    gestureStartY = e.getY(newIndex)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (lockedAxis != AXIS_NONE) return
                val index = e.findPointerIndex(activePointerId)
                if (index < 0) return
                val dx = abs(e.getX(index) - gestureStartX)
                val dy = abs(e.getY(index) - gestureStartY)
                if (max(dx, dy) <= touchSlop) return
                // A horizontal lock is pointless when the content fits the viewport.
                lockedAxis = if (dx > dy && isHorizontallyScrollable) AXIS_HORIZONTAL else AXIS_VERTICAL
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // RecyclerView computes the fling velocity while handling this very event and
                // still needs the lock for that, so release it only after the event is done.
                // Releasing it keeps programmatic scrolling (smoothScrollBy, ...) unrestricted.
                rv.post {
                    if (rv.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) lockedAxis = AXIS_NONE
                }
            }
        }
    }

    // endregion

    // region State

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putInt(KEY_VERTICAL_OFFSET, verticalOffset)
        putInt(KEY_HORIZONTAL_OFFSET, horizontalOffset)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val bundle = state as? Bundle ?: return
        verticalOffset = bundle.getInt(KEY_VERTICAL_OFFSET)
        horizontalOffset = bundle.getInt(KEY_HORIZONTAL_OFFSET)
        requestLayout()
    }

    // endregion

    // region LayoutParams

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(c: Context, attrs: AttributeSet): RecyclerView.LayoutParams =
        LayoutParams(c, attrs)

    override fun generateLayoutParams(lp: ViewGroup.LayoutParams): RecyclerView.LayoutParams =
        when (lp) {
            is RecyclerView.LayoutParams -> LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> LayoutParams(lp)
            else -> LayoutParams(lp)
        }

    override fun checkLayoutParams(lp: RecyclerView.LayoutParams?): Boolean = lp is LayoutParams

    /** Layout params of the rows, carrying per-row measurement state. */
    public class LayoutParams : RecyclerView.LayoutParams {

        // Natural width of the row (decoration insets and margins included).
        internal var naturalWidth: Int = 0

        // Width used by the last layout; equal to the target width means nothing to redo.
        internal var laidOutWidth: Int = UNSET

        public constructor(c: Context, attrs: AttributeSet) : super(c, attrs)
        public constructor(width: Int, height: Int) : super(width, height)
        public constructor(source: ViewGroup.MarginLayoutParams) : super(source)
        public constructor(source: ViewGroup.LayoutParams) : super(source)
        public constructor(source: RecyclerView.LayoutParams) : super(source)
    }

    // endregion
}
