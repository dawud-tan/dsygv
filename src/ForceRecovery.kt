package griyasakha

/** Element end forces recovered from a displacement field.
 *
 * WHY THIS EXISTS. Everything upstream of here is a DEMAND model: it works out
 * how hard the earthquake shakes the building and how far the roof moves. None
 * of it touches whether any individual member can carry what it is being
 * asked to carry. Drift and P-Delta pass by two orders of magnitude on this
 * building, which says only that drift is the wrong question for a stiff
 * masonry-infilled box -- not that the structure is adequate.
 *
 * The missing link was rotational: after Guyan reduction the surviving DOF are
 * translations only, and a bending moment is a rotation-conjugate quantity, so
 * the reduced solution could not produce one. GuyanReduction.Result.expand()
 * (which needs the retained tSM) restores the condensed rotations, and this
 * file turns the restored full-DOF field into member forces.
 *
 * SIGN AND UNITS. Forces kN, moments kN.m, consistent with K in kN/m. Local
 * end-force order matches FrameElement's local DOF order, per node:
 *     [N, Vy, Vz, T, My, Mz]
 * with node A at indices 0..5 and node B at 6..11. `f = K_local * u_local` is
 * the force the NODES apply TO the element, so a bar in tension is pulled
 * outward at both ends and f[6] (node B, local +x) is positive -- hence axial
 * tension is +f[6], and compression is negative.
 */

/** Signed local end forces for one element under one displacement field. */
class LocalEndForces(val f: DoubleArray) {
    /** Tension positive, compression negative. */
    val axial: Double get() = f[6]
    val shearY: Double get() = maxOf(kotlin.math.abs(f[1]), kotlin.math.abs(f[7]))
    val shearZ: Double get() = maxOf(kotlin.math.abs(f[2]), kotlin.math.abs(f[8]))
    val torsion: Double get() = maxOf(kotlin.math.abs(f[3]), kotlin.math.abs(f[9]))
    val momentY: Double get() = maxOf(kotlin.math.abs(f[4]), kotlin.math.abs(f[10]))
    val momentZ: Double get() = maxOf(kotlin.math.abs(f[5]), kotlin.math.abs(f[11]))
}

/** CQC-combined demand on one element. All values are magnitudes: a CQC
 * combination is a square root of a sum of products and carries no sign. */
data class ElementDemand(
    val element: ElementDef,
    val axialKN: Double,
    val shearYKN: Double,
    val shearZKN: Double,
    val torsionKNm: Double,
    val momentYKNm: Double,
    val momentZKNm: Double,
) {
    /** The larger of the two bending demands -- what a symmetric section check
     * should be run against when the two principal directions are not being
     * checked separately. */
    val momentMaxKNm: Double get() = maxOf(momentYKNm, momentZKNm)
    val shearMaxKN: Double get() = maxOf(shearYKN, shearZKN)
}

object ForceRecovery {

    /** Pulls an element's 12 global DOF out of a full free-DOF vector.
     * A fixed base node contributes zeros: it has no DOF in the free system
     * (dofStart = -1), and its displacement is prescribed zero. */
    private fun gatherFrame(el: ElementDef, uFull: DoubleArray): DoubleArray {
        val ue = DoubleArray(12)
        for (i in 0..5) {
            if (el.nodeA.hasDof) ue[i] = uFull[el.nodeA.dofStart + i]
            if (el.nodeB.hasDof) ue[6 + i] = uFull[el.nodeB.dofStart + i]
        }
        return ue
    }

    /** Local end forces for a frame element (column, beam, wall pier).
     *
     * Rebuilds the element stiffness from the material and section the element
     * was ASSEMBLED with -- which is why ElementDef carries them. A shear wall
     * pier's section is computed per instance from a tributary width and
     * cannot be looked up from a constant. */
    fun frameEndForces(el: ElementDef, uFull: DoubleArray): LocalEndForces {
        require(!el.isStrut) { "use strutAxial() for ${el.type}" }
        val dx = el.nodeB.x - el.nodeA.x
        val dy = el.nodeB.y - el.nodeA.y
        val dz = el.nodeB.z - el.nodeA.z
        val kG = FrameElement.globalStiffness(
            el.material, el.section,
            el.nodeA.x, el.nodeA.y, el.nodeA.z, el.nodeB.x, el.nodeB.y, el.nodeB.z
        )
        val fGlobal = matVec(kG, gatherFrame(el, uFull))
        val (lx, ly, lz) = FrameElement.localAxes(dx, dy, dz)
        // K_global = T^T K_local T, so u_local = T u_global and likewise
        // f_local = T f_global.
        return LocalEndForces(matVec(FrameElement.transformationMatrix(lx, ly, lz), fGlobal))
    }

    /** Axial force in a pinned diagonal strut, tension positive.
     *
     * For a truss, elongation = e . (u_B - u_A) with e the unit vector A->B,
     * and N = (EA/L) * elongation. Computed from the displacements directly
     * rather than through the 6x6, which is both cheaper and clearer.
     *
     * A POSITIVE result is a signal, not an error: masonry infill acts in
     * compression only, so a strut in tension is one that would not actually
     * be engaged in that direction of sway. The linear model carries both
     * diagonals of every bay precisely because it cannot switch them off. */
    fun strutAxial(el: ElementDef, uFull: DoubleArray): Double {
        require(el.isStrut) { "strutAxial() called on ${el.type}" }
        val dx = el.nodeB.x - el.nodeA.x
        val dy = el.nodeB.y - el.nodeA.y
        val dz = el.nodeB.z - el.nodeA.z
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        val e = doubleArrayOf(dx / len, dy / len, dz / len)
        var elong = 0.0
        for (i in 0..2) {
            val ua = if (el.nodeA.hasDof) uFull[el.nodeA.dofStart + i] else 0.0
            val ub = if (el.nodeB.hasDof) uFull[el.nodeB.dofStart + i] else 0.0
            elong += e[i] * (ub - ua)
        }
        return el.material.e * el.section.area / len * elong
    }

    /** Modal displacement of the REDUCED system for one mode, signed:
     *     u_i = Gamma_i * phi_i * Sd_i
     * This is a real (sign-coherent) displacement state, unlike the CQC
     * envelope, which is why forces are recovered per mode and combined
     * afterwards rather than being recovered from the envelope. */
    fun modalDisplacement(mode: Eigenpair, mDiag: DoubleArray, r: DoubleArray): DoubleArray {
        val gamma = CQC.participationFactor(mode.phi, mDiag, r)
        val sd = CQC.spectralDisplacement(mode.periodSec, mode.omega2)
        return DoubleArray(mode.phi.size) { gamma * mode.phi[it] * sd }
    }

    /** Per-element CQC-combined demands for one direction of excitation.
     *
     * ORDER MATTERS AND IS NOT INTERCHANGEABLE. Element forces are recovered
     * mode by mode from each mode's own signed displacement state, and the
     * CQC combination is applied to the FORCES. Recovering forces from the
     * already-combined displacement vector would be wrong: CQC discards sign,
     * so the combined vector is an envelope of magnitudes that no instant of
     * the response ever actually takes, and differencing it across a member
     * produces a meaningless curvature. */
    fun combinedDemands(
        elements: List<ElementDef>,
        result: EigensystemResult,
        red: GuyanReduction.Result,
        r: DoubleArray,
        zeta: Double = SeismicParams.DAMPING_RATIO,
    ): List<ElementDemand> {
        val uFullPerMode = result.modes.map { red.expand(modalDisplacement(it, red.mReduced, r)) }
        val rho = Array(result.modes.size) { i ->
            DoubleArray(result.modes.size) { k ->
                CQC.rho(result.modes[i].omega, result.modes[k].omega, zeta)
            }
        }

        fun cqc(per: List<Double>): Double {
            var s = 0.0
            for (i in per.indices) for (k in per.indices) s += rho[i][k] * per[i] * per[k]
            return kotlin.math.sqrt(kotlin.math.abs(s))
        }

        return elements.map { el ->
            if (el.isStrut) {
                val n = cqc(uFullPerMode.map { strutAxial(el, it) })
                ElementDemand(el, n, 0.0, 0.0, 0.0, 0.0, 0.0)
            } else {
                val per = uFullPerMode.map { frameEndForces(el, it) }

                // Each of the 12 local components is combined independently --
                // the standard treatment, and the reason the result is an
                // envelope rather than a single equilibrium force state.
                fun comp(idx: Int) = cqc(per.map { it.f[idx] })
                ElementDemand(
                    element = el,
                    axialKN = maxOf(comp(0), comp(6)),
                    shearYKN = maxOf(comp(1), comp(7)),
                    shearZKN = maxOf(comp(2), comp(8)),
                    torsionKNm = maxOf(comp(3), comp(9)),
                    momentYKNm = maxOf(comp(4), comp(10)),
                    momentZKNm = maxOf(comp(5), comp(11)),
                )
            }
        }
    }

    /** Gravity axial load reaching a roof node, kN.
     *
     * NOT a finite-element gravity solve. This model has no vertical mass at
     * all -- ModelAssembly.addMass() deliberately puts mass on Ux and Uy only
     * -- so there is no vertical load case to solve. What it uses instead is
     * the lumped mass the assembly already distributed to that node by
     * tributary area, which is the same tributary split a hand calculation
     * would make, times g.
     *
     * CONSERVATIVE WHERE IT IS WRONG: on the x=0 and x=6 grid lines the shear
     * wall shares this load path with the column, so charging all of it to the
     * column overstates that column's axial demand. Off those lines it is the
     * column's genuine tributary share. Half the column's own self-weight is
     * tributary to the base and is added back here, since the base is where
     * the axial force is largest. */
    fun gravityAxialKN(asm: ModelAssembly, node: Node, selfWeightKN: Double = 0.0): Double {
        if (!node.hasDof) return 0.0
        val massMg = asm.M[node.dofStart]
        return massMg * SeismicParams.G + selfWeightKN
    }
}