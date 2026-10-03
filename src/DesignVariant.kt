package griyasakha

/** One complete description of what got built.
 *
 * WHY THIS EXISTS. Every structural constant in this project used to be a
 * `val` on an `object`: one concrete grade, one column size, one rebar
 * schedule, baked in. That is fine for analysing a design, and useless for the
 * question an owner actually has, which is not "is the design adequate" but
 * "does what got built match the design, and if not, how much does it cost me
 * in strength".
 *
 * Answering that needs two runs of the same pipeline with one thing changed.
 * An absolute number from a model with unreviewed modelling assumptions is
 * weak evidence; a DIFFERENCE between two runs that share those assumptions
 * is much stronger, because whatever is wrong with the model is wrong in both
 * runs and largely cancels.
 *
 * CONSISTENCY IS ENFORCED, NOT ASSUMED. Concrete is described by its cylinder
 * strength f'c, and the elastic modulus is DERIVED from it via SNI 2847's
 * Ec = 4700*sqrt(f'c). They cannot be set independently. That matters: a
 * variant representing "the contractor delivered K175 against a K250 spec"
 * must soften the frame as well as weaken it, and an f'c that moves while E
 * stays put would silently model concrete that is weaker but just as stiff --
 * flattering the drift check while making the strength check look bad, which
 * is the wrong answer in both directions.
 */
data class DesignVariant(
    val name: String,
    /** Cylinder strength, MPa. The default is the f'c implied by the spec's
     * own Ec of 21410 MPa, and the round trip is bit-exact, so AS_SPECIFIED
     * reproduces the original model's stiffness to the last bit. */
    val concreteFcMPa: Double = 20.75093254866455,
    val mainSteelFyMPa: Double = 400.0,
    val tieSteelFyMPa: Double = 240.0,
    val columnDimM: Double = 0.230,
    val beamWidthM: Double = 0.230,
    val beamDepthM: Double = 0.300,
    val wallThicknessM: Double = 0.200,
    val infillExteriorTM: Double = 0.150,
    val infillInteriorTM: Double = 0.100,
    val infillExterior: Material = Materials.AAC_MASONRY,
    val infillInterior: Material = Materials.BRICK_MASONRY,
    val roofDeadLoadKgM2: Double = 15.0,
    val schedule: RcSchedule = RcSchedule(),
    /** Section overrides. Null means "derive from the dimensions above",
     * which is what any perturbed variant must use so that changing a
     * dimension actually changes the stiffness.
     *
     * AS_SPECIFIED overrides them with the spec's own numbers, whose second
     * moments are rounded: the spec gives Iz = 0.000233 m^4 for the column
     * where 0.230^4/12 is 0.00023320, 0.09% higher. Keeping the spec values
     * for the baseline means none of the verified numbers move. That 0.09% is
     * far below any perturbation worth studying (dropping a column to 150 mm
     * is a 5.5x stiffness change), but it is a real inconsistency between the
     * baseline and a derived variant, so it is stated rather than hidden. */
    val columnSectionOverride: Section? = null,
    val beamSectionOverride: Section? = null,
) {
    val concrete: ConcreteGrade get() = ConcreteGrade(concreteFcMPa)
    val mainSteel: RebarSteel get() = RebarSteel(mainSteelFyMPa)
    val tieSteel: RebarSteel get() = RebarSteel(tieSteelFyMPa)

    val concreteMaterial: Material
        get() = concrete.toMaterial("MAT_CONCRETE_fc${"%.1f".format(concreteFcMPa)}")

    val columnSection: Section
        get() = columnSectionOverride ?: run {
            val d = columnDimM
            val i = d * d * d * d / 12.0
            Section("SEC_COLUMN_${(d * 1000).toInt()}", d * d, i, i, i)
        }

    val beamSection: Section
        get() = beamSectionOverride ?: run {
            val b = beamWidthM
            val h = beamDepthM
            Section(
                "SEC_BEAM_${(b * 1000).toInt()}x${(h * 1000).toInt()}",
                b * h,
                b * h * h * h / 12.0,
                h * b * b * b / 12.0,
                b * h * h * h / 12.0,
            )
        }

    /** A shear-wall pier carrying `tribWidthM` of wall length. Mirrors what
     * Assembler used to build inline, including the axis assignment (the
     * strong in-plane direction is global Y, so it belongs in Iy). */
    fun wallPierSection(tribWidthM: Double): Section {
        val t = wallThicknessM
        return Section(
            name = "SEC_SHEARWALL_pier",
            area = t * tribWidthM,
            ix = (1.0 / 3.0) * tribWidthM * t * t * t,
            iy = (t * tribWidthM * tribWidthM * tribWidthM) / 12.0,
            iz = (tribWidthM * t * t * t) / 12.0,
        )
    }

    // ---- Design sections, for the capacity checks ----

    private fun coverMm() = schedule.coverM * 1000.0

    /** Column as a checkable RC section: main bars in two symmetric layers
     * (which is what 4 corner bars are), ties as scheduled. */
    fun columnRcSection(): RcSection {
        val h = columnDimM * 1000.0
        val dt = coverMm() + schedule.columnTies.diameterMm + schedule.columnMain.diameterMm / 2.0
        val perLayer = schedule.columnMain.count / 2
        return RcSection(
            name = "COLUMN ${(columnDimM * 1000).toInt()}x${(columnDimM * 1000).toInt()}",
            bMm = h, hMm = h,
            layers = listOf(
                BarLayer(dt, barAreaMm2(schedule.columnMain.diameterMm, perLayer)),
                BarLayer(h - dt, barAreaMm2(schedule.columnMain.diameterMm, perLayer)),
            ),
            concrete = concrete, steel = mainSteel,
            tieDiameterMm = schedule.columnTies.diameterMm,
            tieSpacingMm = schedule.columnTies.spacingMm,
            coverMm = coverMm(),
        )
    }

    /** Beam as a checkable RC section. Top and bottom steel are both present,
     * which is what the schedule describes; under seismic reversal both faces
     * see tension, so a symmetric check is the relevant one. */
    fun beamRcSection(): RcSection {
        val b = beamWidthM * 1000.0
        val h = beamDepthM * 1000.0
        val dt = coverMm() + schedule.beamTies.diameterMm + schedule.beamTop.diameterMm / 2.0
        return RcSection(
            name = "BEAM ${(beamWidthM * 1000).toInt()}x${(beamDepthM * 1000).toInt()}",
            bMm = b, hMm = h,
            layers = listOf(
                BarLayer(dt, barAreaMm2(schedule.beamTop.diameterMm, schedule.beamTop.count)),
                BarLayer(
                    h - dt,
                    barAreaMm2(schedule.beamBottom.diameterMm, schedule.beamBottom.count)
                ),
            ),
            concrete = concrete, steel = mainSteel,
            tieDiameterMm = schedule.beamTies.diameterMm,
            tieSpacingMm = schedule.beamTies.spacingMm,
            coverMm = coverMm(),
        )
    }

    companion object {
        /** The building as drawn. Uses the spec's own section constants, so
         * this reproduces every previously verified number exactly. */
        val AS_SPECIFIED = DesignVariant(
            name = "as specified (K250, 230x230 col, D12 main, ties 150)",
            columnSectionOverride = Sections.COLUMN,
            beamSectionOverride = Sections.BEAM,
        )
    }
}