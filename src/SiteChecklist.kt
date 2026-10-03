package griyasakha

import java.util.Locale

/** Generates a site-inspection checklist from the model.
 *
 * WHY A CHECKLIST IS THE POINT. The capacity checks in Assessment.kt say this
 * frame's force demands are negligible -- D/C around 0.001 to 0.07 -- because
 * the shear walls take almost all of the lateral load. The one check that gets
 * anywhere near governing is TIE SPACING, a detailing rule. That is not an
 * accident of this model; it is what a stiff, single-storey, masonry-infilled
 * box is like. Such houses do not fail because someone mis-sized a column.
 * They fail because the ties were too far apart, the cover was wrong, the
 * sloof was not continuous, or the concrete was not the grade on the invoice.
 *
 * Every one of those is observable before or during the pour, and every one is
 * a countable integer or a measurement in millimetres. That is a completely
 * different kind of claim from "my finite-element model disagrees with your
 * design", and it is one an owner can actually make and defend: it needs a
 * tape measure, not a licence.
 *
 * So this file turns the variant and the grid into a list of things to walk
 * around and measure, each with the value the drawing implies and, where it
 * can be computed, what getting it wrong costs.
 */
data class ChecklistItem(
    val stage: String,
    val id: String,
    val what: String,
    val expected: String,
    val tolerance: String,
    val count: String,
    val whyItMatters: String,
)

object SiteChecklist {

    private fun f(v: Double, dp: Int = 0) = String.format(Locale.ROOT, "%.${dp}f", v)

    fun generate(
        grid: Grid,
        variant: DesignVariant = DesignVariant.AS_SPECIFIED,
    ): List<ChecklistItem> {
        val s = variant.schedule
        val items = mutableListOf<ChecklistItem>()
        val nCols = grid.xLines.size * grid.yLines.size
        val nWallLines = 2
        val storyH = grid.zRoof - grid.zBase
        val colSec = variant.columnRcSection()
        val beamSec = variant.beamRcSection()
        val tieLimit = colSec.tieSpacingLimitMm(s.columnMain.diameterMm)

        // Indonesian grades are quoted as K<cube strength in kg/cm2>. Going
        // back from f'c: f_cube[MPa] = f'c / 0.83, and 1 MPa = 10.197 kg/cm2.
        // Rounded to the nearest standard grade step of 25 so the spec's
        // 20.75 MPa prints as K250 rather than an off-by-a-few K249.
        val nominalK = Math.round(variant.concreteFcMPa / 0.83 * 10.197 / 25.0) * 25.0

        // What a wider tie spacing actually costs, computed rather than
        // asserted: same section, ties moved out, shear capacity re-run.
        val shearAtSpec = colSec.designShearKN(0.0)
        val shearAtDouble =
            colSec.copy(tieSpacingMm = s.columnTies.spacingMm * 2).designShearKN(0.0)
        val shearLossPct = (1.0 - shearAtDouble / shearAtSpec) * 100.0

        // And what a grade shortfall costs: K250 -> K175 is the usual
        // substitution, roughly f'c 20.75 -> 14.5 MPa.
        //
        // Quote the AXIAL loss, not the flexural one. At zero axial load a
        // column's moment capacity is governed by the tension steel yielding,
        // so weak concrete barely moves it (about 8% here) -- quoting that
        // number alone would make a grade shortfall look harmless. Axial and
        // shear capacity are the concrete-governed ones, and they are where it
        // actually hurts.
        val weakGrade = ConcreteGrade(14.5)
        val weakCol = colSec.copy(concrete = weakGrade)
        val axialLossPct = (1.0 - weakCol.designAxialMaxKN / colSec.designAxialMaxKN) * 100.0
        val momentLossPct =
            (1.0 - weakCol.designMomentKNm(0.0) / colSec.designMomentKNm(0.0)) * 100.0
        val shearLossWeakPct = (1.0 - weakCol.designShearKN(0.0) / shearAtSpec) * 100.0

        val sloof = "BEFORE THE SLOOF (TIE BEAM) POUR"
        items += ChecklistItem(
            sloof, "S1",
            "Sloof runs CONTINUOUSLY around the full perimeter and under every " +
                    "internal wall line, with corners lapped, not butted",
            "closed loop on all ${grid.xLines.size + grid.yLines.size} grid lines",
            "no gaps",
            "walk the whole loop",
            "A discontinuous sloof is the single most common serious defect in " +
                    "this kind of house. It lets the footings move independently and " +
                    "the walls tear at the corners. It is invisible after the pour.",
        )
        items += ChecklistItem(
            sloof, "S2", "Sloof cross-section",
            "${f(s.sloofWidthM * 1000)} x ${f(s.sloofDepthM * 1000)} mm",
            "+/- 10 mm",
            "at 5 points around the loop",
            "Undersized sloof reduces the tie between footings.",
        )
        items += ChecklistItem(
            sloof, "S3", "Sloof main bars",
            "${s.sloofMain.count} x D${f(s.sloofMain.diameterMm)}",
            "exact count",
            "at 5 points, before the pour",
            "Count the bars in the photograph. This is the cheapest check there is.",
        )
        items += ChecklistItem(
            sloof, "S4", "Sloof stirrup spacing",
            "${f(s.sloofTies.spacingMm)} mm c/c, D${f(s.sloofTies.diameterMm)}",
            "+/- 20 mm",
            "measure 10 consecutive gaps, take the average",
            "Measure ten gaps and average: one gap proves nothing, ten is evidence.",
        )

        val col = "BEFORE EACH COLUMN POUR"
        items += ChecklistItem(
            col, "C1", "Column cross-section",
            "${f(variant.columnDimM * 1000)} x ${f(variant.columnDimM * 1000)} mm",
            "+/- 10 mm",
            "all $nCols columns",
            "Section area drives both axial and shear capacity.",
        )
        items += ChecklistItem(
            col, "C2", "Column main bars",
            "${s.columnMain.count} x D${f(s.columnMain.diameterMm)}",
            "exact count and diameter",
            "all $nCols columns, photographed before the formwork closes",
            "Bar diameter is checkable with a caliper and is sometimes substituted " +
                    "one size down. D${f(s.columnMain.diameterMm)} to " +
                    "D${f(s.columnMain.diameterMm - 2)} loses about " +
                    "${
                        f(
                            (1 - Math.pow(
                                (s.columnMain.diameterMm - 2) / s.columnMain.diameterMm,
                                2.0
                            )) * 100
                        )
                    }% " +
                    "of the steel area.",
        )
        items += ChecklistItem(
            col, "C3", "Column tie (sengkang) spacing",
            "${f(s.columnTies.spacingMm)} mm c/c, D${f(s.columnTies.diameterMm)} " +
                    "(SNI 2847 limit for this section: ${f(tieLimit)} mm)",
            "+/- 20 mm on a 10-gap average",
            "all $nCols columns",
            "THE CHECK THAT GOVERNS THIS BUILDING. Assessment.kt puts every force " +
                    "demand on this frame below D/C 0.10; tie spacing is the only " +
                    "check that approaches its limit. Doubling the spacing to " +
                    "${f(s.columnTies.spacingMm * 2)} mm costs ${f(shearLossPct, 1)}% of " +
                    "the column's shear capacity, and ties are the first thing " +
                    "stretched when steel is short.",
        )
        items += ChecklistItem(
            col, "C4", "Tie hooks close at 135 degrees, not 90",
            "135 deg with a 6d extension",
            "every tie visible",
            "spot-check 3 columns thoroughly",
            "A 90-degree hook springs open once the cover spalls and the tie stops " +
                    "confining anything. Costs nothing to do right, invisible afterwards.",
        )
        items += ChecklistItem(
            col, "C5", "Concrete cover to the ties",
            "${f(s.coverM * 1000)} mm",
            "+/- 5 mm",
            "check the spacer blocks on all $nCols columns",
            "Too little cover means corrosion; too much means the effective depth " +
                    "is lost. Spacer blocks missing entirely is the usual finding.",
        )

        val beam = "BEFORE THE RING BEAM / ROOF BEAM POUR"
        items += ChecklistItem(
            beam, "B1", "Beam cross-section",
            "${f(variant.beamWidthM * 1000)} x ${f(variant.beamDepthM * 1000)} mm",
            "+/- 10 mm",
            "every span",
            "Beam depth drives flexural capacity roughly as the square.",
        )
        items += ChecklistItem(
            beam, "B2", "Beam top and bottom bars",
            "${s.beamTop.count} x D${f(s.beamTop.diameterMm)} top, " +
                    "${s.beamBottom.count} x D${f(s.beamBottom.diameterMm)} bottom",
            "exact count",
            "every span, photographed",
            "Under seismic reversal both faces see tension, so TOP steel matters " +
                    "as much as bottom -- and top steel is the easier one to omit.",
        )
        items += ChecklistItem(
            beam, "B3", "Beam stirrup spacing",
            "${f(s.beamTies.spacingMm)} mm c/c (SNI limit for this section: " +
                    "${f(beamSec.tieSpacingLimitMm(s.beamTop.diameterMm))} mm)",
            "+/- 20 mm on a 10-gap average",
            "every span, tightest near the supports",
            "Stirrups must be CLOSER near the columns, not evenly spaced.",
        )
        items += ChecklistItem(
            beam, "B4", "Beam bars continue THROUGH the column and lap correctly",
            "lap >= 40 bar diameters = ${f(40 * s.beamTop.diameterMm)} mm",
            "measure the lap",
            "every beam-column joint",
            "A beam whose bars stop at the column face is not framed into it. " +
                    "This is not checked anywhere in the model -- joints are outside " +
                    "what Assessment.kt covers.",
        )

        val wall = "BEFORE THE SHEAR WALL POUR"
        items += ChecklistItem(
            wall, "W1", "Shear wall thickness",
            "${f(variant.wallThicknessM * 1000)} mm",
            "+/- 10 mm",
            "$nWallLines walls, full ${f(grid.yLines.last() - grid.yLines.first(), 1)} m length",
            "These two walls carry roughly 95% of the lateral load in this " +
                    "building. Everything else is secondary to them.",
        )
        items += ChecklistItem(
            wall, "W2", "Wall reinforcement mesh",
            "D${f(s.wallVertical.diameterMm)} @ ${f(s.wallVertical.spacingMm)} mm vertical, " +
                    "D${f(s.wallHorizontal.diameterMm)} @ ${f(s.wallHorizontal.spacingMm)} mm " +
                    "horizontal, ${s.wallLayers} layer(s)",
            "+/- 20 mm spacing",
            "both walls, both faces",
            "The model treats these walls as the primary lateral system but does " +
                    "NOT capacity-check them. Getting the mesh right is therefore " +
                    "outside what any number in this app verifies.",
        )
        items += ChecklistItem(
            wall, "W3", "Wall steel is anchored into the sloof and the ring beam",
            "full development at both ends",
            "no loose bar ends",
            "both walls",
            "A wall not tied top and bottom is a panel, not a shear wall.",
        )

        val conc = "AT EVERY CONCRETE POUR"
        items += ChecklistItem(
            conc, "K1", "Concrete grade on the delivery docket",
            "f'c = ${f(variant.concreteFcMPa, 2)} MPa (nominal grade K${f(nominalK)})",
            "matches the specification",
            "every truck or every site mix",
            "K250 to K175 is the classic substitution. It costs about " +
                    "${f(axialLossPct, 1)}% of column AXIAL capacity and " +
                    "${f(shearLossWeakPct, 1)}% of shear, and softens the frame as well. " +
                    "It costs only ${f(momentLossPct, 1)}% of moment capacity, because at " +
                    "low axial load that is governed by the steel yielding -- so do not " +
                    "let anyone reassure you with the flexural number alone.",
        )
        items += ChecklistItem(
            conc, "K2", "Test cubes/cylinders taken and sent to a lab",
            "3 specimens per pour, 28-day test",
            "lab certificate",
            "every structural pour",
            "This is the ONLY objective evidence of what was actually delivered. " +
                    "Without it, grade is the contractor's word. It is cheap.",
        )
        items += ChecklistItem(
            conc, "K3", "Vibration and no cold joints",
            "continuous pour per element, vibrated",
            "no honeycombing visible on stripping",
            "every element",
            "Honeycombing at a column base destroys exactly the region the ties " +
                    "were protecting.",
        )

        val mas = "MASONRY"
        items += ChecklistItem(
            mas, "M1", "Infill wall thickness by location",
            "${f(variant.infillExteriorTM * 1000)} mm exterior (AAC), " +
                    "${f(variant.infillInteriorTM * 1000)} mm interior (brick)",
            "+/- 10 mm",
            "each wall line",
            "Thickness changes both the mass and the strut stiffness in the model.",
        )
        items += ChecklistItem(
            mas, "M2", "Infill anchored to the columns",
            "anchor bars into every column, typically every 3rd course",
            "present on both sides of each panel",
            "every panel",
            "Unanchored infill falls out of plane in an earthquake and is a common " +
                    "cause of injury even when the frame survives. The model's " +
                    "diagonal-strut idealisation ASSUMES this anchorage exists and " +
                    "cannot detect its absence.",
        )
        return items
    }

    fun format(items: List<ChecklistItem>, width: Int = 96): String {
        val sb = StringBuilder()
        fun wrap(text: String, first: String, cont: String): List<String> {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var line = StringBuilder(first)
            var empty = true
            for (w in words) {
                when {
                    empty -> {
                        line.append(w); empty = false
                    }

                    line.length + 1 + w.length <= width -> line.append(' ').append(w)
                    else -> {
                        out += line.toString(); line = StringBuilder(cont).append(w)
                    }
                }
            }
            out += line.toString()
            return out
        }
        sb.appendLine("=".repeat(width))
        sb.appendLine("SITE INSPECTION CHECKLIST")
        for (x in wrap(
            "Generated from the structural model. Photograph every cage BEFORE the " +
                    "formwork closes -- after the pour none of this is checkable. Record what " +
                    "you measure next to what is expected; a discrepancy is a question, not " +
                    "an accusation.", "", ""
        )) sb.appendLine(x)
        sb.appendLine("=".repeat(width))
        var stage = ""
        for (it in items) {
            if (it.stage != stage) {
                stage = it.stage
                sb.appendLine()
                sb.appendLine("-- $stage ".padEnd(width, '-'))
            }
            sb.appendLine()
            // Labels go in the FIRST-LINE indent, not in the wrapped text:
            // the wrapper splits on \s+, so any padding inside the text is
            // collapsed and the columns stop lining up. All four labels are
            // padded to the same 15 characters as the continuation indent.
            for (x in wrap(it.what, "${it.id}. ", "    ")) sb.appendLine(x)
            for (x in wrap(it.expected, "    expected:  ", "               ")) sb.appendLine(x)
            for (x in wrap(it.tolerance, "    tolerance: ", "               ")) sb.appendLine(x)
            for (x in wrap(it.count, "    check:     ", "               ")) sb.appendLine(x)
            for (x in wrap(it.whyItMatters, "    why:       ", "               ")) sb.appendLine(x)
            sb.appendLine("    measured: ______________________   date: __________")
        }
        sb.appendLine()
        sb.appendLine("=".repeat(width))
        return sb.toString()
    }
}

fun main() {
    val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
    print(SiteChecklist.format(SiteChecklist.generate(grid)))
}