package griyasakha

/** Reinforced-concrete section capacity per SNI 2847 (which follows ACI 318).
 *
 * UNITS -- READ THIS FIRST. The rest of this project works in kN, m, Mg.
 * This file works internally in **N and mm**, because those are the units
 * every SNI 2847 / ACI 318 expression is written in: `0.17*sqrt(f'c)*bw*d`
 * is only dimensionally true with f'c in MPa and lengths in mm. Transcribing
 * those formulas into kN and m means rewriting every coefficient, and the
 * project's own history says what happens then -- the kg/Mg mixup survived a
 * long time because the wrong number looked plausible.
 *
 * So: conversion happens ONCE, at the public boundary. Every function taking
 * or returning kN / kN.m says so in its name or its doc, and everything
 * private is N and mm. The two constants below are the only bridge.
 *
 * WHAT THIS IS AND IS NOT. This is a section-strength check: given a factored
 * demand, can this section with this reinforcement carry it. It is NOT a
 * complete design check. It does not cover development length, splice length,
 * bar cut-off points, joint shear, beam-column joint detailing, anchorage of
 * the infill to the frame, or the foundation. Those are real and several of
 * them govern real failures. A passing D/C ratio here means one specific
 * thing passed one specific check.
 */

const val KN_PER_N = 0.001
const val KNM_PER_NMM = 1e-6

/** Concrete, described by its cylinder strength.
 *
 * `fromModulus` inverts SNI 2847's Ec = 4700*sqrt(f'c) so the strength used
 * here is CONSISTENT with the E the stiffness model used. They are two
 * descriptions of one material and must not be set independently -- a variant
 * that lowers the grade has to soften the frame too, or it silently claims
 * weaker concrete that is somehow just as stiff. */
data class ConcreteGrade(val fcMPa: Double) {
    val ecMPa: Double get() = 4700.0 * kotlin.math.sqrt(fcMPa)

    /** beta1 per SNI 2847: 0.85 up to 28 MPa, then reducing, floor 0.65. */
    val beta1: Double
        get() = when {
            fcMPa <= 28.0 -> 0.85
            else -> maxOf(0.65, 0.85 - 0.05 * (fcMPa - 28.0) / 7.0)
        }

    fun toMaterial(name: String, densityKgM3: Double = 2400.0, poisson: Double = 0.2): Material =
        Material(name, ecMPa * 1000.0, ecMPa * 1000.0 / (2 * (1 + poisson)), densityKgM3)

    companion object {
        fun fromModulus(mat: Material): ConcreteGrade {
            val eMPa = mat.e / 1000.0
            return ConcreteGrade((eMPa / 4700.0) * (eMPa / 4700.0))
        }
    }
}

data class RebarSteel(val fyMPa: Double, val esMPa: Double = 200000.0) {
    val yieldStrain: Double get() = fyMPa / esMPa
}

/** One layer of bars, at `depthMm` from the COMPRESSION face. */
data class BarLayer(val depthMm: Double, val areaMm2: Double)

fun barAreaMm2(diameterMm: Double, count: Int): Double =
    count * Math.PI / 4.0 * diameterMm * diameterMm

/** A rectangular RC section with its reinforcement, ready to be checked.
 *
 * `layers` are ordered arbitrarily; the extreme tension layer is found by
 * depth when the strength-reduction factor needs it. */
data class RcSection(
    val name: String,
    val bMm: Double,
    val hMm: Double,
    val layers: List<BarLayer>,
    val concrete: ConcreteGrade,
    val steel: RebarSteel,
    val tieDiameterMm: Double,
    val tieSpacingMm: Double,
    val tieLegs: Int = 2,
    val coverMm: Double = 40.0,
) {
    val agMm2: Double get() = bMm * hMm
    val astMm2: Double get() = layers.sumOf { it.areaMm2 }
    val steelRatio: Double get() = astMm2 / agMm2

    /** Effective depth: to the centroid of the extreme tension layer. */
    val dMm: Double get() = layers.maxOf { it.depthMm }

    /** One point on the P-M interaction diagram, at neutral-axis depth c.
     *
     * Straight strain compatibility with the equivalent rectangular stress
     * block: concrete strain 0.003 at the compression face, plane sections,
     * steel elastic-perfectly-plastic. Compression positive throughout.
     * Moments are taken about the geometric centroid, which for these
     * symmetric sections is also the plastic centroid. */
    fun interactionPoint(cMm: Double): Triple<Double, Double, Double> {
        val fc = concrete.fcMPa
        val a = minOf(concrete.beta1 * cMm, hMm)
        val cc = 0.85 * fc * a * bMm                       // N
        var pn = cc
        var mn = cc * (hMm / 2.0 - a / 2.0)                // N.mm
        var extremeTensileStrain = 0.0
        for (l in layers) {
            val strain = 0.003 * (cMm - l.depthMm) / cMm   // + compression
            val fs = (steel.esMPa * strain).coerceIn(-steel.fyMPa, steel.fyMPa)
            // Bars sitting inside the stress block displace concrete that the
            // Cc term already counted; remove it so it is not double-counted.
            val net = if (l.depthMm <= a) fs - 0.85 * fc else fs
            val force = l.areaMm2 * net
            pn += force
            mn += force * (hMm / 2.0 - l.depthMm)
            extremeTensileStrain = minOf(extremeTensileStrain, strain)
        }
        return Triple(pn, mn, -extremeTensileStrain)
    }

    /** Strength-reduction factor for a tied member, per SNI 2847's transition
     * between compression-controlled (0.65) and tension-controlled (0.90). */
    fun phiFor(tensileStrain: Double): Double {
        val ety = steel.yieldStrain
        return when {
            tensileStrain <= ety -> 0.65
            tensileStrain >= 0.005 -> 0.90
            else -> 0.65 + 0.25 * (tensileStrain - ety) / (0.005 - ety)
        }
    }

    /** Maximum usable design axial capacity of a tied column, kN.
     * phi*Pn,max = 0.80 * phi * [0.85 f'c (Ag - Ast) + fy Ast], phi = 0.65. */
    val designAxialMaxKN: Double
        get() = 0.80 * 0.65 *
                (0.85 * concrete.fcMPa * (agMm2 - astMm2) + steel.fyMPa * astMm2) * KN_PER_N

    /** Design moment capacity phi*Mn at a given factored axial load, kN.m.
     *
     * Bisects on the neutral-axis depth until phi*Pn matches puKN. phi*Pn is
     * monotonic in c over the bracket used (deep tension at small c, the pure
     * compression limit at large c), so bisection is safe. Pass puKN = 0 for
     * pure flexure, which is the beam case. */
    fun designMomentKNm(puKN: Double): Double {
        val puN = puKN / KN_PER_N
        var lo = 1e-4 * hMm
        var hi = 5.0 * hMm
        repeat(200) {
            val mid = 0.5 * (lo + hi)
            val (pn, _, et) = interactionPoint(mid)
            if (phiFor(et) * pn < puN) lo = mid else hi = mid
        }
        val c = 0.5 * (lo + hi)
        val (_, mn, et) = interactionPoint(c)
        return phiFor(et) * mn * KNM_PER_NMM
    }

    /** Design shear capacity phi*Vn, kN, for a factored axial COMPRESSION of
     * nuKN (pass 0 for a beam; negative for net tension is clamped to 0, the
     * conservative reading).
     *
     * Vc = 0.17 (1 + Nu/(14 Ag)) lambda sqrt(f'c) bw d, lambda = 1 (normal
     * weight). Vs = Av fyt d / s, capped at 0.66 sqrt(f'c) bw d, which is the
     * limit past which the section is too small regardless of stirrups. */
    fun designShearKN(nuKN: Double): Double {
        val fc = concrete.fcMPa
        val nuN = maxOf(0.0, nuKN / KN_PER_N)
        val vc = 0.17 * (1.0 + nuN / (14.0 * agMm2)) * kotlin.math.sqrt(fc) * bMm * dMm
        val av = tieLegs * Math.PI / 4.0 * tieDiameterMm * tieDiameterMm
        val vsRaw = av * steel.fyMPa * dMm / tieSpacingMm
        val vs = minOf(vsRaw, 0.66 * kotlin.math.sqrt(fc) * bMm * dMm)
        return 0.75 * (vc + vs) * KN_PER_N
    }

    /** Governing tie-spacing limit, mm, for an ordinary tied column per
     * SNI 2847: the least of 16 main-bar diameters, 48 tie diameters, and the
     * least cross-sectional dimension.
     *
     * This is the single most useful number in this file for site work. It is
     * an integer in millimetres, it is fixed before the pour, and anyone with
     * a tape measure can check it. Section capacity is an argument between
     * engineers; tie spacing is a measurement. */
    fun tieSpacingLimitMm(mainBarDiameterMm: Double): Double =
        minOf(16.0 * mainBarDiameterMm, 48.0 * tieDiameterMm, minOf(bMm, hMm))
}

/** Demand over capacity for one member, in each of the checks run. */
data class CapacityCheck(
    val label: String,
    val demand: Double,
    val capacity: Double,
    val unit: String,
) {
    val ratio: Double get() = if (capacity > 0.0) demand / capacity else Double.POSITIVE_INFINITY
    val passes: Boolean get() = ratio <= 1.0
}

data class MemberCheck(
    val member: String,
    val checks: List<CapacityCheck>,
) {
    val governing: CapacityCheck get() = checks.maxBy { it.ratio }
    val passes: Boolean get() = checks.all { it.passes }
}
