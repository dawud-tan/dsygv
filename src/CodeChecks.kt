package griyasakha

data class DriftResult(
    val elasticDrift: Double,
    val amplifiedDrift: Double,
    val allowable: Double,
    val passes: Boolean
)

object StoryDriftCheck {
    /** delta_x = Cd * delta_xe / Ie, vs 0.020*hsx. Single story, so story
     * drift IS the roof displacement -- the base is fixed (zero), there's no
     * story below to subtract. */
    fun check(elasticDisp: Double, storyHeight: Double): DriftResult {
        val amplified = SeismicParams.CD * elasticDisp / SeismicParams.IE
        val allowable = SeismicParams.DELTA_A_RATIO * storyHeight
        return DriftResult(elasticDisp, amplified, allowable, amplified <= allowable)
    }
}

data class PDeltaResult(val theta: Double, val thetaMax: Double, val stable: Boolean)

object PDeltaCheck {
    /** ASCE 7 Eq. 12.8-16: theta = (Px * Delta * Ie) / (Vx * hsx * Cd).
     *
     * REVIEWED AND CLOSED: beta = 1.0 is not an arbitrary assumption, it is
     * the value ASCE 7 Section 12.8.7 PRESCRIBES when the shear
     * demand-to-capacity ratio is not calculated. It is also the
     * conservative end -- beta < 1 raises theta_max = 0.5/(beta*Cd) and so
     * makes the check easier to pass. Correct default; leave it.
     *
     * Ie is now carried explicitly in the numerator per Eq. 12.8-16. It is
     * 1.0 for this building so nothing moves numerically, but the previous
     * omission would have gone silently wrong for Risk Category III/IV. */
    fun check(
        totalGravityLoadKN: Double, amplifiedDriftM: Double, baseShearKN: Double,
        storyHeightM: Double, beta: Double = 1.0
    ): PDeltaResult {
        val theta = (totalGravityLoadKN * amplifiedDriftM * SeismicParams.IE) /
                (baseShearKN * storyHeightM * SeismicParams.CD)
        val thetaMax = minOf(0.5 / (beta * SeismicParams.CD), 0.25)
        return PDeltaResult(theta, thetaMax, theta <= thetaMax)
    }
}

data class TorsionalIrregularityResult(
    val edge1Drift: Double, val edge2Drift: Double,
    val ratio: Double, val irregular: Boolean, val extreme: Boolean
)

object TorsionalIrregularityCheck {
    /** Corrected node pairing (this was wrong twice before being fixed):
     *   X-direction: hold X=6.0 constant, compare Y-extremes (Y=0 vs Y=10.3)
     *   Y-direction: hold Y=10.3 constant, compare X-extremes (X=0 vs X=6.0)
     * Ratio = max/average; irregular >1.2, extreme >1.4 (ASCE 7 Table 12.3-1). */
    fun checkXDirection(
        nodes: List<Node>,
        disp: DoubleArray,
        roofZ: Double
    ): TorsionalIrregularityResult {
        val n1 = GeometryBuilder.findNode(nodes, 6.0, 0.0, roofZ)
        val n2 = GeometryBuilder.findNode(nodes, 6.0, 10.3, roofZ)
        return buildResult(kotlin.math.abs(disp[n1.reducedUx]), kotlin.math.abs(disp[n2.reducedUx]))
    }

    fun checkYDirection(
        nodes: List<Node>,
        disp: DoubleArray,
        roofZ: Double
    ): TorsionalIrregularityResult {
        val n1 = GeometryBuilder.findNode(nodes, 0.0, 10.3, roofZ)
        val n2 = GeometryBuilder.findNode(nodes, 6.0, 10.3, roofZ)
        return buildResult(kotlin.math.abs(disp[n1.reducedUy]), kotlin.math.abs(disp[n2.reducedUy]))
    }

    private fun buildResult(d1: Double, d2: Double): TorsionalIrregularityResult {
        // Guard the degenerate case: both edges exactly zero (no response in
        // this direction) would otherwise yield 0/0 = NaN, and NaN > 1.2 is
        // false, so an undefined ratio would silently report "regular".
        val avg = (d1 + d2) / 2.0
        if (avg <= 0.0) return TorsionalIrregularityResult(d1, d2, 1.0, false, false)
        val ratio = maxOf(d1, d2) / avg
        return TorsionalIrregularityResult(d1, d2, ratio, ratio > 1.2, ratio > 1.4)
    }
}