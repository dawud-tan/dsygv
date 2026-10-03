package griyasakha

import java.util.Locale

/** Unit rates: what ONE unit of each takeoff line costs, built from the
 * published Indonesian AHSP coefficients and prices the OWNER supplies.
 *
 * WHY THIS FILE EXISTS. QuantityTakeoff says how much work there is; a
 * contractor's RAB says what it costs. Between them sits the unit price, and
 * that is an argument an owner can have without a structural-engineering
 * licence: a unit price is coefficient x price, summed over labour and
 * material, plus overhead and profit -- and every coefficient is published.
 *
 * SOURCES, read from the primary documents (not from blogs that copy them):
 *  - SNI 7394:2008 -- concrete, formwork, reinforcement
 *  - SNI 2837:2008 -- plaster, skim coat (acian)
 *  - Permen PUPR 1/2022, Lampiran IV "AHSP Bidang Cipta Karya dan
 *    Perumahan" -- brick and AAC walls, the 15% cap on overhead and profit,
 *    the bulk densities of sand and gravel. Where it republishes an SNI item
 *    (K250, every formwork item used here) the numbers are identical.
 * Every Analysis carries its clause, so the owner can open the table and point.
 *
 * NO PRICES LIVE IN THIS FILE, deliberately. A default price would be
 * invented, and an invented number printed next to a contractor's number
 * reads as evidence. Prices come from PriceList, filled from local quotes. A
 * resource with no quote leaves every item that needs it UNPRICED -- null,
 * never zero, because a zero would make the contractor look infinitely dear.
 *
 * WHERE A CHOICE EXISTS, IT FAVOURS THE CONTRACTOR: mortar 1:4 rather than
 * 1:5, overhead and profit defaulting to the 15% regulatory maximum, the
 * larger of two AAC mortar extrapolations. A generous reference produces flags
 * that survive the argument; a stingy one produces accusations that do not.
 *
 * DO NOT ROUND OR "TIDY" THE COEFFICIENTS. They are transcriptions, and the
 * rule is the one lapack_port lives by: a coefficient that differs from its
 * table is a bug, even if it is closer to what happens on site.
 */

enum class ResourceKind { LABOUR, MATERIAL }

/** Everything a price list can quote. `key` is what the price file uses;
 * `quoteHint` says how to turn a shop quote into this unit, and is printed
 * into the template because that conversion is where a price file goes wrong. */
enum class Resource(
    val key: String,
    val kind: ResourceKind,
    val unit: String,
    val label: String,
    val quoteHint: String,
) {
    PEKERJA("L.PEKERJA", ResourceKind.LABOUR, "OH", "Pekerja", "labourer, daily wage"),
    TUKANG_BATU("L.TUKANG_BATU", ResourceKind.LABOUR, "OH", "Tukang batu", "mason, daily wage"),
    TUKANG_KAYU("L.TUKANG_KAYU", ResourceKind.LABOUR, "OH", "Tukang kayu", "carpenter, daily wage"),
    TUKANG_BESI("L.TUKANG_BESI", ResourceKind.LABOUR, "OH", "Tukang besi", "steel fixer, daily wage"),
    KEPALA_TUKANG("L.KEPALA_TUKANG", ResourceKind.LABOUR, "OH", "Kepala tukang", "head craftsman, daily wage"),
    MANDOR("L.MANDOR", ResourceKind.LABOUR, "OH", "Mandor", "foreman, daily wage"),

    SEMEN("M.SEMEN", ResourceKind.MATERIAL, "kg", "Semen Portland",
        "sack price / sack kg (40 or 50)"),
    PASIR_BETON("M.PASIR_BETON", ResourceKind.MATERIAL, "m3", "Pasir beton",
        "concrete sand; truck price / truck m3"),
    KERIKIL("M.KERIKIL", ResourceKind.MATERIAL, "m3", "Kerikil/split <=30mm",
        "gravel; truck price / truck m3"),
    AIR("M.AIR", ResourceKind.MATERIAL, "L", "Air kerja",
        "water; write 0 if it comes from your own well"),
    BESI("M.BESI", ResourceKind.MATERIAL, "kg", "Besi beton",
        "bar price / NOMINAL bar kg: 12 m D12 = 10.66, D10 = 7.40, D8 = 4.74"),
    KAWAT("M.KAWAT", ResourceKind.MATERIAL, "kg", "Kawat beton", "tie wire (bendrat), per kg"),
    KAYU_III("M.KAYU_III", ResourceKind.MATERIAL, "m3", "Papan kayu kls III", "formwork boards, per m3"),
    BALOK_II("M.BALOK_II", ResourceKind.MATERIAL, "m3", "Balok kayu kls II", "formwork timber, per m3"),
    PLYWOOD("M.PLYWOOD_9", ResourceKind.MATERIAL, "lbr", "Plywood 9 mm", "per sheet"),
    PAKU("M.PAKU", ResourceKind.MATERIAL, "kg", "Paku 5-12 cm", "nails, per kg"),
    MINYAK("M.MINYAK_BEKISTING", ResourceKind.MATERIAL, "L", "Minyak bekisting", "form oil, per litre"),
    DOLKEN("M.DOLKEN", ResourceKind.MATERIAL, "btg", "Dolken 8-10cm x 4m", "prop pole, per pole"),
    SPACER("M.SPACER", ResourceKind.MATERIAL, "bh", "Spacer bekisting", "formwork spacer, per piece"),
    BATA_MERAH("M.BATA_MERAH", ResourceKind.MATERIAL, "bh", "Bata merah 5x11x22",
        "per brick: price per 1000 / 1000"),
    PASIR_PASANG("M.PASIR_PASANG", ResourceKind.MATERIAL, "m3", "Pasir pasang",
        "mortar/plaster sand; truck price / truck m3"),
    BATA_RINGAN("M.BATA_RINGAN_150", ResourceKind.MATERIAL, "m3", "Bata ringan 15 cm",
        "AAC 600x200x150, per m3 (55.6 blocks)"),
    MORTAR_AAC("M.MORTAR_AAC", ResourceKind.MATERIAL, "kg", "Mortar bata ringan",
        "thin-bed mortar; sack price / 40"),
}

data class Coefficient(val resource: Resource, val perUnit: Double, val basis: String = "")

/** One AHSP analysis: resources consumed per ONE unit of work.
 *
 * `derivation` is null for a published item. When it is not null the item
 * does not exist in any table and the string says how it was built; the
 * report prints DERIVED next to it so nobody quotes it as published. */
data class Analysis(
    val ref: String,
    val title: String,
    val uom: Uom,
    val coefficients: List<Coefficient>,
    /** PUPR's "Peralatan  % (bahan)" row: equipment as a percentage of the
     * material cost. Zero for every item that has no such row. */
    val equipmentPctOfMaterial: Double = 0.0,
    val derivation: String? = null,
)

/** A unit price in the PUPR table's own shape: D = A + B + C, E = O&P% x D,
 * F = D + E. Kept as components rather than a single total because "your
 * LABOUR component is twice the reference" is a sharper question than "your
 * price is high". */
data class UnitPrice(
    val labour: Double,
    val material: Double,
    val equipment: Double,
    val overheadProfitPct: Double,
) {
    val subtotal: Double get() = labour + material + equipment
    val overheadProfit: Double get() = subtotal * overheadProfitPct / 100.0
    val total: Double get() = subtotal + overheadProfit

    /** Composite items (PLS.01 = plaster + skim coat) add component-wise. */
    operator fun plus(o: UnitPrice): UnitPrice {
        require(o.overheadProfitPct == overheadProfitPct) { "mixed overhead/profit rates" }
        return UnitPrice(
            labour + o.labour, material + o.material, equipment + o.equipment,
            overheadProfitPct
        )
    }
}

object Ahsp {

    /** Permen PUPR 1/2022 Lamp. IV, line E of every analysis table:
     * "Biaya Umum dan Keuntungan (Maksimum 15%)". */
    const val MAX_OVERHEAD_PROFIT_PCT = 15.0

    /** Bulk densities from the note heading A.4.1.1 of Permen PUPR 1/2022
     * Lamp. IV. SNI 7394 gives sand and gravel in KG, but they are bought by
     * the m3, so the conversion is part of the analysis, not the price file. */
    const val PASIR_BOBOT_ISI_KG_M3 = 1400.0
    const val KERIKIL_BOBOT_ISI_KG_M3 = 1350.0

    private fun c(r: Resource, perUnit: Double, basis: String = "") = Coefficient(r, perUnit, basis)

    /** SNI 7394:2008 6.1-6.x share one labour row for every grade (checked for
     * K175 and K250: 1.650 / 0.275 / 0.028 / 0.083), so only the mix varies.
     * The K-grade labels are SNI 7394's own (K250 = f'c 21.7 MPa); the analysis
     * model's 20.75 MPa is the same grade through a different conversion. */
    private fun concrete(ref: String, grade: String, pcKg: Double, pbKg: Double, krKg: Double) =
        Analysis(
            ref = ref, title = "1 m3 beton $grade, slump 12+/-2 cm", uom = Uom.M3,
            coefficients = listOf(
                c(Resource.PEKERJA, 1.650), c(Resource.TUKANG_BATU, 0.275),
                c(Resource.KEPALA_TUKANG, 0.028), c(Resource.MANDOR, 0.083),
                c(Resource.SEMEN, pcKg),
                c(Resource.PASIR_BETON, pbKg / PASIR_BOBOT_ISI_KG_M3, "$pbKg kg / 1400 kg/m3"),
                c(Resource.KERIKIL, krKg / KERIKIL_BOBOT_ISI_KG_M3, "$krKg kg / 1350 kg/m3"),
                c(Resource.AIR, 215.0),
            ),
        )

    val CONCRETE_K250 = concrete(
        "SNI 7394:2008 6.8 = PUPR A.4.1.1.8", "K250 (f'c 21.7 MPa, w/c 0.56)",
        384.0, 692.0, 1039.0,
    )

    /** Not used to price anything in this house. It exists so the report can
     * say when a contractor's "K250" costs what K175 costs -- the substitution
     * SiteChecklist warns about. */
    val CONCRETE_K175 = concrete(
        "SNI 7394:2008 6.5", "K175 (f'c 14.5 MPa, w/c 0.66)",
        326.0, 760.0, 1029.0,
    )

    /** SNI 7394:2008 6.17 is written per 10 kg; divided by 10 so the unit is
     * the takeoff's kg. Its 10.500 kg of bar per 10 kg is a 5% cutting
     * allowance ON TOP of the takeoff's own lap/waste factor, so steel is
     * priced generously. The report says so. (PUPR 1/2022 lists this item in
     * its index but does not reprint the table.) */
    val REBAR = Analysis(
        "SNI 7394:2008 6.17", "1 kg pembesian, besi polos/ulir", Uom.KG,
        listOf(
            c(Resource.PEKERJA, 0.070 / 10), c(Resource.TUKANG_BESI, 0.070 / 10),
            c(Resource.KEPALA_TUKANG, 0.007 / 10), c(Resource.MANDOR, 0.004 / 10),
            c(Resource.BESI, 10.500 / 10), c(Resource.KAWAT, 0.150 / 10),
        ),
    )

    val FORM_SLOOF = Analysis(
        "SNI 7394:2008 6.21 = PUPR A.4.1.1.19", "1 m2 bekisting sloof", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.520), c(Resource.TUKANG_KAYU, 0.260),
            c(Resource.KEPALA_TUKANG, 0.026), c(Resource.MANDOR, 0.026),
            c(Resource.KAYU_III, 0.045), c(Resource.PAKU, 0.300), c(Resource.MINYAK, 0.100),
        ),
    )

    val FORM_COLUMN = Analysis(
        "SNI 7394:2008 6.22 = PUPR A.4.1.1.20", "1 m2 bekisting kolom", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.660), c(Resource.TUKANG_KAYU, 0.330),
            c(Resource.KEPALA_TUKANG, 0.033), c(Resource.MANDOR, 0.033),
            c(Resource.KAYU_III, 0.040), c(Resource.PAKU, 0.400), c(Resource.MINYAK, 0.200),
            c(Resource.BALOK_II, 0.015), c(Resource.PLYWOOD, 0.350), c(Resource.DOLKEN, 2.000),
        ),
    )

    val FORM_BEAM = Analysis(
        "SNI 7394:2008 6.23 = PUPR A.4.1.1.21", "1 m2 bekisting balok", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.660), c(Resource.TUKANG_KAYU, 0.330),
            c(Resource.KEPALA_TUKANG, 0.033), c(Resource.MANDOR, 0.033),
            c(Resource.KAYU_III, 0.040), c(Resource.PAKU, 0.400), c(Resource.MINYAK, 0.200),
            c(Resource.BALOK_II, 0.018), c(Resource.PLYWOOD, 0.350), c(Resource.DOLKEN, 2.000),
        ),
    )

    val FORM_WALL = Analysis(
        "SNI 7394:2008 6.25 = PUPR A.4.1.1.23", "1 m2 bekisting dinding", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.660), c(Resource.TUKANG_KAYU, 0.330),
            c(Resource.KEPALA_TUKANG, 0.033), c(Resource.MANDOR, 0.033),
            c(Resource.KAYU_III, 0.030), c(Resource.PAKU, 0.400), c(Resource.MINYAK, 0.200),
            c(Resource.BALOK_II, 0.020), c(Resource.PLYWOOD, 0.350), c(Resource.DOLKEN, 3.000),
            c(Resource.SPACER, 4.000),
        ),
    )

    /** Mortar type N, "setara 1SP:4PP". The spec does not say which mix;
     * 1:4 carries more cement than 1:5 and so is the generous choice. */
    val BRICK_HALF_1_4 = Analysis(
        "PUPR A.4.4.1.9", "1 m2 dinding bata merah 1/2 bata, mortar 1SP:4PP", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.300), c(Resource.TUKANG_BATU, 0.100),
            c(Resource.KEPALA_TUKANG, 0.010), c(Resource.MANDOR, 0.015),
            c(Resource.BATA_MERAH, 70.0), c(Resource.SEMEN, 11.500),
            c(Resource.PASIR_PASANG, 0.043),
        ),
    )

    /** NO PUBLISHED ITEM EXISTS for a 15 cm AAC wall: PUPR 1/2022 stops at
     * 10 cm (A.4.4.1.26). Built from that item as follows.
     *  - Labour: the 10 cm row as published. A 15 cm block is heavier, so this
     *    is the one coefficient that errs AGAINST the contractor. Said, not fudged.
     *  - Blocks: 8.4 per m2 as published -- the count follows the 600x200
     *    face, not the thickness -- bought by the m3: 8.4 x 0.6 x 0.2 x 0.15.
     *  - Mortar: the published 0.063 kg/m2 is unusable. It is about 60x below
     *    the manufacturer's own coverage, and below the 7.5 cm item's 0.473
     *    for a thinner wall. Instead, from the MU-380 ThinBedMax data sheet:
     *    7.5 cm covers 16 m2 per 40 kg (2.5 kg/m2), 10 cm covers 10 m2 (4.0
     *    kg/m2). Extrapolated linearly through both points to 15 cm:
     *    4.0 + 5 x (4.0 - 2.5) / 2.5 = 7.0 kg/m2. Scaling the 10 cm figure in
     *    proportion would give 6.0; the larger is taken.
     *  - Equipment: 10% of material, as the 10 cm item publishes. */
    val AAC_150 = Analysis(
        "PUPR A.4.4.1.26 (10 cm) + MU-380 data sheet", "1 m2 dinding bata ringan 15 cm, mortar siap pakai",
        Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.671), c(Resource.TUKANG_BATU, 0.13),
            c(Resource.KEPALA_TUKANG, 0.013), c(Resource.MANDOR, 0.003),
            c(Resource.BATA_RINGAN, 8.4 * 0.6 * 0.2 * 0.15, "8.4 blocks x 0.6 x 0.2 x 0.15 m"),
            c(Resource.MORTAR_AAC, 7.0, "MU-380 coverage, extrapolated to 15 cm"),
        ),
        equipmentPctOfMaterial = 10.0,
        derivation = "15 cm is not in PUPR 1/2022; labour and block count from the " +
                "10 cm item, mortar from the MU-380 data sheet (see Ahsp.AAC_150)",
    )

    val PLASTER_1_4_15 = Analysis(
        "SNI 2837:2008 6.4", "1 m2 plesteran 1PC:4PP tebal 15 mm", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.300), c(Resource.TUKANG_BATU, 0.150),
            c(Resource.KEPALA_TUKANG, 0.015), c(Resource.MANDOR, 0.015),
            c(Resource.SEMEN, 6.240), c(Resource.PASIR_PASANG, 0.024),
        ),
    )

    val ACIAN = Analysis(
        "SNI 2837:2008 6.27", "1 m2 acian", Uom.M2,
        listOf(
            c(Resource.PEKERJA, 0.200), c(Resource.TUKANG_BATU, 0.100),
            c(Resource.KEPALA_TUKANG, 0.010), c(Resource.MANDOR, 0.010),
            c(Resource.SEMEN, 3.250),
        ),
    )

    /** Which analyses price each takeoff line. A list, because PLS.01 is
     * plaster AND skim coat on the same m2. Every takeoff code must appear
     * here or in NOT_PRICED -- the harness asserts it, so a new takeoff line
     * cannot slip through unpriced without anyone deciding that it should. */
    val FOR_TAKEOFF_CODE: Map<String, List<Analysis>> = linkedMapOf(
        "BET.01" to listOf(CONCRETE_K250),
        "BET.02" to listOf(CONCRETE_K250),
        "BET.03" to listOf(CONCRETE_K250),
        "BET.04" to listOf(CONCRETE_K250),
        "BEK.01" to listOf(FORM_COLUMN),
        "BEK.02" to listOf(FORM_BEAM),
        "BEK.03" to listOf(FORM_WALL),
        "BEK.04" to listOf(FORM_SLOOF),
        "BSI.01" to listOf(REBAR),
        "PAS.01" to listOf(AAC_150),
        "PAS.02" to listOf(BRICK_HALF_1_4),
        "PLS.01" to listOf(PLASTER_1_4_15, ACIAN),
    )

    val NOT_PRICED: Map<String, String> = mapOf(
        "ATP.01" to "plan area only; the roof structure and covering are outside the " +
                "takeoff, and a RAB bills the SLOPED area, so the two are not comparable",
    )

    /** Materials and labour for the whole checked scope: coefficient x takeoff
     * NET quantity. Needs no prices at all, and is the most directly
     * checkable output here -- cement sacks can be counted as they arrive. */
    fun resources(takeoff: TakeoffResult): Map<Resource, Double> {
        val acc = LinkedHashMap<Resource, Double>()
        for (r in Resource.entries) acc[r] = 0.0
        for ((code, analyses) in FOR_TAKEOFF_CODE) {
            val q = takeoff.line(code).net
            for (a in analyses) for (co in a.coefficients)
                acc[co.resource] = acc.getValue(co.resource) + q * co.perUnit
        }
        return acc.filterValues { it != 0.0 }
    }
}

/** Prices the owner collected. A resource ABSENT from `prices` has no quote;
 * a resource present at 0.0 is genuinely free. The two are kept distinct on
 * purpose -- see the file header. */
data class PriceList(
    val prices: Map<Resource, Double>,
    val overheadProfitPct: Double = Ahsp.MAX_OVERHEAD_PROFIT_PCT,
    val source: String = "",
) {
    fun missing(analyses: List<Analysis>): List<Resource> =
        analyses.flatMap { a -> a.coefficients.map { it.resource } }
            .distinct().filter { it !in prices }

    /** Null when any resource is unquoted. Never a partial sum. */
    fun unitPrice(a: Analysis): UnitPrice? {
        if (missing(listOf(a)).isNotEmpty()) return null
        var labour = 0.0
        var material = 0.0
        for (co in a.coefficients) {
            val v = co.perUnit * prices.getValue(co.resource)
            if (co.resource.kind == ResourceKind.LABOUR) labour += v else material += v
        }
        return UnitPrice(
            labour, material, material * a.equipmentPctOfMaterial / 100.0,
            overheadProfitPct
        )
    }

    fun unitPrice(analyses: List<Analysis>): UnitPrice? =
        analyses.map { unitPrice(it) ?: return null }.reduce(UnitPrice::plus)
}

/** Owner-supplied input that could not be read. Carries every problem, not
 * just the first, so one round trip through adb fixes the whole file. */
class InputError(val source: String, val problems: List<String>) :
    IllegalArgumentException("$source: " + problems.joinToString("; "))

/** Number parsing for the owner's files, strict on purpose.
 *
 * The trap is the separator. Indonesian writes 1.250.000 for 1250000 and
 * 19,28 for 19.28; a computer reads "1.500" as one and a half. A price file
 * that silently read Rp 1.500 as Rp 1.5 would make every cement-based item
 * look a thousand times cheaper and every contractor a thief. So rupiah must
 * be whole and may not contain a dot or a comma at all, and decimals may not
 * contain a comma. Both fail loudly with the line number. */
internal object OwnerInput {
    private val GROUPING = Regex("[_  ]")
    private val RP_PREFIX = Regex("^[Rr][Pp]\\.?\\s*")

    fun rupiah(s: String): Double {
        val t = s.trim().replace(RP_PREFIX, "")
        require(t.isNotEmpty()) { "no amount" }
        require('.' !in t && ',' !in t) {
            "'$t': write whole rupiah without dots or commas (1250000 or 1_250_000)"
        }
        val d = t.replace(GROUPING, "")
        require(d.matches(Regex("\\d+"))) { "'$t' is not a whole number of rupiah" }
        return d.toDouble()
    }

    fun decimal(s: String): Double {
        val t = s.trim()
        require(',' !in t) { "'$t': use a point for decimals (19.28), not a comma" }
        require(t.matches(Regex("\\d+(\\.\\d+)?"))) { "'$t' is not a plain decimal number" }
        return t.toDouble()
    }

    /** "62_000" or "62_000 / 40": a pack price divided down to a unit price. */
    fun price(s: String): Double {
        val parts = s.split('/')
        require(parts.size <= 2) { "'$s': at most one /" }
        val rp = rupiah(parts[0])
        if (parts.size == 1) return rp
        val q = decimal(parts[1])
        require(q > 0.0) { "'$s': divide by a positive quantity" }
        return rp / q
    }
}

object PriceFile {

    fun parse(text: String): PriceList {
        val problems = mutableListOf<String>()
        val prices = LinkedHashMap<Resource, Double>()
        var op = Ahsp.MAX_OVERHEAD_PROFIT_PCT
        var source = ""
        val byKey = Resource.entries.associateBy { it.key }
        val seen = mutableSetOf<String>()
        text.lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq < 0) {
                problems += "line $n: expected KEY = value"; return@forEachIndexed
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (!seen.add(key)) {
                problems += "line $n: $key is given twice"; return@forEachIndexed
            }
            try {
                when (key) {
                    "source" -> source = value
                    "overhead_profit_pct" -> {
                        op = OwnerInput.decimal(value)
                        require(op <= 100.0) { "$op% is not a plausible overhead and profit" }
                    }
                    else -> {
                        val r = byKey[key]
                        // An unknown key is an error, not a warning: "M.SEMEM" must not
                        // quietly leave cement unpriced while looking filled in.
                        if (r == null) problems += "line $n: unknown key '$key'"
                        else if (value.isNotEmpty()) prices[r] = OwnerInput.price(value)
                    }
                }
            } catch (e: IllegalArgumentException) {
                problems += "line $n: $key: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw InputError("prices", problems)
        return PriceList(prices, op, source)
    }

    /** Every key, no prices. Generated rather than committed, so it cannot
     * drift from the Resource list. */
    fun template(): String = buildString {
        appendLine("# Griya Sakha price list: YOUR local quotes, not the contractor's.")
        appendLine("#   KEY = rupiah            format: L.PEKERJA = 123_456")
        appendLine("#   KEY = rupiah / qty      format: M.SEMEN = 65_432 / 40   (per 40 kg sack)")
        appendLine("# Whole rupiah, digits only; _ or a space may group the digits.")
        appendLine("# \"1.500\" is REJECTED on purpose: 1500 in Indonesian, 1.5 to a computer.")
        appendLine("# Blank = no quote yet, and every item that needs it stays unpriced.")
        appendLine("# 0 is a real price (well water): write the 0, do not leave it blank.")
        appendLine()
        appendLine("source = ")
        appendLine("# Overhead and profit, percent. 15 is the Permen PUPR 1/2022 maximum.")
        appendLine("overhead_profit_pct = ${Ahsp.MAX_OVERHEAD_PROFIT_PCT.toInt()}")
        for (kind in ResourceKind.entries) {
            appendLine()
            appendLine(if (kind == ResourceKind.LABOUR) "# Labour, per OH (one worker-day)" else "# Materials")
            for (r in Resource.entries.filter { it.kind == kind }) appendLine(
                String.format(
                    Locale.ROOT, "%-20s =              # %-3s %s: %s",
                    r.key, r.unit, r.label, r.quoteHint
                )
            )
        }
    }
}
