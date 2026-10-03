package griyasakha

object MPMR {
    /** Because phi is MASS-NORMALIZED (phi^T M phi = 1, guaranteed by the
     * solver), M_eff = (phi^T M r)^2 / (phi^T M phi) simplifies to just
     * (phi^T M r)^2 -- denominator is exactly 1. */
    fun effectiveModalMass(phi: DoubleArray, mDiag: DoubleArray, r: DoubleArray): Double {
        var phiMr = 0.0
        for (i in phi.indices) phiMr += phi[i] * mDiag[i] * r[i]
        return phiMr * phiMr
    }

    fun totalMass(mDiag: DoubleArray, r: DoubleArray): Double {
        var s = 0.0
        for (i in mDiag.indices) s += mDiag[i] * r[i] * r[i]
        return s
    }

    /** Influence vectors for this model's [Ux,Uy,Ux,Uy,...] reduced layout. */
    fun influenceX(n: Int) = DoubleArray(n) { if (it % 2 == 0) 1.0 else 0.0 }
    fun influenceY(n: Int) = DoubleArray(n) { if (it % 2 == 1) 1.0 else 0.0 }

    data class MpmrRow(
        val modeIndex: Int,
        val periodSec: Double,
        val mpmrX: Double,
        val mpmrY: Double
    )

    fun compute(result: EigensystemResult, mDiag: DoubleArray): List<MpmrRow> {
        val n = result.n
        val rX = influenceX(n)
        val rY = influenceY(n)
        val totalX = totalMass(mDiag, rX)
        val totalY = totalMass(mDiag, rY)
        return result.modes.mapIndexed { idx, mode ->
            MpmrRow(
                idx, mode.periodSec,
                effectiveModalMass(mode.phi, mDiag, rX) / totalX,
                effectiveModalMass(mode.phi, mDiag, rY) / totalY
            )
        }
    }

    /** Modes needed to reach the 90% cumulative-mass requirement, per direction.
     * Returns -1 for a direction that never reaches the target. */
    fun modesNeeded(rows: List<MpmrRow>, target: Double = 0.90): Pair<Int, Int> {
        var cumX = 0.0
        var cumY = 0.0
        var nx = -1
        var ny = -1
        for ((idx, row) in rows.withIndex()) {
            cumX += row.mpmrX; cumY += row.mpmrY
            if (nx < 0 && cumX >= target) nx = idx + 1
            if (ny < 0 && cumY >= target) ny = idx + 1
        }
        return Pair(nx, ny)
    }
}