package griyasakha

object SeismicParams {
    const val SS = 0.778
    const val S1 = 0.348
    const val FA = 1.189
    const val FV = 1.952
    const val SDS = 0.617   // (2/3)*Fa*Ss = 0.6167, verified
    const val SD1 = 0.453   // (2/3)*Fv*S1 = 0.4529, verified
    const val R = 6.0
    const val CD = 5.0
    const val OMEGA0 = 2.5
    const val IE = 1.0
    const val DELTA_A_RATIO = 0.020
    const val CT = 0.0488
    const val PERIOD_X = 0.75
    const val G = 9.81
    const val DAMPING_RATIO = 0.05

    val T0: Double get() = 0.2 * SD1 / SDS
    val TS: Double get() = SD1 / SDS

    /** Design response spectrum Sa(T), in g, per ASCE 7 Section 11.4.6.
     * This is the ELASTIC design-level ordinate: SDS/SD1 already carry the
     * 2/3 MCE-to-DE factor, but NO R-factor reduction. For strength-level
     * modal forces and for the displacements that Cd later amplifies, use
     * saReduced() instead -- see the note there.
     *
     * The TL (long-period transition) branch is NOT implemented: TL isn't
     * given in Section 2a. It is unreachable for this building -- every one
     * of the 24 modes has T <= T1 = 0.085 s, far below TS = 0.734 s (in fact
     * all of them fall below T0). Verification.kt asserts that no modal
     * period exceeds TS, so reusing this for a taller structure fails the
     * harness loudly instead of silently taking the wrong branch. */
    fun sa(t: Double): Double = when {
        t <= T0 -> SDS * (0.4 + 0.6 * t / T0)
        t <= TS -> SDS
        else -> SD1 / t
    }

    /** Sa(T) reduced by R/Ie -- the strength-level ordinate ASCE 7 12.9.2
     * requires for modal response spectrum analysis.
     *
     * This is the single choke point for the R-reduction, and BOTH the modal
     * force path and the modal displacement path go through it. That is not
     * a stylistic preference, it is what makes the two downstream checks
     * correct:
     *
     *   Forces: V_modal must be on the same reduced basis as csElf() (which
     *   divides by R/Ie) or the 12.9.4 85%-of-ELF scaling comparison is
     *   meaningless -- it silently returns 1.0 and skips a required scale-up.
     *
     *   Displacements: ASCE 7 computes delta_xe from REDUCED forces and then
     *   amplifies by Cd/Ie. Feeding StoryDriftCheck an unreduced elastic
     *   displacement and then multiplying by Cd overstates drift by exactly
     *   R/Ie (= 6 here). The correct net factor on an elastic displacement is
     *   Cd/(R/Ie)/Ie = 0.833, not Cd = 5.0. */
    fun saReduced(t: Double): Double = sa(t) / (R / IE)

    fun empiricalPeriod(heightM: Double): Double = CT * Math.pow(heightM, PERIOD_X)

    /** Cs per ASCE 7 12.8.1.1. Note S1=0.348 < 0.6g, so the additional
     * S1-based floor does NOT apply here -- only the general 0.044*SDS*Ie one. */
    fun csElf(t: Double): Double {
        val csMax = SDS / (R / IE)
        val csComputed = SD1 / (t * (R / IE))
        val csFloor = 0.044 * SDS * IE
        return maxOf(minOf(csMax, csComputed), csFloor)
    }
}