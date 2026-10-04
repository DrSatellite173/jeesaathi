package com.exam.app.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream

/**
 * PDF -> test. Text PDFBox se nikalte hain (har line ki position ke saath), question-start lines dhoondhte hain,
 * phir Android PdfRenderer se page render karke har question ka region crop karke image bana dete hain.
 * Isse formulas / diagrams / tables bilkul original jaise dikhte hain. Options A-D / 1-4 buttons se select hote hain.
 * Limit: single-column PDF; scanned (image-only) PDF me text nahi hota, isliye detect nahi hoga.
 */
class PdfTestParser(private val ctx: Context, private val testId: String) {

    data class PdfResult(val questions: List<ParsedQuestion>, val keyText: String, val warning: String?)

    private class Line(val page: Int, val top: Float, val text: String)

    private class Collector : PDFTextStripper() {
        val lines = ArrayList<Line>()
        private val sb = StringBuilder()
        private var top = 0f
        private var pg = 0
        private var has = false

        init { sortByPosition = true }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val first = textPositions.firstOrNull() ?: return
            if (!has) {
                top = first.yDirAdj - first.heightDir
                pg = getCurrentPageNo() - 1
                has = true
            }
            sb.append(text)
        }

        override fun writeWordSeparator() { if (has) sb.append(' ') }
        override fun writeLineSeparator() { flushLine() }
        override fun endPage(page: PDPage?) { flushLine(); super.endPage(page) }

        fun flushLine() {
            if (has) {
                val t = sb.toString().trim()
                if (t.isNotEmpty()) lines.add(Line(pg, top, t))
            }
            sb.setLength(0)
            has = false
        }
    }

    private class Ev(val kind: Int, val lineIdx: Int, val num: Int = 0, val hint: Boolean? = null, val subject: String? = null)
    // kind: 0 = question, 1 = subject heading, 2 = section heading, 3 = answer key

    private val explQ = Regex("^\\s*(?:Q\\.?|Question)\\s*\\.?\\s*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE)
    private val digitDot = Regex("^\\s*(\\d{1,3})\\s*\\.\\s+\\S")
    private val keyHead = Regex("(?i)^\\W*(answer\\s*key|answers)\\b")
    private val sectionRe = Regex("(?i)^section\\s*[-:]?\\s*([A-Za-z])\\b")
    private val labelLine = Regex("^\\s*\\(?\\s*([A-Da-d1-4])\\s*[.)]")
    private val labelInline = Regex("\\(\\s*([A-Da-d1-4])\\s*\\)")

    private val headerFrac = 0.04f
    private val footerFrac = 0.04f

    fun parse(pdf: File, mediaDir: File, progress: (Int, Int) -> Unit): PdfResult {
        PDFBoxResourceLoader.init(ctx)
        val collector = Collector()
        PDDocument.load(pdf).use { doc ->
            collector.getText(doc)
            collector.flushLine()
        }
        val lines = collector.lines
        if (lines.isEmpty()) {
            return PdfResult(emptyList(), "", "PDF me selectable text nahi mila (scanned/image PDF lagta hai).")
        }

        // ---- events
        val explicit = lines.count { explQ.containsMatchIn(it.text) } >= 2
        val evs = ArrayList<Ev>()
        var last = 0
        var sawHeading = true
        for ((i, ln) in lines.withIndex()) {
            val t = ln.text
            val subj = Subjects.detect(t)
            if (subj != null) { evs.add(Ev(1, i, subject = subj)); sawHeading = true; continue }
            if (last > 0 && t.length <= 30 && keyHead.containsMatchIn(t)) { evs.add(Ev(3, i)); break }
            val sec = sectionRe.find(t)
            if (sec != null && t.length <= 70) {
                val low = t.lowercase()
                val hint: Boolean? = when {
                    low.contains("numerical") || low.contains("integer") || low.contains("numeric") -> true
                    low.contains("multiple choice") || low.contains("mcq") || low.contains("single correct") -> false
                    sec.groupValues[1].equals("B", true) -> true
                    sec.groupValues[1].equals("A", true) -> false
                    else -> null
                }
                evs.add(Ev(2, i, hint = hint)); sawHeading = true; continue
            }
            val qn = questionNumber(t, explicit, last, sawHeading)
            if (qn != null) { evs.add(Ev(0, i, num = qn)); last = qn; sawHeading = false }
        }
        val qEvs = evs.count { it.kind == 0 }
        if (qEvs == 0) return PdfResult(emptyList(), "", "PDF me question numbering (Q1 / 1.) detect nahi hui.")

        val keyEv = evs.firstOrNull { it.kind == 3 }
        val keyText = if (keyEv != null) lines.subList(keyEv.lineIdx + 1, lines.size).joinToString(" ") { it.text } else ""

        // ---- renderer
        val out = ArrayList<ParsedQuestion>()
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val cache = HashMap<Int, Bitmap>()
        val scales = HashMap<Int, Float>()
        val pdfDir = File(mediaDir, "pdfq").apply { mkdirs() }

        fun pageBitmap(pg: Int): Bitmap {
            cache[pg]?.let { return it }
            cache.keys.filter { it < pg - 1 }.forEach { k -> cache.remove(k)?.recycle() }
            val page = renderer.openPage(pg.coerceIn(0, renderer.pageCount - 1))
            try {
                val sc = (1400f / page.width).coerceIn(1.5f, 3.0f)
                val bmp = Bitmap.createBitmap((page.width * sc).toInt(), (page.height * sc).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                scales[pg] = bmp.height / page.height.toFloat()
                cache[pg] = bmp
                return bmp
            } finally { page.close() }
        }

        try {
            var hintNum: Boolean? = null
            var curSubject = "General"
            var done = 0
            for ((ei, ev) in evs.withIndex()) {
                when (ev.kind) {
                    1 -> { curSubject = ev.subject ?: curSubject; hintNum = null; continue }
                    2 -> { hintNum = ev.hint; continue }
                    3 -> break
                }
                val endEv = evs.getOrNull(ei + 1)
                val startLine = ev.lineIdx
                val endLine = endEv?.lineIdx ?: lines.size
                val region = lines.subList(startLine, endLine)

                val (isMcq, digits) = detectOptions(region)
                val numerical = hintNum == true || !isMcq

                val startLn = lines[startLine]
                val endLn = if (endEv != null) lines[endLine] else null
                val endPage = endLn?.page ?: lines.last().page
                val bmp = cropRegion(::pageBitmap, scales, startLn.page, startLn.top, endPage, endLn?.top)
                val file = File(pdfDir, "q_%03d.png".format(done + 1))
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()

                val html = "<img class=\"qimg\" style=\"width:100%;height:auto\" src=\"https://exam.local/tm/$testId/pdfq/${file.name}\">"
                val labels = if (digits) listOf("1", "2", "3", "4") else listOf("A", "B", "C", "D")
                out.add(
                    ParsedQuestion(
                        subject = curSubject,
                        type = if (numerical) "NUM" else "MCQ",
                        html = html,
                        options = if (numerical) emptyList() else labels,
                        correct = "",
                        labels = true
                    )
                )
                done++
                progress(done, qEvs)
            }
        } finally {
            cache.values.forEach { it.recycle() }
            cache.clear()
            renderer.close()
            pfd.close()
        }
        return PdfResult(out, keyText, null)
    }

    private fun questionNumber(t: String, explicit: Boolean, last: Int, sawHeading: Boolean): Int? {
        if (explicit) return explQ.find(t)?.groupValues?.get(1)?.toIntOrNull()
        val m = digitDot.find(t) ?: return null
        val n = m.groupValues[1].toInt()
        return if (n == last + 1 || (n == 1 && sawHeading)) n else null
    }

    /** (isMcq, digitsStyle). 3+ alag labels (1-4 ya A-D) mile to MCQ. Dono ho to digits ko tarjeeh. */
    private fun detectOptions(region: List<Line>): Pair<Boolean, Boolean> {
        val letters = HashSet<Char>()
        val digs = HashSet<Char>()
        fun add(c: Char) { if (c.isDigit()) digs.add(c) else letters.add(c.uppercaseChar()) }
        region.forEachIndexed { i, ln ->
            if (i > 0) labelLine.find(ln.text)?.let { add(it.groupValues[1][0]) }
            labelInline.findAll(ln.text).forEach { add(it.groupValues[1][0]) }
        }
        return when {
            digs.size >= 3 -> Pair(true, true)
            letters.size >= 3 -> Pair(true, false)
            else -> Pair(false, false)
        }
    }

    private fun cropRegion(
        pageBitmap: (Int) -> Bitmap,
        scales: Map<Int, Float>,
        startPg: Int, startTop: Float,
        endPg: Int, endTop: Float?
    ): Bitmap {
        val segs = ArrayList<Bitmap>()
        for (pg in startPg..endPg) {
            val bmp = pageBitmap(pg)
            val sc = scales[pg] ?: 1f
            val h = bmp.height
            val top = if (pg == startPg) ((startTop - 3f) * sc).toInt() else (h * headerFrac).toInt()
            val bottom = if (pg == endPg && endTop != null) ((endTop - 2f) * sc).toInt() else (h * (1f - footerFrac)).toInt()
            val t = top.coerceIn(0, h - 1)
            val b = bottom.coerceIn(t + 1, h)
            if (b - t < 6) continue
            segs.add(Bitmap.createBitmap(bmp, 0, t, bmp.width, b - t))
        }
        if (segs.isEmpty()) {
            val bmp = pageBitmap(startPg)
            val t = (startTop * (scales[startPg] ?: 1f)).toInt().coerceIn(0, bmp.height - 2)
            segs.add(Bitmap.createBitmap(bmp, 0, t, bmp.width, minOf(80, bmp.height - t)))
        }
        val w = segs[0].width
        var total = 0
        segs.forEach { total += it.height }
        total = total.coerceAtMost(7000)
        val stitched = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
        stitched.eraseColor(Color.WHITE)
        val canvas = Canvas(stitched)
        var y = 0
        for (s in segs) {
            if (y < total) canvas.drawBitmap(s, 0f, y.toFloat(), null)
            y += s.height
            s.recycle()
        }
        return trimWhite(stitched)
    }

    private fun trimWhite(src: Bitmap, pad: Int = 14): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (r < 240 || g < 240 || b < 240) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src
        val x0 = (minX - pad).coerceAtLeast(0)
        val y0 = (minY - pad).coerceAtLeast(0)
        val x1 = (maxX + pad).coerceAtMost(w - 1)
        val y1 = (maxY + pad).coerceAtMost(h - 1)
        val out = Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        if (out !== src) src.recycle()
        return out
    }
}
