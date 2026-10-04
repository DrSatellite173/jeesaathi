package com.exam.app.parser

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Kisi bhi HTML test paper ko questions me todta hai. 3 strategies (pehli jo kaam kare):
 *  1) explicit question containers (.question-container, .question-block ...)
 *  2) option-groups (ol/ul/div jisme 2-8 options hon) -> unka parent = question
 *  3) linear scan: "Q1." / "1." / "Question 1" markers + "(A)/(1)/A." option markers
 * Images (relative / data: / http) sab handle hote hain; relative images import ki hui files se map hoti hain.
 */
class HtmlTestParser(private val testId: String, private val media: MediaIndex) {

    private val qLabel = Regex("^\\s*(?:(?:Q\\.?|Question)\\s*\\.?\\s*\\d{1,3}\\s*[.):]?|\\d{1,3}\\s*[.):](?=\\s))\\s*", RegexOption.IGNORE_CASE)
    private val optLabel = Regex("^\\s*(?:\\(\\s*[A-Da-d1-4]\\s*\\)|[A-Da-d1-4]\\s*\\)|[A-Da-d1-4]\\s*\\.(?=\\s))\\s*")
    private val optStartText = Regex("^\\(?\\s*[A-Da-d1-4]\\s*[.)]\\s*\\S")
    private val explQ = Regex("^\\s*(?:Q\\.?|Question)\\s*\\.?\\s*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE)
    private val digitDot = Regex("^\\s*(\\d{1,3})\\s*\\.\\s+\\S")
    private val letterOpt = Regex("^\\(?\\s*([A-Da-d])\\s*[.)]\\s*\\S")
    private val parenDigitOpt = Regex("^(?:\\(\\s*([1-4])\\s*\\)|([1-4])\\s*\\))\\s*\\S")
    private val dotDigitOpt = Regex("^([1-4])\\s*\\.\\s+\\S")
    private val ansLine = Regex("^(?:correct\\s*(?:answer|option|ans)|answer|ans|key)\\s*[:\\-\\u2013=]\\s*\\(?\\s*([A-Da-d]|-?\\d+(?:\\.\\d+)?)\\s*\\)?", RegexOption.IGNORE_CASE)
    private val ansBare = Regex("^\\(?\\s*([A-Da-d]|-?\\d+(?:\\.\\d+)?)\\s*\\)?\\.?$")
    private val solStart = Regex("^(?:solution|explanation|sol)\\b\\s*[:.\\-]?", RegexOption.IGNORE_CASE)
    private val inlineOpt = Regex("\\(\\s*([A-Da-d1-4])\\s*\\)")
    private val correctClass = Regex("(?i)(^|[\\s_-])(correct|right|is-correct|answer-correct|tick)($|[\\s_-])")
    private val wrongClass = Regex("(?i)(incorrect|wrong)")

    fun parse(bytes: ByteArray): List<ParsedQuestion> {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        prepare(doc)

        val explicit = explicitBlocks(doc)
        if (explicit.isNotEmpty()) {
            val r = fromBlocks(doc, explicit)
            if (r.isNotEmpty() && (r.any { it.type == "MCQ" } || optionGroupBlocks(doc).isEmpty())) return r
        }
        val grouped = optionGroupBlocks(doc)
        if (grouped.isNotEmpty()) {
            val r = fromBlocks(doc, grouped)
            if (r.isNotEmpty()) return r
        }
        return linear(doc)
    }

    // ------------------------------------------------------------------ prepare
    private fun prepare(doc: Document) {
        doc.select("script[type^=math/tex]").forEach { s -> s.replaceWith(TextNode("\\(" + s.data() + "\\)")) }
        doc.select("script, style, noscript, link, meta, head, iframe, object, embed, form, base").remove()
        // Imported HTML untrusted hai: event handlers / javascript: links hata do (WebView me JS bridge hai)
        for (e in doc.allElements) {
            val bad = e.attributes().asList().map { it.key }.filter { it.startsWith("on", ignoreCase = true) }
            bad.forEach { e.removeAttr(it) }
            for (attr in listOf("href", "src", "xlink:href", "action", "formaction")) {
                if (e.hasAttr(attr) && e.attr(attr).trim().lowercase().startsWith("javascript:")) e.removeAttr(attr)
            }
        }
        doc.select("img").forEach { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }.trim()
            img.removeAttr("srcset")
            img.removeAttr("loading")
            if (src.isNotEmpty()) {
                val lower = src.lowercase()
                if (!(lower.startsWith("data:") || lower.startsWith("http://") || lower.startsWith("https://"))) {
                    val rel = media.find(src)
                    if (rel != null) img.attr("src", "https://exam.local/tm/$testId/" + Uri.encode(rel, "/"))
                } else img.attr("src", src)
            }
            img.addClass("qimg")
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun identitySet(els: Collection<Element>): MutableSet<Element> =
        Collections.newSetFromMap(IdentityHashMap<Element, Boolean>()).also { it.addAll(els) }

    private fun isOptionLike(e: Element): Boolean {
        val tag = e.tagName()
        if (tag == "li") return true
        val cls = e.className().lowercase()
        if (cls.contains("option") || cls.contains("choice")) return true
        if (tag == "p" || tag == "div" || tag == "label" || tag == "tr") {
            if (optStartText.containsMatchIn(e.text().trim())) return true
        }
        return false
    }

    /** Is element ke andar sabse badi option-group (options ki list) lautaata hai. */
    private fun bestOptionGroup(root: Element): Pair<Element, List<Element>>? {
        var best: Pair<Element, List<Element>>? = null
        for (e in root.allElements) {
            val kids = e.children().filter { isOptionLike(it) }
            if (kids.size in 2..8 && kids.size * 10 >= e.children().size * 6) {
                if (best == null || kids.size > best.second.size) best = Pair(e, kids)
            }
        }
        return best
    }

    private fun explicitBlocks(doc: Document): List<Element> {
        val sel = ".question-container, .question-block, .question-wrapper, .q-block, .q-container, .qblock, " +
            ".question-item, .question-box, .question-card, .question-panel, [data-question], section.question, article.question"
        val els = doc.body().select(sel)
        if (els.isEmpty()) return emptyList()
        val set = identitySet(els)
        return els.filter { e -> e.parents().none { set.contains(it) } }
    }

    private fun optionGroupBlocks(doc: Document): List<Element> {
        val groups = ArrayList<Element>()
        for (e in doc.body().allElements) {
            val kids = e.children().filter { isOptionLike(it) }
            if (kids.size in 2..8 && kids.size * 10 >= e.children().size * 6) groups.add(e)
        }
        if (groups.isEmpty()) return emptyList()
        val gset = identitySet(groups)
        val top = groups.filter { g -> g.parents().none { gset.contains(it) } }
        val blocks = top.map { g ->
            val p = g.parent()
            if (p == null || p.tagName() == "body" || p.tagName() == "html") g else p
        }
        val uniq = identitySet(blocks)
        if (uniq.size != blocks.size) return emptyList()
        return blocks
    }

    private fun mapCorrect(v: String?, optCount: Int): String {
        if (v == null) return ""
        val s = v.trim()
        if (optCount >= 2) {
            if (s.length == 1 && s[0].uppercaseChar() in 'A'..'D') {
                val i = s[0].uppercaseChar() - 'A'
                return if (i < optCount) i.toString() else ""
            }
            val d = s.toIntOrNull()
            if (d != null && d in 1..optCount) return (d - 1).toString()
            return ""
        }
        return if (s.toDoubleOrNull() != null) s else ""
    }

    private fun firstText(n: org.jsoup.nodes.Node): TextNode? {
        for (c in n.childNodes()) {
            if (c is TextNode) { if (c.text().isNotBlank()) return c }
            else if (c is Element) { if (c.tagName() == "img" || c.tagName() == "svg") return null; firstText(c)?.let { return it } }
        }
        return null
    }

    private fun stripLeading(el: Element, re: Regex) {
        val tn = firstText(el) ?: return
        tn.text(tn.text().replaceFirst(re, ""))
    }

    private fun optionHtml(el: Element): String {
        val c = el.clone()
        c.select("[class*=label], [class*=letter], [class*=opt-num]").firstOrNull { it !== c }
            ?.takeIf { it.text().trim().length <= 4 }?.remove()
        stripLeading(c, optLabel)
        val h = c.html().trim()
        return if (h.isEmpty()) "&nbsp;" else h
    }

    private fun correctFromMarks(opts: List<Element>): Int {
        opts.forEachIndexed { i, el ->
            val cls = el.className()
            val dc = el.attr("data-correct").lowercase()
            if ((correctClass.containsMatchIn(cls) && !wrongClass.containsMatchIn(cls)) ||
                dc == "true" || dc == "1" || dc == "yes" ||
                el.select("input[checked]").isNotEmpty()
            ) return i
        }
        return -1
    }

    private fun findAnswerText(b: Element, optSet: Set<Element>): String? {
        for (el in b.allElements) {
            if (el === b) continue
            if (optSet.contains(el) || el.parents().any { optSet.contains(it) }) continue
            val cls = el.className().lowercase()
            val clsHit = cls.contains("answer") || cls.contains("ans") || cls.contains("key")
            val own = (if (clsHit) el.text() else el.ownText()).trim()
            if (own.isEmpty() || own.length > 60) continue
            ansLine.find(own)?.let { return it.groupValues[1] }
            if (clsHit) ansBare.find(own)?.let { return it.groupValues[1] }
        }
        return null
    }

    // ------------------------------------------------------------------ block based
    private fun fromBlocks(doc: Document, blocks: List<Element>): List<ParsedQuestion> {
        val bset = identitySet(blocks)
        var cur = "General"
        val out = ArrayList<ParsedQuestion>()
        for (el in doc.allElements) {
            if (bset.contains(el)) {
                val sub = subjectFromAttrs(el) ?: cur
                extractBlock(el, sub)?.let { out.add(it) }
                continue
            }
            if (el.parents().any { bset.contains(it) }) continue
            val t = el.ownText().trim()
            if (t.length in 3..60) Subjects.detect(t)?.let { cur = it }
        }
        return out
    }

    private fun subjectFromAttrs(el: Element): String? {
        el.attr("data-subject").takeIf { it.isNotBlank() }?.let { Subjects.fromClass(it)?.let { s -> return s } }
        var n: Element? = el
        var depth = 0
        while (n != null && depth < 4) {
            Subjects.fromClass(n.className())?.let { return it }
            n = n.parent(); depth++
        }
        return null
    }

    private fun extractBlock(block: Element, subject: String): ParsedQuestion? {
        val b = block.clone()
        val grp = bestOptionGroup(b)
        val optEls = grp?.second ?: emptyList()
        val optSet = identitySet(optEls)
        val markIdx = correctFromMarks(optEls)
        val options = optEls.map { optionHtml(it) }
        val ansText = findAnswerText(b, optSet)
        optEls.forEach { it.remove() }
        b.select("ol, ul").filter { it.children().isEmpty() && it.text().isBlank() }.forEach { it.remove() }
        b.select("[class*=solution], [class*=explanation], [class*=sol-], details, [class*=answer], [class*=hint]")
            .filter { it !== b }.forEach { it.remove() }
        stripLeading(b, qLabel)
        val html = b.html().trim()
        val hasImg = b.select("img").isNotEmpty()
        if (html.isBlank() && !hasImg && options.isEmpty()) return null
        if (html.isBlank() && !hasImg) return null

        val type = if (options.size >= 2) "MCQ" else "NUM"
        val correct = when {
            type == "MCQ" && markIdx >= 0 -> markIdx.toString()
            else -> mapCorrect(ansText, options.size)
        }
        return ParsedQuestion(subject, type, html, if (type == "MCQ") options else emptyList(), correct)
    }

    // ------------------------------------------------------------------ linear
    private class Builder(val subject: String) {
        val body = StringBuilder()
        val options = ArrayList<String>()
        var correct: String? = null
        var solution = false
    }

    private fun flatten(e: Element, out: MutableList<Element>) {
        val containers = setOf("div", "section", "article", "main", "center", "form", "table", "tbody", "tr", "ol", "ul", "body")
        val blockTags = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "ol", "ul", "img", "pre", "blockquote", "tr", "section", "article")
        for (c in e.children()) {
            val descend = c.tagName() in containers &&
                c.ownText().isBlank() &&
                c.children().any { it.tagName() in blockTags }
            if (descend) flatten(c, out) else out.add(c)
        }
    }

    private fun splitInline(html: String): Pair<String, List<String>>? {
        val ms = inlineOpt.findAll(html).toList()
        if (ms.size !in 3..8) return null
        val stem = html.substring(0, ms[0].range.first)
        val opts = ArrayList<String>()
        for (i in ms.indices) {
            val from = ms[i].range.last + 1
            val to = if (i + 1 < ms.size) ms[i + 1].range.first else html.length
            opts.add(html.substring(from, to).trim().ifEmpty { "&nbsp;" })
        }
        return Pair(stem, opts)
    }

    private fun linear(doc: Document): List<ParsedQuestion> {
        var root: Element = doc.body()
        while (root.children().size == 1 && root.child(0).children().isNotEmpty()) root = root.child(0)
        val blocks = ArrayList<Element>()
        flatten(root, blocks)
        val explicitStyle = blocks.count { explQ.containsMatchIn(it.text().trim()) } >= 2

        val out = ArrayList<ParsedQuestion>()
        var cur: Builder? = null
        var subject = "General"
        var lastNum = 0

        fun flush() {
            val c = cur ?: return
            cur = null
            val opts = c.options.toList()
            val type = if (opts.size >= 2) "MCQ" else "NUM"
            val html = c.body.toString().trim()
            if (html.isBlank()) return
            out.add(ParsedQuestion(c.subject, type, html, if (type == "MCQ") opts else emptyList(), mapCorrect(c.correct, opts.size)))
        }

        fun questionNumber(text: String): Int? {
            if (explicitStyle) return explQ.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val m = digitDot.find(text) ?: return null
            val n = m.groupValues[1].toInt()
            return if (n == lastNum + 1 || n == 1) n else null
        }

        fun isOptionStart(text: String): Boolean =
            letterOpt.containsMatchIn(text) || parenDigitOpt.containsMatchIn(text) ||
                (explicitStyle && dotDigitOpt.containsMatchIn(text))

        for (b in blocks) {
            val text = b.text().trim()
            val hasImg = b.select("img").isNotEmpty()
            if (text.isEmpty() && !hasImg) continue

            if (!hasImg) {
                val s = Subjects.detect(text)
                if (s != null) { flush(); subject = s; lastNum = 0; continue }
            }

            val qn = questionNumber(text)
            val c0 = cur

            if (qn == null && c0 != null) {
                if (c0.solution) continue
                if (solStart.containsMatchIn(text)) { c0.solution = true; continue }
                ansLine.find(text)?.let { c0.correct = it.groupValues[1]; return@let }
                if (ansLine.containsMatchIn(text)) continue
            }

            if (qn != null) {
                flush()
                val nb = Builder(subject)
                cur = nb
                lastNum = qn
                val inner = b.html()
                val sp = splitInline(inner)
                if (sp != null) {
                    nb.body.append(qLabel.replaceFirst(sp.first, ""))
                    nb.options.addAll(sp.second)
                } else {
                    val c = b.clone()
                    stripLeading(c, qLabel)
                    nb.body.append(c.outerHtml())
                }
                continue
            }

            val cb = cur ?: continue
            if (cb.solution) continue

            val sp = splitInline(b.html())
            if (sp != null && cb.options.isEmpty()) {
                if (sp.first.isNotBlank()) cb.body.append("<div>").append(sp.first).append("</div>")
                cb.options.addAll(sp.second)
                continue
            }

            if (isOptionStart(text) || (b.tagName() == "li" && cb.body.isNotEmpty())) {
                cb.options.add(optionHtml(b))
            } else if (cb.options.isEmpty()) {
                cb.body.append(b.outerHtml())
            } else if (hasImg) {
                val last = cb.options.size - 1
                cb.options[last] = cb.options[last] + "<br>" + b.outerHtml()
            }
        }
        flush()
        return out
    }
}
