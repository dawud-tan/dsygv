package griyasakha

/** Assertion-based verification harness for the whole pipeline.
 *
 * Every other file's main() PRINTS its checks for a human to eyeball. This
 * one ASSERTS them and exits non-zero on any failure, so CI, hooks, and the
 * test-runner subagent have something concrete to fail on.
 *
 * Run it via ./verify.sh, or compile the sources under src/ and then:
 *       java -cp build/griya.jar griyasakha.VerificationKt
 */

private var failures = 0
private var checks = 0

private fun check(label: String, condition: Boolean, detail: String = "") {
    checks++
    if (condition) {
        println("  PASS  $label${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        failures++
        println("  FAIL  $label${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun near(a: Double, b: Double, relTol: Double = 1e-9): Boolean {
    val scale = maxOf(kotlin.math.abs(a), kotlin.math.abs(b), 1e-300)
    return kotlin.math.abs(a - b) <= relTol * scale
}

private fun cholesky(a: Mat): Mat? {
    val n = a.size
    val l = zeros(n)
    for (i in 0 until n) for (j in 0..i) {
        var s = a[i][j]
        for (k in 0 until j) s -= l[i][k] * l[j][k]
        if (i == j) {
            if (s <= 0.0) return null
            l[i][j] = kotlin.math.sqrt(s)
        } else l[i][j] = s / l[j][j]
    }
    return l
}

private fun cholSolve(a: Mat, b: DoubleArray): DoubleArray {
    val n = a.size
    val l = cholesky(a) ?: throw IllegalStateException("matrix not positive definite")
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i]
    }
    return x
}

/** Jacobi eigensolve of the reduced system, used ONLY for verification.
 * Production eigenvalues come from the verified C++ JNI solver.
 *
 * Public so the on-device instrumented test can cross-check the native
 * solver against it. Compare eigenVALUES only -- eigenvectors differ by
 * sign and by arbitrary rotation within degenerate subspaces. */
fun solveAllModesForTest(k: Mat, mDiag: DoubleArray): List<Eigenpair> {
    val n = k.size
    val sq = DoubleArray(n) { kotlin.math.sqrt(mDiag[it]) }
    val c = Array(n) { i -> DoubleArray(n) { j -> k[i][j] / (sq[i] * sq[j]) } }
    val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
    for (sweep in 0 until 100) {
        var off = 0.0
        for (i in 0 until n) for (j in i + 1 until n) off += c[i][j] * c[i][j]
        if (off < 1e-24) break
        for (p in 0 until n) for (q in p + 1 until n) {
            if (kotlin.math.abs(c[p][q]) < 1e-30) continue
            val theta = (c[q][q] - c[p][p]) / (2 * c[p][q])
            val t =
                (if (theta >= 0) 1.0 else -1.0) / (kotlin.math.abs(theta) + kotlin.math.sqrt(theta * theta + 1))
            val cc = 1.0 / kotlin.math.sqrt(t * t + 1)
            val ss = t * cc
            for (m in 0 until n) {
                val a = c[m][p]
                val b = c[m][q]; c[m][p] = cc * a - ss * b; c[m][q] = ss * a + cc * b
            }
            for (m in 0 until n) {
                val a = c[p][m]
                val b = c[q][m]; c[p][m] = cc * a - ss * b; c[q][m] = ss * a + cc * b
            }
            for (m in 0 until n) {
                val a = v[m][p]
                val b = v[m][q]; v[m][p] = cc * a - ss * b; v[m][q] = ss * a + cc * b
            }
        }
    }
    return (0 until n).map { j -> Eigenpair(c[j][j], DoubleArray(n) { i -> v[i][j] / sq[i] }) }
        .sortedBy { it.omega2 }
}

/** Runs every check and returns the failure count. Does NOT exit.
 *
 * Split out from main() deliberately: on Android this is called from an
 * instrumented test, where exitProcess would kill the test runner instead of
 * reporting failures. JVM callers wrap it in main() below. */
fun runAllChecks(): Int {
    failures = 0
    checks = 0
    val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
    val storyH = grid.zRoof - grid.zBase

    println("=== 1. Geometry ===")
    val (nodes, fullDof) = GeometryBuilder.build(grid)
    check("24 nodes generated", nodes.size == 24, "got ${nodes.size}")
    check("72 free DOF (12 roof nodes x 6)", fullDof == 72, "got $fullDof")
    check("12 fixed base nodes", nodes.count { it.isFixedBase } == 12)
    check("corner node lookup works", GeometryBuilder.findNode(nodes, 6.0, 10.3, 3.5).hasDof)

    println("\n=== 2. Frame element vs closed-form cantilever solutions ===")
    val mat = Materials.CONCRETE_K250
    val sec = Sections.COLUMN
    val kLocal = FrameElement.localStiffness(mat, sec, storyH)
    var asym = 0.0
    for (i in 0..11) for (j in 0..11) asym =
        maxOf(asym, kotlin.math.abs(kLocal[i][j] - kLocal[j][i]))
    check("local stiffness symmetric", asym < 1e-9, "max asym=$asym")

    val kNode2 = Array(6) { i -> DoubleArray(6) { j -> kLocal[6 + i][6 + j] } }
    fun solve6(a0: Mat, b0: DoubleArray): DoubleArray {
        val a = Array(6) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (p in 0 until 6) {
            val piv = a[p][p]
            for (j in 0 until 6) a[p][j] /= piv
            b[p] /= piv
            for (i in 0 until 6) if (i != p) {
                val f = a[i][p]; for (j in 0 until 6) a[i][j] -= f * a[p][j]; b[i] -= f * b[p]
            }
        }
        return b
    }

    val axial = solve6(kNode2, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0))[0]
    check("axial PL/EA", near(axial, storyH / (mat.e * sec.area)))
    val bendZ = solve6(kNode2, doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0))[1]
    check("bending PL^3/(3EIz)", near(bendZ, storyH * storyH * storyH / (3 * mat.e * sec.iz)))
    val bendY = solve6(kNode2, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0))[2]
    check("bending PL^3/(3EIy)", near(bendY, storyH * storyH * storyH / (3 * mat.e * sec.iy)))

    // Rigid-body modes. NOTE the kinematics: axial/torsion are "same value
    // both ends", but a rigid ROTATION about node 1 displaces node 2 by
    // theta*L transversely -- with OPPOSITE sign between the y and z
    // bending planes. Getting this wrong produces a false failure.
    run {
        val d0 = DoubleArray(12); d0[0] = 1.0; d0[6] = 1.0
        check("rigid axial translation", maxAbs(matVec(kLocal, d0)) < 1e-6)
        val d3 = DoubleArray(12); d3[3] = 1.0; d3[9] = 1.0
        check("rigid torsion", maxAbs(matVec(kLocal, d3)) < 1e-6)
        val dz = DoubleArray(12); dz[5] = 1.0; dz[11] = 1.0; dz[7] = storyH
        check("rigid rotation about z (v2=+theta*L)", maxAbs(matVec(kLocal, dz)) < 1e-6)
        val dy = DoubleArray(12); dy[4] = 1.0; dy[10] = 1.0; dy[8] = -storyH
        check("rigid rotation about y (w2=-theta*L)", maxAbs(matVec(kLocal, dy)) < 1e-6)
    }

    println("\n=== 3. Diagonal strut (Mainstone/FEMA-356) ===")
    val w = DiagonalStrut.effectiveWidth(
        Materials.AAC_MASONRY.e,
        0.150,
        mat.e,
        sec.iz,
        3.0,
        storyH - 0.300,
        storyH
    )
    val dDiag = kotlin.math.sqrt(3.0 * 3.0 + (storyH - 0.300) * (storyH - 0.300))
    check(
        "effective width w/d in plausible 0.05-0.25 range",
        w / dDiag in 0.05..0.25,
        "w/d=${"%.4f".format(w / dDiag)}"
    )
    val k6 = DiagonalStrut.globalStiffness6x6(
        Materials.AAC_MASONRY,
        w * 0.150,
        0.0,
        0.0,
        0.0,
        3.0,
        0.0,
        storyH - 0.300
    )
    var sAsym = 0.0
    for (i in 0..5) for (j in 0..5) sAsym = maxOf(sAsym, kotlin.math.abs(k6[i][j] - k6[j][i]))
    check("strut stiffness symmetric", sAsym < 1e-9)
    var rigid = 0.0
    for (dof in 0..2) {
        val d = DoubleArray(6); d[dof] = 1.0; d[dof + 3] = 1.0; rigid =
            maxOf(rigid, maxAbs(matVec(k6, d)))
    }
    check("strut rigid translation gives zero force", rigid < 1e-6)

    println("\n=== 4. Assembly integrity ===")
    val asm = Assembler.assemble(grid, nodes, fullDof)
    Assembler.assignMass(asm, grid, nodes)
    var gAsym = 0.0
    for (i in 0 until fullDof) for (j in 0 until fullDof) gAsym =
        maxOf(gAsym, kotlin.math.abs(asm.K[i][j] - asm.K[j][i]))
    check("global K symmetric", gAsym < 1e-6, "max asym=${"%.2e".format(gAsym)}")
    check("global K positive definite (no disconnected DOF)", cholesky(asm.K) != null)
    check("all nodal masses positive", asm.M.filterIndexed { i, _ -> i % 6 < 2 }.all { it > 0.0 })

    println("\n=== 5. Guyan reduction ===")
    val red = GuyanReduction.reduce(asm.K, asm.M, DOF_PER_NODE, MASTER_DOF_PER_NODE)
    val n = red.kReduced.size
    check("reduces 72 -> 24 DOF", n == 24, "got $n")
    var rAsym = 0.0
    for (i in 0 until n) for (j in 0 until n) rAsym =
        maxOf(rAsym, kotlin.math.abs(red.kReduced[i][j] - red.kReduced[j][i]))
    check("reduced K symmetric", rAsym < 1e-6)
    check("reduced K positive definite", cholesky(red.kReduced) != null)
    var mFull = 0.0; for (i in 0 until fullDof step 6) mFull += asm.M[i]
    var mRed = 0.0; for (i in 0 until n step 2) mRed += red.mReduced[i]
    check(
        "mass conserved through reduction",
        near(mFull, mRed, 1e-12),
        "${"%.3f".format(mFull)} vs ${"%.3f".format(mRed)} Mg"
    )

    println("\n=== 6. Shear wall orientation (walls run along Y -> Y must be stiffer) ===")
    val fx = DoubleArray(n) { if (it % 2 == 0) 1000.0 else 0.0 }
    val fy = DoubleArray(n) { if (it % 2 == 1) 1000.0 else 0.0 }
    var dxMax = 0.0; for (v in cholSolve(red.kReduced, fx)) dxMax = maxOf(dxMax, kotlin.math.abs(v))
    var dyMax = 0.0; for (v in cholSolve(red.kReduced, fy)) dyMax = maxOf(dyMax, kotlin.math.abs(v))
    val kx = 12000.0 / dxMax
    val ky = 12000.0 / dyMax
    check(
        "Y stiffer than X (shear walls along Y)",
        ky > kx,
        "Kx=${"%.0f".format(kx)}, Ky=${"%.0f".format(ky)} kN/m"
    )

    println("\n=== 7. Modal solve, every mode residual-checked ===")
    val modes = solveAllModesForTest(red.kReduced, red.mReduced)
    var worstRes = 0.0
    for (m in modes) {
        val kp = matVec(red.kReduced, m.phi)
        var rn = 0.0
        var kn = 0.0
        for (i in 0 until n) {
            val r = kp[i] - m.omega2 * red.mReduced[i] * m.phi[i]; rn += r * r; kn += kp[i] * kp[i]
        }
        worstRes = maxOf(worstRes, kotlin.math.sqrt(rn) / kotlin.math.sqrt(kn))
    }
    check(
        "all $n modes are genuine eigenpairs",
        worstRes < 1e-8,
        "worst rel residual=${"%.2e".format(worstRes)}"
    )
    check("all eigenvalues positive", modes.all { it.omega2 > 0.0 })

    val t1 = modes[0].periodSec
    val ta = SeismicParams.empiricalPeriod(storyH)
    // UNITS: K in kN/m and M in Mg give omega^2 in rad^2/s^2 directly. If
    // this ratio blows up by ~31x (sqrt(1000)), suspect a kg/Mg mixup --
    // that was a real bug once.
    check(
        "fundamental period in physical range vs empirical Ta", t1 / ta in 0.3..3.0,
        "T1=${"%.4f".format(t1)}s, Ta=${"%.4f".format(ta)}s, ratio=${"%.2f".format(t1 / ta)}"
    )

    println("\n=== 8. MPMR ===")
    val rows = MPMR.compute(EigensystemResult(modes, n), red.mReduced)
    var tx = 0.0
    var ty = 0.0
    for (r in rows) {
        tx += r.mpmrX; ty += r.mpmrY
    }
    check("MPMR_X sums to 1 (completeness)", near(tx, 1.0, 1e-9), "sum=${"%.9f".format(tx)}")
    check("MPMR_Y sums to 1 (completeness)", near(ty, 1.0, 1e-9), "sum=${"%.9f".format(ty)}")
    val (nx, ny) = MPMR.modesNeeded(rows)
    check("90% mass reached in X", nx in 1..n, "needs $nx modes")
    check("90% mass reached in Y", ny in 1..n, "needs $ny modes")

    // Independent cross-check against the golden-ratio 2-DOF reference
    fun unit(v: DoubleArray): DoubleArray {
        var s = 0.0; for (x in v) s += x * x
        val q = kotlin.math.sqrt(s)
        return DoubleArray(v.size) { v[it] / q }
    }

    val refM = doubleArrayOf(1.0, 1.0)
    val refR = doubleArrayOf(1.0, 1.0)
    val refTot = MPMR.totalMass(refM, refR)
    val m0 = MPMR.effectiveModalMass(unit(doubleArrayOf(1.0, 1.618034)), refM, refR) / refTot
    check(
        "MPMR matches 2-DOF golden-ratio reference",
        near(m0, 0.9472, 1e-3),
        "got ${"%.4f".format(m0)}"
    )

    println("\n=== 9. CQC identities ===")
    val z = SeismicParams.DAMPING_RATIO
    check("rho(self) == 1", near(CQC.rho(10.0, 10.0, z), 1.0))
    check("rho decays for separated modes", CQC.rho(1.0, 100.0, z) < 1e-3)
    check("rho large for close modes", CQC.rho(1.0, 1.05, z) > 0.5)
    check("rho symmetric", near(CQC.rho(1.0, 5.0, z), CQC.rho(5.0, 1.0, z)))

    println("\n=== 10. Spectrum + code checks execute on real data ===")
    check("T0 < TS", SeismicParams.T0 < SeismicParams.TS)
    check(
        "Sa(T) continuous at T0",
        near(SeismicParams.sa(SeismicParams.T0), SeismicParams.SDS, 1e-9)
    )
    check(
        "Sa(T) continuous at TS",
        near(SeismicParams.sa(SeismicParams.TS), SeismicParams.SDS, 1e-9)
    )
    // Guards the deliberately-unimplemented TL branch. TL is not given in the
    // spec, and sa() is only correct for T <= TL. Every mode here sits far
    // below TS, so the branch is unreachable -- but if this model is ever
    // reused for a taller/softer structure, this fails loudly instead of
    // silently taking SD1/T past the point where that is valid.
    check(
        "no modal period exceeds TS (TL branch stays unreachable)",
        modes.all { it.periodSec <= SeismicParams.TS },
        "T_max=${"%.4f".format(modes.maxOf { it.periodSec })}s vs TS=${"%.4f".format(SeismicParams.TS)}s"
    )

    val result = EigensystemResult(modes, n)
    val ux = CQC.combinedDisplacements(result, red.mReduced, MPMR.influenceX(n))
    check("CQC displacements all finite", ux.all { it.isFinite() })
    var maxU = 0.0; for (v in ux) maxU = maxOf(maxU, kotlin.math.abs(v))
    val drift = StoryDriftCheck.check(maxU, storyH)
    check(
        "drift check runs and passes",
        drift.passes,
        "amplified=${"%.5f".format(drift.amplifiedDrift)}m vs ${"%.4f".format(drift.allowable)}m"
    )

    var totMg = 0.0; for (i in 0 until n step 2) totMg += red.mReduced[i]
    val wKN = totMg * SeismicParams.G
    val vElf = SeismicParams.csElf(ta) * wKN
    val pd = PDeltaCheck.check(wKN, drift.amplifiedDrift, vElf, storyH)
    check(
        "P-Delta stable",
        pd.stable,
        "theta=${"%.6f".format(pd.theta)} vs max ${"%.3f".format(pd.thetaMax)}"
    )

    val tix = TorsionalIrregularityCheck.checkXDirection(nodes, ux, grid.zRoof)
    check(
        "torsional ratio X >= 1.0 by construction",
        tix.ratio >= 1.0 - 1e-12,
        "ratio=${"%.4f".format(tix.ratio)}"
    )
    val uy = CQC.combinedDisplacements(result, red.mReduced, MPMR.influenceY(n))
    val tiy = TorsionalIrregularityCheck.checkYDirection(nodes, uy, grid.zRoof)
    check(
        "torsional ratio Y >= 1.0 by construction",
        tiy.ratio >= 1.0 - 1e-12,
        "ratio=${"%.4f".format(tiy.ratio)}"
    )

    check(
        "base shear scaling applies below 85% of ELF",
        near(CQC.baseShearScaleFactor(40.0, 50.0), 1.0625)
    )
    check(
        "base shear scaling is 1.0 above 85% of ELF",
        near(CQC.baseShearScaleFactor(45.0, 50.0), 1.0)
    )

    // INTEGRATION check -- this is the one whose absence let the elastic vs
    // R-reduced spectrum mismatch survive. The two cases above only prove the
    // ARITHMETIC of baseShearScaleFactor with synthetic literals; they never
    // touch the model, so a V_CQC computed on a different basis than V_ELF
    // sailed straight through them. While combinedBaseShear used the elastic
    // spectrum this ratio was ~4.5, which pinned the scale factor at 1.0 and
    // silently skipped a scale-up ASCE 7 12.9.4 requires. Both sides are now
    // R-reduced, so the ratio must be O(1); a future basis mismatch moves it
    // by a factor of R/Ie = 6 and trips this immediately.
    val vCqcX = CQC.combinedBaseShear(result, red.mReduced, MPMR.influenceX(n))
    val vRatio = vCqcX / vElf
    check(
        "V_CQC and V_ELF share the same R-reduced basis", vRatio in 0.4..1.6,
        "V_CQC=${"%.1f".format(vCqcX)}kN vs V_ELF=${"%.1f".format(vElf)}kN, ratio=${
            "%.3f".format(
                vRatio
            )
        }"
    )
    val vScale = CQC.baseShearScaleFactor(vCqcX, vElf)
    check(
        "12.9.4 scale factor from real model values is finite and >= 1",
        vScale.isFinite() && vScale >= 1.0, "scale=${"%.4f".format(vScale)}"
    )

    println("\n=== 11. Quantity takeoff ===")
    val tkInput = TakeoffInput(grid)
    val tk = QuantityTakeoff.compute(tkInput)

    // The takeoff hardcodes 230x230 columns and 230x300 beams because a bill
    // of quantities needs DIMENSIONS, while Sections carries only AREA and
    // second moments. These two checks are what keep the two descriptions of
    // the same member from drifting apart: change Sections.COLUMN.area to
    // resize the column and this fails until QuantityTakeoff agrees.
    check(
        "takeoff column dims agree with Sections.COLUMN.area",
        near(0.230 * 0.230, Sections.COLUMN.area, 1e-12),
        "0.230^2=${"%.6f".format(0.230 * 0.230)} vs ${Sections.COLUMN.area}"
    )
    check(
        "takeoff beam dims agree with Sections.BEAM.area",
        near(0.230 * 0.300, Sections.BEAM.area, 1e-12),
        "0.230x0.300=${"%.6f".format(0.230 * 0.300)} vs ${Sections.BEAM.area}"
    )

    // Independent reference: the standard Indonesian bar table.
    check(
        "bar table D10 = 0.617 kg/m", near(QuantityTakeoff.barKgPerM(10.0), 0.617, 2e-3),
        "got ${"%.4f".format(QuantityTakeoff.barKgPerM(10.0))}"
    )
    check(
        "bar table D12 = 0.888 kg/m", near(QuantityTakeoff.barKgPerM(12.0), 0.888, 2e-3),
        "got ${"%.4f".format(QuantityTakeoff.barKgPerM(12.0))}"
    )

    check(
        "every takeoff quantity is finite",
        tk.lines.all { it.gross.isFinite() && it.net.isFinite() })
    check("no takeoff line is negative", tk.lines.all { it.net >= 0.0 })
    check("no deduction exceeds its gross", tk.lines.all { it.net <= it.gross + 1e-12 })

    // Hand-checkable: 4 exterior bays x 3.0m x 3.2m clear height.
    check(
        "AAC infill area = 38.40 m2 (4 bays x 3.0 x 3.2)",
        near(tk.line("PAS.01").gross, 38.40, 1e-9),
        "got ${"%.3f".format(tk.line("PAS.01").gross)}"
    )
    // 4 interior X bays (4 x 3.0 x 3.2 = 38.40) + 10.3m of Y bays x 3.2 = 32.96
    check(
        "brick infill area = 71.36 m2",
        near(tk.line("PAS.02").gross, 71.36, 1e-9),
        "got ${"%.3f".format(tk.line("PAS.02").gross)}"
    )
    check(
        "shear wall concrete = 14.42 m3 (2 x 0.2 x 10.3 x 3.5)",
        near(tk.line("BET.03").gross, 14.42, 1e-9),
        "got ${"%.3f".format(tk.line("BET.03").gross)}"
    )

    // ---- Reconciliation: the takeoff is derived from the GRID, the mass
    // matrix from the ELEMENT LIST. They must describe the same building.
    //
    // This is the check that found the BEAM_WALLEDGE duplication: assemble()
    // built BEAM_Y over all three X grid lines and then built BEAM_WALLEDGE
    // again over x=0 and x=6, same node pairs and same section, for 20.6 m of
    // beam that does not exist (+3411.36 kg, +9.03%). Fixed in Assembly.kt;
    // these three checks are what keep it fixed.
    val rec = QuantityTakeoff.reconcileLumpedMass(tkInput, asm)
    check(
        "takeoff mass == assembled model mass (same building, two derivations)",
        rec.agrees,
        "takeoff=${"%.1f".format(rec.takeoffLumpedKg)}kg vs model=${
            "%.1f".format(rec.modelLumpedKg)
        }kg, delta=${"%.2f".format(rec.deltaKg)}kg (${"%.3f".format(rec.relative * 100)}%)"
    )

    // Generalised regression guard. The specific duplicate is gone, but the
    // SHAPE of the bug -- two frame elements spanning the same node pair -- is
    // what to prevent, and it is cheap to test for directly. Struts are
    // excluded: a bay deliberately carries both of its diagonals, which share
    // no node pair with each other but do pair up across the bay.
    val framePairs = asm.elements
        .filter { !it.type.startsWith("STRUT_") }
        .map { it.type.takeWhile { c -> c != '_' } to setOf(it.nodeA.id, it.nodeB.id) }
    check(
        "no two frame elements span the same node pair",
        framePairs.size == framePairs.toSet().size,
        "${framePairs.size} elements, ${framePairs.toSet().size} distinct"
    )

    // And the count itself, hand-derivable: 12 columns + 8 X-beams (4 Y lines
    // x 2 bays) + 9 Y-beams (3 X lines x 3 bays) + 8 wall piers = 37 frame
    // elements, plus 22 struts (11 bays x 2 diagonals) = 59.
    check(
        "element counts match the hand count",
        asm.elements.count { it.type == "COLUMN" } == 12 &&
                asm.elements.count { it.type == "BEAM_X" } == 8 &&
                asm.elements.count { it.type == "BEAM_Y" } == 9 &&
                asm.elements.count { it.type == "SHEARWALL" } == 8 &&
                asm.elements.count { it.type.startsWith("STRUT_") } == 22,
        "col=${asm.elements.count { it.type == "COLUMN" }} " +
                "bx=${asm.elements.count { it.type == "BEAM_X" }} " +
                "by=${asm.elements.count { it.type == "BEAM_Y" }} " +
                "wall=${asm.elements.count { it.type == "SHEARWALL" }} " +
                "strut=${asm.elements.count { it.type.startsWith("STRUT_") }}"
    )

    println("\n=== 12. Guyan expansion and force recovery ===")
    // An arbitrary, non-symmetric master displacement: a smooth or symmetric
    // one can hide a transform error by making wrong terms cancel.
    val uM = DoubleArray(red.masterIdx.size) { 0.001 * kotlin.math.sin(1.7 * it + 0.3) }
    val uF = red.expand(uM)
    val kU = matVec(asm.K, uF)
    val scale = maxAbs(kU)

    // THE defining property of static condensation: the slave DOF carry no
    // load. If expand() has the sign wrong (u_s = +T*u_m instead of -T*u_m)
    // this is the check that catches it -- nothing downstream would.
    var slaveMax = 0.0
    for (i in red.slaveIdx) slaveMax = maxOf(slaveMax, kotlin.math.abs(kU[i]))
    check(
        "expand(): slave DOF carry no load (K*u zero on slave rows)",
        slaveMax / scale < 1e-12,
        "rel=${"%.2e".format(slaveMax / scale)}"
    )

    var energyFull = 0.0
    for (i in uF.indices) energyFull += uF[i] * kU[i]
    val kRu = matVec(red.kReduced, uM)
    var energyRed = 0.0
    for (i in uM.indices) energyRed += uM[i] * kRu[i]
    check(
        "expand(): strain energy matches the reduced system",
        near(energyFull, energyRed, 1e-12),
        "full=${"%.9e".format(energyFull)} reduced=${"%.9e".format(energyRed)}"
    )
    check(
        "expand() rejects a wrong-sized vector",
        runCatching { red.expand(DoubleArray(red.masterIdx.size - 1)) }.isFailure
    )

    // Recovered element end forces must re-assemble to K*u. This exercises the
    // gather, the element stiffness rebuild (which is why ElementDef carries
    // its material and section) and the local<->global transform together.
    val reassembled = DoubleArray(fullDof)
    for (el in asm.elements) {
        val dxe = el.nodeB.x - el.nodeA.x
        val dye = el.nodeB.y - el.nodeA.y
        val dze = el.nodeB.z - el.nodeA.z
        if (el.isStrut) {
            val nAx = ForceRecovery.strutAxial(el, uF)
            val len = kotlin.math.sqrt(dxe * dxe + dye * dye + dze * dze)
            val dir = doubleArrayOf(dxe / len, dye / len, dze / len)
            for (i in 0..2) {
                if (el.nodeA.hasDof) reassembled[el.nodeA.dofStart + i] -= nAx * dir[i]
                if (el.nodeB.hasDof) reassembled[el.nodeB.dofStart + i] += nAx * dir[i]
            }
        } else {
            val lf = ForceRecovery.frameEndForces(el, uF)
            val (ax, ay, az) = FrameElement.localAxes(dxe, dye, dze)
            val fg = matVec(transpose(FrameElement.transformationMatrix(ax, ay, az)), lf.f)
            for (i in 0..5) {
                if (el.nodeA.hasDof) reassembled[el.nodeA.dofStart + i] += fg[i]
                if (el.nodeB.hasDof) reassembled[el.nodeB.dofStart + i] += fg[6 + i]
            }
        }
    }
    var reasmErr = 0.0
    for (i in 0 until fullDof) reasmErr = maxOf(reasmErr, kotlin.math.abs(reassembled[i] - kU[i]))
    check(
        "recovered element forces re-assemble to K*u",
        reasmErr / scale < 1e-12,
        "rel=${"%.2e".format(reasmErr / scale)}"
    )

    // Global equilibrium for a real modal force state: what is applied at the
    // free DOF must come out of the base.
    run {
        val rXe = MPMR.influenceX(n)
        val uMode = red.expand(ForceRecovery.modalDisplacement(modes[0], red.mReduced, rXe))
        val fMode = matVec(asm.K, uMode)
        var applied = 0.0
        for (nd in nodes) if (nd.hasDof) applied += fMode[nd.dofStart]
        var reaction = 0.0
        for (el in asm.elements) {
            val base = when {
                !el.nodeA.hasDof -> el.nodeA
                !el.nodeB.hasDof -> el.nodeB
                else -> continue
            }
            val dxe = el.nodeB.x - el.nodeA.x
            val dye = el.nodeB.y - el.nodeA.y
            val dze = el.nodeB.z - el.nodeA.z
            reaction += if (el.isStrut) {
                val nAx = ForceRecovery.strutAxial(el, uMode)
                val len = kotlin.math.sqrt(dxe * dxe + dye * dye + dze * dze)
                if (base === el.nodeA) -nAx * dxe / len else nAx * dxe / len
            } else {
                val lf = ForceRecovery.frameEndForces(el, uMode)
                val (ax, ay, az) = FrameElement.localAxes(dxe, dye, dze)
                val fg = matVec(transpose(FrameElement.transformationMatrix(ax, ay, az)), lf.f)
                if (base === el.nodeA) fg[0] else fg[6]
            }
        }
        check(
            "mode 1: base reaction balances the applied force",
            kotlin.math.abs(applied + reaction) / kotlin.math.abs(applied) < 1e-12,
            "applied=${"%.4f".format(applied)}kN reaction=${"%.4f".format(reaction)}kN"
        )
    }

    println("\n=== 13. Section capacity (SNI 2847) ===")
    val grade = ConcreteGrade.fromModulus(Materials.CONCRETE_K250)
    // E and f'c are two descriptions of one material; the round trip through
    // Ec = 4700*sqrt(f'c) must not drift, or a variant that changes the grade
    // would also silently change the stiffness baseline.
    check(
        "f'c <-> Ec round-trips bit-exactly",
        grade.ecMPa == Materials.CONCRETE_K250.e / 1000.0,
        "f'c=${"%.6f".format(grade.fcMPa)} -> Ec=${grade.ecMPa}"
    )

    val s400 = RebarSteel(400.0)
    val dBeam = 300.0 - 40.0 - 8.0 - 6.0
    val beamSingly = RcSection(
        "beam", 230.0, 300.0, listOf(BarLayer(dBeam, barAreaMm2(12.0, 2))),
        grade, s400, 8.0, 150.0
    )
    // Closed form for a singly-reinforced section: Mn = As fy (d - a/2).
    val asB = barAreaMm2(12.0, 2)
    val aB = asB * 400.0 / (0.85 * grade.fcMPa * 230.0)
    val handMn = 0.90 * asB * 400.0 * (dBeam - aB / 2) * KNM_PER_NMM
    check(
        "beam phi*Mn matches the closed form",
        near(beamSingly.designMomentKNm(0.0), handMn, 1e-6),
        "code=${"%.4f".format(beamSingly.designMomentKNm(0.0))} hand=${"%.4f".format(handMn)} kN.m"
    )
    val handVn = 0.75 * (0.17 * kotlin.math.sqrt(grade.fcMPa) * 230.0 * dBeam +
            (2 * Math.PI / 4 * 64.0) * 240.0 * dBeam / 150.0) * KN_PER_N
    check(
        "beam phi*Vn matches the hand formula",
        near(beamSingly.copy(steel = RebarSteel(240.0)).designShearKN(0.0), handVn, 1e-9),
        "code=${"%.3f".format(beamSingly.copy(steel = RebarSteel(240.0)).designShearKN(0.0))} " +
                "hand=${"%.3f".format(handVn)} kN"
    )

    val colSecV = DesignVariant.AS_SPECIFIED.columnRcSection()
    val handP = 0.80 * 0.65 * (0.85 * grade.fcMPa * (52900.0 - colSecV.astMm2) +
            400.0 * colSecV.astMm2) * KN_PER_N
    check(
        "column phi*Pn,max matches the hand formula",
        near(colSecV.designAxialMaxKN, handP, 1e-9),
        "code=${"%.2f".format(colSecV.designAxialMaxKN)} hand=${"%.2f".format(handP)} kN"
    )
    // phi*Pn must rise monotonically with the neutral-axis depth, or the
    // bisection in designMomentKNm() can converge to the wrong branch.
    var prevP = Double.NEGATIVE_INFINITY
    var monotone = true
    for (i in 1..200) {
        val (pn, _, et) = colSecV.interactionPoint(i * 3.0)
        val p = colSecV.phiFor(et) * pn
        if (p < prevP - 1e-9) monotone = false
        prevP = p
    }
    check("phi*Pn is monotonic in the neutral-axis depth", monotone)
    // Classic interaction shape: capacity peaks at the balanced point, not at
    // either end. A monotonic result would mean the axial term is being
    // mishandled.
    val mAt0 = colSecV.designMomentKNm(0.0)
    val mAtBal = colSecV.designMomentKNm(300.0)
    val mAtHigh = colSecV.designMomentKNm(550.0)
    check(
        "P-M interaction peaks between pure flexure and pure axial",
        mAtBal > mAt0 && mAtBal > mAtHigh,
        "M(0)=${"%.2f".format(mAt0)} M(300)=${"%.2f".format(mAtBal)} M(550)=${"%.2f".format(mAtHigh)}"
    )
    check(
        "tie spacing limit is the least of 16db, 48dt, least dimension",
        near(colSecV.tieSpacingLimitMm(12.0), minOf(16.0 * 12.0, 48.0 * 8.0, 230.0), 1e-12),
        "got ${"%.0f".format(colSecV.tieSpacingLimitMm(12.0))} mm"
    )

    println("\n=== 14. Design variant and assessment ===")
    val spec = DesignVariant.AS_SPECIFIED
    check(
        "AS_SPECIFIED concrete is bit-identical to the original constant",
        spec.concreteMaterial.e == Materials.CONCRETE_K250.e &&
                spec.concreteMaterial.g == Materials.CONCRETE_K250.g &&
                spec.concreteMaterial.density == Materials.CONCRETE_K250.density
    )
    check(
        "AS_SPECIFIED sections are the spec's own constants",
        spec.columnSection === Sections.COLUMN && spec.beamSection === Sections.BEAM
    )
    // A derived section must follow its dimension. This is what makes the
    // variant mechanism mean anything: change the size, get a different model.
    val halfCol = spec.copy(columnDimM = 0.115, columnSectionOverride = null)
    check(
        "a derived column section follows its dimension (I ~ d^4)",
        near(halfCol.columnSection.iz, 0.115 * 0.115 * 0.115 * 0.115 / 12.0, 1e-15) &&
                halfCol.columnSection.iz < spec.columnSection.iz / 15.0,
        "I=${"%.3e".format(halfCol.columnSection.iz)} vs spec ${"%.3e".format(spec.columnSection.iz)}"
    )

    val assessSpec = Assessment.run(grid, spec) { kk, mm -> solveAllModesForTest(kk, mm) }
    check(
        "assessment reproduces the baseline period",
        near(assessSpec.periodSec, modes[0].periodSec, 1e-12),
        "T1=${"%.6f".format(assessSpec.periodSec)}s"
    )
    check(
        "every member check produces a finite ratio",
        assessSpec.memberChecks.all { m -> m.checks.all { it.ratio.isFinite() } },
        "${assessSpec.memberChecks.size} members, " +
                "${assessSpec.memberChecks.sumOf { it.checks.size }} checks"
    )
    check(
        "as-specified passes every member check", assessSpec.allPass,
        "worst=${"%.3f".format(assessSpec.worstRatio)} on ${assessSpec.worstMember.member}"
    )
    // The substantive result for THIS building: the frame's force demands are
    // negligible because the shear walls take the load, so the only check that
    // comes near governing is a detailing rule. If this ever stops being true
    // the building or the model has changed materially.
    check(
        "detailing governs, not force demand (walls carry the lateral load)",
        assessSpec.worstMember.governing.label == "tie spacing" &&
                assessSpec.memberChecks.all { m ->
                    m.checks.filter { it.label != "tie spacing" }.all { it.ratio < 0.5 }
                },
        "governing=${assessSpec.worstMember.governing.label} at " +
                "${"%.3f".format(assessSpec.worstRatio)}"
    )

    // Perturbation: weaker concrete, smaller bars, ties at 250. Must come out
    // worse on every axis -- softer, and lower capacity.
    val built = spec.copy(
        name = "perturbed", concreteFcMPa = 14.5,
        columnSectionOverride = null, beamSectionOverride = null,
        schedule = spec.schedule.copy(
            columnMain = BarSpec(10.0, 4), columnTies = TieSpec(8.0, 250.0),
            beamTop = BarSpec(10.0, 2), beamBottom = BarSpec(10.0, 2),
            beamTies = TieSpec(8.0, 250.0)
        )
    )
    val assessBuilt = Assessment.run(grid, built) { kk, mm -> solveAllModesForTest(kk, mm) }
    check(
        "weaker concrete lengthens the period (E follows f'c)",
        assessBuilt.periodSec > assessSpec.periodSec,
        "${"%.6f".format(assessBuilt.periodSec)}s vs ${"%.6f".format(assessSpec.periodSec)}s"
    )
    check(
        "the perturbed variant is worse on the governing ratio",
        assessBuilt.worstRatio > assessSpec.worstRatio && !assessBuilt.allPass,
        "${"%.3f".format(assessBuilt.worstRatio)} vs ${"%.3f".format(assessSpec.worstRatio)}"
    )
    check(
        "weaker concrete lowers column moment capacity",
        built.columnRcSection().designMomentKNm(0.0) < spec.columnRcSection().designMomentKNm(0.0)
    )

    println("\n=== 15. Site checklist ===")
    val clist = SiteChecklist.generate(grid)
    check("checklist generates items", clist.size >= 15, "got ${clist.size}")
    check(
        "no checklist field is blank",
        clist.all {
            it.stage.isNotBlank() && it.id.isNotBlank() && it.what.isNotBlank() &&
                    it.expected.isNotBlank() && it.tolerance.isNotBlank() &&
                    it.count.isNotBlank() && it.whyItMatters.isNotBlank()
        }
    )
    check("checklist ids are unique", clist.map { it.id }.toSet().size == clist.size)
    // The checklist must be DRIVEN by the variant, not a fixed document --
    // otherwise it would happily tell someone to verify a spacing the model
    // is no longer using.
    val clistWide = SiteChecklist.generate(
        grid, spec.copy(
            schedule = spec.schedule.copy(
                columnTies = TieSpec(8.0, 250.0)
            )
        )
    )
    check(
        "checklist tracks the variant's tie spacing",
        clist.first { it.id == "C3" }.expected.contains("150") &&
                clistWide.first { it.id == "C3" }.expected.contains("250")
    )
    check(
        "checklist column count comes from the grid",
        clist.first { it.id == "C1" }.count.contains("${grid.xLines.size * grid.yLines.size}")
    )

    println("\n=== 16. Unit rates and RAB comparison ===")
    // Coverage both ways: a new takeoff line must be priced or deliberately
    // left unpriced, and a mapping must not outlive the line it priced.
    check(
        "every takeoff line is priced or deliberately unpriced, and nothing else is mapped",
        tk.lines.map { it.code }.toSet() == Ahsp.FOR_TAKEOFF_CODE.keys + Ahsp.NOT_PRICED.keys &&
                Ahsp.FOR_TAKEOFF_CODE.keys.intersect(Ahsp.NOT_PRICED.keys).isEmpty()
    )
    // Pricing an m2 line with an m3 analysis is a silent-wrong-answer bug.
    check(
        "every analysis is in the unit of the takeoff line it prices",
        Ahsp.FOR_TAKEOFF_CODE.all { (code, list) -> list.all { it.uom == tk.line(code).uom } }
    )

    // Hand calculation, in the PUPR table's own A/B/D/E/F layout, at toy prices
    // chosen so every term is exact on paper. Plaster 1:4 15 mm:
    //   labour   0.3x100000 + 0.15x150000 + 0.015x175000 + 0.015x200000 = 58125
    //   material 6.24x1000 + 0.024x200000                                = 11040
    //   D = 69165, E = 10% = 6916.5, F = 76081.5
    // Acian: labour 38750, material 3250, D 42000, F 46200.
    val toy = PriceList(
        mapOf(
            Resource.PEKERJA to 100_000.0, Resource.TUKANG_BATU to 150_000.0,
            Resource.KEPALA_TUKANG to 175_000.0, Resource.MANDOR to 200_000.0,
            Resource.SEMEN to 1_000.0, Resource.PASIR_PASANG to 200_000.0,
        ), overheadProfitPct = 10.0
    )
    val plasterUp = toy.unitPrice(Ahsp.PLASTER_1_4_15)
    check(
        "plaster 1:4 15 mm unit price matches the hand calculation (Rp 76 081.5)",
        plasterUp != null && near(plasterUp.labour, 58_125.0, 1e-12) &&
                near(plasterUp.material, 11_040.0, 1e-12) && near(plasterUp.total, 76_081.5, 1e-12),
        "got ${plasterUp?.total}"
    )
    val plsUp = toy.unitPrice(Ahsp.FOR_TAKEOFF_CODE.getValue("PLS.01"))
    check(
        "PLS.01 is plaster PLUS skim coat (76 081.5 + 46 200)",
        plsUp != null && near(plsUp.total, 122_281.5, 1e-12), "got ${plsUp?.total}"
    )
    val ones = PriceList(Resource.entries.associateWith { 1.0 })
    val aacUp = ones.unitPrice(Ahsp.AAC_150)!!
    check(
        "AAC equipment is 10% of material, as PUPR A.4.4.1.26 publishes",
        aacUp.equipment > 0.0 && near(aacUp.equipment, 0.1 * aacUp.material, 1e-12)
    )

    // Unquoted must mean UNPRICED. A zero would make the contractor's price
    // look infinitely high against it.
    check(
        "a full price list prices every mapped line",
        Ahsp.FOR_TAKEOFF_CODE.values.all { ones.unitPrice(it) != null })
    val noSpacer = PriceList(ones.prices - Resource.SPACER)
    check(
        "an unquoted resource leaves exactly the items needing it unpriced (null, not free)",
        noSpacer.unitPrice(Ahsp.FORM_WALL) == null &&
                noSpacer.missing(listOf(Ahsp.FORM_WALL)) == listOf(Resource.SPACER) &&
                noSpacer.unitPrice(Ahsp.FORM_COLUMN) != null
    )

    // Two derivations of the same money: line by line through unitPrice(),
    // and resource by resource through the rollup. Distinct prices per
    // resource, so a coefficient booked to the wrong resource shows.
    val distinct = PriceList(Resource.entries.associateWith { (it.ordinal + 1) * 1_000.0 }, 0.0)
    val byLine = Ahsp.FOR_TAKEOFF_CODE.entries.sumOf { (code, a) ->
        distinct.unitPrice(a)!!.let { it.labour + it.material } * tk.line(code).net
    }
    val rollup = Ahsp.resources(tk)
    val byResource = rollup.entries.sumOf { (r, q) -> q * distinct.prices.getValue(r) }
    check(
        "pricing line by line == pricing the resource rollup",
        near(byLine, byResource, 1e-12),
        "${"%.2f".format(byLine)} vs ${"%.2f".format(byResource)}"
    )
    // Cement is the one resource an owner counts on site, sack by sack.
    // Pins NET (not gross) quantities and the four cement coefficients.
    val concreteNet = tk.lines.filter { it.code.startsWith("BET") }.sumOf { it.net }
    val cementHand = 384.0 * concreteNet + 11.5 * tk.line("PAS.02").net +
            (6.24 + 3.25) * tk.line("PLS.01").net
    check(
        "cement = 384 kg/m3 x net concrete + brick mortar + plaster + skim coat",
        near(rollup.getValue(Resource.SEMEN), cementHand, 1e-12),
        "${"%.1f".format(rollup.getValue(Resource.SEMEN))} kg"
    )

    // ---- The comparison. A RAB built from the takeoff at reference prices
    // must come back clean; each single perturbation must raise its flag and
    // only its flag.
    val refPrices = distinct.copy(overheadProfitPct = 15.0)
    fun refUnit(code: String) = refPrices.unitPrice(Ahsp.FOR_TAKEOFF_CODE.getValue(code))!!.total
    fun exactLine(no: Int, code: String, volMul: Double = 1.0, priceMul: Double = 1.0) = RabLine(
        no, listOf(code), code, tk.line(code).uom.label,
        tk.line(code).net * volMul, refUnit(code) * priceMul
    )
    val exactRab = Ahsp.FOR_TAKEOFF_CODE.keys.mapIndexed { i, c -> exactLine(i + 1, c) }
    fun flagsOf(r: RabResult) = r.groups.flatMap { g -> g.flags.map { g.codes.joinToString("+") to it } }
    val r0 = RabComparison.compare(tk, refPrices, exactRab)
    check(
        "a RAB equal to the takeoff at reference prices raises no flag and has no gap",
        flagsOf(r0).isEmpty() && r0.notInRab.isEmpty() && kotlin.math.abs(r0.gap) < 1e-9 * r0.pricedContractor,
        "flags=${flagsOf(r0)} gap=${r0.gap}"
    )

    val steelUp = RabComparison.compare(tk, refPrices,
        exactRab.map { if (it.codes == listOf("BSI.01")) exactLine(it.lineNo, "BSI.01", volMul = 1.2) else it })
    val gSteel = steelUp.groups.first { it.codes == listOf("BSI.01") }
    check(
        "+20% steel volume flags VOLUME_HIGH on BSI.01 and nothing else",
        flagsOf(steelUp) == listOf("BSI.01" to RabFlag.VOLUME_HIGH), "${flagsOf(steelUp)}"
    )
    check(
        "... and that gap is all volume, none of it price",
        near(gSteel.volumeEffect, 0.2 * gSteel.takeoffNet * gSteel.contractorUnitPrice, 1e-9) &&
                kotlin.math.abs(gSteel.priceEffect!!) < 1e-9 * gSteel.contractorAmount
    )
    val steelDown = RabComparison.compare(tk, refPrices,
        exactRab.map { if (it.codes == listOf("BSI.01")) exactLine(it.lineNo, "BSI.01", volMul = 0.7) else it })
    check(
        "-30% steel volume flags VOLUME_LOW (under-measuring is a finding too)",
        flagsOf(steelDown) == listOf("BSI.01" to RabFlag.VOLUME_LOW), "${flagsOf(steelDown)}"
    )

    // One RAB line for ALL the concrete: summed against the four nets, priced
    // 25% high on a volume 3% high. The gap must split exactly.
    val betCodes = listOf("BET.01", "BET.02", "BET.03", "BET.04")
    val oneConcrete = exactRab.filter { it.codes.single() !in betCodes } +
            RabLine(99, betCodes, "all concrete", "m3", concreteNet * 1.03, refUnit("BET.01") * 1.25)
    val gBet = RabComparison.compare(tk, refPrices, oneConcrete).groups.first { it.codes == betCodes }
    check(
        "one RAB line covering BET.01-04 is compared against their summed net, and flags PRICE_HIGH only",
        near(gBet.takeoffNet, concreteNet, 1e-12) && gBet.flags == listOf(RabFlag.PRICE_HIGH),
        "${gBet.flags}"
    )
    check(
        "gap = volume effect + price effect, exactly",
        near(gBet.contractorAmount - gBet.referenceAmount!!, gBet.volumeEffect + gBet.priceEffect!!, 1e-12)
    )

    val atGross = RabComparison.compare(tk, refPrices,
        exactRab.map { if (it.codes == listOf("BET.01")) it.copy(volume = tk.line("BET.01").gross) else it })
    check(
        "billing BET.01 at its GROSS measure is recognised as an undeducted overlap",
        flagsOf(atGross).toSet() == setOf("BET.01" to RabFlag.VOLUME_HIGH, "BET.01" to RabFlag.MATCHES_GROSS),
        "${flagsOf(atGross)}"
    )

    // ---- The owner's files. Strict, because the separator trap is silent.
    fun rejects(f: () -> Unit) = try {
        f(); false
    } catch (e: InputError) {
        true
    }
    check(
        "price file rejects Indonesian thousands dots: '1.500' must not become Rp 1.5",
        rejects { PriceFile.parse("M.SEMEN = 1.500") } && rejects { PriceFile.parse("M.SEMEN = 1,500") }
    )
    check(
        "price file reads 62000, 62_000, 62 000 and Rp 62000 alike, and / divides a pack price",
        listOf("62000", "62_000", "62 000", "Rp 62000").all {
            PriceFile.parse("M.SEMEN = $it").prices[Resource.SEMEN] == 62_000.0
        } && PriceFile.parse("M.SEMEN = 62_000 / 40").prices[Resource.SEMEN] == 1_550.0
    )
    check(
        "price file rejects an unknown key (a typo must not silently drop a price)",
        rejects { PriceFile.parse("M.SEMEM = 62000") }
    )
    check(
        "price file: blank means unquoted, 0 means free",
        PriceFile.parse("M.AIR =\nM.PAKU = 0").let {
            Resource.AIR !in it.prices && it.prices[Resource.PAKU] == 0.0
        }
    )
    check(
        "both generated templates parse, and quote or claim nothing",
        PriceFile.parse(PriceFile.template()).prices.isEmpty() &&
                RabComparison.parse(RabComparison.template(tk)).isEmpty()
    )
    check(
        "RAB parser rejects a comma decimal and a dotted rupiah",
        rejects { RabComparison.parse("BET.01 | x | m3 | 19,28 | 1000") } &&
                rejects { RabComparison.parse("BET.01 | x | m3 | 19.28 | 1.250.000") }
    )
    check(
        "RAB: unknown code, wrong unit, mixed units, overlapping code sets and ATP.01 are all rejected",
        rejects { RabComparison.compare(tk, null, RabComparison.parse("BET.09 | x | m3 | 1 | 1")) } &&
                rejects { RabComparison.compare(tk, null, RabComparison.parse("BSI.01 | x | m3 | 1 | 1")) } &&
                rejects { RabComparison.compare(tk, null, RabComparison.parse("BET.01+BEK.01 | x | m3 | 1 | 1")) } &&
                rejects {
                    RabComparison.compare(
                        tk, null,
                        RabComparison.parse("BET.01 | x | m3 | 1 | 1\nBET.01+BET.02 | y | m3 | 1 | 1")
                    )
                } &&
                rejects { RabComparison.compare(tk, null, RabComparison.parse("ATP.01 | x | m2 | 1 | 1")) }
    )
    val split = RabComparison.compare(
        tk, null, RabComparison.parse(
            "BSI.01 | besi kolom | kg | 100 | 15000\nBSI.01 | besi balok | kg | 200 | 15000\n- | atap | ls | 1 | 5000000"
        )
    )
    check(
        "RAB items naming the same code are summed, and '-' items are counted as unchecked",
        split.groups.size == 1 && split.groups[0].contractorVolume == 300.0 &&
                split.unchecked.size == 1 && split.rabTotal == 300 * 15_000.0 + 5_000_000.0
    )

    // The phone page. The takeoff's own reconciliation line once overflowed
    // the 44-column style by 6 characters; fixed-format rows are where that
    // happens, so render a full report at realistic magnitudes and measure.
    val bigPrices = Resource.entries.joinToString("\n") { "${it.key} = ${(it.ordinal + 1) * 250_000}" }
    val bigRab = exactRab.joinToString("\n") {
        "${it.codes.single()} | ${it.description} | ${it.uomText} | " +
                "${"%.3f".format(java.util.Locale.ROOT, it.volume * 1.3)} | ${Math.round(it.unitPriceRp * 250 * 1.2)}"
    } + "\n- | pondasi, atap, lantai, MEP | ls | 1 | 987654321"
    val narrow = RabComparison.report(
        tkInput, bigPrices, RabInput.Text(bigRab), QuantityTakeoff.ReportStyle.NARROW, "files: see the app"
    )
    val widest = narrow.lines().maxBy { it.length }
    check(
        "the phone (NARROW) RAB report never exceeds 44 columns",
        widest.length <= QuantityTakeoff.ReportStyle.NARROW.width &&
                "RAB REJECTED" !in narrow && "PRICE LIST REJECTED" !in narrow,
        "widest ${widest.length}: '$widest'"
    )

    println("\n=== 17. BoQ workbook (owner's bill of quantities, .xlsx) ===")
    val pricedCodes = tk.lines.map { it.code }.filter { it in Ahsp.FOR_TAKEOFF_CODE }
    check(
        "the Indonesian item names cover exactly the priced takeoff lines",
        BoqWorkbook.URAIAN.keys == pricedCodes.toSet()
    )
    val blank = BoqWorkbook.write(tk, withQuantities = false, date = "2026-01-01")
    val partsOk = try {
        Xlsx.unzip(blank).keys.containsAll(
            listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")
        )
    } catch (e: Exception) {
        false
    }
    check("the workbook is a zip with every part an .xlsx needs", partsOk)
    val boqCells = Xlsx.readSheet(blank, BoqWorkbook.SHEET)
    val colA = boqCells.values.mapNotNull { it[0]?.text }
    check(
        "every priced takeoff code appears once in column A, ATP.01 not at all",
        pricedCodes.all { c -> colA.count { it == c } == 1 } && "ATP.01" !in colA
    )
    check(
        "the owner's special items (SO-02, SO-03) are rows of their own, priced up front",
        BoqWorkbook.SPECIAL.all { s -> boqCells.values.any { it[1]?.text == s } }
    )
    check("an unfilled workbook reads back as no priced rows, not as an error",
        BoqWorkbook.read(blank).isEmpty())
    val withQ = Xlsx.readSheet(BoqWorkbook.write(tk, withQuantities = true, date = "2026-01-01"), BoqWorkbook.SHEET)
    check(
        "with quantities, column E carries the takeoff net exactly; without, it is empty",
        pricedCodes.all { c ->
            val row = withQ.values.first { it[0]?.text == c }
            row[4]?.num == tk.line(c).net
        } && pricedCodes.all { c -> boqCells.values.first { it[0]?.text == c }[4]?.num == null }
    )

    // Round trip: fill the input cells with the exact takeoff at reference
    // prices, read it back, compare. Both string layouts: inline (ours) and a
    // shared-string table (what Excel and LibreOffice save).
    val fill = exactRab.associate { it.codes.single() to (it.volume to it.unitPriceRp) }
    for (shared in listOf(false, true)) {
        val filled = BoqWorkbook.write(tk, false, "2026-01-01", prefill = fill, sharedStrings = shared)
        val back = BoqWorkbook.read(filled)
        val r = RabComparison.compare(tk, refPrices, back)
        check(
            "a filled workbook reads back to the same lines and a clean comparison (${if (shared) "shared" else "inline"} strings)",
            back.size == fill.size && back.all { l -> fill.getValue(l.codes.single()) == (l.volume to l.unitPriceRp) } &&
                    flagsOf(r).isEmpty() && r.notInRab.isEmpty() &&
                    kotlin.math.abs(r.gap) < 1e-9 * r.pricedContractor
        )
    }

    // What a contractor can do to a workbook, built cell by cell.
    fun wb(vararg rows: List<XCell?>) = Xlsx.write(
        listOf(XSheet(BoqWorkbook.SHEET, listOf(listOf(XCell.Text("Kode"))) + rows.toList(), listOf(10.0)))
    )
    fun boqRejects(bytes: ByteArray) = try {
        BoqWorkbook.read(bytes); false
    } catch (e: InputError) {
        true
    }
    val txt = XCell::Text
    val num = { v: Double -> XCell.Num(v) }
    check(
        "a price typed as the TEXT '1.250.000' is rejected; as text '1250000' or a number it is read",
        boqRejects(wb(listOf(txt("BSI.01", 0), txt("x", 0), null, txt("kg", 0), num(10.0), txt("1.250.000", 0)))) &&
                BoqWorkbook.read(wb(listOf(txt("BSI.01", 0), txt("x", 0), null, txt("kg", 0), num(10.0), txt("1250000", 0))))
                    .single().unitPriceRp == 1_250_000.0 &&
                BoqWorkbook.read(wb(listOf(txt("BSI.01", 0), txt("x", 0), null, txt("kg", 0), num(10.0), num(1_250_000.0))))
                    .single().unitPriceRp == 1_250_000.0
    )
    check(
        "a row with a price but no volume, or a volume as text '19,28', is rejected",
        boqRejects(wb(listOf(txt("BSI.01", 0), txt("x", 0), null, txt("kg", 0), null, num(15_000.0)))) &&
                boqRejects(wb(listOf(txt("BET.01", 0), txt("x", 0), null, txt("m3", 0), txt("19,28", 0), num(1.0))))
    )
    check(
        "a formula with no computed value is rejected, not read as zero",
        boqRejects(wb(listOf(txt("BSI.01", 0), txt("x", 0), null, txt("kg", 0), num(10.0), XCell.Formula("1000*2"))))
    )
    val xxe = run {
        val parts = Xlsx.unzip(blank).toMutableMap()
        val sheet = String(parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)
        parts["xl/worksheets/sheet1.xml"] = sheet.replaceFirst(
            "?>", "?><!DOCTYPE worksheet [<!ENTITY e SYSTEM \"file:///etc/hostname\">]>"
        ).toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((k, v) in parts) {
                z.putNextEntry(java.util.zip.ZipEntry(k)); z.write(v); z.closeEntry()
            }
        }
        out.toByteArray()
    }
    check("a workbook part with a DOCTYPE (external entity) is refused", boqRejects(xxe))
    val viaReport = RabComparison.report(
        tkInput, bigPrices, RabInput.Workbook(BoqWorkbook.write(tk, false, "2026-01-01", prefill = fill)),
        QuantityTakeoff.ReportStyle.NARROW, "files: see the app"
    )
    check(
        "the RAB page takes the filled workbook directly, and names BoQ rows",
        "RAB REJECTED" !in viaReport && "BoQ row" in viaReport
    )

    println("\n" + "=".repeat(56))
    println("$checks checks, $failures failure(s)")
    println("=".repeat(56))
    return failures
}

/** JVM entry point. On Android, call runAllChecks() from an instrumented
 * test and assert the result is 0 -- do not call this. */
fun main() {
    val f = runAllChecks()
    System.out.flush()
    if (f > 0) kotlin.system.exitProcess(1)
}