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

package x.editor.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A maze with a ball that finds its own way out, as a player would, as the background of
 * a card (the resources status' header).
 *
 * The ball sees the exit (the ring at the edge) but not the way to it. It rolls at one
 * steady speed, cell after cell, never stopping; at a fork it takes a way it has not
 * tried, mostly the one heading towards the exit, sometimes another; at a dead end it turns
 * and rolls back to the last fork with a way not yet tried (a depth-first search, walked).
 * In every cell it gets to (going back too) it leaves a dot, which fades out slowly, gone
 * by the time it is [DOT_LIFE_STEPS] cells further on: a short trail behind it, the 6th
 * dot drawn as the 1st has gone. At the exit a ripple goes out, and a new maze, made at
 * random, takes the old one's place from the ball outwards; its exit is one of the edge
 * cells far from the ball, and the ball sets out again from where it is.
 *
 * A tap puts the ball in the cell tapped (a ripple there, the ball growing in); its dots
 * fade, and it sets out from there, to the same exit, knowing nothing of where it has been.
 *
 * The maze: a spanning tree of the grid (Kruskal's, the walls in a random order), with a
 * few more walls taken away so that there are loops. No outer wall: the card's edge is
 * one.
 *
 * It is a background, not a show: everything is drawn in [color] (the ball in [ballColor])
 * at low alphas, nothing flashes, the ball rolls slowly and evenly, and under the views
 * given to [setQuietViews] (the card's text) it is fainter still, so that what is over it
 * stays easy to read. It goes only while shown and while the system lets animations run;
 * otherwise it stands still.
 */
class MazeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** about how big a cell is (the cells are made to fit the view exactly) */
    var cellSize: Float = 24f * density
        set(value) {
            require(value > 0f) { "cellSize must be above 0: $value" }
            if (field == value) return
            field = value
            rebuild()
        }

    /** the colour of the walls, the dots and the rings */
    @ColorInt
    var color: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** the colour of the ball */
    @ColorInt
    var ballColor: Int = Color.WHITE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // the views over the maze it is to be fainter under, and where they are (left, top,
    // right, bottom, in this view), worked out when drawn
    private var quietViews: List<View> = emptyList()
    private var quietRects = FloatArray(0)
    private var quietCount = 0
    private val here = IntArray(2)
    private val there = IntArray(2)

    /**
     * Views over the maze (the card's title, subtitle, numbers) it is drawn fainter under,
     * fading in over a short way around them, so that it does not get in the way of
     * reading them. Views not shown are left out; where they are is followed as they move.
     */
    fun setQuietViews(vararg views: View?) {
        quietViews = views.filterNotNull()
        quietRects = FloatArray(quietViews.size * 4)
        invalidate()
    }

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val random = Random(SystemClock.uptimeMillis())

    // #region the maze

    // the grid: cols × rows cells, a cell (index row * cols + col) cellW × cellH
    private var cols = 0
    private var rows = 0
    private var cellW = 0f
    private var cellH = 0f

    // the walls: east[i], between cell i and the one right of it; south[i], between it and
    // the one below it. While a new maze takes the old one's place, the old one's too.
    private var east = BooleanArray(0)
    private var south = BooleanArray(0)
    private var oldEast = BooleanArray(0)
    private var oldSouth = BooleanArray(0)

    // how far every cell is from the ball (only to pick an exit far from it)
    private var dist = IntArray(0)
    private var queue = IntArray(0)
    private var exit = -1

    private var ballCell = 0
    private var ballX = 0f
    private var ballY = 0f

    // A new maze for the view's size, the ball in a cell at random
    private fun rebuild() {
        if (width == 0 || height == 0) return
        cols = max(3, (width / cellSize).roundToInt())
        cellW = width.toFloat() / cols
        rows = max(2, (height / cellW).roundToInt())
        cellH = height.toFloat() / rows
        val n = cols * rows
        east = BooleanArray(n)
        south = BooleanArray(n)
        oldEast = BooleanArray(n)
        oldSouth = BooleanArray(n)
        dist = IntArray(n)
        queue = IntArray(n)
        tried = BooleanArray(n)
        stack = IntArray(n)
        dotAt = FloatArray(n) { -1f }
        // the shortest step's time (cells may be a little wider than high), so many of them:
        // [DOT_LIFE_STEPS] steps on, the dot is gone whichever way they went
        dotLifeMs = DOT_LIFE_STEPS * CELL_MS * min(cellW, cellH) / ((cellW + cellH) / 2f)
        generate(east, south)
        placeBall(random.nextInt(n))
        pickExit()
        stepTo = -1
        resetDots()
        enter(Phase.REST, REST_MS)
        invalidate()
    }

    // A maze into [e] and [s]: every wall up, then Kruskal's (each wall, in a random order,
    // taken away if the cells on its two sides are not yet joined), then a few walls more
    // taken away (loops)
    private fun generate(e: BooleanArray, s: BooleanArray) {
        e.fill(true)
        s.fill(true)
        val n = cols * rows
        // the walls inside the grid, each as cell * 2 + (0: east, 1: south)
        val walls = IntArray(n * 2)
        var count = 0
        for (i in 0 until n) {
            if (i % cols < cols - 1) walls[count++] = i * 2
            if (i / cols < rows - 1) walls[count++] = i * 2 + 1
        }
        val order = walls.copyOf(count)
        order.shuffle(random)
        val sets = IntArray(n) { it }
        fun find(i: Int): Int {
            var root = i
            while (sets[root] != root) root = sets[root]
            var j = i
            while (sets[j] != root) j = sets[j].also { sets[j] = root }
            return root
        }
        for (wall in order) {
            val a = wall / 2
            val b = if (wall % 2 == 0) a + 1 else a + cols
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) {
                if (random.nextFloat() < LOOPS) clear(e, s, wall)
            } else {
                sets[ra] = rb
                clear(e, s, wall)
            }
        }
    }

    private fun clear(e: BooleanArray, s: BooleanArray, wall: Int) {
        if (wall % 2 == 0) e[wall / 2] = false else s[wall / 2] = false
    }

    // the cells next to [i] with no wall between: into [out], how many
    private fun openNeighbours(i: Int, out: IntArray): Int {
        var count = 0
        val c = i % cols
        val r = i / cols
        if (c < cols - 1 && !east[i]) out[count++] = i + 1
        if (c > 0 && !east[i - 1]) out[count++] = i - 1
        if (r < rows - 1 && !south[i]) out[count++] = i + cols
        if (r > 0 && !south[i - cols]) out[count++] = i - cols
        return count
    }

    // A new exit: an edge cell neither near the ball nor the farthest from it (a way of
    // 45% to 80% of the longest, breadth first), so that a search does not go on too long
    private fun pickExit() {
        dist.fill(-1)
        dist[ballCell] = 0
        var head = 0
        var end = 0
        queue[end++] = ballCell
        val next = IntArray(4)
        while (head < end) {
            val i = queue[head++]
            for (k in 0 until openNeighbours(i, next)) {
                val j = next[k]
                if (dist[j] < 0) {
                    dist[j] = dist[i] + 1
                    queue[end++] = j
                }
            }
        }
        val edge = (0 until cols * rows).filter { i ->
            i != ballCell && (i % cols == 0 || i % cols == cols - 1 || i / cols == 0 || i / cols == rows - 1)
        }.sortedByDescending { dist[it] }
        val far = dist[edge[0]]
        val pool = edge.filter { dist[it] >= max(EXIT_MIN_STEPS, (far * EXIT_NEAR).toInt()) && dist[it] <= (far * EXIT_FAR).toInt() }
        exit = if (pool.isNotEmpty()) pool[random.nextInt(pool.size)] else edge[random.nextInt(max(1, edge.size / 3))]
    }

    private fun placeBall(cell: Int) {
        ballCell = cell
        ballX = centerX(cell)
        ballY = centerY(cell)
    }

    private fun centerX(cell: Int) = (cell % cols + 0.5f) * cellW
    private fun centerY(cell: Int) = (cell / cols + 0.5f) * cellH

    // #endregion

    // #region the ball finding its way

    // the cells it has been in ([tried]), and the way it is on, from where it set out
    // ([stack], [depth] of them; the last is where it is)
    private var tried = BooleanArray(0)
    private var stack = IntArray(0)
    private var depth = 0

    // when it last got to each cell (-1: never): its dot, fading from then
    private var dotAt = FloatArray(0)
    private var dotLifeMs = DOT_LIFE_STEPS * CELL_MS

    // the step it is taking: from, to (-1: none), when it started, how long it takes (its
    // length at the one speed), whether it is going back; and when the last one ended (the
    // next starts then, not at the frame after: no stop between steps)
    private var stepFrom = 0
    private var stepTo = -1
    private var stepStart = 0f
    private var stepMs = 1f
    private var goingBack = false
    private var freeAt = 0f

    private val choices = IntArray(4)

    // Setting out afresh from where it is: nothing tried yet (the dots it left go on fading
    // as they were)
    private fun resetDots() {
        tried.fill(false)
        depth = 0
        stepTo = -1
        tried[ballCell] = true
        stack[depth++] = ballCell
        freeAt = clock
    }

    // In a cell: on to a cell not yet tried, or back
    private fun decide() {
        if (ballCell == exit) {
            arrive()
            return
        }
        var count = 0
        val open = openNeighbours(ballCell, choices)
        for (k in 0 until open) if (!tried[choices[k]]) choices[count++] = choices[k]
        if (count > 0) {
            startStep(choose(count), back = false)
        } else if (depth > 1) {
            // a dead end, or a fork all tried: given up, and back a cell
            depth--
            startStep(stack[depth - 1], back = true)
        } else {
            // (all tried, and the exit not found: cannot be, the maze is all joined) afresh
            resetDots()
        }
    }

    // Which of the [count] cells in [choices] to go to: mostly the one nearest the exit
    // (as the crow flies), sometimes one at random
    private fun choose(count: Int): Int {
        if (count == 1 || random.nextFloat() >= TOWARDS_EXIT) return choices[random.nextInt(count)]
        val ec = exit % cols
        val er = exit / cols
        var best = choices[0]
        var bestDistance = Int.MAX_VALUE
        for (k in 0 until count) {
            val j = choices[k]
            val d = abs(j % cols - ec) + abs(j / cols - er)
            if (d < bestDistance || (d == bestDistance && random.nextBoolean())) {
                best = j
                bestDistance = d
            }
        }
        return best
    }

    // A step to [to], from when the last one ended, as long as its length at the one speed
    // (cells may be a little wider than high: the same speed either way)
    private fun startStep(to: Int, back: Boolean) {
        stepFrom = ballCell
        stepTo = to
        stepStart = freeAt
        goingBack = back
        val length = hypot(centerX(to) - centerX(ballCell), centerY(to) - centerY(ballCell))
        stepMs = max(1f, length / ((cellW + cellH) / 2f) * CELL_MS)
    }

    // The step done: in the cell, free from the moment it got there
    private fun endStep() {
        val to = stepTo
        stepTo = -1
        freeAt = stepStart + stepMs
        placeBall(to)
        // a dot here, going back too (a new one over the old)
        dotAt[to] = freeAt
        if (!goingBack) {
            tried[to] = true
            stack[depth++] = to
        }
    }

    // #endregion

    // #region time

    private enum class Phase { REST, FIND, RENEW }

    // the time of the animation (milliseconds; it stands still while the view is not shown)
    private var clock = 0f
    private var lastFrame = 0L
    private var shown = false

    private var phase = Phase.REST
    private var phaseStart = 0f
    private var restMs = REST_MS

    // when the ball last grew in (a tap), and the ripple: where, and when (-1: none)
    private var grownAt = -APPEAR_MS
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleAt = -1f

    // where a new maze comes in from, and how far it has to go
    private var renewX = 0f
    private var renewY = 0f
    private var renewReach = 0f

    private fun moving() = shown && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    private fun enter(next: Phase, rest: Float = REST_MS) {
        phase = next
        phaseStart = clock
        restMs = rest
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) clock += min(now - lastFrame, MAX_FRAME_MS).toFloat()
        lastFrame = now
        val t = clock - phaseStart
        when (phase) {
            Phase.REST -> if (t >= restMs) {
                enter(Phase.FIND)
                freeAt = clock
                // the first dot of the trail, where it sets out from
                dotAt[ballCell] = clock
            }
            Phase.FIND -> roll()
            Phase.RENEW -> if (t >= RENEW_MS) enter(Phase.REST)
        }
    }

    // The ball on, at the one speed: the steps that ended since the last frame done (each
    // next one starting when the one before ended), and where it is in the one going on
    private fun roll() {
        var steps = 0
        while (phase == Phase.FIND && steps++ < MAX_STEPS_A_FRAME) {
            if (stepTo < 0) {
                decide()
                if (stepTo < 0) return
            }
            val u = (clock - stepStart) / stepMs
            if (u >= 1f) {
                endStep()
                continue
            }
            ballX = centerX(stepFrom) + (centerX(stepTo) - centerX(stepFrom)) * u
            ballY = centerY(stepFrom) + (centerY(stepTo) - centerY(stepFrom)) * u
            return
        }
    }

    // The ball at the exit: a ripple there, its dots fading, and a new maze, coming in
    // from the ball
    private fun arrive() {
        ripple(ballX, ballY)
        east.copyInto(oldEast)
        south.copyInto(oldSouth)
        generate(east, south)
        pickExit()
        resetDots()
        renewX = ballX
        renewY = ballY
        renewReach = max(
            max(hypot(renewX, renewY), hypot(width - renewX, renewY)),
            max(hypot(renewX, height - renewY), hypot(width - renewX, height - renewY)),
        ) + RENEW_EDGE * cellW
        enter(Phase.RENEW)
    }

    private fun ripple(x: Float, y: Float) {
        rippleX = x
        rippleY = y
        rippleAt = clock
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        // back on screen: on from where it stood, not from where the clock would be
        lastFrame = 0L
        if (isVisible) invalidate()
    }

    // #endregion

    // #region touch

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cols == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                // a tap, not a scroll of what the view is in
                if (abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                    performClick()
                    moveBall(event.x, event.y)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // The ball into the cell at ([x], [y]); it sets out from there, to the same exit
    private fun moveBall(x: Float, y: Float) {
        val c = (x / cellW).toInt().coerceIn(0, cols - 1)
        val r = (y / cellH).toInt().coerceIn(0, rows - 1)
        placeBall(r * cols + c)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        ripple(ballX, ballY)
        grownAt = clock
        // (a new maze still coming in: it is all there at once)
        resetDots()
        enter(Phase.REST, TAP_REST_MS)
        invalidate()
    }

    // #endregion

    // #region drawing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // no size of its own: as big as it is made (match_parent in a card), never more
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        if (cols == 0) return
        val moving = moving()
        if (moving) step() else lastFrame = 0L
        val t = clock - phaseStart
        val cell = min(cellW, cellH)
        findQuietRects()

        // the walls; while a new maze comes in, it shows from the ball outwards, the old one
        // going where it has come
        if (phase == Phase.RENEW) {
            val reach = renewReach * (t / RENEW_MS)
            drawWalls(canvas, oldEast, oldSouth, reach, old = true)
            drawWalls(canvas, east, south, reach, old = false)
        } else {
            drawWalls(canvas, east, south, Float.NaN, old = false)
        }

        drawDots(canvas, cell)
        drawExit(canvas, cell, if (phase == Phase.RENEW) revealed(centerX(exit), centerY(exit), renewReach * (t / RENEW_MS)) else 1f)
        drawBall(canvas, cell)
        drawRipple(canvas, cell)

        if (moving) postInvalidateOnAnimation()
    }

    // Where the quiet views are now, in this view
    private fun findQuietRects() {
        quietCount = 0
        if (quietViews.isEmpty()) return
        getLocationInWindow(here)
        for (view in quietViews) {
            if (!view.isShown || view.width == 0 || view.height == 0) continue
            view.getLocationInWindow(there)
            val left = (there[0] - here[0]).toFloat()
            val top = (there[1] - here[1]).toFloat()
            quietRects[quietCount * 4] = left
            quietRects[quietCount * 4 + 1] = top
            quietRects[quietCount * 4 + 2] = left + view.width
            quietRects[quietCount * 4 + 3] = top + view.height
            quietCount++
        }
    }

    // how much of what is at ([x], [y]) is drawn: [QUIET] under a quiet view, all of it
    // [QUIET_EDGE_DP] away from them, in between on the way
    private fun quiet(x: Float, y: Float): Float {
        var f = 1f
        val edge = QUIET_EDGE_DP * density
        for (k in 0 until quietCount) {
            val dx = max(max(quietRects[k * 4] - x, x - quietRects[k * 4 + 2]), 0f)
            val dy = max(max(quietRects[k * 4 + 1] - y, y - quietRects[k * 4 + 3]), 0f)
            val d = hypot(dx, dy)
            if (d >= edge) continue
            f = min(f, QUIET + (1f - QUIET) * (d / edge))
        }
        return f
    }

    // how far the new maze has come over ([x], [y]): 0, not yet; 1, all there
    private fun revealed(x: Float, y: Float, reach: Float): Float {
        val d = hypot(x - renewX, y - renewY)
        return ((reach - d) / (RENEW_EDGE * cellW)).coerceIn(0f, 1f)
    }

    // The walls of [e] and [s] (none on the outside); with a [reach] (not NaN), each as far
    // as the new maze has come over it ([old]: as far as it has not)
    private fun drawWalls(canvas: Canvas, e: BooleanArray, s: BooleanArray, reach: Float, old: Boolean) {
        wallPaint.color = color
        val base = Color.alpha(color) * WALL_ALPHA
        for (i in 0 until cols * rows) {
            val c = i % cols
            val r = i / cols
            val left = c * cellW
            val top = r * cellH
            val right = left + cellW
            val bottom = top + cellH
            if (c < cols - 1 && e[i]) wall(canvas, right, top, right, bottom, base, reach, old)
            if (r < rows - 1 && s[i]) wall(canvas, left, bottom, right, bottom, base, reach, old)
        }
    }

    private fun wall(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, reach: Float, old: Boolean) {
        val mx = (x0 + x1) / 2
        val my = (y0 + y1) / 2
        var a = quiet(mx, my)
        if (!reach.isNaN()) {
            val f = revealed(mx, my, reach)
            a *= if (old) 1f - f else f
        }
        val alpha = (base * a).roundToInt()
        if (alpha <= 0) return
        wallPaint.alpha = alpha
        canvas.drawLine(x0, y0, x1, y1, wallPaint)
    }

    // How bright the dot of cell [i] is now (0: none): coming up as the ball gets there,
    // then fading out slowly (gently at first and at the end, smoothstep), gone
    // [DOT_LIFE_STEPS] cells' time later, when the ball gets that many cells further on
    // (it rolls at one speed). A little smaller as it fades.
    private fun dotLevel(i: Int): Float {
        val at = dotAt[i]
        if (at < 0f) return 0f
        val age = clock - at
        val left = 1f - age / dotLifeMs
        if (left <= 0f) return 0f
        val up = min(1f, age / DOT_RISE_MS)
        return up * left * left * (3f - 2f * left)
    }

    // The dots the ball left, each fading out on its own
    private fun drawDots(canvas: Canvas, cell: Float) {
        fillPaint.color = color
        val base = Color.alpha(color) * DOT_ALPHA
        for (i in 0 until cols * rows) {
            if (i == ballCell && stepTo < 0) continue
            val level = dotLevel(i)
            if (level <= 0f) continue
            val alpha = (base * level * quiet(centerX(i), centerY(i))).roundToInt()
            if (alpha <= 0) continue
            fillPaint.alpha = alpha
            canvas.drawCircle(centerX(i), centerY(i), cell * DOT * (0.6f + 0.4f * level), fillPaint)
        }
    }

    // The exit: a ring, breathing
    private fun drawExit(canvas: Canvas, cell: Float, fade: Float) {
        if (exit < 0 || fade <= 0f) return
        val beat = 0.5f + 0.5f * sin(2f * PI.toFloat() * clock / EXIT_BEAT_MS)
        val x = centerX(exit)
        val y = centerY(exit)
        ringPaint.color = color
        ringPaint.alpha = (Color.alpha(color) * (EXIT_ALPHA + EXIT_BEAT_ALPHA * beat) * fade * quiet(x, y)).roundToInt()
        canvas.drawCircle(x, y, cell * (0.26f + 0.02f * beat), ringPaint)
    }

    // The ball, with a soft halo; after a tap it grows in (a little past its size, then back)
    private fun drawBall(canvas: Canvas, cell: Float) {
        val u = ((clock - grownAt) / APPEAR_MS).coerceIn(0f, 1f)
        val v = u - 1f
        val grow = if (u >= 1f) 1f else 1f + 2.4f * v * v * v + 1.4f * v * v
        val r = cell * BALL * grow
        if (r <= 0f) return
        val q = quiet(ballX, ballY)
        fillPaint.color = ballColor
        fillPaint.alpha = (Color.alpha(ballColor) * HALO_ALPHA * q).roundToInt()
        canvas.drawCircle(ballX, ballY, r * 1.7f, fillPaint)
        fillPaint.alpha = (Color.alpha(ballColor) * BALL_ALPHA * q).roundToInt()
        canvas.drawCircle(ballX, ballY, r, fillPaint)
    }

    private fun drawRipple(canvas: Canvas, cell: Float) {
        if (rippleAt < 0f) return
        val u = (clock - rippleAt) / RIPPLE_MS
        if (u >= 1f) {
            rippleAt = -1f
            return
        }
        val out = 1f - (1f - u) * (1f - u)
        ringPaint.color = ballColor
        ringPaint.alpha = (Color.alpha(ballColor) * RIPPLE_ALPHA * (1f - u) * quiet(rippleX, rippleY)).roundToInt()
        canvas.drawCircle(rippleX, rippleY, cell * (0.3f + 1.2f * out), ringPaint)
    }

    // #endregion

    private companion object {
        // of the walls inside a spanning tree, how many more are taken away (loops)
        const val LOOPS = 0.07f

        // at a fork, how often the ball takes the way heading towards the exit (otherwise
        // one at random)
        const val TOWARDS_EXIT = 0.75f

        // how far the exit is from the ball, in steps of the way to it: at least, and a part
        // of the farthest edge cell's (from, to)
        const val EXIT_MIN_STEPS = 5
        const val EXIT_NEAR = 0.45f
        const val EXIT_FAR = 0.8f

        // the ball's one speed: milliseconds a cell, forwards and back alike
        const val CELL_MS = 300f

        // a dot fades out over the time the ball takes to roll this many cells: drawing the
        // 6th, the 1st is gone
        const val DOT_LIFE_STEPS = 5

        // milliseconds: the rest before setting out (after a tap), a new maze coming in, a
        // ripple, the ball growing in, the exit's ring breathing, a dot coming up
        const val REST_MS = 1200f
        const val TAP_REST_MS = 400f
        const val RENEW_MS = 1400f
        const val RIPPLE_MS = 600f
        const val APPEAR_MS = 320f
        const val EXIT_BEAT_MS = 2400f
        const val DOT_RISE_MS = 120f

        // a frame longer than this (the view held up) counts as this; and how many steps
        // can end in one frame
        const val MAX_FRAME_MS = 50L
        const val MAX_STEPS_A_FRAME = 4

        // the soft edge of a new maze coming in, in cells
        const val RENEW_EDGE = 2.5f

        // under a quiet view: how much is drawn, and how far around it that grows to all
        const val QUIET = 0.35f
        const val QUIET_EDGE_DP = 20f

        // sizes, in cells, and alphas (of the colours given): low, a background
        const val BALL = 0.14f
        const val DOT = 0.075f
        const val WALL_ALPHA = 0.12f
        const val DOT_ALPHA = 0.26f
        const val BALL_ALPHA = 0.55f
        const val HALO_ALPHA = 0.08f
        const val EXIT_ALPHA = 0.18f
        const val EXIT_BEAT_ALPHA = 0.08f
        const val RIPPLE_ALPHA = 0.3f
    }
}
