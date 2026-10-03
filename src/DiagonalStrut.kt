package griyasakha

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object DiagonalStrut {
    /** Mainstone (1971) effective strut width, as adopted by FEMA 356 / ASCE 41-06:
     *   w = 0.175 * d * (lambda_h)^(-0.4)
     *   lambda_h = h * [ Em * t * sin(2*theta) / (4 * Ec * Ic * h_inf) ]^(1/4)
     * Verified against two independent published sources before implementing
     * (search results, both giving the 0.175 coefficient and -0.4 exponent).
     *
     * @param em       infill (masonry) modulus, kPa
     * @param t        infill thickness, m
     * @param ec       frame (column) concrete modulus, kPa
     * @param ic       column moment of inertia in the strut's bending plane, m^4
     * @param panelL   infill panel clear length (horizontal), m
     * @param panelH   infill panel clear height (vertical), m -- this is h_inf,
     *                 already deducting the beam depth from the full story
     *                 height; pass it in explicitly rather than assuming a
     *                 convention here
     * @param columnH  full column height, center-to-center (this is the "h" in
     *                 the outer lambda_h formula, per Mainstone -- distinct
     *                 from panelH)
     */
    fun effectiveWidth(
        em: Double,
        t: Double,
        ec: Double,
        ic: Double,
        panelL: Double,
        panelH: Double,
        columnH: Double
    ): Double {
        val theta = atan2(panelH, panelL)
        val d = sqrt(panelL * panelL + panelH * panelH)
        val sin2theta = sin(2 * theta)
        val lambda4 = (em * t * sin2theta) / (4 * ec * ic * panelH)
        val lambdaH = columnH * lambda4.pow(0.25)
        val w = 0.175 * d * lambdaH.pow(-0.4)
        return w
    }

    /** Axial-only (pinned-end truss) global stiffness for a diagonal strut
     * between two 3D points. Only translational DOF (Ux,Uy,Uz) at each end
     * are engaged -- no rotational coupling at all, matching "release
     * rotational DOFs at strut ends" in the spec. Returns a 6x6 matrix
     * (3 translations x 2 nodes), NOT 12x12 -- the caller is responsible for
     * placing these into the right rows/columns of the full nodal DOF set
     * (this element simply doesn't touch Rx,Ry,Rz at all).
     */
    fun globalStiffness6x6(
        mat: Material,
        area: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double
    ): Mat {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val c = doubleArrayOf(dx / length, dy / length, dz / length) // direction cosines
        val kAxial = mat.e * area / length
        val k = zeros(6)
        // k = kAxial * [c -c; -c c] (outer product blocks), truss element formula
        for (i in 0..2) for (j in 0..2) {
            val term = kAxial * c[i] * c[j]
            k[i][j] = term            // node1-node1
            k[i][3 + j] = -term        // node1-node2
            k[3 + i][j] = -term        // node2-node1
            k[3 + i][3 + j] = term    // node2-node2
        }
        return k
    }
}

fun main() {
    println("=== Effective width formula: sanity + rigid-body checks ===")
    // Reasonable single-story infill panel: 3.0m bay, story height 3.5m,
    // beam depth 0.3m -> clear infill height 3.5-0.3=3.2m. AAC exterior wall,
    // 150mm thick, against the given column section.
    val ec = Materials.CONCRETE_K250.e
    val ic = Sections.COLUMN.iz // bending in the frame's plane
    val em = Materials.AAC_MASONRY.e
    val t = 0.150
    val panelL = 3.0
    val panelH = 3.5 - 0.3 // story height minus beam depth
    val columnH = 3.5

    val w = DiagonalStrut.effectiveWidth(em, t, ec, ic, panelL, panelH, columnH)
    val d = sqrt(panelL * panelL + panelH * panelH)
    println("panel: L=$panelL h_inf=$panelH d=${"%.4f".format(d)}")
    println("effective width w = ${"%.4f".format(w)} m  (w/d = ${"%.4f".format(w / d)}, sanity range is typically ~0.05-0.25)")

    println("\n=== Axial truss stiffness: symmetry + rigid-body + known-case checks ===")
    val strutArea = w * t
    val k6 = DiagonalStrut.globalStiffness6x6(
        Materials.AAC_MASONRY,
        strutArea,
        0.0,
        0.0,
        0.0,
        panelL,
        0.0,
        panelH
    )
    var maxAsym = 0.0
    for (i in 0..5) for (j in 0..5) maxAsym = maxOf(maxAsym, abs(k6[i][j] - k6[j][i]))
    println("symmetry: max_asym = $maxAsym (expect 0)")

    // Rigid translation (all 3 global axes): zero force expected
    var maxRigidTrans = 0.0
    for (dof in 0..2) {
        val dvec = DoubleArray(6); dvec[dof] = 1.0; dvec[dof + 3] = 1.0
        maxRigidTrans = maxOf(maxRigidTrans, maxAbs(matVec(k6, dvec)))
    }
    println("rigid translation: max|F| = $maxRigidTrans (expect ~0)")

    // Known case: pure axial stretch along the strut's own direction should
    // give force = k_axial * elongation, zero force perpendicular to the strut
    val length = d
    val dirX = panelL / length
    val dirZ = panelH / length
    val stretch = 0.001
    val dvec = DoubleArray(6)
    dvec[3] = dirX * stretch; dvec[5] =
        dirZ * stretch // node2 moves 'stretch' along strut axis, node1 fixed at 0
    val f = matVec(k6, dvec)
    val expectedAxialForce = Materials.AAC_MASONRY.e * strutArea / length * stretch
    println("pure axial stretch: F_node1 = [${f[0]},${f[1]},${f[2]}]  F_node2=[${f[3]},${f[4]},${f[5]}]")
    println("  expected reaction magnitude at node1 = ${-expectedAxialForce} along strut direction ($dirX, 0, $dirZ)")
    println(
        "  node1 force matches direction*magnitude: ${
            abs(f[0] - (-expectedAxialForce * dirX)) < 1e-9 && abs(
                f[2] - (-expectedAxialForce * dirZ)
            ) < 1e-9
        }"
    )
}