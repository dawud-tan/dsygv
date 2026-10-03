package griyasakha

/** One assembled element, carrying the material and section it was BUILT
 * with -- not just its endpoints.
 *
 * The material/section used to be implicit: assemble() picked them, used them
 * to form the element stiffness, and kept only the node pair. That is enough
 * to draw the frame and to total its mass, but not to recover element forces
 * afterwards, because there is no way to rebuild the element stiffness. It is
 * especially not enough for SHEARWALL, whose section is computed per pier from
 * a tributary width and so cannot be looked up from a constant at all.
 *
 * For struts, `section` carries the strut's effective AREA with zero second
 * moments: the strut is an axial-only pinned truss element and its stiffness
 * genuinely uses nothing else. */
data class ElementDef(
    val type: String,
    val nodeA: Node,
    val nodeB: Node,
    val material: Material,
    val section: Section,
) {
    val isStrut: Boolean get() = type.startsWith("STRUT_")

    val lengthM: Double
        get() {
            val dx = nodeB.x - nodeA.x
            val dy = nodeB.y - nodeA.y
            val dz = nodeB.z - nodeA.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
}

class ModelAssembly(
    val nodes: List<Node>,
    val fullDofCount: Int,
    /** What this model is a model OF. Carried so that downstream capacity
     * checks read the same dimensions, grade and rebar schedule the stiffness
     * was built from, instead of re-deriving them from constants. */
    val variant: DesignVariant = DesignVariant.AS_SPECIFIED,
) {
    val K = zeros(fullDofCount)
    val M = DoubleArray(fullDofCount) // diagonal only (lumped mass)
    val elements = mutableListOf<ElementDef>()

    /** Adds a 12x12 element stiffness into global K, skipping rows/cols for
     * a fully-fixed-base node entirely (a prescribed-zero DOF isn't part of
     * the free system -- there's no equation to add it to). */
    fun addFrameElement(kGlobal12: Mat, nodeA: Node, nodeB: Node) {
        val globalIdx = IntArray(12) { -1 }
        for (i in 0..5) globalIdx[i] = if (nodeA.hasDof) nodeA.dofStart + i else -1
        for (i in 0..5) globalIdx[6 + i] = if (nodeB.hasDof) nodeB.dofStart + i else -1
        for (i in 0..11) {
            val gi = globalIdx[i]; if (gi < 0) continue
            for (j in 0..11) {
                val gj = globalIdx[j]; if (gj < 0) continue
                K[gi][gj] += kGlobal12[i][j]
            }
        }
    }

    /** Same skipping logic, for a 6x6 (translations-only, pinned) strut. */
    fun addStrutElement(kGlobal6: Mat, nodeA: Node, nodeB: Node) {
        val globalIdx = IntArray(6) { -1 }
        for (i in 0..2) globalIdx[i] = if (nodeA.hasDof) nodeA.dofStart + i else -1
        for (i in 0..2) globalIdx[3 + i] = if (nodeB.hasDof) nodeB.dofStart + i else -1
        for (i in 0..5) {
            val gi = globalIdx[i]; if (gi < 0) continue
            for (j in 0..5) {
                val gj = globalIdx[j]; if (gj < 0) continue
                K[gi][gj] += kGlobal6[i][j]
            }
        }
    }

    /** Adds mass to a node's Ux and Uy only (Section 8) -- no-op for
     * fixed-base nodes.
     *
     * UNITS (this was a real bug -- it produced a fundamental period ~21x
     * too long before being caught): the model's base units are kN, m, s.
     * In that system the CONSISTENT mass unit is the megagram/tonne (Mg),
     * NOT the kilogram, because kN = Mg * m/s^2. Callers pass kilograms
     * (the natural unit for densities and floor loads) and this method
     * converts once, here, so K [kN/m] and M [Mg] combine correctly in
     * omega^2 = K/M without any external fudge factor. */
    fun addMass(node: Node, massKg: Double) {
        if (!node.hasDof) return
        val massMg = massKg * KG_TO_MG
        M[node.dofStart] += massMg
        M[node.dofStart + 1] += massMg
    }

    companion object {
        const val KG_TO_MG = 0.001
    }
}

object Assembler {
    // Infill thickness and every other structural constant now live on
    // DesignVariant, so that "what if the contractor used a thinner wall"
    // is a parameter rather than an edit. The default variant reproduces
    // the spec exactly; see DesignVariant.AS_SPECIFIED.

    fun assemble(
        grid: Grid,
        nodes: List<Node>,
        fullDofCount: Int,
        variant: DesignVariant = DesignVariant.AS_SPECIFIED,
    ): ModelAssembly {
        val asm = ModelAssembly(nodes, fullDofCount, variant)
        val beamDepth = variant.beamDepthM
        val xs = grid.xLines
        val ys = grid.yLines
        val storyH = grid.zRoof - grid.zBase

        // ---- Columns: every grid intersection, base -> roof ----
        for (y in ys) for (x in xs) {
            val base = GeometryBuilder.findNode(nodes, x, y, grid.zBase)
            val roof = GeometryBuilder.findNode(nodes, x, y, grid.zRoof)
            val k = FrameElement.globalStiffness(
                variant.concreteMaterial, variant.columnSection,
                base.x, base.y, base.z, roof.x, roof.y, roof.z
            )
            asm.addFrameElement(k, base, roof)
            asm.elements.add(
                ElementDef("COLUMN", base, roof, variant.concreteMaterial, variant.columnSection)
            )
        }

        // ---- Beams: adjacent roof nodes, along X lines then Y lines ----
        for (y in ys) for (i in 0 until xs.size - 1) {
            val n1 = GeometryBuilder.findNode(nodes, xs[i], y, grid.zRoof)
            val n2 = GeometryBuilder.findNode(nodes, xs[i + 1], y, grid.zRoof)
            val k = FrameElement.globalStiffness(
                variant.concreteMaterial, variant.beamSection,
                n1.x, n1.y, n1.z, n2.x, n2.y, n2.z
            )
            asm.addFrameElement(k, n1, n2)
            asm.elements.add(
                ElementDef("BEAM_X", n1, n2, variant.concreteMaterial, variant.beamSection)
            )
        }
        for (x in xs) for (i in 0 until ys.size - 1) {
            val n1 = GeometryBuilder.findNode(nodes, x, ys[i], grid.zRoof)
            val n2 = GeometryBuilder.findNode(nodes, x, ys[i + 1], grid.zRoof)
            val k = FrameElement.globalStiffness(
                variant.concreteMaterial, variant.beamSection,
                n1.x, n1.y, n1.z, n2.x, n2.y, n2.z
            )
            asm.addFrameElement(k, n1, n2)
            asm.elements.add(
                ElementDef("BEAM_Y", n1, n2, variant.concreteMaterial, variant.beamSection)
            )
        }

        // ---- Shear walls: perimeters X0 (Y0->Y3) and X2 (Y0->Y3), as pier
        // segments between adjacent roof Y-nodes (this replaces the beam on
        // those two lines with the wall pier's own section, since the wall
        // itself is the roof-level connecting element there). ----
        // CORRECTED (was a real bug, found via static-stiffness isolation
        // testing): wall piers must be VERTICAL (base-to-roof), like very
        // wide columns. A wall resists lateral sway by spanning from the
        // fixed base up to the roof -- connecting adjacent roof nodes
        // horizontally makes it a stiff beam, contributing almost no real
        // lateral stiffness. One vertical pier per Y-grid position, each
        // carrying a tributary WIDTH of the wall's length (same half-
        // distance convention as the mass tributary calculation).
        fun tribWidthAlong(lines: List<Double>, idx: Int): Double {
            val left = if (idx == 0) 0.0 else (lines[idx] - lines[idx - 1]) / 2.0
            val right = if (idx == lines.size - 1) 0.0 else (lines[idx + 1] - lines[idx]) / 2.0
            return left + right
        }
        for (x in listOf(xs.first(), xs.last())) for (yi in ys.indices) {
            val base = GeometryBuilder.findNode(nodes, x, ys[yi], grid.zBase)
            val roof = GeometryBuilder.findNode(nodes, x, ys[yi], grid.zRoof)
            val tribW = tribWidthAlong(ys, yi)
            // Section moved to DesignVariant.wallPierSection(), which keeps
            // the reasoning that used to sit here:
            //   - Torsion constant is thin-rectangle St. Venant J ~ (1/3) b t^3.
            //     (An earlier version scaled with WIDTH cubed instead of
            //     THICKNESS cubed, overestimating torsional stiffness ~77x.)
            //     REVIEWED AND CLOSED: correct here -- at t=0.200 and a typical
            //     tributary width of ~3.4 m the aspect ratio b/t is ~17, well
            //     inside the thin-rectangle validity range, and neglecting
            //     warping restraint is right for a squat single-storey pier.
            //   - AXIS ASSIGNMENT (was swapped -- a real bug, caught by testing
            //     a single pier against 3EI/L^3 both ways). For a VERTICAL
            //     element localAxes() maps local_y -> global_x and local_z ->
            //     global_y, so bending on Iy resists GLOBAL Y. This wall runs
            //     along Y, so its large in-plane inertia belongs in Iy.
            val wallSec = variant.wallPierSection(tribW)
            val k = FrameElement.globalStiffness(
                variant.concreteMaterial, wallSec,
                base.x, base.y, base.z, roof.x, roof.y, roof.z
            )
            asm.addFrameElement(k, base, roof)
            asm.elements.add(
                ElementDef("SHEARWALL", base, roof, variant.concreteMaterial, wallSec)
            )
        }
        // REMOVED: a separate "BEAM_WALLEDGE" loop used to run here, adding a
        // roof beam over x=xs.first() and x=xs.last(). It was a leftover from
        // when the shear walls were modelled HORIZONTALLY and so replaced the
        // roof beam on those two lines -- once the walls became vertical piers
        // the general BEAM_Y loop above already covered all three X grid
        // lines, and this added a SECOND beam on the same node pairs with the
        // same section.
        //
        // All 6 were exact duplicates: +20.6 m of beam that does not exist,
        // = +3411.36 kg (+9.03%) of phantom mass, plus duplicated stiffness on
        // both Y roof lines. Found by QuantityTakeoff.reconcileLumpedMass(),
        // which derives quantities from the grid rather than from this element
        // list and so did not inherit the error. Verification section 11 now
        // asserts no two beams share a node pair, so it cannot come back.

        // ---- Cross-diagonal struts: every OTHER bay (not shear-wall
        // bays), spanning base-to-roof across one story height and one
        // bay width -- each bay gets BOTH diagonals (the standard linear-
        // elastic simplification for compression-only strut behavior,
        // since only one is actually active at a time depending on sway
        // direction, and tracking which needs nonlinear analysis this
        // model doesn't do). Exterior bays (on the plan perimeter) use AAC;
        // interior bays use brick.
        //
        // JUDGMENT CALL, flagged for your review: for this 2-bay x 3-bay
        // plan, the X0 and X2 lines (running the long/Y direction) are
        // shear walls already. That leaves infill bays along Y at the
        // middle line (X1), and along X at every Y line. Every X-direction
        // bay touches either Y0 or Y3 (the plan's short edges) making it
        // "exterior" by this reading; every Y-direction bay at X1 is fully
        // interior. This is one reasonable reading of Section 7, not the
        // only possible one -- worth confirming against your own intent
        // before treating this as final.
        // A bay is exterior when the GRID LINE IT SITS ON lies on the plan
        // perimeter -- not when its endpoints happen to touch one.
        //
        // FIXED (this was a real bug, not just a judgment call). The old
        // predicate tested all four endpoint coordinates at once:
        //     xa == xs.first() || xb == xs.last() || ya == ys.first() || yb == ys.last()
        // With only three X grid lines there are exactly two X-spanning bays,
        // and each one necessarily touches x=0 or x=6 -- so the first two
        // clauses were VACUOUSLY TRUE for every X bay and swamped the y test,
        // marking the y=3.5 and y=7.0 interior partitions as exterior. The
        // leftover y clauses then leaked into the Y-spanning bays, marking 2
        // of the 3 middle-line bays exterior as well. 6 of 11 bays were
        // misclassified, and the old comment asserted the opposite of what
        // the code actually did.
        //
        // x=0 and x=6 are concrete shear walls, so the only exterior INFILL
        // runs along y=0 and y=10.3. Correct split is 4 exterior / 7 interior.
        fun isExteriorXBay(y: Double) = y == ys.first() || y == ys.last()
        fun isExteriorYBay(x: Double) = x == xs.first() || x == xs.last()

        fun addStrutBay(
            baseA: Node,
            roofA: Node,
            baseB: Node,
            roofB: Node,
            panelSpan: Double,
            isExterior: Boolean
        ) {
            val mat = if (isExterior) variant.infillExterior else variant.infillInterior
            val thickness = if (isExterior) variant.infillExteriorTM else variant.infillInteriorTM
            val panelHInf = storyH - beamDepth
            val ic = variant.columnSection.iz
            val w = DiagonalStrut.effectiveWidth(
                mat.e,
                thickness,
                variant.concreteMaterial.e,
                ic,
                panelSpan,
                panelHInf,
                storyH
            )
            val area = w * thickness
            // Second moments are zero on purpose: globalStiffness6x6() is an
            // axial-only pinned truss and reads nothing but the area.
            val strutSec = Section(
                name = "SEC_STRUT_${if (isExterior) "AAC" else "BRICK"}",
                area = area, ix = 0.0, iy = 0.0, iz = 0.0
            )
            // Diagonal 1: baseA -> roofB
            val k1 = DiagonalStrut.globalStiffness6x6(
                mat,
                area,
                baseA.x,
                baseA.y,
                baseA.z,
                roofB.x,
                roofB.y,
                roofB.z
            )
            asm.addStrutElement(k1, baseA, roofB)
            asm.elements.add(
                ElementDef(
                    "STRUT_${if (isExterior) "AAC" else "BRICK"}",
                    baseA, roofB, mat, strutSec
                )
            )
            // Diagonal 2: baseB -> roofA
            val k2 = DiagonalStrut.globalStiffness6x6(
                mat,
                area,
                baseB.x,
                baseB.y,
                baseB.z,
                roofA.x,
                roofA.y,
                roofA.z
            )
            asm.addStrutElement(k2, baseB, roofA)
            asm.elements.add(
                ElementDef(
                    "STRUT_${if (isExterior) "AAC" else "BRICK"}",
                    baseB, roofA, mat, strutSec
                )
            )
        }

        // X-direction bays, every Y line
        for (y in ys) for (i in 0 until xs.size - 1) {
            val baseA = GeometryBuilder.findNode(nodes, xs[i], y, grid.zBase)
            val roofA = GeometryBuilder.findNode(nodes, xs[i], y, grid.zRoof)
            val baseB = GeometryBuilder.findNode(nodes, xs[i + 1], y, grid.zBase)
            val roofB = GeometryBuilder.findNode(nodes, xs[i + 1], y, grid.zRoof)
            addStrutBay(baseA, roofA, baseB, roofB, xs[i + 1] - xs[i], isExteriorXBay(y))
        }
        // Y-direction bays, middle X line only (X0, X2 are shear walls)
        val midX = xs[xs.size / 2]
        for (i in 0 until ys.size - 1) {
            val baseA = GeometryBuilder.findNode(nodes, midX, ys[i], grid.zBase)
            val roofA = GeometryBuilder.findNode(nodes, midX, ys[i], grid.zRoof)
            val baseB = GeometryBuilder.findNode(nodes, midX, ys[i + 1], grid.zBase)
            val roofB = GeometryBuilder.findNode(nodes, midX, ys[i + 1], grid.zRoof)
            addStrutBay(baseA, roofA, baseB, roofB, ys[i + 1] - ys[i], isExteriorYBay(midX))
        }

        return asm
    }

    /** Mass: self-weight of columns/beams/walls (half to each end node, per
     * standard lumped-mass convention), AAC/brick weight split to the two
     * roof nodes of each bay, roof dead load by tributary area (the rate is
     * variant.roofDeadLoadKgM2, 15 kg/m^2 as specified), LL excluded per
     * Section 8's 0.0 factor. */
    fun assignMass(asm: ModelAssembly, grid: Grid, nodes: List<Node>) {
        // Read from the assembly rather than take a second parameter: the mass
        // MUST be built from the same variant as the stiffness, and a separate
        // argument is an opportunity to pass a different one.
        val variant = asm.variant
        val beamDepth = variant.beamDepthM
        val g = 1.0 // masses are added directly in kg; no g-conversion needed
        // for a mass matrix (that conversion belongs to load
        // calculations elsewhere, not here)

        // Self-weight of columns: half to roof node (other half would go to
        // the base node, which has no DOF and so isn't part of this model).
        for (el in asm.elements.filter { it.type == "COLUMN" }) {
            val length = grid.zRoof - grid.zBase
            val mass = variant.concreteMaterial.density * variant.columnSection.area * length
            asm.addMass(el.nodeB, mass / 2.0) // nodeB is the roof end for columns
        }
        // Shear walls are now VERTICAL (base-to-roof, like columns), so
        // their mass goes half to the roof end -- the base end has no DOF.
        run {
            val ys = grid.yLines
            fun tribWidthAlong(lines: List<Double>, idx: Int): Double {
                val left = if (idx == 0) 0.0 else (lines[idx] - lines[idx - 1]) / 2.0
                val right = if (idx == lines.size - 1) 0.0 else (lines[idx + 1] - lines[idx]) / 2.0
                return left + right
            }
            for (el in asm.elements.filter { it.type == "SHEARWALL" }) {
                val length = grid.zRoof - grid.zBase // vertical span
                val yi = ys.indexOfFirst { kotlin.math.abs(it - el.nodeA.y) < 1e-6 }
                val tribW = tribWidthAlong(ys, yi)
                val mass =
                    variant.concreteMaterial.density * (variant.wallThicknessM * tribW) * length
                asm.addMass(el.nodeB, mass / 2.0) // nodeB is the roof end
            }
        }
        // Self-weight of horizontal beams: half to each of their two (both
        // roof-level) end nodes.
        for (el in asm.elements.filter { it.type == "BEAM_X" || it.type == "BEAM_Y" }) {
            val dx = el.nodeB.x - el.nodeA.x
            val dy = el.nodeB.y - el.nodeA.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            val mass = variant.concreteMaterial.density * variant.beamSection.area * length
            asm.addMass(el.nodeA, mass / 2.0)
            asm.addMass(el.nodeB, mass / 2.0)
        }
        // Struts: half their mass to whichever of their two ends has a DOF
        // (base end has none -- all of a strut's "effective" mass, for this
        // reduced dynamic model, lands on its roof-level end).
        for (el in asm.elements.filter { it.type.startsWith("STRUT_") }) {
            val dx = el.nodeB.x - el.nodeA.x
            val dy = el.nodeB.y - el.nodeA.y
            val isExterior = el.type.endsWith("AAC")
            val mat = if (isExterior) variant.infillExterior else variant.infillInterior
            val thickness = if (isExterior) variant.infillExteriorTM else variant.infillInteriorTM
            // Mass of the INFILL PANEL this strut stands for -- NOT the mass
            // of the strut's own diagonal cross-section.
            //
            // FIXED: the strut is a STIFFNESS idealisation. Its effective
            // width w is only ~9% of the diagonal (w/d = 0.091 here), so
            // using w*t*L_diag as a mass proxy captured just ~38% of the
            // panel mass tributary to the roof -- a 3.3 t shortfall on a
            // 35.5 t model (9.4%), rising to 4.8 t (13.6%) once the bay
            // classification above is corrected and the heavier brick
            // panels are assigned to 7 bays instead of 1.
            //
            // Each bay carries TWO struts and each strut has exactly one
            // roof-level end, so charging every strut a QUARTER of its panel
            // puts half the panel at roof level, split evenly between the
            // bay's two roof nodes. The other half is tributary to the
            // foundation, which has no DOF -- addMass() no-ops on the fixed
            // base end, which is what makes the quarter-per-strut split land
            // as an even half-panel at the roof.
            val panelSpan = kotlin.math.sqrt(dx * dx + dy * dy)
            val panelHInf = (grid.zRoof - grid.zBase) - beamDepth
            val panelMass = mat.density * thickness * panelSpan * panelHInf
            asm.addMass(el.nodeA, panelMass / 4.0)
            asm.addMass(el.nodeB, panelMass / 4.0)
        }

        // Roof dead load, 15 kg/m^2, by tributary area (half-distance to
        // each neighboring grid line, standard tributary-area convention).
        val xs = grid.xLines
        val ys = grid.yLines
        fun tribWidth(lines: List<Double>, idx: Int): Double {
            val left = if (idx == 0) 0.0 else (lines[idx] - lines[idx - 1]) / 2.0
            val right = if (idx == lines.size - 1) 0.0 else (lines[idx + 1] - lines[idx]) / 2.0
            return left + right
        }
        for (yi in ys.indices) for (xi in xs.indices) {
            val node = GeometryBuilder.findNode(nodes, xs[xi], ys[yi], grid.zRoof)
            val tribArea = tribWidth(xs, xi) * tribWidth(ys, yi)
            asm.addMass(node, variant.roofDeadLoadKgM2 * tribArea)
        }
    }
}