package griyasakha

import java.io.File
import java.util.Locale
import kotlin.math.abs

/** A contractor's RAB, set line by line against the takeoff and the AHSP
 * reference built from the owner's own prices.
 *
 * WHAT IT ANSWERS. For every RAB item that measures something the takeoff
 * also measures: is the VOLUME right, and is the UNIT PRICE defensible? Those
 * are separate questions with separate remedies -- a padded volume is a
 * measurement to redo, a high unit price is a negotiation -- so the money gap
 * is split exactly into the two:
 *
 *     RAB - reference = (V_rab - V_ref) x P_rab  +  V_ref x (P_rab - P_ref)
 *                       \_____ volume _______/     \_______ price ______/
 *
 * That identity is exact, not an approximation, and the harness asserts it.
 *
 * WHAT IT DOES NOT. Most of a house RAB is outside the takeoff (foundation,
 * roof, floor, doors, MEP, finishes). Those lines are entered with code "-"
 * and reported as unchecked, so the report always says what fraction of the
 * money it actually looked at. A report that checked 30% of the RAB must not
 * read as a verdict on 100% of it.
 */

data class RabLine(
    val lineNo: Int,
    /** Takeoff codes this RAB item measures; empty for "-" (not checkable). */
    val codes: List<String>,
    val description: String,
    val uomText: String,
    val volume: Double,
    val unitPriceRp: Double,
) {
    val amount: Double get() = volume * unitPriceRp
    val isChecked: Boolean get() = codes.isNotEmpty()
}

/** Where the contractor's lines come from: the pipe-separated rab.txt, or the
 * owner's BoQ workbook filled in by the contractor (BoqWorkbook). */
sealed interface RabInput {
    class Text(val text: String) : RabInput
    class Workbook(val bytes: ByteArray) : RabInput
}

enum class RabFlag { VOLUME_HIGH, VOLUME_LOW, MATCHES_GROSS, PRICE_HIGH, PRICE_LOW, AT_K175_PRICE }

/** Every RAB line naming the same set of takeoff codes, summed, against the
 * sum of those codes' takeoff quantities. */
data class RabGroup(
    val codes: List<String>,
    val lines: List<RabLine>,
    val uom: Uom,
    val takeoffNet: Double,
    val takeoffGross: Double,
    /** The takeoff net priced at the owner's prices + AHSP; null if any
     * resource is unquoted (or no price list was given). */
    val referenceAmount: Double?,
    val missing: List<Resource>,
    /** What K175 would cost per m3, set only for all-concrete groups. */
    val k175UnitPrice: Double?,
) {
    val contractorVolume: Double get() = lines.sumOf { it.volume }
    val contractorAmount: Double get() = lines.sumOf { it.amount }
    val contractorUnitPrice: Double get() = contractorAmount / contractorVolume
    val referenceUnitPrice: Double? get() = referenceAmount?.let { it / takeoffNet }
    val volumeGap: Double get() = contractorVolume / takeoffNet - 1.0
    val priceGap: Double? get() = referenceUnitPrice?.let { contractorUnitPrice / it - 1.0 }
    val volumeEffect: Double get() = (contractorVolume - takeoffNet) * contractorUnitPrice
    val priceEffect: Double? get() = referenceUnitPrice?.let { takeoffNet * (contractorUnitPrice - it) }

    val flags: List<RabFlag>
        get() = buildList {
            if (volumeGap > RabComparison.VOLUME_HIGH_TOL) add(RabFlag.VOLUME_HIGH)
            if (volumeGap < -RabComparison.VOLUME_LOW_TOL) add(RabFlag.VOLUME_LOW)
            val tol = RabComparison.GROSS_MATCH_TOL
            if (takeoffGross > takeoffNet * (1 + tol) &&
                abs(contractorVolume / takeoffGross - 1.0) <= tol
            ) add(RabFlag.MATCHES_GROSS)
            priceGap?.let {
                if (it > RabComparison.PRICE_HIGH_TOL) add(RabFlag.PRICE_HIGH)
                if (it < -RabComparison.PRICE_LOW_TOL) add(RabFlag.PRICE_LOW)
            }
            if (k175UnitPrice != null && contractorUnitPrice <= k175UnitPrice) add(RabFlag.AT_K175_PRICE)
        }
}

data class RabResult(
    val groups: List<RabGroup>,
    val unchecked: List<RabLine>,
    /** Priced takeoff codes no RAB line names: bundled into some other line,
     * or missing from the RAB altogether. Either way, worth a question. */
    val notInRab: List<String>,
) {
    val checkedTotal: Double get() = groups.sumOf { it.contractorAmount }
    val uncheckedTotal: Double get() = unchecked.sumOf { it.amount }
    val rabTotal: Double get() = checkedTotal + uncheckedTotal

    private val priced: List<RabGroup> get() = groups.filter { it.referenceAmount != null }
    val pricedContractor: Double get() = priced.sumOf { it.contractorAmount }
    val pricedReference: Double get() = priced.sumOf { it.referenceAmount!! }
    val gap: Double get() = pricedContractor - pricedReference
    val gapFromVolume: Double get() = priced.sumOf { it.volumeEffect }
    val gapFromPrice: Double get() = priced.sumOf { it.priceEffect!! }
}

object RabComparison {

    /** +5%. The takeoff net is geometry; steel already carries its lap/waste
     * factor and the AHSP material coefficients carry their own 5-20% waste
     * (SNI 7394 5.2b), so no waste is left to claim on the VOLUME. The band
     * absorbs rounding and small differences in measuring convention. */
    const val VOLUME_HIGH_TOL = 0.05

    /** -10%. Below this the RAB measures less than the drawing needs. */
    const val VOLUME_LOW_TOL = 0.10

    const val GROSS_MATCH_TOL = 0.01

    /** +10%, on top of a reference that already includes the MAXIMUM 15%
     * overhead and profit. */
    const val PRICE_HIGH_TOL = 0.10

    /** -20%: cheap enough to ask what specification the price buys. */
    const val PRICE_LOW_TOL = 0.20

    private val CODE = Regex("[A-Z]{3}\\.\\d{2}")

    /** "-" -> no codes; "BET.01+BET.02" -> both. Null when the field is not a
     * code list at all (a section heading, a total row). */
    internal fun parseCodes(field: String): List<String>? {
        val f = field.trim()
        if (f == "-") return emptyList()
        val codes = f.split('+').map { it.trim() }
        return if (codes.all { it.matches(CODE) } && codes.size == codes.toSet().size) codes else null
    }

    fun parseUom(s: String): Uom? = when (s.trim().lowercase(Locale.ROOT)) {
        "m3", "m³" -> Uom.M3
        "m2", "m²" -> Uom.M2
        "m'", "m’", "m1", "m" -> Uom.M
        "kg" -> Uom.KG
        else -> null
    }

    /** Syntax only; what the codes MEAN is checked by compare(), which has
     * the takeoff to check them against. */
    fun parse(text: String): List<RabLine> {
        val problems = mutableListOf<String>()
        val out = mutableListOf<RabLine>()
        text.lines().forEachIndexed { i, raw ->
            val n = i + 1
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val f = line.split('|', '\t').map { it.trim() }
            if (f.size != 5) {
                problems += "line $n: expected 5 fields (code | description | unit | " +
                        "volume | unit price), found ${f.size}"
                return@forEachIndexed
            }
            val codes = if (f[0] == "-") emptyList() else f[0].split('+').map { it.trim() }
            val bad = codes.filter { !it.matches(CODE) }
            if (bad.isNotEmpty()) problems += "line $n: '${bad.joinToString()}' is not a " +
                    "takeoff code like BET.01 (use - for a line this app cannot check)"
            if (codes.size != codes.toSet().size) problems += "line $n: a code is repeated"
            try {
                val v = OwnerInput.decimal(f[3])
                require(v > 0.0) { "volume must be positive" }
                val p = OwnerInput.rupiah(f[4])
                out += RabLine(n, codes, f[1], f[2], v, p)
            } catch (e: IllegalArgumentException) {
                problems += "line $n: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw InputError("rab", problems)
        return out
    }

    fun compare(takeoff: TakeoffResult, prices: PriceList?, lines: List<RabLine>): RabResult {
        val problems = mutableListOf<String>()
        val codeUom = takeoff.lines.associate { it.code to it.uom }
        val valid = Ahsp.FOR_TAKEOFF_CODE.keys.joinToString(" ")
        val checked = lines.filter { it.isChecked }

        for (l in checked) {
            val uom = parseUom(l.uomText)
            if (uom == null) problems += "line ${l.lineNo}: unit '${l.uomText}' is not m3, m2, m' or kg"
            for (c in l.codes) when {
                c in Ahsp.NOT_PRICED -> problems += "line ${l.lineNo}: $c ${Ahsp.NOT_PRICED[c]}"
                c !in codeUom -> problems += "line ${l.lineNo}: unknown code $c (valid: $valid)"
                uom != null && codeUom[c] != uom -> problems +=
                    "line ${l.lineNo}: $c is measured in ${codeUom[c]!!.label}, this RAB line in ${uom.label}"
            }
        }
        // A takeoff code may belong to ONE code set. "BET.01" on one line and
        // "BET.01+BET.02" on another would count the columns twice.
        val setOfCode = mutableMapOf<String, Pair<List<String>, Int>>()
        for (l in checked) {
            val key = l.codes.sorted()
            for (c in key) {
                val prev = setOfCode[c]
                if (prev == null) setOfCode[c] = key to l.lineNo
                else if (prev.first != key) problems += "line ${l.lineNo}: $c is also in " +
                        "${prev.first.joinToString("+")} on line ${prev.second}; " +
                        "a takeoff line can belong to one code set only"
            }
        }
        if (problems.isNotEmpty()) throw InputError("rab", problems)

        val groups = checked.groupBy { it.codes.sorted() }.map { (codes, ls) ->
            val analyses = codes.flatMap { Ahsp.FOR_TAKEOFF_CODE.getValue(it) }
            val reference = prices?.let { p ->
                codes.sumOf { c ->
                    val u = p.unitPrice(Ahsp.FOR_TAKEOFF_CODE.getValue(c)) ?: return@let null
                    u.total * takeoff.line(c).net
                }
            }
            val allConcrete = codes.all { Ahsp.FOR_TAKEOFF_CODE[it] == listOf(Ahsp.CONCRETE_K250) }
            RabGroup(
                codes = codes,
                lines = ls,
                uom = codeUom.getValue(codes.first()),
                takeoffNet = codes.sumOf { takeoff.line(it).net },
                takeoffGross = codes.sumOf { takeoff.line(it).gross },
                referenceAmount = reference,
                missing = prices?.missing(analyses) ?: emptyList(),
                k175UnitPrice = if (allConcrete) prices?.unitPrice(Ahsp.CONCRETE_K175)?.total else null,
            )
        }
        val named = groups.flatMap { it.codes }.toSet()
        return RabResult(
            groups,
            lines.filter { !it.isChecked },
            Ahsp.FOR_TAKEOFF_CODE.keys.filter { it !in named },
        )
    }

    /** The RAB file template, with the current takeoff printed into its
     * comments so the owner can map lines without switching pages. */
    fun template(takeoff: TakeoffResult): String = buildString {
        appendLine("# Contractor RAB: one line per RAB item, five fields split by | (or tab)")
        appendLine("#   code | description | unit | volume | unit price")
        appendLine("#")
        appendLine("# code        which takeoff line(s) the item measures:")
        appendLine("#               BET.01          one takeoff line")
        appendLine("#               BET.01+BET.02   one item covering several, same unit")
        appendLine("#               -               anything this app cannot check")
        appendLine("#             Several items may name the same code; they are summed.")
        appendLine("# unit        m3, m2, m' (or m1), kg. Anything when the code is -.")
        appendLine("# volume      decimal POINT: 19.28. A comma is rejected.")
        appendLine("# unit price  whole rupiah; _ or a space may group: 1_250_000.")
        appendLine("#             \"1.250.000\" is rejected: to a computer that is 1250.")
        appendLine("#")
        appendLine("# Enter EVERY item, the uncheckable ones as -, so the report can say")
        appendLine("# how much of the money it actually looked at. Format examples only:")
        appendLine("#   BSI.01 | Pembesian kolom, balok, sloof | kg | 1950.5 | 12_345")
        appendLine("#   -      | Pondasi batu kali 1:5          | m3 | 12.5   | 1_234_567")
        appendLine("#")
        appendLine("# Takeoff lines (NET quantity, as on the Bill page):")
        for (l in takeoff.lines) {
            val why = Ahsp.NOT_PRICED[l.code]
            appendLine(
                if (why != null) "#   ${l.code}  not comparable: $why"
                else String.format(
                    Locale.ROOT, "#   %-7s %-3s %10.3f  %s",
                    l.code, l.uom.label, l.net, l.description
                )
            )
        }
    }

    // ---------------- Reporting ----------------

    /** Rupiah grouped with spaces: "Rp 1 250 000". Spaces are unambiguous in
     * both conventions, where "1.250.000" and "1,250,000" each mean something
     * else to half the readers. Never passed through wrap(), which splits on
     * spaces. */
    internal fun rp(v: Double, signed: Boolean = false): String =
        "Rp " + String.format(Locale.ROOT, if (signed) "%+,d" else "%,d", Math.round(v))
            .replace(',', ' ')

    /** "+0.0%" rather than "-0.0%" for a gap that is zero but for rounding. */
    private fun pct(v: Double) =
        if (abs(v) < 5e-4) "+0.0%" else String.format(Locale.ROOT, "%+.1f%%", v * 100.0)
    private fun num(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    fun report(
        input: TakeoffInput,
        pricesText: String?,
        rab: RabInput?,
        style: QuantityTakeoff.ReportStyle,
        whereFiles: String,
    ): String {
        val sb = StringBuilder()
        val w = style.width
        val lw = if (style == QuantityTakeoff.ReportStyle.NARROW) 9 else 14
        fun rule(c: String = "=") = sb.appendLine(c.repeat(w))
        fun para(text: String, first: String = "", cont: String = first) {
            for (x in QuantityTakeoff.wrap(text, w, first, cont)) sb.appendLine(x)
        }
        fun row(label: String, value: String, indent: String = "  ") =
            sb.appendLine("$indent${label.padEnd(lw)} $value")

        val takeoff = QuantityTakeoff.compute(input)
        val placeholderSchedule = input.schedule == RcSchedule()
        val noOpenings = input.openings.isEmpty()

        rule()
        sb.appendLine("RAB CHECK")
        para("unit rates: SNI 7394:2008, SNI 2837:2008, Permen PUPR 1/2022 Lampiran IV")
        rule()

        // ---- Prices
        var prices: PriceList? = null
        sb.appendLine("PRICES")
        if (pricesText == null) {
            para("None loaded, so only volumes can be checked. $whereFiles", "  ", "  ")
        } else try {
            val p = PriceFile.parse(pricesText)
            prices = p
            row("source", p.source.ifBlank { "(not stated -- date your quotes)" })
            row(
                "O&P", String.format(Locale.ROOT, "%.1f%%", p.overheadProfitPct) +
                        if (p.overheadProfitPct > Ahsp.MAX_OVERHEAD_PROFIT_PCT) " ABOVE the 15% max"
                        else " (max 15%)"
            )
            val needed = Ahsp.FOR_TAKEOFF_CODE.values.flatten().flatMap { a -> a.coefficients.map { it.resource } }.toSet()
            row("quoted", "${needed.count { it in p.prices }} of ${needed.size} needed")
            val miss = needed.filter { it !in p.prices }
            if (miss.isNotEmpty()) para("missing: " + miss.joinToString(", ") { it.key }, "  ", "    ")
        } catch (e: InputError) {
            sb.appendLine("  PRICE LIST REJECTED -- nothing below is priced")
            for (x in e.problems) para("- $x", "  ", "    ")
        }
        sb.appendLine()
        rule("-")

        // ---- Resources, price-free
        sb.appendLine("MATERIALS AND LABOUR, checked scope")
        para("AHSP coefficient x takeoff net. Needs no prices: count these as they arrive on site.")
        val res = Ahsp.resources(takeoff)
        for ((r, q) in res) {
            sb.appendLine(String.format(Locale.ROOT, "  %-20s %10.2f %s", r.label, q, r.unit))
            if (r == Resource.SEMEN) sb.appendLine(
                String.format(Locale.ROOT, "    = %.1f sacks of 40 kg, %.1f of 50", q / 40.0, q / 50.0)
            )
        }
        sb.appendLine()
        rule("-")

        // ---- Reference unit prices
        if (prices != null) {
            sb.appendLine("REFERENCE: AHSP at your prices")
            var total = 0.0
            val unpriced = mutableListOf<String>()
            for (l in takeoff.lines) {
                val analyses = Ahsp.FOR_TAKEOFF_CODE[l.code]
                if (analyses == null) {
                    para("${l.code}  not priced: ${Ahsp.NOT_PRICED[l.code]}", "", "        ")
                    sb.appendLine()
                    continue
                }
                para("${l.code}  ${l.description}", "", "        ")
                val u = prices.unitPrice(analyses)
                if (u == null) {
                    unpriced += l.code
                    para("unpriced, needs " + prices.missing(analyses).joinToString(", ") { it.key }, "    ", "      ")
                } else {
                    total += u.total * l.net
                    row("unit", "${rp(u.total)} /${l.uom.label}", "    ")
                    row("amount", "${rp(u.total * l.net)} for ${num(l.net)}", "    ")
                    row("labour", rp(u.labour), "    ")
                    row("material", rp(u.material + u.equipment), "    ")
                    row("O&P", rp(u.overheadProfit), "    ")
                }
                for (a in analyses) para(
                    (if (a.derivation != null) "DERIVED from " else "") + a.ref, "    ", "      "
                )
                sb.appendLine()
            }
            row("TOTAL", rp(total), "")
            if (unpriced.isNotEmpty()) para(
                "PARTIAL: leaves out ${unpriced.joinToString(", ")}, which are unpriced", "  ", "  "
            )
            sb.appendLine()
            rule("-")
        }

        // ---- Contractor RAB
        var result: RabResult? = null
        sb.appendLine("CONTRACTOR RAB")
        val lineWord = if (rab is RabInput.Workbook) "BoQ row" else "RAB line"
        if (rab == null) {
            para("None loaded. $whereFiles", "  ", "  ")
        } else try {
            val lines = when (rab) {
                is RabInput.Text -> parse(rab.text)
                is RabInput.Workbook -> BoqWorkbook.read(rab.bytes)
            }
            val r = compare(takeoff, prices, lines)
            result = r
            sb.appendLine()
            if (r.groups.isEmpty() && r.unchecked.isEmpty())
                para("No priced rows yet: nothing in it has a unit price.", "  ", "  ")
            for (g in r.groups) {
                para("${g.codes.joinToString("+")}  (${g.uom.label})", "", "  ")
                para(
                    "$lineWord${if (g.lines.size > 1) "s" else ""} " +
                            g.lines.joinToString(", ") { "${it.lineNo}" } + ": " +
                            g.lines.joinToString("; ") { it.description }, "  ", "    "
                )
                row("volume", "${num(g.contractorVolume)} RAB", "  ")
                row("", "${num(g.takeoffNet)} takeoff  ${pct(g.volumeGap)}", "  ")
                row("price", "${rp(g.contractorUnitPrice)} RAB", "  ")
                val ref = g.referenceUnitPrice
                if (ref != null) row("", "${rp(ref)} ref  ${pct(g.priceGap!!)}", "  ")
                else row("", if (prices == null) "no price list" else "unpriced", "  ")
                row("amount", "${rp(g.contractorAmount)} RAB", "  ")
                if (g.referenceAmount != null) {
                    row("", "${rp(g.referenceAmount)} ref", "  ")
                    row("gap", rp(g.contractorAmount - g.referenceAmount, true), "  ")
                    row(" volume", rp(g.volumeEffect, true), "  ")
                    row(" price", rp(g.priceEffect!!, true), "  ")
                } else {
                    row("by volume", rp(g.volumeEffect, true), "  ")
                }
                for (f in g.flags) para("! " + explain(f, g, placeholderSchedule, noOpenings), "  ", "    ")
                sb.appendLine()
            }

            rule("-")
            sb.appendLine("SUMMARY")
            row("RAB total", rp(r.rabTotal))
            row("checked", rp(r.checkedTotal) + share(r.checkedTotal, r.rabTotal))
            row("unchecked", rp(r.uncheckedTotal) + share(r.uncheckedTotal, r.rabTotal))
            if (prices != null && r.groups.any { it.referenceAmount != null }) {
                sb.appendLine()
                sb.appendLine("  checked and priced:")
                row("RAB", rp(r.pricedContractor))
                row("reference", rp(r.pricedReference))
                row("gap", rp(r.gap, true))
                row(" volume", rp(r.gapFromVolume, true))
                row(" price", rp(r.gapFromPrice, true))
            }
            val unpricedGroups = r.groups.filter { it.referenceAmount == null }
            if (prices != null && unpricedGroups.isNotEmpty()) para(
                "not priced (a resource has no quote): " +
                        unpricedGroups.joinToString(", ") { it.codes.joinToString("+") }, "  ", "    "
            )
            if (r.notInRab.isNotEmpty()) para(
                "Takeoff lines no RAB item names: ${r.notInRab.joinToString(", ")}. " +
                        "Bundled into another item, or missing? Ask.", "  ", "    "
            )
        } catch (e: InputError) {
            sb.appendLine("  RAB REJECTED")
            for (x in e.problems) para("- $x", "  ", "    ")
        }
        sb.appendLine()
        rule("-")

        // ---- Caveats: printed every time, like the takeoff's NOT INCLUDED.
        sb.appendLine("READ BEFORE QUOTING ANY OF THIS")
        para("- Scope is the structural frame and walls only. Not in the takeoff:", "  ", "    ")
        for (x in takeoff.excluded) para("* $x", "    ", "      ")
        val caveats = mutableListOf<String>()
        if (placeholderSchedule) caveats += "BSI.01 uses the PLACEHOLDER rebar schedule " +
                "(SNI/PUPR practical minimums), not the drawing. Until RcSchedule is set " +
                "from the drawing, a steel gap is a question, not a finding."
        if (noOpenings) caveats += "With no openings entered, a RAB volume BELOW PAS.01, " +
                "PAS.02 and PLS.01 is expected, not suspicious."
        caveats += "Steel is priced generously: AHSP's 10.5 kg per 10 kg is a cutting " +
                "allowance on top of the takeoff's own x${input.schedule.lapAndWastageFactor} " +
                "lap and waste factor."
        caveats += "Formwork is the most generous line. The AHSP timber items carry a full " +
                "set of boards, plywood and 2-3 props per m2 and state no reuse (PUPR gives " +
                "use counts only for its precast moulds, A.4.1.2), while real formwork is " +
                "reused. A BEK price well under the reference is normal; it also weighs " +
                "heavily in the priced total, so read the gap line by line."
        caveats += "PAS.01 (15 cm AAC) has no published AHSP item. Its rate is derived, " +
                "from the 10 cm item and the MU-380 mortar data sheet, and says DERIVED."
        val concreteNet = takeoff.lines.filter { it.code.startsWith("BET") }.sumOf { it.net }
        caveats += "SNI 7394:2008 5.2(e): the lump items 6.28-6.36 (e.g. 'kolom beton " +
                "bertulang, 300 kg besi + bekisting' per m3) are for plans WITHOUT detail " +
                "drawings. This house has them, and its steel is " +
                String.format(Locale.ROOT, "%.0f", takeoff.line("BSI.01").net / concreteNet) +
                " kg per m3 of concrete. A RAB priced on a lump item is billing an assumed steel content."
        caveats += "AHSP labour coefficients are national reference productivity (5 effective " +
                "hours a day, SNI 7394 5.2c) and usually generous next to a village crew. At " +
                "or under the reference is not proof of a fair price; well over it is hard to defend."
        caveats += "The reference is only as good as your quotes. Get two per material and " +
                "put the date in the price file's source line."
        for (x in caveats) para("- $x", "  ", "    ")
        rule()
        return sb.toString()
    }

    private fun share(part: Double, whole: Double) =
        if (whole > 0) String.format(Locale.ROOT, " %.0f%%", part / whole * 100.0) else ""

    private fun explain(f: RabFlag, g: RabGroup, placeholderSchedule: Boolean, noOpenings: Boolean): String {
        val finishes = g.codes.any { it.startsWith("PAS") || it.startsWith("PLS") }
        val steel = "BSI.01" in g.codes
        return when (f) {
            RabFlag.VOLUME_HIGH -> "VOLUME HIGH: more than 5% over the takeoff net. Ask for " +
                    "their measurement sheet (backup volume)." +
                    if (steel && placeholderSchedule) " (The steel reference is still the placeholder schedule.)" else ""

            RabFlag.MATCHES_GROSS -> "MATCHES GROSS: the RAB volume equals the takeoff GROSS within " +
                    "1%, so the overlaps listed as 'less:' on the Bill page were not deducted " +
                    "and that part is billed twice."

            RabFlag.VOLUME_LOW -> "VOLUME LOW: more than 10% under the takeoff. Either they " +
                    "measured less than the drawing shows, or the difference returns later as " +
                    "pekerjaan tambah. Ask which." +
                    (if (steel) " Less steel than the schedule is also how under-reinforcement " +
                            "starts: see the Site page." else "") +
                    (if (finishes && noOpenings) " (Expected here: no openings are entered yet.)" else "") +
                    (if (steel && placeholderSchedule) " (The steel reference is still the placeholder schedule.)" else "")

            RabFlag.PRICE_HIGH -> "PRICE HIGH: more than 10% over AHSP at your prices, a " +
                    "reference that already includes the maximum 15% overhead and profit. Ask " +
                    "for their unit-price analysis (analisa harga satuan) and compare it " +
                    "coefficient by coefficient."

            RabFlag.PRICE_LOW -> "PRICE LOW: more than 20% under the reference. " +
                    if (g.codes.all { it.startsWith("BEK") })
                        "Normal for formwork, whose AHSP items carry a full set of timber " +
                                "per m2 and state no reuse."
                    else "Fine if their labour is cheaper, but ask what specification " +
                            "that price buys (grade, mix, thickness)."

            RabFlag.AT_K175_PRICE -> "AT K175 PRICE: this 'K250' costs no more than K175 at your " +
                    "prices. Not proof of a weaker mix -- labour may simply be cheaper than AHSP -- " +
                    "but ask how much cement goes into one m3: K250 is 384 kg (9.6 sacks of 40 kg), " +
                    "K175 is 326 kg (8.2)."
        }
    }
}

/** Host entry point.
 *   java -cp griya.jar griyasakha.RabComparisonKt [prices.txt|- [rab.txt|boq.xlsx]]
 *   java -cp griya.jar griyasakha.RabComparisonKt --templates DIR */
fun main(args: Array<String>) {
    val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
    val input = TakeoffInput(grid)
    if (args.size == 2 && args[0] == "--templates") {
        val dir = File(args[1]).apply { mkdirs() }
        File(dir, "prices.template.txt").writeText(PriceFile.template())
        File(dir, "rab.template.txt").writeText(RabComparison.template(QuantityTakeoff.compute(input)))
        println("wrote prices.template.txt and rab.template.txt to ${dir.path}")
        return
    }
    val prices = args.getOrNull(0)?.takeIf { it != "-" }?.let { File(it).readText() }
    val rab = args.getOrNull(1)?.takeIf { it != "-" }?.let {
        if (it.endsWith(".xlsx", ignoreCase = true)) RabInput.Workbook(File(it).readBytes())
        else RabInput.Text(File(it).readText())
    }
    println(
        RabComparison.report(
            input, prices, rab, QuantityTakeoff.ReportStyle.WIDE,
            "Pass the files as arguments: RabComparisonKt prices.txt rab.txt|boq.xlsx " +
                    "(- for none); --templates DIR writes blank ones."
        )
    )
}
