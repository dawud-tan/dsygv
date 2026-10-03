package griyasakha

import kotlin.math.sqrt

typealias Mat = Array<DoubleArray>

fun zeros(n: Int, m: Int = n): Mat = Array(n) { DoubleArray(m) }

fun matMul(a: Mat, b: Mat): Mat {
    val n = a.size
    val k = a[0].size
    val m = b[0].size
    val c = zeros(n, m)
    for (i in 0 until n) for (j in 0 until m) {
        var s = 0.0
        for (l in 0 until k) s += a[i][l] * b[l][j]
        c[i][j] = s
    }
    return c
}

fun transpose(a: Mat): Mat {
    val n = a.size
    val m = a[0].size
    val t = zeros(m, n)
    for (i in 0 until n) for (j in 0 until m) t[j][i] = a[i][j]
    return t
}

fun matVec(a: Mat, v: DoubleArray): DoubleArray {
    val n = a.size
    val m = a[0].size
    val r = DoubleArray(n)
    for (i in 0 until n) {
        var s = 0.0; for (j in 0 until m) s += a[i][j] * v[j]; r[i] = s
    }
    return r
}

object FrameElement {
    /** Local 12x12 stiffness matrix for a 3D beam-column element.
     * DOF order per node: [u, v, w, rx, ry, rz] (axial, two transverse,
     * twist, two bending rotations), 2 nodes -> 12 total.
     */
    fun localStiffness(mat: Material, sec: Section, length: Double): Mat {
        val l = length
        val l2 = l * l
        val l3 = l2 * l
        val ea_l = mat.e * sec.area / l
        val gj_l = mat.g * sec.ix / l
        val k = zeros(12)

        // Axial: u1(0), u2(6)
        k[0][0] = ea_l; k[0][6] = -ea_l
        k[6][0] = -ea_l; k[6][6] = ea_l

        // Torsion: rx1(3), rx2(9)
        k[3][3] = gj_l; k[3][9] = -gj_l
        k[9][3] = -gj_l; k[9][9] = gj_l

        // Bending about local z (in-plane x-y): v1(1), rz1(5), v2(7), rz2(11), using Iz
        val ez = mat.e * sec.iz
        val kzvv = 12 * ez / l3
        val kzvr = 6 * ez / l2
        val kzrr4 = 4 * ez / l
        val kzrr2 = 2 * ez / l
        val zIdx = intArrayOf(1, 5, 7, 11)
        val zLocal = arrayOf(
            doubleArrayOf(kzvv, kzvr, -kzvv, kzvr),
            doubleArrayOf(kzvr, kzrr4, -kzvr, kzrr2),
            doubleArrayOf(-kzvv, -kzvr, kzvv, -kzvr),
            doubleArrayOf(kzvr, kzrr2, -kzvr, kzrr4)
        )
        for (a in 0..3) for (b in 0..3) k[zIdx[a]][zIdx[b]] = zLocal[a][b]

        // Bending about local y (in-plane x-z): w1(2), ry1(4), w2(8), ry2(10), using Iy.
        // Sign pattern on the moment-shear coupling terms is flipped relative
        // to z-bending -- this is the piece most at risk of a memory error,
        // which is exactly why the rigid-body-mode and cantilever checks
        // below exist rather than trusting this block on inspection alone.
        val ey = mat.e * sec.iy
        val kyww = 12 * ey / l3
        val kywr = 6 * ey / l2
        val kyrr4 = 4 * ey / l
        val kyrr2 = 2 * ey / l
        val yIdx = intArrayOf(2, 4, 8, 10)
        val yLocal = arrayOf(
            doubleArrayOf(kyww, -kywr, -kyww, -kywr),
            doubleArrayOf(-kywr, kyrr4, kywr, kyrr2),
            doubleArrayOf(-kyww, kywr, kyww, kywr),
            doubleArrayOf(-kywr, kyrr2, kywr, kyrr4)
        )
        for (a in 0..3) for (b in 0..3) k[yIdx[a]][yIdx[b]] = yLocal[a][b]

        return k
    }

    /** Local axis unit vectors (each a global-frame 3-vector), for the
     * three axis-aligned cases that occur in this specific building (all
     * columns vertical, all beams along global X or Y). Does not handle
     * arbitrary skewed orientations -- not needed for this grid, and
     * simpler / less bug-prone than a general cross-product formulation
     * that has to special-case the vertical-member degeneracy anyway.
     */
    fun localAxes(
        dx: Double,
        dy: Double,
        dz: Double
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        return when {
            kotlin.math.abs(dz) > 1e-9 && kotlin.math.abs(dx) < 1e-9 && kotlin.math.abs(dy) < 1e-9 -> {
                val s = if (dz > 0) 1.0 else -1.0
                // vertical (column): local_x=global_z, local_y=global_x, local_z=global_y
                Triple(
                    doubleArrayOf(0.0, 0.0, s),
                    doubleArrayOf(1.0, 0.0, 0.0),
                    doubleArrayOf(0.0, 1.0, 0.0)
                )
            }

            kotlin.math.abs(dx) > 1e-9 && kotlin.math.abs(dy) < 1e-9 && kotlin.math.abs(dz) < 1e-9 -> {
                val s = if (dx > 0) 1.0 else -1.0
                // along global X: identity (up to direction sign)
                Triple(
                    doubleArrayOf(s, 0.0, 0.0),
                    doubleArrayOf(0.0, 1.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 1.0)
                )
            }

            kotlin.math.abs(dy) > 1e-9 && kotlin.math.abs(dx) < 1e-9 && kotlin.math.abs(dz) < 1e-9 -> {
                val s = if (dy > 0) 1.0 else -1.0
                // along global Y: local_x=global_y, local_y=-global_x, local_z=global_z
                Triple(
                    doubleArrayOf(0.0, s, 0.0),
                    doubleArrayOf(-s, 0.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 1.0)
                )
            }

            else -> throw IllegalArgumentException("Element not axis-aligned: dx=$dx dy=$dy dz=$dz -- this building's geometry should never produce this")
        }
    }

    /** Full 12x12 local-to-global transformation (block-diagonal, 4 copies
     * of the 3x3 rotation, one per translation/rotation triple at each node). */
    fun transformationMatrix(lx: DoubleArray, ly: DoubleArray, lz: DoubleArray): Mat {
        val t3 = arrayOf(lx, ly, lz) // rows = local axes expressed in global coords
        val t12 = zeros(12)
        for (block in 0 until 4) {
            val off = block * 3
            for (i in 0..2) for (j in 0..2) t12[off + i][off + j] = t3[i][j]
        }
        return t12
    }

    fun globalStiffness(
        mat: Material,
        sec: Section,
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
        val kLocal = localStiffness(mat, sec, length)
        val (lx, ly, lz) = localAxes(dx, dy, dz)
        val t = transformationMatrix(lx, ly, lz)
        // K_global = T^T * K_local * T
        return matMul(matMul(transpose(t), kLocal), t)
    }
}

fun maxAbs(v: DoubleArray): Double {
    var m = 0.0
    for (x in v) if (kotlin.math.abs(x) > m) m = kotlin.math.abs(x)
    return m
}

fun main() {
    val mat = Materials.CONCRETE_K250
    val sec = Sections.COLUMN
    val length = 3.5

    println("=== Check 1: local stiffness matrix is symmetric ===")
    val kLocal = FrameElement.localStiffness(mat, sec, length)
    var maxAsym = 0.0
    for (i in 0..11) for (j in 0..11) maxAsym =
        maxOf(maxAsym, kotlin.math.abs(kLocal[i][j] - kLocal[j][i]))
    println("max |K[i][j]-K[j][i]| = $maxAsym (expect 0.0 exactly, or true floating-point noise)")

    println("\n=== Check 2: rigid-body modes give exactly zero force (LOCAL) ===")
    // Axial (dof0) and torsion (dof3): "same value at both ends, zero
    // elsewhere" genuinely is rigid motion -- translating along, or
    // twisting about, the element's own axis doesn't displace anything
    // transversely.
    for (dof in intArrayOf(0, 3)) {
        val d = DoubleArray(12)
        d[dof] = 1.0; d[dof + 6] = 1.0
        val f = matVec(kLocal, d)
        println("  rigid mode dof=$dof (axial/torsion): max|F| = ${maxAbs(f)} (expect ~0)")
    }
    // The two bending rotations (ry=4, rz=5) are different: a rigid rotation
    // theta about node 1 moves node 2 transversely by theta*L, not zero --
    // and the sign of that coupling is opposite between the y and z bending
    // planes (verified below, not assumed).
    val theta = 1.0
    run {
        val d = DoubleArray(12); d[5] = theta; d[11] = theta; d[7] = theta * length // v2 = +theta*L
        println(
            "  rigid rotation about z (rz): max|F| = ${
                maxAbs(
                    matVec(
                        kLocal,
                        d
                    )
                )
            } (expect ~0, v2=+theta*L)"
        )
    }
    run {
        val d = DoubleArray(12); d[4] = theta; d[10] = theta; d[8] =
        -theta * length // w2 = -theta*L
        println(
            "  rigid rotation about y (ry): max|F| = ${
                maxAbs(
                    matVec(
                        kLocal,
                        d
                    )
                )
            } (expect ~0, w2=-theta*L)"
        )
    }

    println("\n=== Check 3: known cantilever cases (node1 fixed, unit load at node2) ===")
    val kNode2 = Array(6) { i -> DoubleArray(6) { j -> kLocal[6 + i][6 + j] } }
    fun solve6(a0: Mat, b0: DoubleArray): DoubleArray {
        val n = 6
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (p in 0 until n) {
            val piv = a[p][p]
            for (j in 0 until n) a[p][j] /= piv
            b[p] /= piv
            for (i in 0 until n) if (i != p) {
                val f = a[i][p]
                for (j in 0 until n) a[i][j] -= f * a[p][j]
                b[i] -= f * b[p]
            }
        }
        return b
    }

    val dAxial = solve6(kNode2, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val expectedAxial = length / (mat.e * sec.area)
    println(
        "  axial: computed=${dAxial[0]}  expected PL/EA=$expectedAxial  match=${
            kotlin.math.abs(
                dAxial[0] - expectedAxial
            ) < 1e-9 * expectedAxial
        }"
    )

    val dShearZ = solve6(kNode2, doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
    val expectedShearZ = length * length * length / (3 * mat.e * sec.iz)
    println(
        "  transverse-y (bending about z): computed=${dShearZ[1]}  expected PL^3/(3EIz)=$expectedShearZ  match=${
            kotlin.math.abs(
                dShearZ[1] - expectedShearZ
            ) < 1e-9 * expectedShearZ
        }"
    )

    val dShearY = solve6(kNode2, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0))
    val expectedShearY = length * length * length / (3 * mat.e * sec.iy)
    println(
        "  transverse-z (bending about y): computed=${dShearY[2]}  expected PL^3/(3EIy)=$expectedShearY  match=${
            kotlin.math.abs(
                dShearY[2] - expectedShearY
            ) < 1e-9 * expectedShearY
        }"
    )

    println("\n=== Check 4: global transform + rigid-body mode, for all 3 axis-aligned cases ===")
    val cases = listOf(
        Triple(Triple(0.0, 0.0, 0.0), Triple(0.0, 0.0, 3.5), "vertical (column)"),
        Triple(Triple(0.0, 0.0, 0.0), Triple(3.0, 0.0, 0.0), "along +X (beam)"),
        Triple(Triple(0.0, 0.0, 0.0), Triple(0.0, 3.5, 0.0), "along +Y (beam)")
    )
    for ((p1, p2, label) in cases) {
        val kGlobal = FrameElement.globalStiffness(
            mat,
            sec,
            p1.first,
            p1.second,
            p1.third,
            p2.first,
            p2.second,
            p2.third
        )
        var maxAsymG = 0.0
        for (i in 0..11) for (j in 0..11) maxAsymG =
            maxOf(maxAsymG, kotlin.math.abs(kGlobal[i][j] - kGlobal[j][i]))
        // Global rigid body translation (any of the 3 translational DOF,
        // same at both nodes -- this one IS simply "same value both ends"
        // regardless of element orientation, since a pure translation
        // doesn't care about the element's local axes) and rigid rotation
        // about each of the 3 GLOBAL axes (which, unlike the local-frame
        // check above, must be built directly in global coordinates: a
        // small global rotation vector theta_g applied rigidly gives node i
        // a translation of theta_g x r_i, where r_i is node i's position
        // relative to an arbitrary rotation center -- using node 1 as that
        // center keeps node 1's translation exactly zero).
        var maxRigid = 0.0
        for (dof in 0..2) { // global translations Ux,Uy,Uz
            val d = DoubleArray(12); d[dof] = 1.0; d[dof + 6] = 1.0
            maxRigid = maxOf(maxRigid, maxAbs(matVec(kGlobal, d)))
        }
        val r = doubleArrayOf(p2.first - p1.first, p2.second - p1.second, p2.third - p1.third)
        for (axis in 0..2) {
            val thetaVec = DoubleArray(3); thetaVec[axis] = 1.0
            // node2 translation = thetaVec x r
            val dTrans = doubleArrayOf(
                thetaVec[1] * r[2] - thetaVec[2] * r[1],
                thetaVec[2] * r[0] - thetaVec[0] * r[2],
                thetaVec[0] * r[1] - thetaVec[1] * r[0]
            )
            val d = DoubleArray(12)
            d[3 + axis] = 1.0; d[9 + axis] = 1.0 // same rotation at both nodes
            d[6] = dTrans[0]; d[7] = dTrans[1]; d[8] =
                dTrans[2] // node2 translation from the rotation
            maxRigid = maxOf(maxRigid, maxAbs(matVec(kGlobal, d)))
        }
        println("  $label: max_asym=$maxAsymG  max_rigid_body_force=$maxRigid (both expect ~0)")
    }
}