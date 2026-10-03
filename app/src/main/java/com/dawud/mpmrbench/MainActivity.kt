package com.dawud.mpmrbench

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import griyasakha.Assembler
import griyasakha.Assessment
import griyasakha.BoqWorkbook
import griyasakha.CQC
import griyasakha.DOF_PER_NODE
import griyasakha.Eigenpair
import griyasakha.GeometryBuilder
import griyasakha.Grid
import griyasakha.GuyanReduction
import griyasakha.MASTER_DOF_PER_NODE
import griyasakha.MPMR
import griyasakha.PriceFile
import griyasakha.QuantityTakeoff
import griyasakha.RabComparison
import griyasakha.RabInput
import griyasakha.SeismicParams
import griyasakha.SiteChecklist
import griyasakha.StoryDriftCheck
import griyasakha.TakeoffInput
import griyasakha.TorsionalIrregularityCheck
import griyasakha.matVec
import java.io.File
import java.util.Locale

/** Interactive viewer for the analysis: an axonometric wireframe of the frame
 * model with animated mode shapes, plus the full numeric report behind a
 * toggle. Lives here (not in the shared src/) because it imports android.*
 * and would break the host harness build. */
class MainActivity : Activity() {

    private lateinit var view: StructureView
    private lateinit var header: TextView
    private lateinit var nodeInfo: TextView
    private lateinit var playBtn: Button
    private lateinit var report: ScrollView

    private var modes: List<Eigenpair> = emptyList()
    private var rows: List<MPMR.MpmrRow> = emptyList()
    private var current = 0

    /** Single source of the model grid. runAnalysis() and runTakeoff() both
     * read it; it used to be a literal inside runAnalysis(), and adding the
     * takeoff would have made a second copy free to drift. */
    private val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)

    /** Pages behind the one toggle button, in cycle order. Addressed by NAME
     * through page(), so inserting one does not silently shift the others. */
    private val pageNames = listOf("3D", "Report", "Bill", "RAB", "Checks", "Site")
    private var reportPage = 0
    private val pageText = Array(pageNames.size) { "" }

    private fun page(name: String): Int =
        pageNames.indexOf(name).also { require(it >= 0) { "no page named $name" } }

    /** Each page builds in its own try, so one failure (a malformed rab.txt, a
     * native library that did not load) leaves every other page readable. */
    private fun safely(label: String, f: () -> String) = try {
        f()
    } catch (e: Throwable) {
        "$label FAILED: ${e::class.java.simpleName}\n${e.message}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        header = TextView(this).apply {
            setPadding(24, 20, 24, 8); textSize = 15f; setTextColor(Color.rgb(38, 50, 56))
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        view = StructureView(this)
        val reportText = TextView(this).apply {
            setPadding(24, 24, 24, 24); textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        report = ScrollView(this).apply {
            addView(reportText); visibility = View.GONE
            setBackgroundColor(Color.rgb(250, 250, 250)) // opaque: it sits over the canvas
        }

        val stage = FrameLayout(this)
        stage.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stage.addView(report, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        nodeInfo = TextView(this).apply {
            setPadding(24, 6, 24, 6); textSize = 13f
            setTextColor(Color.rgb(194, 24, 91))
            text = "Drag to orbit · pinch to zoom · tap a roof node"
        }
        root.addView(nodeInfo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Displacement scale. The slider is a multiplier on an automatic
        // per-mode scale, because mass-normalized mode shapes carry no
        // physical displacement magnitude to draw at 1:1.
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val scaleLabel = TextView(this).apply { textSize = 13f; text = "scale x1.0" }
        val bar = SeekBar(this).apply {
            max = 100; progress = 33
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val m = p / 33f
                    view.userScale = m
                    scaleLabel.text = "scale x${"%.1f".format(Locale.ROOT, m)}"
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        scaleRow.addView(bar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        scaleRow.addView(scaleLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(scaleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val ctl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 12)
        }
        val prev = Button(this).apply { text = "◀"; setOnClickListener { step(-1) } }
        val next = Button(this).apply { text = "▶"; setOnClickListener { step(+1) } }
        playBtn = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                view.animating = !view.animating
                text = if (view.animating) "Pause" else "Play"
            }
        }
        // All the report pages sit behind one button, cycling
        // 3D -> Report -> Bill -> RAB -> Checks -> Site -> 3D. The label names the
        // DESTINATION, which is what the original two-state version did. One
        // button per page was the alternative and the control row is already
        // tight -- see the note below it.
        val toggle = Button(this).apply {
            text = "Report"
            setOnClickListener {
                reportPage = (reportPage + 1) % pageNames.size
                // Hide the canvas underneath. The ScrollView is stacked over
                // it in a FrameLayout, so toggling only the report drew the
                // text ON TOP of the wireframe. Hiding the view also stops
                // its postInvalidateOnAnimation loop while the report is up.
                report.visibility = if (reportPage == 0) View.GONE else View.VISIBLE
                view.visibility = if (reportPage == 0) View.VISIBLE else View.GONE
                // RAB is rebuilt on every visit: it needs no eigenvalues, takes
                // milliseconds, and its inputs are files pushed with adb while
                // the app is running.
                if (reportPage == page("RAB")) pageText[reportPage] = safely("RAB") { runRab() }
                if (reportPage != 0) reportText.text = pageText[reportPage]
                // The label names the page the button will take you to next.
                text = pageNames[(reportPage + 1) % pageNames.size]
                report.scrollTo(0, 0)
            }
        }
        // Equal weights, no label in this row. Four WRAP_CONTENT buttons ate
        // 1056 of 1080 px, leaving the weighted mode label ~24 px wide -- its
        // text then wrapped into a ~10-line column that inflated the row to
        // 579 px tall and hid the label entirely. The mode number is already
        // in the header, so the row is just controls now.
        for (b in arrayOf(prev, next, playBtn, toggle))
            ctl.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(ctl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)

        view.onNodePicked = { n, ux, uy ->
            nodeInfo.text = "node ${n.id} at (${"%.1f".format(Locale.ROOT, n.x)}, " +
                    "${"%.1f".format(Locale.ROOT, n.y)}) m   " +
                    "φUx ${"%+.4f".format(Locale.ROOT, ux)}   φUy ${
                        "%+.4f".format(
                            Locale.ROOT,
                            uy
                        )
                    }"
        }

        // Takeoff FIRST and in its own try: it needs no eigenvalues, so the
        // bill stays readable on a device where the native solver failed to
        // load -- which is exactly when someone is staring at the phone
        // wondering whether anything in the app still works.
        // Everything that does not need eigenvalues is built first, each in
        // its own try, so those pages stay readable on a device where the
        // native solver failed to load -- which is exactly when someone is
        // staring at the phone wondering whether anything in the app works.
        pageText[page("Bill")] = safely("TAKEOFF") { runTakeoff() }
        pageText[page("RAB")] = safely("RAB") { runRab() }
        pageText[page("Site")] = safely("CHECKLIST") { runChecklist() }
        try {
            pageText[page("Report")] = runAnalysis()
            reportText.text = pageText[page("Report")]
        } catch (e: Throwable) {
            pageText[page("Report")] = "FAILED: ${e::class.java.simpleName}\n${e.message}\n\n" +
                    e.stackTrace.take(8).joinToString("\n")
            reportText.text = pageText[page("Report")]
            reportPage = page("Report")
            report.visibility = View.VISIBLE
            view.visibility = View.GONE
            toggle.text = pageNames[(reportPage + 1) % pageNames.size]
            header.text = "Analysis failed — see report"
        }
        // The capacity assessment needs modes, so it runs last and only if the
        // solver came up.
        pageText[page("Checks")] = safely("ASSESSMENT") { runAssessment() }
    }

    private fun step(d: Int) {
        if (modes.isEmpty()) return
        current = (current + d + modes.size) % modes.size
        showMode()
    }

    private fun showMode() {
        val m = modes[current]
        view.setMode(m.phi)
        val r = rows[current]
        header.text = "Mode ${current + 1} of ${modes.size}   T = ${
            "%.4f".format(
                Locale.ROOT,
                m.periodSec
            )
        } s   " +
                "f = ${"%.1f".format(Locale.ROOT, m.frequencyHz)} Hz\n" +
                "MPMR  X ${"%.3f".format(Locale.ROOT, r.mpmrX)}   Y ${
                    "%.3f".format(
                        Locale.ROOT,
                        r.mpmrY
                    )
                }"
    }

    /** Site-inspection checklist, generated from the same variant the model
     * was built from. Narrow width so it is usable standing on a site. */
    private fun runChecklist(): String =
        SiteChecklist.format(SiteChecklist.generate(grid), width = NARROW_COLS)

    /** Member-by-member demand/capacity, using the NATIVE solver -- the same
     * modes the rest of the app reports. */
    private fun runAssessment(): String = buildString {
        if (!EigensolverBridge.ensureLoaded()) {
            appendLine("Native solver not loaded - no modes, so no capacity check.")
            appendLine("The Bill, RAB and Site pages do not need it and are still valid.")
            return@buildString
        }
        val r = Assessment.run(grid) { k, m -> EigensolverBridge.solve(k, m).modes }
        appendLine("VARIANT: ${r.variant.name}")
        appendLine("T1 = ${"%.4f".format(Locale.ROOT, r.periodSec)} s")
        appendLine(
            "V_ELF ${"%.1f".format(Locale.ROOT, r.baseShearElfKN)} kN   " +
                    "V_CQC ${"%.1f".format(Locale.ROOT, r.baseShearCqcKN)} kN"
        )
        appendLine(
            "worst D/C = ${"%.3f".format(Locale.ROOT, r.worstRatio)} " +
                    "(${r.worstMember.governing.label})"
        )
        appendLine("on ${r.worstMember.member}")
        appendLine("all members pass: ${r.allPass}")
        appendLine()
        appendLine("GOVERNING MEMBER, every check")
        for (c in r.worstMember.checks) appendLine(
            "  ${c.label.padEnd(14)} ${"%8.3f".format(Locale.ROOT, c.demand)} /" +
                    "${"%8.3f".format(Locale.ROOT, c.capacity)} ${c.unit.padEnd(5)}" +
                    " ${"%.3f".format(Locale.ROOT, c.ratio)} ${if (c.passes) "ok" else "FAIL"}"
        )
        val failing = r.memberChecks.flatMap { m -> m.checks.filter { !it.passes }.map { m to it } }
        appendLine()
        appendLine(
            "FAILING CHECKS: ${failing.size} of " +
                "${r.memberChecks.sumOf { it.checks.size }}"
        )
        for ((m, c) in failing.take(12)) appendLine(
            "  ${m.member} - ${c.label} ${"%.3f".format(Locale.ROOT, c.ratio)}"
        )
        appendLine()
        appendLine("NOT CHECKED - read this before trusting the above")
        for (nte in r.notes) appendLine("  - $nte")
    }

    /** Bill of quantities, in the narrow style so the fixed-width columns fit
     * a phone instead of wrapping into confetti. */
    private fun runTakeoff(): String {
        val (nodes, fullDof) = GeometryBuilder.build(grid)
        val asm = Assembler.assemble(grid, nodes, fullDof)
        Assembler.assignMass(asm, grid, nodes)
        val input = TakeoffInput(grid)
        return QuantityTakeoff.format(
            QuantityTakeoff.compute(input),
            QuantityTakeoff.reconcileLumpedMass(input, asm),
            QuantityTakeoff.ReportStyle.NARROW,
        )
    }

    /** Contractor RAB against the takeoff and AHSP at the owner's prices.
     *
     * Inputs are plain files in the app's external files dir, written on a PC
     * and pushed with adb -- no rebuild for a new quote or a revised RAB. The
     * blank templates are rewritten there on every visit, so they always
     * match the current takeoff and the current resource list. The contractor's
     * side is either rab.txt or, preferred, the BoQ workbook they filled in,
     * pushed back as rab.xlsx. */
    private fun runRab(): String {
        val input = TakeoffInput(grid)
        val takeoff = QuantityTakeoff.compute(input)
        val dir = getExternalFilesDir(null)
            ?: return "RAB: external storage is unavailable, so no files can be read."
        File(dir, "prices.template.txt").writeText(PriceFile.template())
        File(dir, "rab.template.txt").writeText(RabComparison.template(takeoff))
        File(dir, "boq-template.xlsx").writeBytes(
            BoqWorkbook.write(takeoff, withQuantities = false, date = java.time.LocalDate.now().toString())
        )
        fun file(name: String) = File(dir, name).takeIf { it.isFile }
        val rab = file("rab.xlsx")?.let { RabInput.Workbook(it.readBytes()) }
            ?: file("rab.txt")?.let { RabInput.Text(it.readText()) }
        val d = dir.absolutePath
        val how = "Templates are in $d. adb pull boq-template.xlsx and send it to the contractor; " +
                "push their filled copy back as rab.xlsx (or a rab.txt), plus your prices.txt " +
                "from prices.template.txt, then reopen this page."
        return RabComparison.report(
            input, file("prices.txt")?.readText(), rab, QuantityTakeoff.ReportStyle.NARROW, how
        )
    }

    private companion object {
        /** Monospace columns that fit a phone at 12sp. If text wraps on a
         * real device, this is the one number to change. */
        const val NARROW_COLS = 44
    }

    private fun runAnalysis(): String = buildString {
        val (nodes, fullDof) = GeometryBuilder.build(grid)
        val asm = Assembler.assemble(grid, nodes, fullDof)
        Assembler.assignMass(asm, grid, nodes)
        val red = GuyanReduction.reduce(asm.K, asm.M, DOF_PER_NODE, MASTER_DOF_PER_NODE)
        appendLine("Model: ${nodes.size} nodes, $fullDof DOF -> ${red.kReduced.size} reduced")

        // Geometry is drawable whether or not the native solver is available.
        view.setModel(nodes, asm.elements)

        if (!EigensolverBridge.ensureLoaded()) {
            appendLine("\nNATIVE LIBRARY NOT LOADED")
            appendLine("libmpmreigensolver.so missing or wrong ABI.")
            appendLine("Check abiFilters includes arm64-v8a. See CLAUDE.md.")
            header.text = "Native solver unavailable — undeformed geometry only"
            return@buildString
        }
        appendLine("Native solver: loaded")

        val t0 = System.nanoTime()
        val result = EigensolverBridge.solve(red.kReduced, red.mReduced)
        val ms = (System.nanoTime() - t0) / 1e6
        appendLine("Solve: ${"%.1f".format(Locale.ROOT, ms)} ms for ${result.modes.size} modes\n")

        // Residual check on device -- never trust modes that fail this.
        var worst = 0.0
        for (m in result.modes) {
            val kp = matVec(red.kReduced, m.phi)
            var rn = 0.0;
            var kn = 0.0
            for (j in kp.indices) {
                val r = kp[j] - m.omega2 * red.mReduced[j] * m.phi[j]
                rn += r * r; kn += kp[j] * kp[j]
            }
            worst = maxOf(worst, kotlin.math.sqrt(rn) / kotlin.math.sqrt(kn))
        }
        appendLine(
            "Worst residual: ${
                "%.2e".format(
                    Locale.ROOT,
                    worst
                )
            } ${if (worst < 1e-8) "OK" else "SUSPECT"}"
        )

        val t1 = result.modes[0].periodSec
        appendLine(
            "T1 = ${
                "%.4f".format(
                    Locale.ROOT,
                    t1
                )
            } s (empirical Ta = ${
                "%.4f".format(
                    Locale.ROOT,
                    SeismicParams.empiricalPeriod(3.5)
                )
            } s)"
        )

        rows = MPMR.compute(result, red.mReduced)
        var sx = 0.0;
        var sy = 0.0
        for (r in rows) {
            sx += r.mpmrX; sy += r.mpmrY
        }
        appendLine(
            "MPMR total: X=${"%.6f".format(Locale.ROOT, sx)} Y=${
                "%.6f".format(
                    Locale.ROOT,
                    sy
                )
            } (expect 1.0)"
        )
        val (nx, ny) = MPMR.modesNeeded(rows)
        appendLine("Modes to 90%: X=$nx Y=$ny\n")

        val ux = CQC.combinedDisplacements(result, red.mReduced, MPMR.influenceX(red.kReduced.size))
        var maxU = 0.0; for (v in ux) maxU = maxOf(maxU, kotlin.math.abs(v))
        val dr = StoryDriftCheck.check(maxU, 3.5)
        appendLine(
            "Drift: ${"%.5f".format(Locale.ROOT, dr.amplifiedDrift)} m / ${
                "%.4f".format(
                    Locale.ROOT,
                    dr.allowable
                )
            } m -> ${if (dr.passes) "PASS" else "FAIL"}"
        )
        val ti = TorsionalIrregularityCheck.checkXDirection(nodes, ux, 3.5)
        appendLine(
            "Torsion X ratio: ${
                "%.4f".format(
                    Locale.ROOT,
                    ti.ratio
                )
            } -> ${if (ti.irregular) "IRREGULAR" else "regular"}"
        )

        var totMg = 0.0
        for (i in red.mReduced.indices step MASTER_DOF_PER_NODE) totMg += red.mReduced[i]
        val vElf = SeismicParams.csElf(SeismicParams.empiricalPeriod(3.5)) * totMg * SeismicParams.G
        val vCqc = CQC.combinedBaseShear(result, red.mReduced, MPMR.influenceX(red.kReduced.size))
        appendLine(
            "V_CQC = ${
                "%.1f".format(
                    Locale.ROOT,
                    vCqc
                )
            } kN   V_ELF = ${"%.1f".format(Locale.ROOT, vElf)} kN"
        )
        appendLine(
            "12.9.4 scale factor: ${
                "%.4f".format(
                    Locale.ROOT,
                    CQC.baseShearScaleFactor(vCqc, vElf)
                )
            }"
        )

        modes = result.modes
        current = 0
        showMode()
    }
}