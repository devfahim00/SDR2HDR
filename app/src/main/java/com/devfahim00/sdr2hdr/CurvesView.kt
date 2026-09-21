package com.devfahim00.sdr2hdr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive RGB curves editor (advanced mode).
 *
 * - Tap the plot to add a control point, drag to shape the curve.
 * - Long-press a point to delete it (endpoints are anchored).
 * - Channel chips switch between master / red / green / blue.
 * - Exports the ffmpeg `curves` filter point list per channel.
 */
class CurvesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Channel(val ffmpegKey: String, val color: Int) {
        MASTER("master", 0xFFF2F4F8.toInt()),
        RED("r", 0xFFEB5D6B.toInt()),
        GREEN("g", 0xFF34C759.toInt()),
        BLUE("b", 0xFF1C75FD.toInt())
    }

    var activeChannel: Channel = Channel.MASTER
        private set

    var onCurvesChanged: ((Map<String, List<Pair<Double, Double>>>) -> Unit)? = null

    private val points = LinkedHashMap<Channel, MutableList<Pair<Float, Float>>>()

    init {
        for (c in Channel.entries) points[c] = mutableListOf(0f to 0f, 1f to 1f)
    }

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D1016.toInt() }
    private val bgRect = RectF()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        strokeWidth = 1f
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        strokeWidth = 1.2f * density / 2
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density / 2
    }
    private val curvePaintDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density / 2
        alpha = 70
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density / 2
        color = 0xFF0D1016.toInt()
    }

    private val path = Path()

    private var draggingIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dirtySinceDown = false
    private val longPressRunnable = Runnable {
        if (draggingIndex > 0 && draggingIndex < currentPoints().size - 1) {
            currentPoints().removeAt(draggingIndex)
            draggingIndex = -1
            dirtySinceDown = true
            notifyChanged()
            invalidate()
        }
    }

    private fun currentPoints(): MutableList<Pair<Float, Float>> = points[activeChannel]!!

    private fun padPx(): Float = 10f * density

    private fun plotLeft() = padPx()
    private fun plotTop() = padPx()
    private fun plotRight() = width - padPx()
    private fun plotBottom() = height - padPx()

    private fun nx(x: Float): Float = plotLeft() + (plotRight() - plotLeft()) * x.coerceIn(0f, 1f)
    private fun ny(y: Float): Float = plotBottom() - (plotBottom() - plotTop()) * y.coerceIn(0f, 1f)

    fun setChannel(channel: Channel) {
        activeChannel = channel
        draggingIndex = -1
        invalidate()
    }

    fun resetActive() {
        points[activeChannel] = mutableListOf(0f to 0f, 1f to 1f)
        notifyChanged()
        invalidate()
    }

    fun resetAll() {
        for (c in Channel.entries) points[c] = mutableListOf(0f to 0f, 1f to 1f)
        notifyChanged()
        invalidate()
    }

    fun isNeutral(): Boolean = points.values.all { isNeutralList(it) }

    private fun isNeutralList(p: List<Pair<Float, Float>>): Boolean =
        p.size == 2 && p[0].first == 0f && p[0].second == 0f && p[1].first == 1f && p[1].second == 1f

    /** Non-neutral channels in the config serialisation format. */
    fun exportPoints(): Map<String, List<Pair<Double, Double>>> {
        val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
        for ((ch, pts) in points) {
            if (isNeutralList(pts)) continue
            out[ch.ffmpegKey] = pts.map { Pair(it.first.toDouble(), it.second.toDouble()) }
        }
        return out
    }

    private fun notifyChanged() {
        onCurvesChanged?.invoke(exportPoints())
    }

    // ── drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, 12f * density, 12f * density, bgPaint)

        // grid
        for (i in 1..3) {
            val gx = nx(i / 4f)
            canvas.drawLine(gx, plotTop(), gx, plotBottom(), gridPaint)
            val gy = ny(i / 4f)
            canvas.drawLine(plotLeft(), gy, plotRight(), gy, gridPaint)
        }

        // diagonal reference
        canvas.drawLine(nx(0f), ny(0f), nx(1f), ny(1f), refPaint)

        // inactive channels
        for ((ch, pts) in points) {
            if (ch == activeChannel) continue
            if (isNeutralList(pts) && ch != Channel.MASTER) continue
            drawCurve(canvas, sampleCurve(pts), ch.color, dim = true)
        }
        if (activeChannel != Channel.MASTER) {
            // show master faintly as reference
            drawCurve(canvas, sampleCurve(points[Channel.MASTER]!!), Channel.MASTER.color, dim = true)
        }

        // active curve
        drawCurve(canvas, sampleCurve(currentPoints()), activeChannel.color, dim = false)

        // control points
        val r = 4.5f * density / 2 + 2f
        for ((i, p) in currentPoints().withIndex()) {
            val cx = nx(p.first)
            val cy = ny(p.second)
            pointPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, r, pointPaint)
            pointStroke.color = activeChannel.color
            canvas.drawCircle(cx, cy, r, pointStroke)
        }
    }

    private fun drawCurve(
        canvas: Canvas,
        sampled: List<Pair<Float, Float>>,
        color: Int,
        dim: Boolean
    ) {
        val paint = if (dim) curvePaintDim else curvePaint
        paint.color = color
        if (dim) paint.alpha = 70
        path.reset()
        var first = true
        for (p in sampled) {
            if (first) {
                path.moveTo(nx(p.first), ny(p.second))
                first = false
            } else {
                path.lineTo(nx(p.first), ny(p.second))
            }
        }
        canvas.drawPath(path, paint)
    }

    // ── interaction ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dirtySinceDown = false
                val nd = nearestPoint(event.x, event.y)
                if (nd >= 0) {
                    draggingIndex = nd
                } else if (currentPoints().size < 6) {
                    // insert a new interior point and start dragging it
                    val nx01 = xToNormalized(event.x)
                    val ny01 = yToNormalized(event.y)
                    val interior = nx01.coerceIn(0.02f, 0.98f)
                    val list = currentPoints()
                    var insertAt = list.indexOfFirst { it.first > interior }
                    if (insertAt <= 0) insertAt = list.size - 1
                    list.add(insertAt, interior to ny01.coerceIn(0f, 1f))
                    draggingIndex = insertAt
                    dirtySinceDown = true
                    notifyChanged()
                }
                postDelayed(longPressRunnable, 550)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 12f * density / 2 || abs(event.y - downY) > 12f * density / 2) {
                    removeCallbacks(longPressRunnable)
                }
                if (draggingIndex >= 0) {
                    val list = currentPoints()
                    if (draggingIndex < list.size) {
                        val locked = draggingIndex == 0 || draggingIndex == list.size - 1
                        val p = list[draggingIndex]
                        val lo = if (draggingIndex > 0) list[draggingIndex - 1].first + 0.01f else 0f
                        val hi =
                            if (draggingIndex < list.size - 1) list[draggingIndex + 1].first - 0.01f else 1f
                        val x = if (locked) p.first else xToNormalized(event.x).coerceIn(lo, hi)
                        val y = yToNormalized(event.y).coerceIn(0f, 1f)
                        list[draggingIndex] = x to y
                        dirtySinceDown = true
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_UP && draggingIndex < 0 && !dirtySinceDown) {
                    performClick()
                }
                if (dirtySinceDown) notifyChanged()
                draggingIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestPoint(px: Float, py: Float): Int {
        val threshold = 20f * density / 2
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((i, p) in currentPoints().withIndex()) {
            val dx = nx(p.first) - px
            val dy = ny(p.second) - py
            val d = dx * dx + dy * dy
            if (d < bestDist && d < threshold * threshold) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun xToNormalized(px: Float): Float =
        ((px - plotLeft()) / max(1f, plotRight() - plotLeft())).coerceIn(0f, 1f)

    private fun yToNormalized(py: Float): Float =
        ((plotBottom() - py) / max(1f, plotBottom() - plotTop())).coerceIn(0f, 1f)

    // ── monotone cubic spline (Fritsch–Carlson) ────────────────────────────────

    private fun sampleCurve(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pts.size < 2) return pts.toList()
        val n = pts.size
        val xs = FloatArray(n) { pts[it].first }
        val ys = FloatArray(n) { pts[it].second }

        val d = FloatArray(n - 1) {
            (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it])
        }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f
            } else {
                val hPrev = xs[i] - xs[i - 1]
                val hNext = xs[i + 1] - xs[i]
                val w1 = 2f * hNext + hPrev
                val w2 = hNext + 2f * hPrev
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }

        val out = ArrayList<Pair<Float, Float>>(64)
        val steps = 14
        for (i in 0 until n - 1) {
            val h = xs[i + 1] - xs[i]
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                val y = (2 * t3 - 3 * t2 + 1) * ys[i] +
                    (t3 - 2 * t2 + t) * h * m[i] +
                    (-2 * t3 + 3 * t2) * ys[i + 1] +
                    (t3 - t2) * h * m[i + 1]
                out.add(xs[i] + t * h to y.coerceIn(0f, 1f))
            }
        }
        out.add(xs[n - 1] to ys[n - 1])
        return out
    }
}
