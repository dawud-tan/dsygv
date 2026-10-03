package com.dawud.mpmrbench

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import griyasakha.ElementDef
import griyasakha.Node
import griyasakha.reducedUx
import griyasakha.reducedUy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Axonometric wireframe of the frame model, with animated mode shapes.
 *
 * Deliberately a plain Canvas View. 24 nodes and ~50 line segments do not
 * justify OpenGL, and Compose was removed from this module on purpose -- it
 * was 44.9 MB of the 44.9 MB dex for a screen whose job is drawing lines.
 *
 * Lives under app/ rather than src/ because it imports android.*, which would
 * break the host harness build (see CLAUDE.md).
 */
class StructureView(context: Context) : View(context) {

    // ---- model ----------------------------------------------------------
    private var nodes: List<Node> = emptyList()
    private var elements: List<ElementDef> = emptyList()

    /** Current mode shape over the Guyan-reduced [Ux, Uy, ...] layout, or
     * null to draw the undeformed structure only. */
    private var phi: DoubleArray? = null

    /** Scale that maps this mode's largest |phi| onto a fixed fraction of the
     * building size, so every mode is legible regardless of how the
     * mass-normalization happened to scale it. Recomputed per mode. */
    private var autoScale = 0.0

    /** User multiplier on top of autoScale, from the slider. */
    var userScale = 1.0f
        set(v) {
            field = v; invalidate()
        }

    var animating = true
        set(v) {
            // Capture/restore the phase HERE, once per toggle. Doing it in
            // onDraw (the old `else pausedNs = now - startNs`) re-stamped it
            // on every redraw, so a paused view jumped phase whenever
            // anything else invalidated it -- moving the slider, picking a
            // node -- which looks like a glitch rather than a pause.
            if (v) startNs = System.nanoTime() - pausedNs
            else pausedNs = System.nanoTime() - startNs
            field = v; invalidate()
        }

    /** Called with (node, phiUx, phiUy) when a roof node is tapped. */
    var onNodePicked: ((Node, Double, Double) -> Unit)? = null

    // ---- view state -----------------------------------------------------
    private var azimuth = Math.toRadians(35.0)
    private var elevation = Math.toRadians(24.0)
    private var zoom = 1.0f
    private var panX = 0f
    private var panY = 0f
    private var selected: Node? = null

    private var startNs = System.nanoTime()
    private var pausedNs = 0L

    /** Wall-clock seconds for one animation cycle. NOT the real period: the
     * fundamental is ~0.09 s (11 Hz), which is far too fast to read. The
     * animation is illustrative of the SHAPE, not of the timing. */
    private val visualPeriodS = 1.8

    // model centre, set in setModel()
    private var cx = 0.0;
    private var cy = 0.0;
    private var cz = 0.0
    private var modelSpan = 1.0

    // ---- paints (preallocated; onDraw must not allocate) -----------------
    private val bg = Color.rgb(250, 250, 250)
    private val colColumn = Color.rgb(55, 71, 79)
    private val colBeam = Color.rgb(0, 121, 107)
    private val colWall = Color.rgb(26, 35, 126)
    private val colAac = Color.rgb(239, 108, 0)
    private val colBrick = Color.rgb(198, 40, 40)
    private val colGhost = Color.rgb(206, 212, 218)
    private val colNode = Color.rgb(38, 50, 56)
    private val colPick = Color.rgb(194, 24, 91)

    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(0.3f, 6f)
                invalidate(); return true
            }
        })

    fun setModel(nodes: List<Node>, elements: List<ElementDef>) {
        this.nodes = nodes
        this.elements = elements
        if (nodes.isNotEmpty()) {
            cx = (nodes.minOf { it.x } + nodes.maxOf { it.x }) / 2.0
            cy = (nodes.minOf { it.y } + nodes.maxOf { it.y }) / 2.0
            cz = (nodes.minOf { it.z } + nodes.maxOf { it.z }) / 2.0
            modelSpan = max(
                nodes.maxOf { it.x } - nodes.minOf { it.x },
                max(
                    nodes.maxOf { it.y } - nodes.minOf { it.y },
                    nodes.maxOf { it.z } - nodes.minOf { it.z })
            )
        }
        invalidate()
    }

    fun setMode(phi: DoubleArray?) {
        this.phi = phi
        // Peak |phi| maps to 12% of the building span. Mass-normalized mode
        // shapes carry no physical displacement scale, so an absolute factor
        // would make some modes invisible and others leave the screen.
        autoScale = if (phi == null || phi.isEmpty()) 0.0 else {
            val peak = phi.maxOf { abs(it) }
            if (peak > 1e-30) 0.12 * modelSpan / peak else 0.0
        }
        selected = null
        startNs = System.nanoTime(); pausedNs = 0L
        invalidate()
    }

    // ---- projection ------------------------------------------------------
    // Rotate about the vertical (Z) axis by azimuth, tilt about the screen-
    // horizontal axis by elevation, then project orthographically. Returns
    // (screenX, screenY, depth) in model units; depth is used only for
    // painter's-algorithm ordering.
    private val proj = DoubleArray(3)
    private fun project(x: Double, y: Double, z: Double) {
        val dx = x - cx;
        val dy = y - cy;
        val dz = z - cz
        val ca = cos(azimuth);
        val sa = sin(azimuth)
        val x1 = dx * ca - dy * sa
        val y1 = dx * sa + dy * ca
        val ce = cos(elevation);
        val se = sin(elevation)
        proj[0] = x1
        proj[1] = -(y1 * se + dz * ce)   // screen Y grows downward
        proj[2] = y1 * ce - dz * se      // depth, larger = farther
    }

    private fun displacedOf(n: Node, amp: Double, out: DoubleArray) {
        out[0] = n.x; out[1] = n.y; out[2] = n.z
        val p = phi ?: return
        // Base nodes are fixed and have no reduced DOF at all -- reducedUx
        // throws for them by design, so gate on hasDof rather than catching.
        if (!n.hasDof) return
        val s = autoScale * userScale * amp
        out[0] = n.x + p[n.reducedUx] * s
        out[1] = n.y + p[n.reducedUy] * s
    }

    private val tmpA = DoubleArray(3)
    private val tmpB = DoubleArray(3)

    private val ampExtremes = doubleArrayOf(-1.0, 0.0, 1.0)
    private val tmpF = DoubleArray(3)

    private fun fitScale(): Double {
        if (nodes.isEmpty()) return 1.0
        // Fit over the animation's EXTREMES, not the current frame. Fitting
        // the undeformed shape alone let the deformed shape run off both
        // screen edges at peak amplitude; fitting the current frame instead
        // would make the whole view breathe in and out as it animates.
        // Sampling amp = -1, 0, +1 bounds the motion and stays constant.
        var minX = Double.MAX_VALUE;
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE;
        var maxY = -Double.MAX_VALUE
        for (a in ampExtremes) for (n in nodes) {
            displacedOf(n, a, tmpF)
            project(tmpF[0], tmpF[1], tmpF[2])
            minX = min(minX, proj[0]); maxX = max(maxX, proj[0])
            minY = min(minY, proj[1]); maxY = max(maxY, proj[1])
        }
        val bw = max(maxX - minX, 1e-9);
        val bh = max(maxY - minY, 1e-9)
        val usableH = height - 90.0 // leave room for the legend strip
        return min(width * 0.82 / bw, usableH * 0.82 / bh) * zoom
    }

    private fun colorFor(type: String): Int = when {
        type == "COLUMN" -> colColumn
        type == "SHEARWALL" -> colWall
        type.startsWith("BEAM") -> colBeam
        type == "STRUT_AAC" -> colAac
        type == "STRUT_BRICK" -> colBrick
        else -> colNode
    }

    private fun widthFor(type: String): Float = when {
        type == "SHEARWALL" -> 9f
        type == "COLUMN" -> 6f
        type.startsWith("BEAM") -> 5f
        else -> 2.5f            // struts: thin, they are an idealisation
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        if (nodes.isEmpty()) return

        val amp = if (animating) {
            val t = (System.nanoTime() - startNs) / 1e9
            sin(2 * PI * t / visualPeriodS)
        } else {
            sin(2 * PI * (pausedNs / 1e9) / visualPeriodS)
        }

        val f = fitScale()
        val ox = width / 2f + panX
        val oy = height / 2f + panY - 30f

        // Undeformed reference, faint, behind everything.
        if (phi != null) {
            pLine.color = colGhost; pLine.strokeWidth = 2f
            for (e in elements) {
                project(e.nodeA.x, e.nodeA.y, e.nodeA.z)
                val ax = ox + (f * proj[0]).toFloat();
                val ay = oy + (f * proj[1]).toFloat()
                project(e.nodeB.x, e.nodeB.y, e.nodeB.z)
                canvas.drawLine(
                    ax,
                    ay,
                    ox + (f * proj[0]).toFloat(),
                    oy + (f * proj[1]).toFloat(),
                    pLine
                )
            }
        }

        // Painter's algorithm: far elements first, so near ones overdraw.
        val order = elements.indices.sortedByDescending { i ->
            val e = elements[i]
            displacedOf(e.nodeA, amp, tmpA); project(tmpA[0], tmpA[1], tmpA[2])
            val da = proj[2]
            displacedOf(e.nodeB, amp, tmpB); project(tmpB[0], tmpB[1], tmpB[2])
            da + proj[2]
        }

        for (i in order) {
            val e = elements[i]
            pLine.color = colorFor(e.type)
            pLine.strokeWidth = widthFor(e.type)
            displacedOf(e.nodeA, amp, tmpA); project(tmpA[0], tmpA[1], tmpA[2])
            val ax = ox + (f * proj[0]).toFloat();
            val ay = oy + (f * proj[1]).toFloat()
            displacedOf(e.nodeB, amp, tmpB); project(tmpB[0], tmpB[1], tmpB[2])
            canvas.drawLine(
                ax,
                ay,
                ox + (f * proj[0]).toFloat(),
                oy + (f * proj[1]).toFloat(),
                pLine
            )
        }

        // Nodes: roof nodes are the ones that move and can be tapped.
        for (n in nodes) {
            displacedOf(n, amp, tmpA); project(tmpA[0], tmpA[1], tmpA[2])
            val sx = ox + (f * proj[0]).toFloat();
            val sy = oy + (f * proj[1]).toFloat()
            pDot.color = if (n === selected) colPick else colNode
            canvas.drawCircle(sx, sy, if (n === selected) 11f else if (n.hasDof) 6f else 4f, pDot)
        }

        drawLegend(canvas)

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawLegend(canvas: Canvas) {
        val items = arrayOf(
            "column" to colColumn, "beam" to colBeam, "wall" to colWall,
            "AAC strut" to colAac, "brick strut" to colBrick
        )
        var x = 18f
        val y = height - 22f
        pText.textSize = 24f
        for ((label, c) in items) {
            pLine.color = c; pLine.strokeWidth = 7f
            canvas.drawLine(x, y - 8f, x + 26f, y - 8f, pLine)
            pText.color = colNode
            canvas.drawText(label, x + 33f, y, pText)
            x += 40f + pText.measureText(label) + 20f
        }
    }

    // ---- touch -----------------------------------------------------------
    private var downX = 0f;
    private var downY = 0f
    private var lastX = 0f;
    private var lastY = 0f
    private var downAt = 0L
    private var dragged = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                downAt = System.currentTimeMillis(); dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || ev.pointerCount > 1) {
                    lastX = ev.x; lastY = ev.y; return true
                }
                val dx = ev.x - lastX;
                val dy = ev.y - lastY
                if (!dragged && hypot(ev.x - downX, ev.y - downY) > slop) dragged = true
                if (dragged) {
                    // 0.004 rad/px: at the previous 0.008 a single 300 px
                    // swipe spun the model ~137 degrees, which made it
                    // impossible to land on a chosen viewpoint.
                    azimuth += dx * 0.004
                    // Clamp to [0, 90 deg]: past vertical the model turns
                    // inside out and the plan/elevation reading is lost.
                    elevation = (elevation + dy * 0.004).coerceIn(0.0, PI / 2)
                    invalidate()
                }
                lastX = ev.x; lastY = ev.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragged && System.currentTimeMillis() - downAt < 350) pick(ev.x, ev.y)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun pick(px: Float, py: Float) {
        val p = phi ?: return
        val amp = if (animating) sin(2 * PI * ((System.nanoTime() - startNs) / 1e9) / visualPeriodS)
        else sin(2 * PI * (pausedNs / 1e9) / visualPeriodS)
        val f = fitScale()
        val ox = width / 2f + panX
        val oy = height / 2f + panY - 30f
        var best: Node? = null
        var bestD = 70f  // generous: fingers are wide, nodes are 6 px
        for (n in nodes) {
            if (!n.hasDof) continue          // only roof nodes carry phi
            displacedOf(n, amp, tmpA); project(tmpA[0], tmpA[1], tmpA[2])
            val d = hypot(ox + (f * proj[0]).toFloat() - px, oy + (f * proj[1]).toFloat() - py)
            if (d < bestD) {
                bestD = d; best = n
            }
        }
        selected = best
        if (best != null) onNodePicked?.invoke(best, p[best.reducedUx], p[best.reducedUy])
        invalidate()
    }
}