package griyasakha

/** Runs the whole pipeline for one DesignVariant and checks member strength.
 *
 * This is the file that finally answers a different question from the rest of
 * the project. Everything else computes DEMAND -- how hard the ground shakes,
 * how far the roof moves. This computes demand over CAPACITY, member by
 * member, which is the only form in which "is it strong enough" has an answer.
 *
 * READ THE EXCLUSIONS. The checks here are section-strength checks on columns
 * and beams. They do not cover the shear walls (in-plane wall design is a
 * different check with boundary-element requirements), the beam-column joints,
 * development and splice lengths, the infill-to-frame anchorage, or anything
 * below the sloof. Several of those govern real earthquake failures in exactly
 * this kind of house. A green report here is not a safe building; it is one
 * family of checks passing.
 */
object Assessment {

    /** Seismic load combination, SNI 1726 / ASCE 7 Section 2.3.6:
     *     U = (1.2 + 0.2 SDS) D + rho QE  (+ 0.5 L, zero here)
     * The 0.2*SDS*D term is the vertical component of the earthquake and is
     * NOT optional -- omitting it understates column axial demand by 12% at
     * this site's SDS = 0.617.
     *
     * rho is the redundancy factor. 1.0 is used here and is the value that
     * applies when the structure qualifies under ASCE 7 12.3.4.2; for a
     * Seismic Design Category D building that does not qualify it is 1.3, and
     * that is a 30% increase straight onto every seismic demand below. It is a
     * parameter rather than a constant so the difference can be seen, not a
     * claim that 1.0 is right for this house. */
    data class LoadCombination(val rho: Double = 1.0) {
        val gravityFactor: Double get() = 1.2 + 0.2 * SeismicParams.SDS
    }

    data class Result(
        val variant: DesignVariant,
        val periodSec: Double,
        val totalMassMg: Double,
        val baseShearElfKN: Double,
        val baseShearCqcKN: Double,
        val amplifiedDriftM: Double,
        val allowableDriftM: Double,
        val memberChecks: List<MemberCheck>,
        val notes: List<String>,
    ) {
        val worstRatio: Double get() = memberChecks.maxOf { it.governing.ratio }
        val worstMember: MemberCheck get() = memberChecks.maxBy { it.governing.ratio }
        val allPass: Boolean get() = memberChecks.all { it.passes }
    }

    /** The eigensolver is injected so this file stays free of JNI: the host
     * harness passes the JVM Jacobi reference, the app passes the native
     * bridge. Same pipeline either way. */
    fun run(
        grid: Grid,
        variant: DesignVariant = DesignVariant.AS_SPECIFIED,
        combo: LoadCombination = LoadCombination(),
        solver: (Mat, DoubleArray) -> List<Eigenpair>,
    ): Result {
        val (nodes, fullDof) = GeometryBuilder.build(grid)
        val asm = Assembler.assemble(grid, nodes, fullDof, variant)
        Assembler.assignMass(asm, grid, nodes)
        val red = GuyanReduction.reduce(asm.K, asm.M, DOF_PER_NODE, MASTER_DOF_PER_NODE)
        val modes = solver(red.kReduced, red.mReduced)
        val n = red.kReduced.size
        val result = EigensystemResult(modes, n)
        val storyH = grid.zRoof - grid.zBase

        var totMg = 0.0
        for (i in red.mReduced.indices step MASTER_DOF_PER_NODE) totMg += red.mReduced[i]
        val wKN = totMg * SeismicParams.G
        val ta = SeismicParams.empiricalPeriod(storyH)
        val vElf = SeismicParams.csElf(ta) * wKN

        val rX = MPMR.influenceX(n)
        val vCqc = CQC.combinedBaseShear(result, red.mReduced, rX)
        val ux = CQC.combinedDisplacements(result, red.mReduced, rX)
        val drift = StoryDriftCheck.check(maxAbs(ux), storyH)

        // Demands in each direction separately; a member is checked against
        // the worse of the two. Combining X and Y demands (the 100/30 rule)
        // would be more complete and is not done here -- flagged below.
        val demandX = ForceRecovery.combinedDemands(asm.elements, result, red, rX)
        val demandY = ForceRecovery.combinedDemands(asm.elements, result, red, MPMR.influenceY(n))

        val colSec = variant.columnRcSection()
        val beamSec = variant.beamRcSection()
        val checks = mutableListOf<MemberCheck>()

        asm.elements.forEachIndexed { i, el ->
            val dx = demandX[i]
            val dy = demandY[i]
            fun worst(f: (ElementDemand) -> Double) = maxOf(f(dx), f(dy))
            when {
                el.type == "COLUMN" -> {
                    val selfW = variant.concreteMaterial.density * variant.columnSection.area *
                            el.lengthM * SeismicParams.G / 1000.0
                    val grav = ForceRecovery.gravityAxialKN(asm, el.nodeB, selfW)
                    val pu = combo.gravityFactor * grav + combo.rho * worst { it.axialKN }
                    val mu = combo.rho * worst { it.momentMaxKNm }
                    val vu = combo.rho * worst { it.shearMaxKN }
                    checks += MemberCheck(
                        "COLUMN at (${fmt(el.nodeA.x)}, ${fmt(el.nodeA.y)})",
                        listOf(
                            CapacityCheck("axial", pu, colSec.designAxialMaxKN, "kN"),
                            CapacityCheck("flexure at Pu", mu, colSec.designMomentKNm(pu), "kN.m"),
                            CapacityCheck("shear", vu, colSec.designShearKN(pu), "kN"),
                            CapacityCheck(
                                "tie spacing", variant.schedule.columnTies.spacingMm,
                                colSec.tieSpacingLimitMm(variant.schedule.columnMain.diameterMm),
                                "mm"
                            ),
                        )
                    )
                }

                el.type.startsWith("BEAM") -> {
                    // Gravity moment is NOT a finite-element result: this model
                    // has no vertical load case at all (mass is lumped on Ux
                    // and Uy only). It is estimated as a continuous-beam
                    // wL^2/12 from self weight plus tributary roof dead load.
                    // Small here (~1.6 kN.m against ~19 available) but it is
                    // the term that governs a beam under gravity alone, so
                    // leaving it at zero would be unconservative in a way that
                    // silently flatters the result.
                    val tribW = 0.5 * (grid.xLines.last() - grid.xLines.first()) /
                            (grid.xLines.size - 1) * 2
                    val wSelf = variant.concreteMaterial.density * variant.beamSection.area *
                            SeismicParams.G / 1000.0
                    val wRoof = variant.roofDeadLoadKgM2 * tribW * SeismicParams.G / 1000.0
                    val w = wSelf + wRoof
                    val l = el.lengthM
                    val mGrav = w * l * l / 12.0
                    val vGrav = w * l / 2.0
                    val mu = combo.gravityFactor * mGrav + combo.rho * worst { it.momentMaxKNm }
                    val vu = combo.gravityFactor * vGrav + combo.rho * worst { it.shearMaxKN }
                    checks += MemberCheck(
                        "${el.type} ${fmt(l)}m at y=${fmt(el.nodeA.y)}",
                        listOf(
                            CapacityCheck("flexure", mu, beamSec.designMomentKNm(0.0), "kN.m"),
                            CapacityCheck("shear", vu, beamSec.designShearKN(0.0), "kN"),
                            CapacityCheck(
                                "tie spacing", variant.schedule.beamTies.spacingMm,
                                beamSec.tieSpacingLimitMm(variant.schedule.beamTop.diameterMm),
                                "mm"
                            ),
                        )
                    )
                }
            }
        }

        val notes = mutableListOf(
            "Shear walls carry most of the lateral load here (Ky >> Kx) and are NOT " +
                    "capacity-checked: in-plane wall design needs boundary-element and " +
                    "sliding-shear checks this file does not implement.",
            "Beam-column joints, development/splice lengths, infill-to-frame anchorage " +
                    "and everything below the sloof are not checked.",
            "X and Y demands are checked separately; the 100/30 directional combination " +
                    "of ASCE 7 12.5 is not applied, so biaxial columns are under-checked.",
            "Column gravity axial comes from the assembly's own tributary mass, not a " +
                    "gravity FE solve -- on the x=0 and x=6 lines the wall shares that " +
                    "load path, so those columns' axial demand is overstated.",
            "Redundancy factor rho = ${combo.rho}. If ASCE 7 12.3.4.2 is not satisfied " +
                    "it is 1.3, which raises every seismic demand above by 30%.",
        )

        return Result(
            variant, modes[0].periodSec, totMg, vElf, vCqc,
            drift.amplifiedDrift, drift.allowable, checks, notes
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    /** Side-by-side comparison of two variants.
     *
     * This is the output worth showing anyone: both runs share every modelling
     * assumption, so whatever is wrong with the model is wrong identically in
     * both and the DIFFERENCE is far more trustworthy than either absolute
     * number. "Ties at 250 instead of 150 costs 23% of the column shear
     * capacity" survives scrutiny that "your column is inadequate" does not. */
    data class Comparison(val a: Result, val b: Result) {
        fun rows(): List<Triple<String, String, String>> {
            fun f(v: Double, u: String) = String.format(java.util.Locale.ROOT, "%.3f %s", v, u)
            return listOf(
                Triple("fundamental period", f(a.periodSec, "s"), f(b.periodSec, "s")),
                Triple("total mass", f(a.totalMassMg, "Mg"), f(b.totalMassMg, "Mg")),
                Triple("V_ELF", f(a.baseShearElfKN, "kN"), f(b.baseShearElfKN, "kN")),
                Triple("V_CQC", f(a.baseShearCqcKN, "kN"), f(b.baseShearCqcKN, "kN")),
                Triple("amplified drift", f(a.amplifiedDriftM, "m"), f(b.amplifiedDriftM, "m")),
                Triple("worst D/C ratio", f(a.worstRatio, ""), f(b.worstRatio, "")),
                Triple("governing member", a.worstMember.member, b.worstMember.member),
                Triple(
                    "governing check",
                    a.worstMember.governing.label,
                    b.worstMember.governing.label
                ),
                Triple("all members pass", "${a.allPass}", "${b.allPass}"),
            )
        }
    }
}