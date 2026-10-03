package griyasakha

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The owner's bill of quantities (BoQ) as an .xlsx workbook: the contractor
 * prices OUR structure, and the filled workbook goes straight into the RAB page.
 *
 * WHY. A contractor's own RAB has to be mapped line by line onto the takeoff
 * codes, and a line that bundles concrete, formwork and steel per m3 cannot be
 * mapped at all. Asking them to price this workbook instead makes the check
 * exact: one row per takeoff code, a "-" section for everything outside the
 * takeoff, and the owner-supplied special items (SO-02, SO-03) priced up front
 * so they cannot come back later as pekerjaan tambah.
 *
 * WITH OR WITHOUT QUANTITIES. Without (the default) the contractor measures,
 * and the RAB page tests their measurement as well as their prices -- that is
 * how billing at the gross measure gets caught. With quantities the comparison
 * becomes price-only. The owner decides per round.
 *
 * READING IT BACK is strict for the same reason OwnerInput is: a price typed
 * as the TEXT "1.250.000" is rejected with its row number. A real numeric cell
 * has no separator problem at all -- Excel stores the number, not the digits
 * the contractor's locale displayed -- which is the main gain over rab.txt.
 *
 * No dependency: an .xlsx is a zip of XML, written and read here with
 * java.util.zip and the platform DOM parser, which exist on the JVM and on
 * Android alike. The workbook comes from outside, so any part with a DOCTYPE
 * is refused before parsing (SpreadsheetML never has one).
 */

/** A cell as the writer emits it. */
internal sealed interface XCell {
    val style: Int

    data class Text(val s: String, override val style: Int = 0) : XCell
    data class Num(val v: Double, override val style: Int = 0) : XCell
    data class Formula(val f: String, override val style: Int = 0) : XCell
    data class Empty(override val style: Int) : XCell
}

internal class XSheet(
    val name: String,
    val rows: List<List<XCell?>>,
    val widths: List<Double>,
    val merges: List<String> = emptyList(),
    val freezeRows: Int = 0,
    val rowHeights: Map<Int, Double> = emptyMap(),
    /** A-column range restricted to `listValues` (Excel data validation). */
    val listRange: String? = null,
    val listValues: List<String> = emptyList(),
)

/** A cell as read back: text for strings, num for numbers, and a flag for a
 * formula whose result was never computed (no cached value in the file). */
internal data class RCell(val text: String?, val num: Double?, val uncomputed: Boolean)

internal object Xlsx {
    // Indices into cellXfs below.
    const val PLAIN = 0
    const val BOLD = 1
    const val TITLE = 2
    const val HEAD = 3
    const val TEXT = 4
    const val VOL = 5
    const val VOL_IN = 6
    const val RP_IN = 7
    const val RP_SUM = 8
    const val SECTION = 9
    const val TOTAL = 10
    const val WRAP = 11
    const val CODE = 12
    const val TEXT_IN = 13

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="$NS">
<numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0"/><numFmt numFmtId="165" formatCode="0.000"/></numFmts>
<fonts count="3"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font><font><b/><sz val="13"/><name val="Arial"/></font></fonts>
<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9D9D9"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDEBF7"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="14">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
<xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="165" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="164" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="0" fontId="1" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="164" fontId="1" fillId="2" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, ('A' + r))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun colIndex(letters: String): Int =
        letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun num(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** `sharedStrings` switches from inline strings to a shared-string table,
     * the form Excel and LibreOffice save in; the harness uses it to test the
     * reader against that layout too. */
    fun write(sheets: List<XSheet>, sharedStrings: Boolean = false): ByteArray {
        val sst = LinkedHashMap<String, Int>()
        val parts = LinkedHashMap<String, String>()
        sheets.forEachIndexed { si, sh -> parts["xl/worksheets/sheet${si + 1}.xml"] = sheetXml(sh, sharedStrings, sst) }
        val ct = StringBuilder()
        ct.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        ct.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        ct.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        ct.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        ct.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in sheets.indices) ct.append(
            """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        )
        ct.append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        if (sharedStrings) ct.append(
            """<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>"""
        )
        ct.append("</Types>")

        val wb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        wb.append("""<workbook xmlns="$NS" xmlns:r="$NS_R"><sheets>""")
        sheets.forEachIndexed { i, sh -> wb.append("""<sheet name="${esc(sh.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        wb.append("""</sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")

        val rels = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        rels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.forEachIndexed { i, _ ->
            rels.append("""<Relationship Id="rId${i + 1}" Type="$NS_R/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
        }
        rels.append("""<Relationship Id="rId${sheets.size + 1}" Type="$NS_R/styles" Target="styles.xml"/>""")
        if (sharedStrings) rels.append(
            """<Relationship Id="rId${sheets.size + 2}" Type="$NS_R/sharedStrings" Target="sharedStrings.xml"/>"""
        )
        rels.append("</Relationships>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", ct.toString())
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$NS_R/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )
            put("xl/workbook.xml", wb.toString())
            put("xl/_rels/workbook.xml.rels", rels.toString())
            put("xl/styles.xml", STYLES)
            for ((name, xml) in parts) put(name, xml)
            if (sharedStrings) {
                val s = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                s.append("""<sst xmlns="$NS" count="${sst.size}" uniqueCount="${sst.size}">""")
                for (t in sst.keys) s.append("""<si><t xml:space="preserve">${esc(t)}</t></si>""")
                s.append("</sst>")
                put("xl/sharedStrings.xml", s.toString())
            }
        }
        return out.toByteArray()
    }

    private fun sheetXml(sh: XSheet, shared: Boolean, sst: MutableMap<String, Int>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="$NS" xmlns:r="$NS_R">""")
        sb.append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        sb.append("""<sheetViews><sheetView workbookViewId="0">""")
        if (sh.freezeRows > 0) sb.append(
            """<pane ySplit="${sh.freezeRows}" topLeftCell="A${sh.freezeRows + 1}" activePane="bottomLeft" state="frozen"/>"""
        )
        sb.append("</sheetView></sheetViews>")
        sb.append("""<sheetFormatPr defaultRowHeight="15"/><cols>""")
        sh.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        sh.rows.forEachIndexed { ri, row ->
            val r = ri + 1
            val ht = sh.rowHeights[r]
            sb.append(if (ht != null) """<row r="$r" ht="$ht" customHeight="1">""" else """<row r="$r">""")
            row.forEachIndexed { ci, cell ->
                if (cell == null) return@forEachIndexed
                val ref = "${colName(ci)}$r"
                when (cell) {
                    is XCell.Text -> if (shared) {
                        val idx = sst.getOrPut(cell.s) { sst.size }
                        sb.append("""<c r="$ref" s="${cell.style}" t="s"><v>$idx</v></c>""")
                    } else {
                        sb.append("""<c r="$ref" s="${cell.style}" t="inlineStr"><is><t xml:space="preserve">${esc(cell.s)}</t></is></c>""")
                    }
                    is XCell.Num -> sb.append("""<c r="$ref" s="${cell.style}"><v>${num(cell.v)}</v></c>""")
                    is XCell.Formula -> sb.append("""<c r="$ref" s="${cell.style}"><f>${esc(cell.f)}</f></c>""")
                    is XCell.Empty -> sb.append("""<c r="$ref" s="${cell.style}"/>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (sh.merges.isNotEmpty()) {
            sb.append("""<mergeCells count="${sh.merges.size}">""")
            for (m in sh.merges) sb.append("""<mergeCell ref="$m"/>""")
            sb.append("</mergeCells>")
        }
        if (sh.listRange != null) {
            val list = "\"" + sh.listValues.joinToString(",") + "\""
            require(list.length <= 257) { "data-validation list longer than Excel allows" }
            sb.append("""<dataValidations count="1"><dataValidation type="list" allowBlank="1" showErrorMessage="1" sqref="${sh.listRange}"><formula1>${esc(list)}</formula1></dataValidation></dataValidations>""")
        }
        sb.append("""<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>""")
        sb.append("""<pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
        sb.append("</worksheet>")
        return sb.toString()
    }

    // ---------------- reading ----------------

    private const val MAX_PART = 20L * 1024 * 1024

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = z.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    total += n
                    require(buf.size() <= MAX_PART && total <= 4 * MAX_PART) { "workbook is implausibly large" }
                }
                parts[e.name.removePrefix("/")] = buf.toByteArray()
                require(parts.size <= 500) { "workbook has too many parts" }
            }
        }
        require(parts.isNotEmpty()) { "not an .xlsx (no zip entries)" }
        return parts
    }

    fun parse(xml: ByteArray): Document {
        require(!String(xml, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "a workbook part declares a DOCTYPE; refused"
        }
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Not every platform parser knows the feature; the raw check above is the guard.
        }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun Element.kids(local: String): List<Element> {
        val out = mutableListOf<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) out += n
        }
        return out
    }

    private fun Element.allText(local: String): String {
        val nl = getElementsByTagNameNS("*", local)
        val sb = StringBuilder()
        for (i in 0 until nl.length) sb.append(nl.item(i).textContent)
        return sb.toString()
    }

    /** Cells of the named sheet: row number -> column index -> cell. */
    fun readSheet(bytes: ByteArray, sheetName: String): Map<Int, Map<Int, RCell>> {
        val parts = unzip(bytes)
        val wb = parse(parts["xl/workbook.xml"] ?: error("no xl/workbook.xml")).documentElement
        val sheetsEl = wb.getElementsByTagNameNS("*", "sheet")
        var rid: String? = null
        for (i in 0 until sheetsEl.length) {
            val e = sheetsEl.item(i) as Element
            if (e.getAttribute("name").equals(sheetName, ignoreCase = true)) rid = e.getAttributeNS(NS_R, "id")
        }
        require(rid != null) { "no sheet named '$sheetName'" }
        val rels = parse(parts["xl/_rels/workbook.xml.rels"] ?: error("no workbook relationships")).documentElement
        val relEls = rels.getElementsByTagNameNS("*", "Relationship")
        var target: String? = null
        for (i in 0 until relEls.length) {
            val e = relEls.item(i) as Element
            if (e.getAttribute("Id") == rid) target = e.getAttribute("Target")
        }
        require(target != null) { "sheet '$sheetName' has no part" }
        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

        val shared = parts["xl/sharedStrings.xml"]?.let { sstBytes ->
            val sst = parse(sstBytes).documentElement
            sst.kids("si").map { it.allText("t") }
        } ?: emptyList()

        val sheet = parse(parts[path] ?: error("missing $path")).documentElement
        val out = sortedMapOf<Int, MutableMap<Int, RCell>>()
        val rows = sheet.getElementsByTagNameNS("*", "row")
        var lastRow = 0
        for (i in 0 until rows.length) {
            val row = rows.item(i) as Element
            val r = row.getAttribute("r").toIntOrNull() ?: (lastRow + 1)
            lastRow = r
            var lastCol = -1
            val cells = out.getOrPut(r) { mutableMapOf() }
            for (c in row.kids("c")) {
                val ref = c.getAttribute("r")
                val col = if (ref.isNotEmpty()) colIndex(ref.takeWhile { it.isLetter() }) else lastCol + 1
                lastCol = col
                val t = c.getAttribute("t")
                val v = c.kids("v").firstOrNull()?.textContent
                val hasF = c.kids("f").isNotEmpty()
                val cell = when (t) {
                    "s" -> RCell(v?.toIntOrNull()?.let { shared.getOrNull(it) }, null, false)
                    "inlineStr" -> RCell(c.allText("t"), null, false)
                    "str" -> RCell(v, null, hasF && v == null)
                    "b", "e" -> RCell(v ?: "", null, false)
                    else -> RCell(null, v?.toDoubleOrNull(), hasF && v == null)
                }
                cells[col] = cell
            }
        }
        return out
    }
}

object BoqWorkbook {

    const val SHEET = "BoQ"
    const val INSTRUCTIONS = "Petunjuk"
    private const val HEADER = "Kode"

    // Columns of the BoQ sheet.
    private const val C_CODE = 0
    private const val C_DESC = 1
    private const val C_SPEC = 2
    private const val C_UNIT = 3
    private const val C_VOL = 4
    private const val C_PRICE = 5
    private const val C_SUM = 6
    private const val C_NOTE = 7

    /** Indonesian item names, free of numbers on purpose: the dimensions and
     * the grade come from the takeoff's own description in the next column,
     * so they cannot drift. The harness asserts this covers exactly the
     * priced takeoff lines. */
    val URAIAN: Map<String, String> = linkedMapOf(
        "BET.01" to "Beton kolom",
        "BET.02" to "Beton balok / ring balok",
        "BET.03" to "Beton dinding geser",
        "BET.04" to "Beton sloof",
        "BEK.01" to "Bekisting kolom",
        "BEK.02" to "Bekisting balok (2 sisi + bawah)",
        "BEK.03" to "Bekisting dinding geser (2 muka)",
        "BEK.04" to "Bekisting sloof (2 sisi)",
        "BSI.01" to "Pembesian semua elemen (termasuk overlap dan sisa potong)",
        "PAS.01" to "Pasangan dinding bata ringan (dinding luar)",
        "PAS.02" to "Pasangan dinding bata merah (dinding dalam)",
        "PLS.01" to "Plesteran + acian, dua muka (bata dan dinding geser)",
    )

    /** Owner-supplied items, priced up front. "-" because the takeoff does not
     * measure them; the drawings do. */
    val SPECIAL = listOf(
        "Ruang meridian: siku L50.50.5 + angkur ke ring balok + pelat lubang (gambar SO-02)",
        "Atap polikarbonat bening di atas lubang, termasuk kedap air (SO-02)",
        "Strip kuningan/stainless 3 mm tanam rata lantai (SO-02)",
        "Tiang gnomon baja 60x60 + pondasi, lengan dan bola (SO-03)",
        "Pad gnomon datar 0.5 mm, pin kuningan, garis utara-selatan (SO-03)",
    )

    /** Everything else a house RAB carries, as prompts. */
    val OTHER = listOf(
        "Pekerjaan persiapan, termasuk papan bouwplank (uitzet oleh Pemilik: JANGAN dihitung)",
        "Pekerjaan tanah dan pondasi",
        "Pekerjaan atap (rangka, penutup, lisplang)",
        "Pekerjaan lantai",
        "Kusen, pintu dan jendela",
        "Plafon",
        "Instalasi listrik",
        "Instalasi air bersih, air kotor dan sanitasi",
        "Pengecatan",
        "Lain-lain",
    )
    private const val BLANK_OTHER_ROWS = 20

    fun write(
        takeoff: TakeoffResult,
        withQuantities: Boolean,
        date: String,
        /** Harness only: code -> (volume, unit price) written into the input cells. */
        prefill: Map<String, Pair<Double, Double>> = emptyMap(),
        sharedStrings: Boolean = false,
    ): ByteArray {
        val priced = takeoff.lines.filter { it.code in Ahsp.FOR_TAKEOFF_CODE }
        val rows = mutableListOf<List<XCell?>>()
        val T = XCell::Text
        rows.add(listOf(T("DAFTAR KUANTITAS DAN HARGA (BoQ): RUMAH GRIYA SAKHA", Xlsx.TITLE)))
        rows.add(listOf(T("Sumber, Jatipohon, Grobogan. Disusun Pemilik dari model rumah, $date. Baca lembar '$INSTRUCTIONS'.", Xlsx.PLAIN)))
        rows.add(listOf(T("Isi sel KUNING saja. Kode dan satuan jangan diubah. Harga satuan sudah termasuk bahan, upah, alat, biaya umum dan keuntungan, sebelum pajak." +
                if (withQuantities) " Volume bagian A diisi Pemilik." else " Volume bagian A diukur dan diisi kontraktor.", Xlsx.WRAP)))
        rows.add(emptyList())
        rows.add(listOf(
            T(HEADER, Xlsx.HEAD), T("Uraian pekerjaan", Xlsx.HEAD), T("Spesifikasi (dari model)", Xlsx.HEAD),
            T("Sat.", Xlsx.HEAD), T("Volume", Xlsx.HEAD), T("Harga satuan (Rp)", Xlsx.HEAD),
            T("Jumlah (Rp)", Xlsx.HEAD), T("Catatan kontraktor", Xlsx.HEAD),
        ))
        val headerRow = rows.size

        fun section(title: String) {
            rows.add(listOf(T(title, Xlsx.SECTION)) + List(7) { XCell.Empty(Xlsx.SECTION) })
        }

        fun item(code: String, desc: XCell, spec: String, unit: XCell, vol: XCell, price: XCell) {
            val r = rows.size + 1
            rows.add(listOf(
                T(code, Xlsx.CODE), desc, T(spec, Xlsx.TEXT), unit, vol, price,
                XCell.Formula("IF(OR(E$r=\"\",F$r=\"\"),\"\",E$r*F$r)", Xlsx.RP_SUM), XCell.Empty(Xlsx.TEXT_IN),
            ))
        }

        section("A. PEKERJAAN STRUKTUR DAN DINDING (dihitung ulang oleh Pemilik; lihat gambar struktur)")
        val firstItem = rows.size + 1
        for (l in priced) {
            val pre = prefill[l.code]
            val vol: XCell = when {
                pre != null -> XCell.Num(pre.first, Xlsx.VOL_IN)
                withQuantities -> XCell.Num(l.net, Xlsx.VOL)
                else -> XCell.Empty(Xlsx.VOL_IN)
            }
            val price: XCell = pre?.let { XCell.Num(it.second, Xlsx.RP_IN) } ?: XCell.Empty(Xlsx.RP_IN)
            item(l.code, T(URAIAN.getValue(l.code), Xlsx.TEXT), l.description, T(l.uom.label, Xlsx.TEXT), vol, price)
        }
        section("B. PEKERJAAN KHUSUS PEMILIK (gambar SO-02 dan SO-03)")
        for (s in SPECIAL) item("-", T(s, Xlsx.TEXT), "sesuai gambar", T("ls", Xlsx.TEXT),
            XCell.Num(1.0, Xlsx.VOL), XCell.Empty(Xlsx.RP_IN))
        section("C. PEKERJAAN LAIN: semua pekerjaan lain dalam penawaran, kode '-' (boleh tambah baris)")
        for (s in OTHER) item("-", T(s, Xlsx.TEXT_IN), "", XCell.Empty(Xlsx.TEXT_IN),
            XCell.Empty(Xlsx.VOL_IN), XCell.Empty(Xlsx.RP_IN))
        repeat(BLANK_OTHER_ROWS) {
            item("-", XCell.Empty(Xlsx.TEXT_IN), "", XCell.Empty(Xlsx.TEXT_IN), XCell.Empty(Xlsx.VOL_IN),
                XCell.Empty(Xlsx.RP_IN))
        }
        val lastItem = rows.size
        rows.add(listOf(
            XCell.Empty(Xlsx.TOTAL), T("JUMLAH SELURUHNYA (A + B + C)", Xlsx.TOTAL), XCell.Empty(Xlsx.TOTAL),
            XCell.Empty(Xlsx.TOTAL), XCell.Empty(Xlsx.TOTAL), XCell.Empty(Xlsx.TOTAL),
            XCell.Formula("SUM(G$firstItem:G$lastItem)", Xlsx.TOTAL), XCell.Empty(Xlsx.TOTAL),
        ))

        val boq = XSheet(
            SHEET, rows, listOf(9.0, 46.0, 40.0, 7.0, 12.0, 16.0, 17.0, 30.0),
            merges = listOf("A1:H1", "A2:H2", "A3:H3"),
            freezeRows = headerRow,
            rowHeights = mapOf(3 to 30.0),
            listRange = "A$firstItem:A${lastItem + 40}",
            listValues = priced.map { it.code } + "-",
        )

        val ins = mutableListOf<List<XCell?>>()
        fun p(s: String, style: Int = Xlsx.WRAP) {
            ins.add(listOf(T(s, style)))
        }
        p("PETUNJUK PENGISIAN", Xlsx.TITLE)
        p("1. Daftar ini disusun Pemilik dari model rumah. Mohon penawaran disusun PADA daftar ini, agar dapat dibandingkan baris per baris dengan perhitungan Pemilik.")
        p("2. Isi hanya sel kuning: " + (if (withQuantities) "harga satuan bagian A (volume sudah diisi Pemilik)" else "volume dan harga satuan bagian A") +
                ", harga satuan bagian B, dan uraian, satuan, volume dan harga bagian C.")
        p("3. Tulis angka sebagai ANGKA di Excel, bukan teks. Pemisah ribuan boleh mengikuti pengaturan Excel Anda. Jangan menulis 'Rp' di dalam sel.")
        p("4. Kode dan satuan jangan diubah. Boleh menambah baris dengan KODE YANG SAMA untuk memecah satu pekerjaan (misalnya pembesian per elemen): volume dan jumlahnya dijumlahkan.")
        p("5. Semua pekerjaan lain dalam penawaran ditulis di bagian C dengan kode '-', agar total daftar ini sama dengan total penawaran.")
        p("6. Harga satuan sudah termasuk bahan, upah, alat, biaya umum dan keuntungan. Pajak, bila ada, ditulis terpisah di kolom Catatan.")
        p("7. Uitzet (pengukuran letak dan arah bangunan ke kiblat) dilakukan Pemilik, lihat gambar SO-01: jangan dihitung. Papan bouwplank (bahan dan pemasangan) masuk Pekerjaan persiapan.")
        p("8. Lampirkan perhitungan volume (backup volume) dan analisa harga satuan untuk setiap baris bagian A.")
        p("9. Gambar acuan: SO-01 uitzet, SO-02 ruang meridian, SO-03 gnomon Asr, ditambah gambar struktur dan arsitek dari perencana.")
        p("")
        p("DAFTAR KODE BAGIAN A", Xlsx.BOLD)
        ins.add(
            listOf(
                T("Kode", Xlsx.HEAD), T("Uraian", Xlsx.HEAD), T("Spesifikasi (dari model)", Xlsx.HEAD),
                T("Sat.", Xlsx.HEAD),
            ) + if (withQuantities) listOf(T("Volume model", Xlsx.HEAD)) else emptyList()
        )
        for (l in priced) ins.add(
            listOf(
                T(l.code, Xlsx.CODE), T(URAIAN.getValue(l.code), Xlsx.TEXT), T(l.description, Xlsx.TEXT),
                T(l.uom.label, Xlsx.TEXT),
            ) + if (withQuantities) listOf(XCell.Num(l.net, Xlsx.VOL)) else emptyList()
        )
        val instr = XSheet(INSTRUCTIONS, ins, listOf(9.0, 50.0, 40.0, 7.0, 14.0),
            merges = (1..10).map { "A$it:E$it" }, rowHeights = (2..10).associateWith { 28.0 })

        return Xlsx.write(listOf(boq, instr), sharedStrings)
    }

    /** The priced rows as RabLines. Row numbers are the workbook's own, so a
     * complaint names the row the contractor will find.
     *
     * A row is priced when its PRICE cell is filled. A volume alone is not
     * enough: the owner pre-fills volumes (section B, and section A in the
     * with-quantities variant), so "volume but no price" is simply an unpriced
     * row. An unpriced takeoff row still shows up in the report, as a takeoff
     * line no RAB item names. A price without a volume is an error. */
    fun read(bytes: ByteArray): List<RabLine> {
        val problems = mutableListOf<String>()
        val cells = try {
            Xlsx.readSheet(bytes, SHEET)
        } catch (e: Exception) {
            throw InputError("boq", listOf(e.message ?: e::class.java.simpleName))
        }
        val header = cells.entries.firstOrNull { (_, row) -> row[C_CODE]?.text?.trim() == HEADER }?.key
            ?: throw InputError("boq", listOf("no header row with '$HEADER' in column A of sheet '$SHEET'"))
        val out = mutableListOf<RabLine>()
        for ((r, row) in cells) {
            if (r <= header) continue
            val codeField = row[C_CODE]?.text?.trim() ?: continue
            val codes = RabComparison.parseCodes(codeField) ?: continue   // section and total rows
            val vol = row[C_VOL]
            val price = row[C_PRICE]
            val hasVol = vol != null && (vol.num != null || !vol.text.isNullOrBlank() || vol.uncomputed)
            val hasPrice = price != null && (price.num != null || !price.text.isNullOrBlank() || price.uncomputed)
            if (!hasPrice) continue
            if (!hasVol) {
                problems += "row $r: a unit price but no volume"
                continue
            }
            try {
                require(!vol.uncomputed && !price.uncomputed) {
                    "a formula has no computed value; open and save the file in Excel or LibreOffice"
                }
                val v = vol.num ?: OwnerInput.decimal(vol.text!!)
                val p = price.num ?: OwnerInput.rupiah(price.text!!)
                require(v > 0.0) { "volume must be positive" }
                require(p >= 0.0) { "unit price must not be negative" }
                out += RabLine(
                    r, codes, row[C_DESC]?.text?.trim().orEmpty(), row[C_UNIT]?.text?.trim().orEmpty(), v, p
                )
            } catch (e: IllegalArgumentException) {
                problems += "row $r: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) throw InputError("boq", problems)
        return out
    }
}

/** Host entry point:  BoqWorkbookKt OUT.xlsx [--with-quantities] */
fun main(args: Array<String>) {
    val out = args.firstOrNull { !it.startsWith("--") } ?: "boq.xlsx"
    val grid = Grid(listOf(0.0, 3.0, 6.0), listOf(0.0, 3.5, 7.0, 10.3), 0.0, 3.5)
    val takeoff = QuantityTakeoff.compute(TakeoffInput(grid))
    val date = String.format(Locale.ROOT, "%tF", java.util.Date())
    File(out).writeBytes(BoqWorkbook.write(takeoff, "--with-quantities" in args, date))
    println("wrote $out (${if ("--with-quantities" in args) "with" else "without"} quantities)")
}