package com.example.constructiontracker

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal .xlsx writer (no external library). One sheet per project, plus a Summary sheet for several. */
object Xlsx {
    private const val HEAD = 1
    private const val DATE = 2
    private const val NUM = 3
    private const val BOLDNUM = 4

    private sealed class Cell
    private class Str(val v: String, val s: Int = 0) : Cell()
    private class Num(val v: Double, val s: Int = 0) : Cell()

    private class Sheet(val name: String, val widths: List<Int>) {
        val rows = TreeMap<Int, TreeMap<Int, Cell>>()
        fun put(r: Int, c: Int, cell: Cell) { rows.getOrPut(r) { TreeMap() }[c] = cell }
    }

    private fun col(c: Int): String {
        var n = c
        val sb = StringBuilder()
        do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch.code < 32 && ch != '\n' && ch != '\t' -> {}
            else -> append(ch)
        }
    }

    private fun plain(d: Double) = BigDecimal.valueOf(d).toPlainString()

    private fun serial(ts: Long): Double {
        val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
        return Duration.between(LocalDateTime.of(1899, 12, 30, 0, 0), ldt).seconds / 86400.0
    }

    private fun uniqueName(raw: String, used: MutableSet<String>): String {
        val base = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "-").trim().ifEmpty { "Project" }.take(31)
        var n = base
        var i = 2
        while (!used.add(n.lowercase())) {
            val suf = " ($i)"
            n = base.take(31 - suf.length) + suf
            i++
        }
        return n
    }

    private fun projectSheet(name: String, list: List<Txn>, cats: Map<Long, String>, parties: Map<Long, String>, o: Options, fmt: Fmt, NUM: Int, BOLDNUM: Int): Sheet {
        val sh = Sheet(name, listOf(18, 10, 18, 18, 18, 14, 28, 3, 24, 16, 16, 16))
        listOf("Date and time", "Type", "Item", "Owner", "Supplier", if (fmt.symbol.isBlank()) "Amount" else "Amount (${fmt.symbol})", "Note")
            .forEachIndexed { c, h -> sh.put(0, c, Str(h, HEAD)) }
        list.sortedBy { it.timestamp }.forEachIndexed { i, t ->
            val r = i + 1
            sh.put(r, 0, Num(serial(t.timestamp), DATE))
            sh.put(r, 1, Str(if (t.kind == Kind.EXPENSE) "Expense" else "Income"))
            sh.put(r, 2, Str(cats[t.categoryId] ?: ""))
            sh.put(r, 3, Str(parties[t.payerId] ?: ""))
            sh.put(r, 4, Str(t.payeeId?.let { parties[it] } ?: ""))
            sh.put(r, 5, Num(t.amount, NUM))
            sh.put(r, 6, Str(t.note))
        }
        if (!o.summary) return sh
        val inc = list.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
        val exp = list.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
        var r = 0
        sh.put(r, 8, Str("Summary", HEAD)); r++
        sh.put(r, 8, Str("Income")); sh.put(r, 9, Num(inc, NUM)); r++
        sh.put(r, 8, Str("Expense")); sh.put(r, 9, Num(exp, NUM)); r++
        sh.put(r, 8, Str("Balance", HEAD)); sh.put(r, 9, Num(inc - exp, BOLDNUM)); r += 2
        fun block(title: String, data: List<Pair<String, Double>>) {
            sh.put(r, 8, Str(title, HEAD)); r++
            data.forEach { sh.put(r, 8, Str(it.first)); sh.put(r, 9, Num(it.second, NUM)); r++ }
            r++
        }
        val spent = list.filter { it.kind == Kind.EXPENSE }
        block("Spend by item", spent.groupBy { cats[it.categoryId] ?: "?" }
            .map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second })
        block("Spend by owner", spent.groupBy { parties[it.payerId] ?: "?" }
            .map { it.key to it.value.sumOf { x -> x.amount } }.sortedByDescending { it.second })
        sh.put(r, 8, Str("Balance by owner", HEAD))
        listOf("Funds", "Paid", "Balance").forEachIndexed { i, h -> sh.put(r, 9 + i, Str(h, HEAD)) }
        r++
        list.map { it.payerId }.distinct().sortedBy { parties[it] ?: "" }.forEach { id ->
            val funds = list.filter { it.payerId == id && it.kind == Kind.INCOME }.sumOf { it.amount }
            val paid = list.filter { it.payerId == id && it.kind == Kind.EXPENSE }.sumOf { it.amount }
            sh.put(r, 8, Str(parties[id] ?: "?"))
            sh.put(r, 9, Num(funds, NUM)); sh.put(r, 10, Num(paid, NUM)); sh.put(r, 11, Num(funds - paid, BOLDNUM))
            r++
        }
        return sh
    }

    private fun xml(sh: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("<cols>")
        sh.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        append("</cols><sheetData>")
        sh.rows.forEach { (r, cells) ->
            append("""<row r="${r + 1}">""")
            cells.forEach { (c, cell) ->
                val ref = col(c) + (r + 1)
                when (cell) {
                    is Str -> append("""<c r="$ref" t="inlineStr" s="${cell.s}"><is><t xml:space="preserve">${esc(cell.v)}</t></is></c>""")
                    is Num -> append("""<c r="$ref" s="${cell.s}"><v>${plain(cell.v)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    class Options(val summary: Boolean, val dateStyle: Int)

    private fun amountFormat(f: Fmt): String {
        val dec = if (f.decimals > 0) "." + "0".repeat(f.decimals) else ""
        return if (f.indian) "[&gt;=10000000]##\\,##\\,##\\,##0$dec;[&gt;=100000]##\\,##\\,##0$dec;##,##0$dec" else "#,##0$dec"
    }

    /** Styles 0-2 are fixed. Each distinct currency format adds two more: normal (3 + 2k) and bold (4 + 2k). */
    private fun styles(fmts: List<Fmt>, o: Options): String {
        val dateFmt = if (o.dateStyle == 0) "dd\\-mm\\-yyyy\\ hh:mm" else "yyyy\\-mm\\-dd\\ hh:mm"
        val numFmts = StringBuilder("""<numFmt numFmtId="164" formatCode="$dateFmt"/>""")
        val xfs = StringBuilder()
        fmts.forEachIndexed { k, f ->
            numFmts.append("""<numFmt numFmtId="${165 + k}" formatCode="${esc(amountFormat(f)).replace("&amp;gt;", "&gt;")}"/>""")
            xfs.append("""<xf numFmtId="${165 + k}" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""")
            xfs.append("""<xf numFmtId="${165 + k}" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="${1 + fmts.size}">$numFmts</numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="${3 + 2 * fmts.size}"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>$xfs</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }

    fun build(projects: List<Project>, txns: Map<Long, List<Txn>>, cats: Map<Long, String>, parties: Map<Long, String>, o: Options): ByteArray {
        val fmts = projects.map { it.fmt() }.distinct()
        val used = mutableSetOf<String>()
        val sheets = mutableListOf<Sheet>()
        if (o.summary && projects.size > 1) {
            used.add("summary")
            val s = Sheet("Summary", listOf(26, 16, 16, 16))
            listOf("Project", "Income", "Expense", "Balance").forEachIndexed { c, h -> s.put(0, c, Str(h, HEAD)) }
            projects.forEachIndexed { i, p ->
                val l = txns[p.id].orEmpty()
                val ns = 3 + 2 * fmts.indexOf(p.fmt())
                val inc = l.filter { it.kind == Kind.INCOME }.sumOf { it.amount }
                val exp = l.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }
                s.put(i + 1, 0, Str(p.name)); s.put(i + 1, 1, Num(inc, ns))
                s.put(i + 1, 2, Num(exp, ns)); s.put(i + 1, 3, Num(inc - exp, ns))
            }
            sheets.add(s)
        }
        projects.forEach {
            val k = fmts.indexOf(it.fmt())
            sheets.add(projectSheet(uniqueName(it.name, used), txns[it.id].orEmpty(), cats, parties, o, it.fmt(), 3 + 2 * k, 4 + 2 * k))
        }

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun add(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            val head = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            add("[Content_Types].xml", buildString {
                append(head)
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            add("_rels/.rels", """$head<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add("xl/workbook.xml", buildString {
                append(head)
                append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$rel"><sheets>""")
                sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            add("xl/_rels/workbook.xml.rels", buildString {
                append(head)
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="$rel/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="$rel/styles" Target="styles.xml"/></Relationships>""")
            })
            add("xl/styles.xml", styles(fmts, o))
            sheets.forEachIndexed { i, s -> add("xl/worksheets/sheet${i + 1}.xml", xml(s)) }
        }
        return bos.toByteArray()
    }
}
