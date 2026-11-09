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

package x.github.module.editor.layout

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import x.github.module.editor.model.EditorTextSource

/**
 * Keeps the layout in step with the text whenever *all* of it has to be laid out
 * again: another text size or typeface, another buffer, word wrap switched on or
 * off, the end of a pinch-zoom, and a change of the width that lines are wrapped at.
 *
 * It owns the current [layout] and the things that are easy to get wrong about
 * replacing or rebuilding it:
 *
 *  - **one at a time**: every full relayout goes through [relayout], which lets only
 *    one run at any moment, whichever threads they come from;
 *  - **in the background when nobody asked for it**: the two relayouts that the user
 *    causes just by touching the screen or turning the device, after a zoom and
 *    after a change of the wrap width, run on a background thread. The owner is told
 *    when one becomes necessary ([Host.onBackgroundRelayoutStarted]) and when the
 *    layout is right again ([Host.onBackgroundRelayoutFinished]); a newer one calls
 *    off an older one that is still running;
 *  - **the zoom**: when a pinch ends the layout is first scaled in place if it can be
 *    (so scrolling is right immediately), then measured again exactly;
 *  - **edits and relayouts never overlap**: a relayout reads the whole text, an edit
 *    changes it, and the two run on different threads. Both go through the same
 *    lock: an edit is made with [runEdit], which refuses if a relayout is under way
 *    and holds relayouts off for as long as the edit takes. This is not a flag that
 *    callers are trusted to check first; an edit cannot get in any other way.
 *
 * What a single edit does to the layout is not its business: that goes straight to
 * [TextLayout.update], from inside the edit.
 */
class LayoutCoordinator(
    private val host: LayoutCoordinator.Host,
    private val source: EditorTextSource,
    private val metrics: TextMeasurer
) {

    /** What the coordinator needs from the view. Implemented by EditorView. */
    interface Host {
        /** The width available to a wrapped line (view width minus both text indents). */
        val wordwrapWidth: Int

        /** Runs [action] on the main thread. Inherited from View as-is. */
        fun post(action: Runnable): Boolean

        /**
         * The layout got new numbers without an edit (it was scaled after a zoom, or
         * laid out again in the background): whatever is positioned from it has to be
         * refreshed. Main thread.
         */
        fun onLayoutUpdated()

        /**
         * The layout no longer fits (the text size or the wrap width changed) and is
         * about to be rebuilt in the background. From now until
         * [onBackgroundRelayoutFinished] the text is locked. Main thread.
         *
         * Always followed by exactly one [onBackgroundRelayoutFinished], however many
         * relayouts it takes in between (a second change that arrives while the first
         * relayout is still running does not start a second pair).
         */
        fun onBackgroundRelayoutStarted()

        /**
         * The layout is right again and the text may change again. Main thread.
         */
        fun onBackgroundRelayoutFinished()
    }

    // one background relayout, with a way to call it off and to wait until it is gone
    private class BackgroundRelayout {
        @Volatile
        var cancelled = false
        // Taken by whoever gets there first: the background thread when it starts on
        // the relayout, or the main thread when it cancels one that has not started.
        val claimed = AtomicBoolean(false)
        // released when the background thread has stopped touching the layout
        val ended = CountDownLatch(1)
    }

    /** The layout in use. Replaced as a whole by [setWordwrap]. */
    @Volatile
    var layout: TextLayout = UnwrappedLayout(source, metrics)
        private set

    val isWordwrap: Boolean get() = layout is WordwrapLayout

    /**
     * True from [beginZoom] until the layout fits the zoomed text size again: until
     * [endZoom] if the pinch changed nothing, otherwise until the background relayout
     * that follows has finished. While it is, the text must not change at all.
     */
    @Volatile
    var isZoomInProgress = false
        private set

    // The layout does not fit and a background relayout has to finish before it does
    // again: true from onBackgroundRelayoutStarted to onBackgroundRelayoutFinished.
    // It outlives the individual relayouts, which can be called off and replaced.
    @Volatile
    private var backgroundRelayoutPending = false

    // only one full relayout at a time
    private val relayoutLock = ReentrantLock()

    // how many relayout() / setWordwrap() calls are running or waiting for their turn
    private val activeRelayouts = AtomicInteger(0)

    /**
     * True while the layout does not (yet) match the text and is being rebuilt: during
     * a zoom, while a background relayout is due or running, and for as long as a
     * [relayout] or [setWordwrap] call is under way on some thread. While it is, the
     * text must not be edited: the edit would be laid out against numbers that are
     * about to be thrown away.
     */
    val isBusy: Boolean
        get() = isZoomInProgress || backgroundRelayoutPending || activeRelayouts.get() > 0

    /**
     * True while a [relayout] or [setWordwrap] call is measuring the text on some
     * thread (or waiting for its turn). Whatever the text is measured with, the text
     * size above all, should be left alone until it is done: a relayout that sees
     * two text sizes produces a layout that fits neither.
     */
    val isRelayoutRunning: Boolean get() = activeRelayouts.get() > 0

    /**
     * Where the background relayouts run. Null (the default) means a thread of the
     * coordinator's own.
     *
     * Set it to run that work on a thread pool or coroutine dispatcher you already
     * have, e.g. `Dispatchers.Default.asExecutor()`. Two things are expected of it:
     * it must not run the task on the calling (main) thread, and it should get to it
     * soon, because the editor stays locked until the task is done.
     */
    @Volatile
    var backgroundExecutor: Executor? = null

    // One thread, so background relayouts queue up instead of running side by side. It is
    // only created if no executor was supplied, and only alive while there is work:
    // an idle editor keeps no thread around.
    private val ownExecutor: Executor by lazy {
        ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable ->
                Thread(runnable, "editor-layout").apply { isDaemon = true }
            }
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    // the text size when the current pinch started
    private var zoomStartTextSize = 0f
    // fingers are on the screen, between beginZoom and endZoom (main thread only)
    private var pinching = false
    // the background relayout that is queued or running, if any (main thread only)
    private var backgroundRelayout: BackgroundRelayout? = null
    // the wrap width it lays out for
    private var backgroundRelayoutWrapWidth = 0

    // #region edits

    /**
     * Runs [edit], which may change the text, unless the layout is being rebuilt.
     *
     * An edit and a relayout must not run at the same time: the relayout reads every
     * line of the text on another thread. So the edit is refused (and nothing is
     * run) if a relayout is running, or is due after a zoom or a change of the wrap
     * width; and while the edit runs, no relayout can start: one that is asked for
     * on another thread waits until the edit is over.
     *
     * Never blocks. Main thread.
     *
     * @return whether [edit] was run
     */
    @MainThread
    fun runEdit(edit: () -> Unit): Boolean {
        // the layout does not fit the text right now; a relayout is on its way
        if (isZoomInProgress || backgroundRelayoutPending) {
            return false
        }
        // a relayout has the text right now
        if (!relayoutLock.tryLock()) {
            return false
        }
        try {
            edit()
            return true
        } finally {
            relayoutLock.unlock()
        }
    }

    // #endregion

    // #region full relayout

    /**
     * Lays out the whole text again, on the calling thread. Slow for a large text,
     * so call it from a worker thread. If another relayout is running, waits for it.
     *
     * @param prepare what makes the relayout necessary (set the text size, swap the
     *        buffer, ...). It runs right before the layout, in the same turn, so no
     *        other relayout can be measuring while it changes things under it.
     * @return true if it ran to the end, false if [isCancelled] stopped it
     */
    @WorkerThread
    fun relayout(
        isCancelled: () -> Boolean = { false },
        prepare: () -> Unit = {}
    ): Boolean {
        activeRelayouts.incrementAndGet()
        relayoutLock.lock()
        try {
            prepare()
            return relayoutLocked(isCancelled)
        } finally {
            relayoutLock.unlock()
            activeRelayouts.decrementAndGet()
        }
    }

    /**
     * The text size was multiplied by [factor]: scales the layout in place, if it is
     * of a kind that can be (see [TextLayout.rescale]). A stand-in until the text has
     * been measured again, good enough to show the text in its new size meanwhile.
     *
     * Only from inside the `prepare` block of [relayout], where no other relayout
     * can be running and the text is locked against edits.
     *
     * @return whether the layout was scaled
     */
    fun rescaleLayout(factor: Float): Boolean {
        return factor > 0f && !factor.isNaN() && !factor.isInfinite() && layout.rescale(factor)
    }

    private fun relayoutLocked(isCancelled: () -> Boolean): Boolean {
        var completed = false
        layout.relayout(
            isCancelled = isCancelled,
            onCompleted = { completed = true }
        )
        return completed
    }

    /**
     * Switches word wrap on or off: builds the other kind of layout, lays the text
     * out with it on the calling thread, and only then puts it in place of the
     * current one, so nobody ever sees a half-built layout.
     *
     * @return the layout that was replaced. It may still be in use by a frame being
     *         drawn, so the caller recycles it, on the main thread.
     */
    @WorkerThread
    fun setWordwrap(enabled: Boolean): TextLayout {
        activeRelayouts.incrementAndGet()
        relayoutLock.lock()
        try {
            val previousLayout = layout
            val newLayout: TextLayout = if (enabled)
                WordwrapLayout(source, metrics) { host.wordwrapWidth }
            else
                UnwrappedLayout(source, metrics)

            newLayout.relayout()
            layout = newLayout
            return previousLayout
        } finally {
            relayoutLock.unlock()
            activeRelayouts.decrementAndGet()
        }
    }

    // #endregion

    // #region zoom

    /** A pinch started. From now on the text must not change. */
    @MainThread
    fun beginZoom() {
        // a relayout that is still running is for a text size that is about to change
        cancelBackgroundRelayout()
        zoomStartTextSize = metrics.textSize
        pinching = true
        isZoomInProgress = true
    }

    /**
     * The pinch ended. If it changed the text size, the layout is brought up to date
     * in the background, announced with [Host.onBackgroundRelayoutStarted] and
     * [Host.onBackgroundRelayoutFinished]. If it changed nothing, the zoom is simply
     * over.
     */
    @MainThread
    fun endZoom() {
        if (!isZoomInProgress) {
            return
        }
        pinching = false

        val textSize = metrics.textSize
        if (textSize == zoomStartTextSize && !backgroundRelayoutPending) {
            // nothing changed, nothing to do
            isZoomInProgress = false
            return
        }

        // Without word wrap the layout can be scaled in place, which makes the
        // horizontal scroll range right immediately instead of only once the relayout
        // is done. With word wrap this does nothing, and the relayout does it all.
        if (zoomStartTextSize > 0f && layout.rescale(textSize / zoomStartTextSize)) {
            host.onLayoutUpdated()
        }
        // the layout is as good as it gets for this text size: a pinch that starts
        // before the relayout below is done must not scale by this one again
        zoomStartTextSize = textSize

        requestBackgroundRelayout()
    }

    // #endregion

    // #region wrap width

    /**
     * Call whenever the width available to wrapped lines may have changed: the view
     * was resized (rotation, split screen), or its padding was. If lines are wrapped
     * and the width really is another one, the text is laid out again in the
     * background, announced with [Host.onBackgroundRelayoutStarted] and
     * [Host.onBackgroundRelayoutFinished]. Cheap to call when nothing changed.
     */
    @MainThread
    fun syncWrapWidth() {
        val wrapped = layout as? WordwrapLayout ?: return
        val wrapWidth = host.wordwrapWidth
        // a view that has no size yet, or no room for text at all
        if (wrapWidth <= 0 || wrapWidth == wrapped.width()) {
            return
        }
        // already on its way: do not start over for every layout pass in the meantime
        if (backgroundRelayout != null && wrapWidth == backgroundRelayoutWrapWidth) {
            return
        }
        requestBackgroundRelayout()
    }

    // #endregion

    // #region background relayout

    /**
     * An edit was laid out only roughly ([TextLayout.updateRoughly]): lays the text
     * out properly in the background. Edits are refused meanwhile, and the host hears
     * onBackgroundRelayoutStarted / Finished, as for a zoom (the app's progress bar).
     */
    @MainThread
    fun relayoutAfterLargeEdit() = requestBackgroundRelayout()

    // The layout has to be rebuilt in the background. Whatever is running is called
    // off first: it works towards a text size or a wrap width that no longer holds.
    @MainThread
    private fun requestBackgroundRelayout() {
        cancelBackgroundRelayout()

        if (!backgroundRelayoutPending) {
            backgroundRelayoutPending = true
            host.onBackgroundRelayoutStarted()
        }
        if (pinching) {
            // The text size is still changing under the fingers. There is no point in
            // laying out for it now; endZoom will, because a relayout is pending.
            return
        }

        val relayout = BackgroundRelayout()
        backgroundRelayout = relayout
        backgroundRelayoutWrapWidth = host.wordwrapWidth

        val task = Runnable {
            // cancelled before it even started: nothing to do, and nobody is waiting
            if (relayout.claimed.compareAndSet(false, true)) {
                var completed = false
                try {
                    completed = relayoutUnlessCancelled(relayout)
                } finally {
                    // the layout is no longer being touched: cancelBackgroundRelayout may go on
                    relayout.ended.countDown()
                }

                // Hand over to the main thread only after the latch:
                // cancelBackgroundRelayout blocks the main thread on it, and must
                // never wait for something that is itself waiting for the main thread.
                if (completed) {
                    host.post(Runnable { onBackgroundRelayoutCompleted(relayout) })
                }
            }
        }

        try {
            (backgroundExecutor ?: ownExecutor).execute(task)
        } catch (e: RejectedExecutionException) {
            // the supplied executor was shut down or is full; the editor must not
            // stay locked because of that
            ownExecutor.execute(task)
        }
    }

    // Like relayout(), but gives up waiting for its turn as soon as it is cancelled.
    // Someone else's relayout can take seconds, and cancelBackgroundRelayout is waiting.
    private fun relayoutUnlessCancelled(relayout: BackgroundRelayout): Boolean {
        while (!relayoutLock.tryLock(50L, TimeUnit.MILLISECONDS)) {
            if (relayout.cancelled) {
                return false
            }
        }
        try {
            return relayoutLocked(isCancelled = { relayout.cancelled })
        } finally {
            relayoutLock.unlock()
        }
    }

    @MainThread
    private fun onBackgroundRelayoutCompleted(relayout: BackgroundRelayout) {
        // unless it was called off (and maybe replaced) in the meantime
        if (backgroundRelayout !== relayout) {
            return
        }
        backgroundRelayout = null
        backgroundRelayoutPending = false
        isZoomInProgress = false

        host.onLayoutUpdated()
        host.onBackgroundRelayoutFinished()

        // The view may have been resized again while this relayout was already past
        // the point of noticing. Then this starts the next round.
        syncWrapWidth()
    }

    /**
     * The view left its window. A relayout that is running in the background is
     * called off: it would go on reading the text and holding on to the view for
     * nothing. That the layout still needs it is remembered; see
     * [resumeBackgroundRelayout].
     */
    @MainThread
    fun pauseBackgroundRelayout() {
        cancelBackgroundRelayout()
    }

    /** The view is back in a window: a relayout that was called off by [pauseBackgroundRelayout] starts again. */
    @MainThread
    fun resumeBackgroundRelayout() {
        if (backgroundRelayoutPending && backgroundRelayout == null && !pinching) {
            requestBackgroundRelayout()
        }
    }

    // Calls off the background relayout and, if it is running, waits until it has
    // really let go of the layout. The wait is short: a relayout checks for
    // cancellation after every line. One that is still queued in the executor is not
    // waited for (who knows what is ahead of it): it is claimed here instead, so it
    // will find itself cancelled and do nothing when its turn comes.
    //
    // Calling a relayout off does not make the layout fit: backgroundRelayoutPending
    // stays as it is, and whoever calls this starts (or will start) another one.
    @MainThread
    private fun cancelBackgroundRelayout() {
        val relayout = backgroundRelayout ?: return
        backgroundRelayout = null
        relayout.cancelled = true
        if (!relayout.claimed.compareAndSet(false, true)) {
            relayout.ended.await()
        }
    }

    // #endregion
}
