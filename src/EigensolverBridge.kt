package griyasakha

/** One eigenpair: eigenvalue (omega^2, rad^2/s^2) and its MASS-NORMALIZED
 * mode shape (phi^T M phi = 1) -- not roof-normalized. Unit-roof
 * normalization was found and fixed as a real bug during Phase 1
 * verification (it divides by a coordinate not guaranteed to have healthy
 * amplitude in every mode), so the native solver guarantees mass
 * normalization instead. */
data class Eigenpair(val omega2: Double, val phi: DoubleArray) {
    val omega: Double get() = kotlin.math.sqrt(omega2)
    val frequencyHz: Double get() = omega / (2 * Math.PI)
    val periodSec: Double get() = 1.0 / frequencyHz
}

data class EigensystemResult(val modes: List<Eigenpair>, val n: Int) {
    companion object {
        /** Unpacks the fixed JNI return format: [lambda_0..lambda_{n-1},
         * phi_0[0..n-1], phi_1[0..n-1], ...], ascending by lambda. An empty
         * array means the native side REJECTED the input -- surfaced as an
         * exception rather than silently returning zero modes, since a
         * caller that didn't check would index into empty data. */
        fun unpack(raw: DoubleArray, n: Int): EigensystemResult {
            if (raw.isEmpty()) throw IllegalStateException(
                "solveEigensystemRaw rejected the input (size mismatch or n<=0) -- " +
                        "check Logcat for the native error; do not treat this as zero modes."
            )
            require(raw.size == n + n * n) { "unexpected packed length ${raw.size}, expected ${n + n * n} for n=$n" }
            val modes = (0 until n).map { i ->
                Eigenpair(raw[i], DoubleArray(n) { j -> raw[n + i * n + j] })
            }
            return EigensystemResult(modes, n)
        }
    }
}

/** Pure-Kotlin helpers, usable without a native library present. */
object EigensolverSupport {

    fun flatten(m: Mat): DoubleArray {
        val n = m.size
        val out = DoubleArray(n * n)
        for (i in 0 until n) for (j in 0 until n) out[i * n + j] = m[i][j]
        return out
    }

    /** Builds the flattened dense M the bridge expects from the Guyan-reduced
     * mass vector. M_reduced is diagonal (M_ss = 0 makes the mass reduction a
     * direct sub-block extraction, no off-diagonal terms to represent). */
    fun flattenDiagonalMass(mReduced: DoubleArray): DoubleArray {
        val n = mReduced.size
        val out = DoubleArray(n * n)
        for (i in 0 until n) out[i * n + i] = mReduced[i]
        return out
    }

}