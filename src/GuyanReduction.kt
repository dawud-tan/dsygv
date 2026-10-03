package griyasakha

import griyasakha.ejmlstandin.SimpleMatrix

// Real Android project: replace the import above with
//   import org.ejml.simple.SimpleMatrix
// No other line in this file needs to change -- same method names/signatures.

object GuyanReduction {
    /** The condensed system, plus everything needed to get back OUT of it.
     *
     * `tSM` used to be a local that was computed, used once and dropped. It is
     * the expansion operator: static condensation eliminates the slave DOF by
     * asserting they carry no load, i.e.
     *
     *     K_sm * u_m + K_ss * u_s = 0   ->   u_s = -K_ss^-1 * K_sm * u_m
     *                                             = -tSM * u_m
     *
     * Without it the reduced displacement vector is a dead end: the masters
     * here are translations only, so every rotation -- and therefore every
     * bending moment, which is what actually governs a reinforced-concrete
     * section -- is unrecoverable. Keeping tSM is what makes ForceRecovery
     * possible at all. */
    class Result(
        val kReduced: Mat,
        val mReduced: DoubleArray,
        val masterIdx: IntArray,
        val slaveIdx: IntArray,
        val tSM: Mat,
        val fullDofCount: Int,
    ) {
        /** Expands a reduced (master-only) displacement vector back to the
         * full free-DOF system, filling the slave DOF from the condensation
         * relation above.
         *
         * This is EXACT for the static case, and an approximation under
         * dynamics -- Guyan assumes the slave DOF have no inertia, which is
         * exactly the assumption already made when the mass was lumped onto
         * translations only. The recovered rotations are therefore as good as
         * the reduction itself, no better and no worse. */
        fun expand(uMaster: DoubleArray): DoubleArray {
            require(uMaster.size == masterIdx.size) {
                "expected ${masterIdx.size} master DOF, got ${uMaster.size}"
            }
            val full = DoubleArray(fullDofCount)
            for (i in masterIdx.indices) full[masterIdx[i]] = uMaster[i]
            val uSlave = matVec(tSM, uMaster)
            for (i in slaveIdx.indices) full[slaveIdx[i]] = -uSlave[i]
            return full
        }
    }

    /** Condenses a full free-DOF system (masterCount + slaveCount total) down
     * to just the master DOF. Assumes DOF are laid out in fixed-size blocks
     * per node (6 here: Ux,Uy,Uz,Rx,Ry,Rz), with the first `dofPerNodeMaster`
     * of each block being master DOF and the rest slave -- matching this
     * model's node layout exactly.
     */
    fun reduce(kFull: Mat, mFull: DoubleArray, dofPerNode: Int, masterDofPerNode: Int): Result {
        val n = kFull.size
        val nodeCount = n / dofPerNode
        val masterIdx = IntArray(nodeCount * masterDofPerNode)
        val slaveIdx = IntArray(nodeCount * (dofPerNode - masterDofPerNode))
        var mi = 0
        var si = 0
        for (node in 0 until nodeCount) {
            val base = node * dofPerNode
            for (k in 0 until masterDofPerNode) masterIdx[mi++] = base + k
            for (k in masterDofPerNode until dofPerNode) slaveIdx[si++] = base + k
        }

        fun extract(rows: IntArray, cols: IntArray): SimpleMatrix {
            val out = SimpleMatrix(rows.size, cols.size)
            for (i in rows.indices) for (j in cols.indices) out.set(i, j, kFull[rows[i]][cols[j]])
            return out
        }

        val kMM = extract(masterIdx, masterIdx)
        val kMS = extract(masterIdx, slaveIdx)
        val kSS = extract(slaveIdx, slaveIdx)
        val kSM = extract(slaveIdx, masterIdx)

        val tSM = kSS.solve(kSM)          // T_sm = K_ss^-1 * K_sm
        val kReducedSM = kMM.minus(kMS.mult(tSM)) // K_mm - K_ms * T_sm

        val kReduced = kReducedSM.toArray2D()
        // Symmetrize defensively (should already be symmetric to floating
        // point noise, given kFull was symmetric and this is a Schur
        // complement of a symmetric matrix, which is itself symmetric).
        val n2 = kReduced.size
        for (i in 0 until n2) for (j in 0 until n2) {
            val avg = 0.5 * (kReduced[i][j] + kReduced[j][i])
            kReduced[i][j] = avg
        }

        val mReduced = DoubleArray(masterIdx.size) { mFull[masterIdx[it]] }
        return Result(kReduced, mReduced, masterIdx, slaveIdx, tSM.toArray2D(), n)
    }
}

fun main() {
    val grid = Grid(
        xLines = listOf(0.0, 3.0, 6.0),
        yLines = listOf(0.0, 3.5, 7.0, 10.3),
        zBase = 0.0,
        zRoof = 3.5
    )
    val (nodes, fullDofCount) = GeometryBuilder.build(grid)
    val asm = Assembler.assemble(grid, nodes, fullDofCount)
    Assembler.assignMass(asm, grid, nodes)

    val result = GuyanReduction.reduce(
        asm.K,
        asm.M,
        dofPerNode = DOF_PER_NODE,
        masterDofPerNode = MASTER_DOF_PER_NODE
    )

    println("=== Guyan reduction result ===")
    println("Reduced DOF count: ${result.kReduced.size} (expect 24)")

    var maxAsym = 0.0
    for (i in result.kReduced.indices) for (j in result.kReduced.indices)
        maxAsym = maxOf(maxAsym, kotlin.math.abs(result.kReduced[i][j] - result.kReduced[j][i]))
    println("Reduced K symmetry: max_asym = $maxAsym (expect 0 or float noise)")

    val n = result.kReduced.size
    val L = zeros(n)
    var posDef = true
    for (i in 0 until n) {
        for (j in 0..i) {
            var s = result.kReduced[i][j]
            for (k in 0 until j) s -= L[i][k] * L[j][k]
            if (i == j) {
                if (s <= 0.0) {
                    posDef = false; break
                }
                L[i][j] = kotlin.math.sqrt(s)
            } else L[i][j] = s / L[j][j]
        }
        if (!posDef) break
    }
    println("Reduced K positive definite (Cholesky succeeds): $posDef")

    println("\nReduced M (per node, Ux and Uy -- should be identical to each other")
    println("per node, since mass is added equally to both, and should sum to the")
    println("same total as the full pre-reduction assembly):")
    for (i in result.mReduced.indices step 2) {
        println(
            "  node ${i / 2}: Ux mass=${"%.1f".format(result.mReduced[i])} kg  Uy mass=${
                "%.1f".format(
                    result.mReduced[i + 1]
                )
            } kg"
        )
    }
    var totalM = 0.0
    for (i in result.mReduced.indices step 2) totalM += result.mReduced[i]
    println("total mass (sum of Ux entries) = ${"%.1f".format(totalM)} kg")
}