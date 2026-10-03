package griyasakha

import java.util.Locale

/** Bill-of-quantities takeoff derived from the SAME geometry the structural
 * model uses.
 *
 * WHY THIS FILE EXISTS, and why it does not just walk ModelAssembly.elements:
 *
 * The purpose here is to give the OWNER an independent quantity figure to set
 * against a contractor's RAB. Independence is the whole value. If this file
 * derived its quantities by traversing asm.elements it would inherit every
 * idealisation -- and every bug -- baked into the analysis model, and the
 * two would agree for the wrong reason.
 *
 * So this file re-derives everything from `Grid` plus the section/material
 * constants, and `reconcileLumpedMass()` then compares the result against the
 * assembled mass matrix. A DISAGREEMENT IS THE POINT: it means the physical
 * building and the analysis model have diverged, and one of them is wrong.
 * (It found one immediately -- see BEAM_WALLEDGE in the reconciliation note.)
 *
 * UNITS. Lengths m, areas m^2, volumes m^3, masses kg. Note that is kg, not
 * the Mg the stiffness/mass pipeline works in: a takeoff is priced against
 * steel in kg and concrete in m^3, so converting here would only invite the
 * same class of mistake ModelAssembly.KG_TO_MG exists to prevent. The one
 * place the two systems meet is reconcileLumpedMass(), which converts once,
 * explicitly, at the comparison.
 *
 * WHAT THIS IS NOT. This covers structural concrete, reinforcement, masonry
 * and the finishes that fall straight out of masonry area. It is NOT a
 * complete RAB -- see TakeoffResult.excluded, which is printed with the
 * report precisely so a partial takeoff is never mistaken for a total.
 */

enum class Uom(val label: String) { M3("m3"), M2("m2"), M("m'"), KG("kg") }

/** One line of the bill.
 *
 * `gross` is the centreline/model measure; `deduction` is what a quantity
 * surveyor removes for overlaps (beam-column joints, columns embedded in
 * walls, openings). Both are carried rather than only the net because the
 * gross-vs-net argument is exactly where an RAB dispute lands, and the owner
 * needs to be able to show which convention produced which number.
 *
 * `basis` carries the actual arithmetic, not a description of it. A line the
 * owner cannot re-derive on paper is a line they cannot defend. */
data class QtyLine(
    val code: String,
    val description: String,
    val uom: Uom,
    val gross: Double,
    val basis: String,
    val deduction: Double = 0.0,
    val deductionBasis: String = "",
) {
    val net: Double get() = gross - deduction
}

/** A door or window. Not in the structural model (the infill is idealised as
 * a pair of diagonal struts, which carry no opening information at all), so
 * these MUST be entered from the architectural drawing. Leaving the list
 * empty does not mean "no openings", it means "not yet counted" -- and the
 * masonry, plaster and paint lines will be overstated by 15-25% until it is
 * filled in. TakeoffResult.excluded says so out loud. */
data class Opening(
    val description: String,
    val widthM: Double,
    val heightM: Double,
    val count: Int,
    val inExteriorWall: Boolean,
) {
    val areaM2: Double get() = widthM * heightM * count
}

/** Reinforcement as DRAWN. Every field here is something the owner reads off
 * the structural drawing and can later count on site with a tape measure --
 * which is the point: these are the numbers that get quietly reduced during
 * construction, and they are all observable before the pour.
 *
 * Defaults are the SNI 8140 / PUPR "rumah tahan gempa" practical minimums for
 * a simple single-storey house. They are a PLACEHOLDER so the file runs, not
 * a recommendation -- replace them with what the drawing actually says. */
data class BarSpec(val diameterMm: Double, val count: Int)

data class TieSpec(val diameterMm: Double, val spacingMm: Double)

data class RcSchedule(
    val columnMain: BarSpec = BarSpec(12.0, 4),
    val columnTies: TieSpec = TieSpec(8.0, 150.0),
    val beamTop: BarSpec = BarSpec(12.0, 2),
    val beamBottom: BarSpec = BarSpec(12.0, 2),
    val beamTies: TieSpec = TieSpec(8.0, 150.0),
    val wallVertical: TieSpec = TieSpec(10.0, 200.0),
    val wallHorizontal: TieSpec = TieSpec(10.0, 200.0),
    val wallLayers: Int = 2,
    val sloofMain: BarSpec = BarSpec(12.0, 4),
    val sloofTies: TieSpec = TieSpec(8.0, 150.0),
    val sloofWidthM: Double = 0.150,
    val sloofDepthM: Double = 0.200,
    /** Laps, hooks, bends and cutting waste, applied once to the total steel
     * mass. 1.10 is the usual allowance; contractors often claim 1.15-1.20.
     * Kept as a single explicit multiplier rather than being smeared through
     * every bar length, so the owner can argue about one number. */
    val lapAndWastageFactor: Double = 1.10,
    val coverM: Double = 0.040,
)

data class TakeoffInput(
    val grid: Grid,
    val schedule: RcSchedule = RcSchedule(),
    val openings: List<Opening> = emptyList(),
    /** Must match the literal in Assembler.assignMass(). Duplicated rather
     * than shared because extracting it would edit the analysis path, and
     * reconcileLumpedMass() fails loudly if the two ever drift apart. */
    val roofDeadLoadKgM2: Double = 15.0,
)

data class TakeoffResult(
    val lines: List<QtyLine>,
    val notes: List<String>,
    val excluded: List<String>,
) {
    fun line(code: String): QtyLine =
        lines.firstOrNull { it.code == code } ?: error("no takeoff line with code $code")

    fun totalFor(uom: Uom): Double = lines.filter { it.uom == uom }.sumOf { it.net }
}

object QuantityTakeoff {

    /** Steel unit mass, kg per metre, for a plain round bar of diameter d mm:
     * 7850 * (pi/4) * (d/1000)^2 = 0.0061654 * d^2. Matches the standard
     * Indonesian bar table (D10 = 0.617, D12 = 0.888 kg/m). */
    fun barKgPerM(diameterMm: Double): Double = 0.0061654 * diameterMm * diameterMm

    private const val BEAM_DEPTH = 0.300
    private const val BEAM_WIDTH = 0.230   // SEC_BEAM area 0.069 / depth 0.300
    private const val COLUMN_DIM = 0.230   // SEC_COLUMN area 0.0529 = 0.23^2
    private const val T_INFILL_EXTERIOR = 0.150
    private const val T_INFILL_INTERIOR = 0.100

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    /** Total length of a tie/stirrup of given bar diameter around a b x h
     * section, including a simplified 135-degree seismic hook allowance of
     * max(6d, 75mm) at each end. */
    private fun tieLengthM(bM: Double, hM: Double, coverM: Double, diaMm: Double): Double {
        val dM = diaMm / 1000.0
        val perimeter = 2.0 * ((bM - 2 * coverM) + (hM - 2 * coverM))
        val hook = 2.0 * maxOf(6.0 * dM, 0.075)
        return perimeter + hook
    }

    private fun tieCount(memberLengthM: Double, spacingMm: Double): Int =
        Math.floor(memberLengthM / (spacingMm / 1000.0)).toInt() + 1

    fun compute(input: TakeoffInput): TakeoffResult {
        val g = input.grid
        val s = input.schedule
        val xs = g.xLines
        val ys = g.yLines
        val storyH = g.zRoof - g.zBase
        val planX = xs.last() - xs.first()
        val planY = ys.last() - ys.first()
        val hInfill = storyH - BEAM_DEPTH

        val lines = mutableListOf<QtyLine>()
        val notes = mutableListOf<String>()

        // ---------------- Concrete ----------------

        val nColumns = xs.size * ys.size
        val columnVol = nColumns * COLUMN_DIM * COLUMN_DIM * storyH
        // Columns on the x=0 and x=6 grid lines sit INSIDE the 200mm shear
        // wall. Counting both the full column and the full wall over that
        // height bills the same concrete twice.
        val wallLineColumns = 2 * ys.size
        val colWallOverlap =
            wallLineColumns * Sections.SHEARWALL_THICKNESS * COLUMN_DIM * storyH
        lines += QtyLine(
            code = "BET.01", description = "Concrete K250 - columns 230x230",
            uom = Uom.M3, gross = columnVol,
            basis = "$nColumns col x ${f(COLUMN_DIM)}x${f(COLUMN_DIM)}x${f(storyH)}m",
            deduction = colWallOverlap,
            deductionBasis = "$wallLineColumns col on wall lines embedded in " +
                    "${f(Sections.SHEARWALL_THICKNESS)}m wall: " +
                    "x ${f(Sections.SHEARWALL_THICKNESS)}x${f(COLUMN_DIM)}x${f(storyH)}m",
        )

        // Roof beams: along X on every Y line, and along Y on every X line.
        // This is the PHYSICAL count. The analysis model additionally carries
        // BEAM_WALLEDGE on the x=0/x=6 lines, duplicating beams the BEAM_Y
        // loop already created -- see reconcileLumpedMass().
        val beamXLen = ys.size * planX
        val beamYLen = xs.size * planY
        val beamLen = beamXLen + beamYLen
        val beamVol = beamLen * BEAM_WIDTH * BEAM_DEPTH
        // Centreline measure runs each beam into the column at both ends.
        val beamEnds = ys.size * (xs.size - 1) * 2 + xs.size * (ys.size - 1) * 2
        val beamJointDed = beamEnds * (COLUMN_DIM / 2.0) * BEAM_WIDTH * BEAM_DEPTH
        // On the two wall lines the roof beam also sits within the wall, which
        // is already billed full height to roof level.
        val beamWallDed =
            2 * planY * Sections.SHEARWALL_THICKNESS * BEAM_DEPTH
        lines += QtyLine(
            code = "BET.02", description = "Concrete K250 - roof beams 230x300",
            uom = Uom.M3, gross = beamVol,
            basis = "${f(beamLen)}m (X ${f(beamXLen)} + Y ${f(beamYLen)}) x " +
                    "${f(BEAM_WIDTH)}x${f(BEAM_DEPTH)}m",
            deduction = beamJointDed + beamWallDed,
            deductionBasis = "$beamEnds beam-column joints x ${f(COLUMN_DIM / 2)}m " +
                    "(${f(beamJointDed)} m3) + ${f(2 * planY)}m over shear wall " +
                    "(${f(beamWallDed)} m3)",
        )

        val wallVol = 2 * Sections.SHEARWALL_THICKNESS * planY * storyH
        lines += QtyLine(
            code = "BET.03",
            description = "Concrete K250 - shear walls t=200 (x=${f(xs.first())}, x=${f(xs.last())})",
            uom = Uom.M3, gross = wallVol,
            basis = "2 walls x ${f(Sections.SHEARWALL_THICKNESS)}x${f(planY)}x${f(storyH)}m",
        )

        // Sloof / tie beam. NOT in the structural model at all -- the analysis
        // fixes the base, so this concrete is invisible to it. It is real, it
        // is in the contractor's RAB, and it is one of the commonest places to
        // skimp, so it is billed here from grid geometry.
        val sloofLen = beamLen
        val sloofVol = sloofLen * s.sloofWidthM * s.sloofDepthM
        lines += QtyLine(
            code = "BET.04",
            description = "Concrete K250 - sloof ${(s.sloofWidthM * 1000).toInt()}x${(s.sloofDepthM * 1000).toInt()}",
            uom = Uom.M3, gross = sloofVol,
            basis = "${f(sloofLen)}m x ${f(s.sloofWidthM)}x${f(s.sloofDepthM)}m " +
                    "(all grid lines at base level; NOT in the analysis model)",
        )

        // ---------------- Formwork ----------------

        val colForm = nColumns * 4 * COLUMN_DIM * storyH
        val colFormDed = wallLineColumns * 2 * COLUMN_DIM * storyH
        lines += QtyLine(
            code = "BEK.01", description = "Formwork - columns",
            uom = Uom.M2, gross = colForm,
            basis = "$nColumns col x 4 faces x ${f(COLUMN_DIM)}x${f(storyH)}m",
            deduction = colFormDed,
            deductionBasis = "$wallLineColumns col on wall lines: 2 faces buried in wall",
        )
        lines += QtyLine(
            code = "BEK.02", description = "Formwork - beams (2 sides + soffit)",
            uom = Uom.M2, gross = beamLen * (2 * BEAM_DEPTH + BEAM_WIDTH),
            basis = "${f(beamLen)}m x (2x${f(BEAM_DEPTH)} + ${f(BEAM_WIDTH)})m",
        )
        lines += QtyLine(
            code = "BEK.03", description = "Formwork - shear walls (both faces)",
            uom = Uom.M2, gross = 2 * 2 * planY * storyH,
            basis = "2 walls x 2 faces x ${f(planY)}x${f(storyH)}m",
        )
        lines += QtyLine(
            code = "BEK.04", description = "Formwork - sloof (2 sides)",
            uom = Uom.M2, gross = sloofLen * 2 * s.sloofDepthM,
            basis = "${f(sloofLen)}m x 2 sides x ${f(s.sloofDepthM)}m",
        )

        // ---------------- Reinforcement ----------------

        // Columns: main bars full storey height, ties over the clear height.
        val colMainM = nColumns * s.columnMain.count * storyH
        val colTieOne = tieLengthM(COLUMN_DIM, COLUMN_DIM, s.coverM, s.columnTies.diameterMm)
        val colTieN = tieCount(hInfill, s.columnTies.spacingMm)
        val colTieM = nColumns * colTieN * colTieOne
        val colSteel = colMainM * barKgPerM(s.columnMain.diameterMm) +
                colTieM * barKgPerM(s.columnTies.diameterMm)

        val beamMainM = beamLen * (s.beamTop.count + s.beamBottom.count)
        val beamTieOne = tieLengthM(BEAM_WIDTH, BEAM_DEPTH, s.coverM, s.beamTies.diameterMm)
        val beamTieN = tieCount(beamLen, s.beamTies.spacingMm)
        val beamSteel = beamMainM * barKgPerM(s.beamTop.diameterMm) +
                beamTieN * beamTieOne * barKgPerM(s.beamTies.diameterMm)

        val wallArea = 2 * planY * storyH
        val wallVertN = tieCount(planY, s.wallVertical.spacingMm) * 2
        val wallVertM = wallVertN * storyH * s.wallLayers
        val wallHorizN = tieCount(storyH, s.wallHorizontal.spacingMm) * 2
        val wallHorizM = wallHorizN * planY * s.wallLayers
        val wallSteel = wallVertM * barKgPerM(s.wallVertical.diameterMm) +
                wallHorizM * barKgPerM(s.wallHorizontal.diameterMm)

        val sloofMainM = sloofLen * s.sloofMain.count
        val sloofTieOne = tieLengthM(s.sloofWidthM, s.sloofDepthM, s.coverM, s.sloofTies.diameterMm)
        val sloofTieN = tieCount(sloofLen, s.sloofTies.spacingMm)
        val sloofSteel = sloofMainM * barKgPerM(s.sloofMain.diameterMm) +
                sloofTieN * sloofTieOne * barKgPerM(s.sloofTies.diameterMm)

        val steelRaw = colSteel + beamSteel + wallSteel + sloofSteel
        val steelTotal = steelRaw * s.lapAndWastageFactor
        lines += QtyLine(
            code = "BSI.01", description = "Reinforcement - all members (incl. lap/waste)",
            uom = Uom.KG, gross = steelTotal,
            basis = "col ${f(colSteel)} + beam ${f(beamSteel)} + wall ${f(wallSteel)} + " +
                    "sloof ${f(sloofSteel)} = ${f(steelRaw)} kg, x ${s.lapAndWastageFactor} lap/waste",
        )
        notes += "Steel intensity: ${
            f(steelTotal / (columnVol - colWallOverlap + beamVol - beamJointDed - beamWallDed + wallVol + sloofVol))
        } kg/m3 of concrete. 100-150 is typical for a simple house; " +
                "far below that means under-reinforced, far above means the schedule was misread."

        // ---------------- Masonry and finishes ----------------

        // Infill panels, re-derived from the grid. x=0 and x=6 are RC shear
        // walls, so the only masonry is: X-direction bays on every Y line,
        // plus Y-direction bays on the middle X line.
        var aacArea = 0.0
        var brickArea = 0.0
        for (y in ys) for (i in 0 until xs.size - 1) {
            val a = (xs[i + 1] - xs[i]) * hInfill
            if (y == ys.first() || y == ys.last()) aacArea += a else brickArea += a
        }
        val midX = xs[xs.size / 2]
        for (i in 0 until ys.size - 1) brickArea += (ys[i + 1] - ys[i]) * hInfill
        notes += "Infill bays: middle X line is x=${f(midX)}; x=${f(xs.first())} and " +
                "x=${f(xs.last())} are RC shear walls and carry no masonry."

        val openExt = input.openings.filter { it.inExteriorWall }.sumOf { it.areaM2 }
        val openInt = input.openings.filter { !it.inExteriorWall }.sumOf { it.areaM2 }
        lines += QtyLine(
            code = "PAS.01", description = "AAC block wall t=150 (exterior infill)",
            uom = Uom.M2, gross = aacArea,
            basis = "bays on y=${f(ys.first())} and y=${f(ys.last())}, clear height ${f(hInfill)}m",
            deduction = openExt, deductionBasis = "openings entered: ${f(openExt)} m2",
        )
        lines += QtyLine(
            code = "PAS.02", description = "Brick wall t=100 (interior infill)",
            uom = Uom.M2, gross = brickArea,
            basis = "interior X bays + Y bays at x=${f(midX)}, clear height ${f(hInfill)}m",
            deduction = openInt, deductionBasis = "openings entered: ${f(openInt)} m2",
        )

        val masonryNet = (aacArea - openExt) + (brickArea - openInt)
        val wallFaces = 2 * masonryNet + 2 * (2 * planY * storyH)
        lines += QtyLine(
            code = "PLS.01", description = "Plaster + skim coat (both faces, masonry + RC walls)",
            uom = Uom.M2, gross = wallFaces,
            basis = "2 x ${f(masonryNet)}m2 masonry + 2 x ${f(2 * planY * storyH)}m2 shear wall",
        )

        val roofArea = planX * planY
        lines += QtyLine(
            code = "ATP.01", description = "Roof plan area (for covering + structure)",
            uom = Uom.M2, gross = roofArea,
            basis = "${f(planX)} x ${f(planY)}m plan; " +
                    "model carries only ${f(input.roofDeadLoadKgM2)} kg/m2 of roof dead load",
        )

        val excluded = mutableListOf(
            "Foundation below sloof (footings, batu kali, excavation, lean concrete) - " +
                    "not derivable from the model, which fixes the base.",
            "Roof structure above the ring beam (rafters, purlins, tiles/sheet) - only its " +
                    "${f(input.roofDeadLoadKgM2)} kg/m2 dead load appears in the model.",
            "Floor slab, screed and finishes.",
            "Doors, windows and their frames (only their AREA is deducted from masonry).",
            "Electrical, plumbing, sanitary, painting, site overheads, profit and tax.",
        )
        if (input.openings.isEmpty()) {
            excluded += "NO OPENINGS ENTERED. Masonry, plaster and paint above are GROSS of " +
                    "doors and windows and will overstate by roughly 15-25% until " +
                    "TakeoffInput.openings is filled in from the architectural drawing."
        }

        return TakeoffResult(lines, notes, excluded)
    }

    // ---------------- Reconciliation against the analysis model ----------------

    data class MassReconciliation(
        val takeoffLumpedKg: Double,
        val modelLumpedKg: Double,
        val components: List<Pair<String, Double>>,
    ) {
        val deltaKg: Double get() = modelLumpedKg - takeoffLumpedKg
        val relative: Double get() = deltaKg / takeoffLumpedKg
        val agrees: Boolean get() = kotlin.math.abs(relative) < 1e-9
    }

    /** Rebuilds what the analysis model's lumped mass SHOULD be from takeoff
     * quantities, following ModelAssembly's own conventions:
     *   - vertical members (columns, wall piers): half the mass to the roof
     *     node, the other half to a fixed base node that has no DOF
     *   - roof beams: full mass, both ends are roof nodes
     *   - infill panels: half the panel at roof level
     *   - roof dead load: full, by tributary area
     *
     * Any gap between this and the assembled M means the physical building and
     * the analysis model disagree about what exists. That is a bug in one of
     * them, and this is the check that makes it visible. */
    fun reconcileLumpedMass(input: TakeoffInput, asm: ModelAssembly): MassReconciliation {
        val g = input.grid
        val xs = g.xLines
        val ys = g.yLines
        val storyH = g.zRoof - g.zBase
        val planX = xs.last() - xs.first()
        val planY = ys.last() - ys.first()
        val hInfill = storyH - BEAM_DEPTH
        val rho = Materials.CONCRETE_K250.density

        val columns = xs.size * ys.size * Sections.COLUMN.area * storyH * rho / 2.0
        val walls = 2 * Sections.SHEARWALL_THICKNESS * planY * storyH * rho / 2.0
        val beamLen = ys.size * planX + xs.size * planY
        val beams = beamLen * Sections.BEAM.area * rho

        var panels = 0.0
        for (y in ys) for (i in 0 until xs.size - 1) {
            val ext = y == ys.first() || y == ys.last()
            val mat = if (ext) Materials.AAC_MASONRY else Materials.BRICK_MASONRY
            val t = if (ext) T_INFILL_EXTERIOR else T_INFILL_INTERIOR
            panels += mat.density * t * (xs[i + 1] - xs[i]) * hInfill / 2.0
        }
        for (i in 0 until ys.size - 1) {
            panels += Materials.BRICK_MASONRY.density * T_INFILL_INTERIOR *
                    (ys[i + 1] - ys[i]) * hInfill / 2.0
        }

        val roof = input.roofDeadLoadKgM2 * planX * planY

        val takeoff = columns + walls + beams + panels + roof
        var modelMg = 0.0
        for (i in asm.M.indices step DOF_PER_NODE) modelMg += asm.M[i]
        val modelKg = modelMg / ModelAssembly.KG_TO_MG

        return MassReconciliation(
            takeoffLumpedKg = takeoff,
            modelLumpedKg = modelKg,
            components = listOf(
                "columns (half)" to columns,
                "shear walls (half)" to walls,
                "roof beams (full)" to beams,
                "infill panels (half)" to panels,
                "roof dead load" to roof,
            ),
        )
    }

    // ---------------- Reporting ----------------

    /** WIDE is the host/terminal table. NARROW is for the phone, where a
     * 96-column fixed table wraps into unreadable confetti: it stacks each
     * line vertically and word-wraps the basis prose with a hanging indent. */
    enum class ReportStyle(val width: Int) { WIDE(96), NARROW(44) }

    /** Headline totals -- what an owner actually reads before anything else.
     * Concrete and formwork are NET (after overlap deductions) because that is
     * the figure a contractor's RAB should be measuring. */
    fun summary(result: TakeoffResult): List<Pair<String, String>> {
        fun n(v: Double, u: String) = String.format(Locale.ROOT, "%.2f %s", v, u)
        val concrete = result.lines.filter { it.code.startsWith("BET") }.sumOf { it.net }
        val formwork = result.lines.filter { it.code.startsWith("BEK") }.sumOf { it.net }
        val steel = result.lines.filter { it.code.startsWith("BSI") }.sumOf { it.net }
        val masonry = result.lines.filter { it.code.startsWith("PAS") }.sumOf { it.net }
        return listOf(
            "Concrete K250 (net)" to n(concrete, "m3"),
            "Reinforcement" to n(steel, "kg"),
            "Steel intensity" to n(steel / concrete, "kg/m3"),
            "Formwork (net)" to n(formwork, "m2"),
            "Masonry walls" to n(masonry, "m2"),
            "Plaster (2 faces)" to n(result.line("PLS.01").net, "m2"),
        )
    }

    /** Word-wrap `text` to `width`, indenting the first line with
     * `firstIndent` and every continuation with `contIndent`.
     *
     * The indents are parameters rather than part of `text` because splitting
     * an already-indented string on whitespace silently eats the indent: the
     * leading spaces become empty tokens, each one looks like "line is still
     * empty", and the first line comes back flush left. */
    internal fun wrap(
        text: String,
        width: Int,
        firstIndent: String = "",
        contIndent: String = "",
    ): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var line = StringBuilder(firstIndent)
        var empty = true
        for (word in words) {
            when {
                empty -> {
                    line.append(word); empty = false
                }

                line.length + 1 + word.length <= width -> line.append(' ').append(word)
                else -> {
                    out += line.toString(); line = StringBuilder(contIndent).append(word)
                }
            }
        }
        out += line.toString()
        return out
    }

    /** Locale.ROOT throughout: this report is read next to an RAB and the
     * decimal separator must not depend on the device's locale. (The default
     * JVM locale here already prints "41,19" rather than "41.19".) */
    fun format(
        result: TakeoffResult,
        rec: MassReconciliation? = null,
        style: ReportStyle = ReportStyle.WIDE,
    ): String {
        val sb = StringBuilder()
        val w = style.width
        val narrow = style == ReportStyle.NARROW
        fun rule(c: String = "=") = sb.appendLine(c.repeat(w))

        rule()
        sb.appendLine("QUANTITY TAKEOFF")
        if (!narrow) sb.appendLine(
            "derived from model geometry, independent of the element list"
        )
        rule()

        sb.appendLine("SUMMARY")
        for ((k, v) in summary(result)) sb.appendLine(
            String.format(Locale.ROOT, "  %-26s %s", k, v)
        )
        sb.appendLine()
        rule("-")

        if (narrow) {
            for (l in result.lines) {
                for (x in wrap("${l.code}  ${l.description}", w, "", "        ")) sb.appendLine(x)
                sb.appendLine(
                    String.format(
                        Locale.ROOT,
                        "    gross %12.3f %s",
                        l.gross,
                        l.uom.label
                    )
                )
                if (l.deduction != 0.0) sb.appendLine(
                    String.format(Locale.ROOT, "    net   %12.3f %s", l.net, l.uom.label)
                )
                for (x in wrap("basis: ${l.basis}", w, "    ", "      ")) sb.appendLine(x)
                if (l.deduction != 0.0)
                    for (x in wrap(
                        "less:  ${l.deductionBasis}",
                        w,
                        "    ",
                        "      "
                    )) sb.appendLine(x)
                sb.appendLine()
            }
        } else {
            sb.appendLine(
                String.format(
                    Locale.ROOT, "%-8s %-46s %-4s %12s %12s",
                    "CODE", "DESCRIPTION", "UOM", "GROSS", "NET"
                )
            )
            rule("-")
            for (l in result.lines) {
                sb.appendLine(
                    String.format(
                        Locale.ROOT, "%-8s %-46s %-4s %12.3f %12.3f",
                        l.code, l.description.take(46), l.uom.label, l.gross, l.net
                    )
                )
                sb.appendLine("         basis: ${l.basis}")
                if (l.deduction != 0.0) sb.appendLine("         less:  ${l.deductionBasis}")
            }
        }

        if (result.notes.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("NOTES")
            for (x in result.notes) for (y in wrap("- $x", w, "  ", "    ")) sb.appendLine(y)
        }

        if (rec != null) {
            sb.appendLine()
            sb.appendLine("RECONCILIATION vs the assembled mass matrix")
            for ((k, v) in rec.components) sb.appendLine(
                String.format(Locale.ROOT, "  %-22s %12.1f kg", k, v)
            )
            sb.appendLine(
                String.format(
                    Locale.ROOT,
                    "  %-22s %12.1f kg",
                    "takeoff total",
                    rec.takeoffLumpedKg
                )
            )
            sb.appendLine(
                String.format(
                    Locale.ROOT,
                    "  %-22s %12.1f kg",
                    "analysis model M",
                    rec.modelLumpedKg
                )
            )
            // Percentage on its own line: inline it overflows the narrow
            // (44-column) phone style by 6 characters.
            sb.appendLine(
                String.format(Locale.ROOT, "  %-22s %12.1f kg", "difference", rec.deltaKg)
            )
            sb.appendLine(
                String.format(
                    Locale.ROOT,
                    "    (%+.2f%% of the real building)",
                    rec.relative * 100.0
                )
            )
            sb.appendLine(
                "  ${
                    if (rec.agrees) "OK - model and takeoff describe the same building"
                    else "MISMATCH - INVESTIGATE"
                }"
            )
        }

        sb.appendLine()
        sb.appendLine("NOT INCLUDED (this is not a complete RAB)")
        for (x in result.excluded) for (y in wrap("- $x", w, "  ", "    ")) sb.appendLine(y)
        rule()
        return sb.toString()
    }

}

fun main() {
    val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
    val (nodes, fullDof) = GeometryBuilder.build(grid)
    val asm = Assembler.assemble(grid, nodes, fullDof)
    Assembler.assignMass(asm, grid, nodes)

    val input = TakeoffInput(grid)
    val result = QuantityTakeoff.compute(input)
    val rec = QuantityTakeoff.reconcileLumpedMass(input, asm)
    println(QuantityTakeoff.format(result, rec))
}