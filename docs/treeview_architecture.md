# treeview: Design and internals

> Part of the documentation of the [treeview](../treeview/README.md) module · [中文](treeview_architecture_cn.md)

## Data model: `TreeNode`

A node holds a `value`, its `parent` and `children`, `isExpanded` and a `loadState`. Nodes
are compared by identity, never by value, so two nodes may hold equal values. The `depth` is
cached and updated whenever a node is attached or detached, because the adapter compares
depths in tight loops.

`TreeNode`'s structural methods (`addChild`, `insertChild`, `removeChild`) only change the data
and notify nobody; they are meant for building a tree before it is handed to the adapter. For
the same reason `isExpanded` and `loadState` are read-only outside the library: once a tree is
displayed, every change goes through `TreeAdapter`.

## Flattened projection: `TreeAdapter`

RecyclerView shows a flat list, so the adapter keeps two structures:

- `roots` — the top-level nodes;
- `visibleNodes` — a pre-order traversal containing only nodes whose ancestors are all
  expanded. The adapter position of a node is its index in this list.

```
x ▼                     visibleNodes[0]
├── github ▼            visibleNodes[1]
│   └── module ▶        visibleNodes[2]   (children hidden: not in the list)
└── README.md           visibleNodes[3]
```

Because the visible descendants of a node always form a contiguous block right after it
(the rows deeper than the node), collapsing is a range removal and expanding is a range
insertion. Each operation edits `visibleNodes` in place and dispatches the narrowest
notification: `notifyItemRangeInserted` / `notifyItemRangeRemoved` for the subtree, plus
`notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)` for the node whose indicator changed.
Partial rebinds with that payload let `onBindNode` update only the indicator.

Expand, collapse, insert and remove are O(visible rows) in the worst case (list shifting and
position lookup). `toggleAt(position)` skips the lookup and is preferred in click handlers.

## Expandability

`isExpandable(node)` decides whether a row shows an indicator and reacts to expansion. The
default is *has children, or its children aren't loaded yet*. It is open, so other rules are
possible, for example making empty directories expandable:

```kotlin
override fun isExpandable(node: TreeNode<FileItem>) =
    super.isExpandable(node) || node.value.isDirectory
```

`node.isKnownEmpty` (loaded, no children) lets the UI show empty nodes differently.

## Lazy loading

```mermaid
stateDiagram-v2
    [*] --> NOT_LOADED: childrenLoaded = false
    [*] --> LOADED: childrenLoaded = true
    NOT_LOADED --> LOADING: expanded
    LOAD_FAILED --> LOADING: expanded again (retry)
    LOADING --> LOADED: onLoaded(children)
    LOADING --> LOAD_FAILED: onFailed(error) / node collapses
    LOADED --> NOT_LOADED: reloadChildren()
```

- **Request identity.** Every load is represented by a request object that the node stores as
  the one it waits for. A result is applied only if it belongs to that request, so results
  for nodes that were removed, reloaded or replaced by `setRoots` are dropped silently, and a
  callback can never be applied twice.
- **Thread hop.** `ChildLoadCallback` may be called from any thread; results are posted to
  the main thread before they touch the adapter.
- **Deferred start.** Loads requested during an operation are queued and started only when the
  adapter state is consistent again. A loader that answers synchronously therefore re-enters
  a valid adapter, and loads requested by that nested result are picked up by the same loop.
- **Collapse while loading** doesn't cancel the request: the children are added but stay
  hidden until the node is expanded again.
- A node that turns out to be empty and isn't expandable is collapsed again; a failed node is
  collapsed so that the next expansion retries.

## Single-child chains

With `autoExpandSingleChild`, expanding a node keeps walking down while the current node has
exactly one child that is expandable, not known to be empty and accepted by the protected hook
`canAutoExpand(child)`. All states along the chain are updated first and the rows are inserted
with **one** notification, so the chain animates as a single change. If the walk reaches a
node whose children aren't loaded, that node is loaded and the walk resumes when the result
arrives. Collapsing is unaffected, and `reveal()` never chain-expands.

## Layout: `TreeLayoutManager`

**Coordinate model.** The scroll position is two absolute offsets, `verticalOffset` and
`horizontalOffset`. With a fixed row height the visible row range is simply
`verticalOffset / rowHeight … (verticalOffset + viewportHeight) / rowHeight`, so layout costs
O(visible rows), large scroll deltas (fast flings) never leave gaps, and jumping to any
position is O(1). Horizontal scrolling never changes which rows are visible; it only translates
the attached children.

**Recycling.** On vertical scroll the layout manager shifts the children, recycles rows that
left the visible range and adds rows that entered it, keeping attached children contiguous and
sorted by position. Rows that were only shifted are neither re-measured nor re-laid out.

**Content width.** The horizontal scroll range is the width of the widest row. It comes from
two sources, and the larger one wins, so an underestimate never clips a row:

1. a `ContentWidthProvider` — `contentWidthProvider` if set, otherwise the adapter if it
   implements the interface. `TreeAdapter` does: with a `RowWidthCalculator` it reports the
   exact widest visible row;
2. the natural width of the rows measured so far. This value is reset when the structure
   changes (expand, collapse, insert, remove) and only grows while scrolling, so vertical
   scrolling never makes the horizontal position jump.

When the content becomes narrower (e.g. after a collapse) and the view is scrolled to the
right, the horizontal offset is pulled back into range.

**Row width cache.** The adapter caches each row's width on its node together with a
generation stamp. `invalidateRowWidths()` just increments the stamp (O(1)); a node's own
cache is dropped by `notifyNodeChanged` or when its depth changes. The maximum is updated
incrementally when rows are added and recomputed lazily, once, after rows were removed.

**Row measurement.** Each row is first measured with an `UNSPECIFIED` width to get its natural
width. With `stretchRowWidth` (default) rows are then measured with an exact width of
`max(contentWidth, viewportWidth)`, so backgrounds and dividers span the whole scrollable area.
Rows are re-measured only when that target width changes.

**Axis lock.** RecyclerView asks `canScrollHorizontally()` / `canScrollVertically()` for every
touch event and again when it computes the fling velocity. The layout manager observes touch
events through a non-intercepting `OnItemTouchListener`, which RecyclerView calls *before* it
handles each event. As soon as the finger has moved beyond the touch slop, the dominant axis is
locked and the other `canScroll…()` returns `false`, which suppresses both dragging and flinging
along it, without subclassing RecyclerView. The lock is released after the gesture, so
programmatic scrolling stays unrestricted.

**Other behavior.** `scrollToPosition` scrolls the minimum distance needed to show the row
(like `LinearLayoutManager`); `scrollToPositionWithOffset` aligns it to the top;
`smoothScrollToPosition` scrolls vertically only and keeps the horizontal position. Both offsets
are saved and restored with the instance state.

## Clicks

`TreeAdapter.onCreateViewHolder` is final: it calls `onCreateNodeViewHolder` and installs click
and long-click listeners on the item view once per view holder. The node is resolved from
`bindingAdapterPosition` when the event happens, because holders are rebound and rows move as
the tree changes. A click notifies `onNodeClickListener` first and then, with `toggleOnClick`,
toggles the row — but only if the row still shows the same node, in case the listener changed
the tree. Rows are long-clickable only while `onNodeLongClickListener` is set, so accessibility
services don't announce a long-press action that does nothing.

## Threading

All adapter and layout manager methods must be called on the main thread. The only exception
is `ChildLoadCallback`, which may be called from any thread.
