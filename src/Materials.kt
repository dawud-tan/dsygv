package griyasakha

data class Material(
    val name: String,
    val e: Double,      // Young's modulus, kPa (kN/m^2)
    val g: Double,      // Shear modulus, kPa
    val density: Double  // kg/m^3
)

object Materials {
    // f'c, E given in MPa in the spec -> convert to kPa (x1000) since our
    // unit system is kN, m, s, kg throughout (Section 2).
    val CONCRETE_K250 = Material(
        name = "MAT_CONCRETE_K250",
        e = 21410.0 * 1000.0,               // 21410 MPa -> kPa
        g = 21410.0 * 1000.0 / (2 * (1 + 0.2)), // G = E / (2(1+v)), v=0.2
        density = 2400.0
    )
    val AAC_MASONRY = Material(
        name = "MAT_AAC_MASONRY",
        e = 2000.0 * 1000.0,
        g = 2000.0 * 1000.0 / (2 * (1 + 0.2)), // Poisson's ratio not given for masonry; 0.2 is a standard default for this check -- flagged below
        density = 600.0
    )
    val BRICK_MASONRY = Material(
        name = "MAT_BRICK_MASONRY",
        e = 3000.0 * 1000.0,
        g = 3000.0 * 1000.0 / (2 * (1 + 0.2)),
        density = 1700.0
    )
    // NOTE: the spec doesn't give a Poisson's ratio for AAC/brick masonry.
    // G isn't actually used for the diagonal struts (axial-only, pinned-end
    // truss elements per Section 7 -- no torsion/bending DOF engaged), so
    // this default doesn't affect anything in this model, but flagging it
    // rather than silently assuming 0.2 is fine for masonry in general.
}

data class Section(
    val name: String,
    val area: Double,   // m^2
    val ix: Double,     // torsion constant, m^4 (or major-axis I where noted)
    val iy: Double,     // m^4
    val iz: Double       // m^4
)

object Sections {
    // Ix given in the spec is used here as the polar/torsion-adjacent value
    // per the spec's own labeling ("Ix = Iy" for the square column, "Ix"
    // listed first for the rectangular beam) -- see the frame-element
    // builder for exactly how ix/iy/iz map to axial/torsion/bending terms.
    val COLUMN =
        Section(name = "SEC_COLUMN", area = 0.0529, ix = 0.000233, iy = 0.000233, iz = 0.000233)
    val BEAM = Section(name = "SEC_BEAM", area = 0.069, ix = 0.000517, iy = 0.000304, iz = 0.000517)

    // Shear wall pier: 200mm thick, length = spacing between the Y grid
    // lines it spans (set per-instance at assembly time, not fixed here).
    const val SHEARWALL_THICKNESS = 0.200
}