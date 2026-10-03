package griyasakha

object CQC {
    /** CQC correlation coefficient. Symmetric in i,k. */
    fun rho(omegaI: Double, omegaK: Double, zeta: Double): Double {
        val r = if (omegaI <= omegaK) omegaI / omegaK else omegaK / omegaI
        val num = 8 * zeta * zeta * (1 + r) * Math.pow(r, 1.5)
        val den = (1 - r * r) * (1 - r * r) + 4 * zeta * zeta * r * (1 + r) * (1 + r)
        return num / den
    }

    /** Sd = Sa/omega^2, on the REDUCED (strength-level) spectrum. Callers
     * feed the result to StoryDriftCheck, which applies Cd/Ie -- so this must
     * be the reduced ordinate, or the Cd amplification double-counts the
     * R-factor. See SeismicParams.saReduced(). */
    fun spectralDisplacement(t: Double, omega2: Double): Double =
        SeismicParams.saReduced(t) * SeismicParams.G / omega2

    /** Modal participation factor (mass-normalized phi, so no denominator). */
    fun participationFactor(phi: DoubleArray, mDiag: DoubleArray, r: DoubleArray): Double {
        var s = 0.0
        for (i in phi.indices) s += phi[i] * mDiag[i] * r[i]
        return s
    }

    fun combinedDisplacements(
        result: EigensystemResult, mDiag: DoubleArray, r: DoubleArray,
        zeta: Double = SeismicParams.DAMPING_RATIO
    ): DoubleArray {
        val n = result.n
        val u = Array(result.modes.size) { i ->
            val mode = result.modes[i]
            val gamma = participationFactor(mode.phi, mDiag, r)
            val sd = spectralDisplacement(mode.periodSec, mode.omega2)
            DoubleArray(n) { j -> gamma * mode.phi[j] * sd }
        }
        val out = DoubleArray(n)
        for (j in 0 until n) {
            var s = 0.0
            for (i in result.modes.indices) for (k in result.modes.indices)
                s += rho(result.modes[i].omega, result.modes[k].omega, zeta) * u[i][j] * u[k][j]
            // abs() guards tiny negative round-off in the double sum. A
            // LARGE negative value would be a real bug, not noise.
            out[j] = kotlin.math.sqrt(kotlin.math.abs(s))
        }
        return out
    }

    /** CQC-combined base shear. Modal base shear V_i = M_eff,i * Sa_red(Ti) * g,
     * on the REDUCED (strength-level) spectrum per ASCE 7 12.9.2.
     *
     * RESOLVED (was flagged): this previously used the elastic Sa(T) with no
     * R-factor while csElf() divided by R/Ie = 6, so V_CQC came out ~4.5x
     * V_ELF. That gap was the inconsistency, not a structural result, and it
     * made baseShearScaleFactor() return 1.0 unconditionally -- silently
     * skipping a scale-up that 12.9.4 requires (the correct factor here is
     * ~1.30). Both V_CQC and V_ELF are now on the same reduced basis, so the
     * 85% comparison is live. Verification.kt asserts the two are within a
     * sane band of each other, which is the check whose absence let the
     * original mismatch survive. */
    fun combinedBaseShear(
        result: EigensystemResult, mDiag: DoubleArray, r: DoubleArray,
        zeta: Double = SeismicParams.DAMPING_RATIO
    ): Double {
        val v = DoubleArray(result.modes.size) { i ->
            val mode = result.modes[i]
            MPMR.effectiveModalMass(
                mode.phi,
                mDiag,
                r
            ) * SeismicParams.saReduced(mode.periodSec) * SeismicParams.G
        }
        var s = 0.0
        for (i in result.modes.indices) for (k in result.modes.indices)
            s += rho(result.modes[i].omega, result.modes[k].omega, zeta) * v[i] * v[k]
        return kotlin.math.sqrt(kotlin.math.abs(s))
    }

    /** ASCE 7 12.9.4 base-shear scaling: if the dynamic (CQC) base shear is
     * less than 85% of the ELF base shear, scale the dynamic results up. */
    fun baseShearScaleFactor(vDynamicCQC: Double, vStaticELF: Double): Double {
        val floor = 0.85 * vStaticELF
        return if (vDynamicCQC < floor) floor / vDynamicCQC else 1.0
    }
}