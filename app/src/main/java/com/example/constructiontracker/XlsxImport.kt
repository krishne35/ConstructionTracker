package com.example.constructiontracker

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.zip.ZipInputStream

/** Reads workbooks in the layout that Export to Excel writes. Also copes with files re-saved by Excel. */
object XlsxImport {
    class Row(
        val ts: Long, val kind: Kind, val item: String,
        val owner: String, val supplier: String, val amount: Double, val note: String
    )

    class SheetData(val name: String, val fmt: Fmt, val rows: List<Row>, val skipped: Int)

    private class Raw(val text: String?, val num: Double?, val style: Int)
    private class Styles(val xfFmt: List<Int>, val codes: Map<Int, String>)

    private fun parser(b: ByteArray): XmlPullParser =
        Xml.newPullParser().also { it.setInput(ByteArrayInputStream(b), "UTF-8") }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name.trimStart('/')] = z.readBytes()
                e = z.nextEntry
            }
        }
        return out
    }

    private fun sharedStrings(b: ByteArray?): List<String> {
        if (b == null) return emptyList()
        val p = parser(b)
        val out = mutableListOf<String>()
        var sb: StringBuilder? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "si") sb = StringBuilder()
                else if (p.name == "t") sb?.append(p.nextText())
            } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                out.add(sb?.toString() ?: "")
                sb = null
            }
            ev = p.next()
        }
        return out
    }

    private fun styles(b: ByteArray?): Styles {
        val codes = HashMap<Int, String>()
        val xfs = mutableListOf<Int>()
        if (b != null) {
            val p = parser(b)
            var inXfs = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "numFmt" -> {
                            val id = p.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                            val code = p.getAttributeValue(null, "formatCode")
                            if (id != null && code != null) codes[id] = code
                        }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) xfs.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "cellXfs") {
                    inXfs = false
                }
                ev = p.next()
            }
        }
        return Styles(xfs, codes)
    }

    private fun builtin(id: Int): String? = when (id) {
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        else -> null
    }

    /** Indian grouping and decimals are read back from the number format of the amount column. */
    private fun fmtFrom(code: String?, symbol: String): Fmt {
        if (code == null) return Fmt(symbol)
        val indian = code.contains("##\\,##")
        val last = code.substringAfterLast(';')
        val dec = if ('.' in last) last.substringAfter('.').takeWhile { it == '0' }.length else 0
        return Fmt(symbol, indian, dec)
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    private fun readSheet(b: ByteArray, shared: List<String>): TreeMap<Int, TreeMap<Int, Raw>> {
        val rows = TreeMap<Int, TreeMap<Int, Raw>>()
        val p = parser(b)
        var rowNo = 0
        var nextCol = 0
        var ref = ""
        var type = ""
        var style = 0
        var v: String? = null
        var inl: StringBuilder? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> {
                        rowNo = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowNo + 1)
                        nextCol = 0
                    }
                    "c" -> {
                        ref = p.getAttributeValue(null, "r") ?: ""
                        type = p.getAttributeValue(null, "t") ?: ""
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        v = null
                        inl = null
                    }
                    "v" -> v = p.nextText()
                    "is" -> inl = StringBuilder()
                    "t" -> inl?.append(p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "c") {
                val col = if (ref.isNotEmpty()) colIndex(ref) else nextCol
                nextCol = col + 1
                val raw = when (type) {
                    "s" -> Raw(v?.toIntOrNull()?.let { shared.getOrNull(it) }, null, style)
                    "inlineStr" -> Raw(inl?.toString(), null, style)
                    "str", "b", "e" -> Raw(v, null, style)
                    else -> Raw(v, v?.toDoubleOrNull(), style)
                }
                rows.getOrPut(rowNo) { TreeMap() }[col] = raw
            }
            ev = p.next()
        }
        return rows
    }

    private val epoch = LocalDateTime.of(1899, 12, 30, 0, 0)
    private val dateFormats = listOf(
        "dd-MM-yyyy HH:mm", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm", "dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy")

    private fun parseDate(r: Raw?): Long? {
        if (r == null) return null
        r.num?.let {
            return epoch.plusSeconds(Math.round(it * 86400.0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val t = r.text?.trim() ?: return null
        for (f in dateFormats) {
            try {
                val fmt = DateTimeFormatter.ofPattern(f)
                val ldt = if (f.contains("HH")) LocalDateTime.parse(t, fmt) else LocalDate.parse(t, fmt).atStartOfDay()
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                // try the next format
            }
        }
        return null
    }

    private fun parseAmount(r: Raw?): Double? {
        if (r == null) return null
        r.num?.let { return it }
        return r.text?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()
    }

    private fun colOf(header: Map<Int, Raw>, fallback: Int, vararg keys: String): Int {
        val hit = header.entries.firstOrNull { en ->
            val t = en.value.text?.trim()?.lowercase() ?: ""
            keys.any { t.startsWith(it) }
        }
        return hit?.key ?: fallback
    }

    fun parse(bytes: ByteArray): List<SheetData> {
        val files = unzip(bytes)
        val wb = files["xl/workbook.xml"] ?: error("This is not an Excel .xlsx file")
        val rels = files["xl/_rels/workbook.xml.rels"] ?: error("The workbook is missing its relationships")
        val shared = sharedStrings(files["xl/sharedStrings.xml"])
        val st = styles(files["xl/styles.xml"])

        val targets = HashMap<String, String>()
        run {
            val p = parser(rels)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val t = p.getAttributeValue(null, "Target") ?: ""
                    targets[p.getAttributeValue(null, "Id") ?: ""] = if (t.startsWith("/")) t.trimStart('/') else "xl/$t"
                }
                ev = p.next()
            }
        }

        val sheets = mutableListOf<Pair<String, String>>()
        run {
            val p = parser(wb)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = p.getAttributeValue(null, "name") ?: "Sheet"
                    var rid = ""
                    for (i in 0 until p.attributeCount) {
                        val an = p.getAttributeName(i)
                        if (an == "id" || an.endsWith(":id")) rid = p.getAttributeValue(i)
                    }
                    sheets.add(name to (targets[rid] ?: ""))
                }
                ev = p.next()
            }
        }

        val out = mutableListOf<SheetData>()
        for ((name, path) in sheets) {
            val data = readSheet(files[path] ?: continue, shared)
            val header = data[1] ?: continue
            if (header[0]?.text?.trim()?.lowercase()?.startsWith("date") != true) continue // e.g. the Summary sheet

            val cDate = colOf(header, 0, "date")
            val cType = colOf(header, 1, "type")
            val cItem = colOf(header, 2, "item")
            val cOwner = colOf(header, 3, "owner", "paid by")
            val cSupplier = colOf(header, 4, "supplier", "paid to")
            val cAmount = colOf(header, 5, "amount")
            val cNote = colOf(header, 6, "note")

            val symbol = Regex("\\((.*)\\)").find(header[cAmount]?.text ?: "")?.groupValues?.get(1) ?: ""
            val styleIdx = data.entries.firstOrNull { it.key >= 2 && it.value[cAmount]?.num != null }?.value?.get(cAmount)?.style
            val numFmtId = styleIdx?.let { st.xfFmt.getOrNull(it) }
            val fmt = fmtFrom(numFmtId?.let { st.codes[it] ?: builtin(it) }, symbol)

            val rows = mutableListOf<Row>()
            var skipped = 0
            for ((_, cells) in data.tailMap(2)) {
                val any = (0..6).any { c -> !cells[c]?.text.isNullOrBlank() || cells[c]?.num != null }
                if (!any) continue // blank here; only the summary block sits on this row
                val ts = parseDate(cells[cDate])
                val amount = parseAmount(cells[cAmount])
                val item = cells[cItem]?.text?.trim().orEmpty()
                if (ts == null || amount == null || amount <= 0 || item.isBlank()) { skipped++; continue }
                val typeText = cells[cType]?.text?.trim()?.lowercase() ?: "expense"
                rows.add(Row(
                    ts, if (typeText.startsWith("i") || typeText.startsWith("c")) Kind.INCOME else Kind.EXPENSE,
                    item,
                    cells[cOwner]?.text?.trim().orEmpty().ifBlank { "Unknown" },
                    cells[cSupplier]?.text?.trim().orEmpty(),
                    amount, cells[cNote]?.text?.trim().orEmpty()))
            }
            out.add(SheetData(name, fmt, rows, skipped))
        }
        return out
    }
}
