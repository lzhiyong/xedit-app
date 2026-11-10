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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

import x.github.module.editor.testing.FakeMeasurer
import x.github.module.editor.testing.FakeTextSource

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

import org.junit.jupiter.api.Timeout

/**
 * The test thread plays the main thread: it is the one that calls beginZoom/endZoom
 * and that runs what the coordinator posts.
 */
class LayoutCoordinatorTest {

    // the view, reduced to what the coordinator asks of it
    private class FakeHost : LayoutCoordinator.Host {
        override var wordwrapWidth = 100
        var layoutUpdates = 0
        var relayoutsStarted = 0
        var relayoutsFinished = 0
        val posted = ConcurrentLinkedQueue<Runnable>()

        override fun post(action: Runnable): Boolean {
            posted.add(action)
            return true
        }

        override fun onLayoutUpdated() {
            layoutUpdates++
        }

        override fun onBackgroundRelayoutStarted() {
            relayoutsStarted++
        }

        override fun onBackgroundRelayoutFinished() {
            relayoutsFinished++
        }

        // the main thread gets around to its message queue
        fun runPosted() {
            while (true) {
                (posted.poll() ?: return).run()
            }
        }
    }

    // a background thread that only works when told to
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    private val host = FakeHost()
    private val source = FakeTextSource("aaaa", "bb")
    private val measurer = FakeMeasurer(textSize = 10f)
    private val background = ManualExecutor()
    private val coordinator = LayoutCoordinator(host, source, measurer).also {
        it.backgroundExecutor = background
        it.relayout()
    }

    // #region relayout

    @Test
    fun relayoutLaysOutTheWholeText() {
        assertEquals(2, coordinator.layout.count())
        assertEquals(40, coordinator.layout.width())
    }

    @Test
    fun relayoutRunsWhatMadeItNecessaryFirst() {
        coordinator.relayout { measurer.textSize = 20f }

        assertEquals(80, coordinator.layout.width())
    }

    // Changing the text size without a pinch: the layout is scaled first, so the
    // text can be shown in its new size while it is measured again.
    @Test
    fun theLayoutCanBeScaledAheadOfARelayout() {
        var widthBeforeMeasuring = 0
        var measuredBeforeScaling = -1

        coordinator.relayout {
            measurer.textSize = 20f
            measurer.measureCalls = 0
            assertTrue(coordinator.rescaleLayout(2f))
            measuredBeforeScaling = measurer.measureCalls
            widthBeforeMeasuring = coordinator.layout.width()
        }

        assertEquals(80, widthBeforeMeasuring, "right already before anything was measured")
        assertEquals(0, measuredBeforeScaling)
        assertEquals(80, coordinator.layout.width(), "and the same after measuring")
    }

    @Test
    fun aWrappedLayoutOrASenselessFactorIsNotScaled() {
        assertFalse(coordinator.rescaleLayout(0f))
        assertFalse(coordinator.rescaleLayout(Float.NaN))
        assertFalse(coordinator.rescaleLayout(Float.POSITIVE_INFINITY))

        coordinator.setWordwrap(true)
        assertFalse(coordinator.rescaleLayout(2f))
    }

    @Test
    fun theCoordinatorIsBusyExactlyWhileARelayoutRuns() {
        assertFalse(coordinator.isBusy)

        var busyInside = false
        var runningInside = false
        coordinator.relayout {
            busyInside = coordinator.isBusy
            runningInside = coordinator.isRelayoutRunning
        }

        assertTrue(busyInside)
        assertTrue(runningInside)
        assertFalse(coordinator.isBusy)
        assertFalse(coordinator.isRelayoutRunning)

        // a zoom makes the editor busy, but nothing is measuring yet
        coordinator.beginZoom()
        assertTrue(coordinator.isBusy)
        assertFalse(coordinator.isRelayoutRunning)
    }

    @Test
    fun relayoutTellsWhetherItWasCancelled() {
        assertTrue(coordinator.relayout())
        assertFalse(coordinator.relayout(isCancelled = { true }))
        assertFalse(coordinator.isBusy, "not busy after a cancelled relayout either")
    }

    @Test
    @Timeout(10)
    fun aSecondRelayoutWaitsForTheFirst() {
        val firstIsInside = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()

        val first = Thread {
            coordinator.relayout {
                firstIsInside.countDown()
                letFirstFinish.await()
                events.add("first")
            }
        }
        first.start()
        firstIsInside.await()

        val second = Thread {
            coordinator.relayout { events.add("second") }
        }
        second.start()
        // give the second every chance to barge in
        second.join(200)
        assertTrue(events.isEmpty(), "the second relayout did not wait")
        assertTrue(coordinator.isBusy, "busy while one runs and one waits")

        letFirstFinish.countDown()
        first.join()
        second.join()

        assertEquals(listOf("first", "second"), events.toList())
        assertFalse(coordinator.isBusy)
    }

    // #endregion

    // #region edits

    @Test
    fun anEditRunsWhenNothingElseIsGoingOn() {
        var ran = false

        assertTrue(coordinator.runEdit { ran = true })

        assertTrue(ran)
    }

    @Test
    fun anEditIsRefusedFromTheStartOfAPinchUntilTheLayoutIsRightAgain() {
        var edits = 0

        coordinator.beginZoom()
        assertFalse(coordinator.runEdit { edits++ }, "fingers on the screen")

        measurer.textSize = 20f
        coordinator.endZoom()
        assertFalse(coordinator.runEdit { edits++ }, "the relayout is queued")

        background.runAll()
        assertFalse(coordinator.runEdit { edits++ }, "done, but not yet delivered to the main thread")

        host.runPosted()
        assertTrue(coordinator.runEdit { edits++ })
        assertEquals(1, edits)
    }

    // The point of the gate: an edit and a relayout on another thread can never be
    // in the text at the same time, whichever of them comes first.
    @Test
    @Timeout(10)
    fun anEditIsRefusedWhileARelayoutHasTheText() {
        val relayoutIsInside = CountDownLatch(1)
        val letRelayoutFinish = CountDownLatch(1)
        val worker = Thread {
            coordinator.relayout {
                relayoutIsInside.countDown()
                letRelayoutFinish.await()
            }
        }
        worker.start()
        relayoutIsInside.await()

        var ran = false
        assertFalse(coordinator.runEdit { ran = true })
        assertFalse(ran, "the edit must not have run at all")

        letRelayoutFinish.countDown()
        worker.join()
        assertTrue(coordinator.runEdit { ran = true })
    }

    @Test
    @Timeout(10)
    fun aRelayoutWaitsUntilAnEditIsOver() {
        val editIsInside = CountDownLatch(1)
        val letEditFinish = CountDownLatch(1)
        val events = ConcurrentLinkedQueue<String>()

        // this thread plays the main thread, in the middle of an edit
        val editor = Thread {
            coordinator.runEdit {
                editIsInside.countDown()
                letEditFinish.await()
                events.add("edit")
            }
        }
        editor.start()
        editIsInside.await()

        val worker = Thread {
            coordinator.relayout { events.add("relayout") }
        }
        worker.start()
        // give the relayout every chance to barge in
        worker.join(200)
        assertTrue(events.isEmpty(), "the relayout did not wait")

        letEditFinish.countDown()
        editor.join()
        worker.join()

        assertEquals(listOf("edit", "relayout"), events.toList())
    }

    @Test
    fun anEditMayContainAnotherOne() {
        var inner = false

        assertTrue(coordinator.runEdit { inner = coordinator.runEdit { } })

        assertTrue(inner, "an edit hook that edits again must not lock itself out")
    }

    // #endregion

    // #region word wrap

    @Test
    fun setWordwrapSwapsTheLayoutAndHandsBackTheOldOne() {
        val unwrapped = coordinator.layout
        assertFalse(coordinator.isWordwrap)

        val previous = coordinator.setWordwrap(true)

        assertSame(unwrapped, previous)
        assertTrue(coordinator.isWordwrap)
        assertEquals(2, coordinator.layout.count(), "the new layout is already laid out")
        assertEquals(host.wordwrapWidth, coordinator.layout.width())

        coordinator.setWordwrap(false)
        assertFalse(coordinator.isWordwrap)
        assertEquals(40, coordinator.layout.width())
    }

    // #endregion

    // #region zoom

    @Test
    fun aZoomLocksUntilTheBackgroundRelayoutHasBeenDelivered() {
        coordinator.beginZoom()
        assertTrue(coordinator.isZoomInProgress)
        assertTrue(coordinator.isBusy)

        measurer.textSize = 20f
        coordinator.endZoom()
        assertTrue(coordinator.isZoomInProgress, "still locked: nothing was measured yet")
        assertEquals(1, host.relayoutsStarted)

        background.runAll()
        assertTrue(coordinator.isZoomInProgress, "still locked: the main thread has not heard of it yet")
        assertEquals(0, host.relayoutsFinished)

        host.runPosted()
        assertFalse(coordinator.isZoomInProgress)
        assertFalse(coordinator.isBusy)
        assertEquals(1, host.relayoutsFinished)
    }

    @Test
    fun withoutWordwrapTheLayoutIsRightAsSoonAsThePinchEnds() {
        coordinator.beginZoom()
        measurer.textSize = 20f
        measurer.measureCalls = 0

        coordinator.endZoom()

        assertEquals(80, coordinator.layout.width(), "scaled in place")
        assertEquals(0, measurer.measureCalls, "without measuring")
        assertEquals(1, host.layoutUpdates, "and the view was told")

        background.runAll()
        host.runPosted()

        assertTrue(measurer.measureCalls > 0, "then measured exactly")
        assertEquals(80, coordinator.layout.width())
        assertEquals(2, host.layoutUpdates)
    }

    @Test
    fun withWordwrapTheLayoutChangesOnlyWhenTheRelayoutIsDone() {
        source.lines[0] = "one two three four"
        coordinator.setWordwrap(true)
        assertEquals(3, coordinator.layout.count())

        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()
        assertEquals(0, host.layoutUpdates, "nothing to scale in place")

        background.runAll()
        host.runPosted()

        // twice the text size, same wrap width: more rows
        assertTrue(coordinator.layout.count() > 3)
        assertEquals(1, host.layoutUpdates)
        assertEquals(1, host.relayoutsFinished)
    }

    @Test
    fun aPinchThatChangesNothingIsOverAtOnceAndAnnouncesNothing() {
        measurer.measureCalls = 0

        coordinator.beginZoom()
        assertTrue(coordinator.isBusy)
        coordinator.endZoom()

        assertFalse(coordinator.isZoomInProgress)
        assertFalse(coordinator.isBusy)
        assertEquals(0, host.relayoutsStarted, "no relayout, so nothing to announce")
        assertEquals(0, host.relayoutsFinished)
        assertTrue(background.tasks.isEmpty(), "no background work")
        assertEquals(0, measurer.measureCalls)
    }

    @Test
    fun endZoomWithoutBeginZoomDoesNothing() {
        coordinator.endZoom()

        assertEquals(0, host.relayoutsFinished)
        assertTrue(background.tasks.isEmpty())
    }

    // A relayout still waiting in the executor when the next pinch starts: it must
    // not be waited for (the test would time out) and must not do anything later.
    @Test
    @Timeout(10)
    fun aNewPinchCallsOffARelayoutThatHasNotStarted() {
        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()

        coordinator.beginZoom()
        measurer.textSize = 30f
        coordinator.endZoom()
        assertEquals(120, coordinator.layout.width())

        measurer.measureCalls = 0
        background.runAll()
        host.runPosted()

        assertEquals(source.getLineCount(), measurer.measureCalls, "one relayout ran, not two")
        assertEquals(1, host.relayoutsStarted, "announced once, for both pinches together")
        assertEquals(1, host.relayoutsFinished, "and finished once")
        assertFalse(coordinator.isZoomInProgress)
        assertEquals(120, coordinator.layout.width())
    }

    // A relayout that has finished in the background, but whose result the main
    // thread has not picked up yet when the next pinch starts.
    @Test
    fun aResultDeliveredAfterTheNextPinchBeganIsIgnored() {
        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()
        background.runAll()

        coordinator.beginZoom()
        host.runPosted()

        assertEquals(0, host.relayoutsFinished, "the stale result must not end the new zoom")
        assertTrue(coordinator.isZoomInProgress)

        // The new pinch ends without changing the size again. The previous relayout
        // was never delivered, so one more is due before the zoom can finish.
        coordinator.endZoom()
        assertTrue(coordinator.isZoomInProgress)
        background.runAll()
        host.runPosted()

        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isZoomInProgress)
    }

    // A relayout that is in the middle of measuring when the next pinch starts:
    // beginZoom has to wait until it has let go of the layout.
    @Test
    @Timeout(10)
    fun aNewPinchWaitsForARunningRelayoutToStop() {
        val relayoutIsMeasuring = CountDownLatch(1)
        val blockingSource = object : x.github.module.editor.model.EditorTextSource {
            override fun getLineCount() = 100_000
            override fun getLine(lineNumber: Int): String {
                relayoutIsMeasuring.countDown()
                return "abc"
            }
            override fun getLineLength(lineNumber: Int) = 3
        }
        val coordinator = LayoutCoordinator(host, blockingSource, measurer)
        val worker = Executor { Thread(it).start() }
        coordinator.backgroundExecutor = worker

        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()
        assertTrue(relayoutIsMeasuring.await(5, TimeUnit.SECONDS))

        // returns only once the background thread is out of the layout
        coordinator.beginZoom()
        host.runPosted()

        assertEquals(0, host.relayoutsFinished, "a cancelled relayout finishes no zoom")
        assertTrue(coordinator.isZoomInProgress)
    }

    // #endregion

    // #region wrap width

    // "one two three four" and "bb", wrapped at 100: three rows
    private fun wrapTheText() {
        source.lines[0] = "one two three four"
        coordinator.setWordwrap(true)
        assertEquals(3, coordinator.layout.count())
    }

    @Test
    fun anotherWrapWidthLaysTheTextOutAgainInTheBackground() {
        wrapTheText()

        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()

        assertEquals(1, host.relayoutsStarted)
        assertTrue(coordinator.isBusy, "locked until the lines are broken again")
        assertFalse(coordinator.isZoomInProgress, "but this is not a zoom")
        assertEquals(3, coordinator.layout.count(), "nothing happens on the calling thread")

        background.runAll()
        host.runPosted()

        assertEquals(50, coordinator.layout.width())
        assertTrue(coordinator.layout.count() > 3)
        assertEquals(1, host.layoutUpdates)
        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
    }

    @Test
    fun theSameWrapWidthDoesNothing() {
        wrapTheText()

        coordinator.syncWrapWidth()

        assertEquals(0, host.relayoutsStarted)
        assertTrue(background.tasks.isEmpty())
        assertFalse(coordinator.isBusy)
    }

    // Layout passes keep coming while a relayout is under way (showing a progress
    // bar causes one). They must not make it start over.
    @Test
    fun askingAgainForTheSameWidthDoesNotStartOver() {
        wrapTheText()
        host.wordwrapWidth = 50

        coordinator.syncWrapWidth()
        coordinator.syncWrapWidth()
        coordinator.syncWrapWidth()

        assertEquals(1, background.tasks.size)
        assertEquals(1, host.relayoutsStarted)
    }

    @Test
    fun withoutWordwrapTheWidthDoesNotMatter() {
        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()

        assertEquals(0, host.relayoutsStarted)
        assertTrue(background.tasks.isEmpty())
    }

    @Test
    fun aViewWithoutRoomForTextIsLeftAlone() {
        wrapTheText()

        host.wordwrapWidth = 0
        coordinator.syncWrapWidth()
        host.wordwrapWidth = -40
        coordinator.syncWrapWidth()

        assertEquals(0, host.relayoutsStarted)
    }

    // Dragging the divider of a split screen: the width changes many times before
    // the first relayout gets anywhere.
    @Test
    @Timeout(10)
    fun aSecondWidthChangeReplacesTheRelayoutForTheFirst() {
        wrapTheText()

        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()
        host.wordwrapWidth = 70
        coordinator.syncWrapWidth()

        background.runAll()
        host.runPosted()

        assertEquals(70, coordinator.layout.width())
        assertEquals(1, host.relayoutsStarted, "one announcement for the whole episode")
        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
    }

    @Test
    fun aWidthChangeDuringAPinchWaitsForThePinchToEnd() {
        wrapTheText()
        coordinator.beginZoom()

        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()

        assertEquals(1, host.relayoutsStarted)
        assertTrue(background.tasks.isEmpty(), "not while the text size is still changing")

        // the pinch ends without having changed the text size
        coordinator.endZoom()
        assertTrue(coordinator.isBusy, "the relayout for the new width is still due")
        background.runAll()
        host.runPosted()

        assertEquals(50, coordinator.layout.width())
        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
        assertFalse(coordinator.isZoomInProgress)
    }

    @Test
    @Timeout(10)
    fun aPinchDuringAReflowTakesItOver() {
        wrapTheText()
        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()

        // before the reflow has run
        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()
        background.runAll()
        host.runPosted()

        assertEquals(50, coordinator.layout.width())
        assertEquals(1, host.relayoutsStarted)
        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
    }

    // The width changes while a relayout is running, too late for it to notice.
    @Test
    fun aWidthThatChangedDuringARelayoutIsCaughtWhenItIsDelivered() {
        wrapTheText()
        host.wordwrapWidth = 50
        coordinator.syncWrapWidth()
        background.runAll()

        // after the relayout ran, before the main thread heard of it
        host.wordwrapWidth = 70
        host.runPosted()
        assertEquals(1, host.relayoutsFinished)
        assertTrue(coordinator.isBusy, "the next round has started")

        background.runAll()
        host.runPosted()

        assertEquals(70, coordinator.layout.width())
        assertEquals(2, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
    }

    // The view leaves its window while a relayout is running in the background.
    @Test
    @Timeout(10)
    fun aPausedRelayoutIsCalledOffAndStartsAgainWhenResumed() {
        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()

        coordinator.pauseBackgroundRelayout()
        measurer.measureCalls = 0
        background.runAll()
        host.runPosted()

        assertEquals(0, measurer.measureCalls, "nothing is laid out for a view that is gone")
        assertEquals(0, host.relayoutsFinished)
        assertTrue(coordinator.isBusy, "it is still due")

        coordinator.resumeBackgroundRelayout()
        background.runAll()
        host.runPosted()

        assertEquals(1, host.relayoutsStarted, "the same episode, not a new one")
        assertEquals(1, host.relayoutsFinished)
        assertFalse(coordinator.isBusy)
    }

    @Test
    fun resumingWithNothingPendingDoesNothing() {
        coordinator.resumeBackgroundRelayout()

        assertTrue(background.tasks.isEmpty())
        assertEquals(0, host.relayoutsStarted)
    }

    // #endregion

    // #region the coordinator's own thread

    @Test
    @Timeout(10)
    fun theCoordinatorHasAThreadOfItsOwnWhenNoneIsGiven() {
        coordinator.backgroundExecutor = null

        coordinator.beginZoom()
        measurer.textSize = 20f
        coordinator.endZoom()

        while (host.posted.isEmpty()) {
            Thread.sleep(10)
        }
        host.runPosted()

        assertEquals(1, host.relayoutsFinished)
        assertEquals(80, coordinator.layout.width())
    }

    // #endregion

    // An edit too large to measure on the main thread: laid out roughly at once,
    // then properly in the background, with edits refused in between (the app shows
    // its progress bar meanwhile).
    @Test
    fun aLargeEditIsMeasuredInTheBackgroundWithEditsRefusedMeanwhile() {
        source.edit(2, "bb", 0, listOf("cccccc", "d"))
        assertTrue(coordinator.runEdit { coordinator.layout.updateRoughly(2, 2, 0) })
        assertEquals(4, coordinator.layout.count(), "every line has its row at once")

        coordinator.relayoutAfterLargeEdit()
        assertEquals(1, host.relayoutsStarted)
        assertFalse(coordinator.runEdit {}, "an edit while the text is being laid out")

        background.runAll()
        host.runPosted()

        assertEquals(1, host.relayoutsFinished)
        assertEquals(60, coordinator.layout.width())
        assertTrue(coordinator.runEdit {})
    }
}
