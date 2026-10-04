package com.exam.app.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.exam.app.data.UserStore
import com.exam.app.models.Question
import com.exam.app.models.TestMeta
import com.exam.app.models.TestPaper
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Import pipeline: HTML / PDF / ZIP(html+images) / alag images -> TestPaper (UserStore me save).
 * Ek hi baar me kai files (html + saari images) select kar sakte ho.
 */
class TestImporter(private val ctx: Context, private val store: UserStore) {

    data class Options(val name: String, val durationMin: Int, val subjectMode: Int) // 0 auto, 1 split P/C/M, 2 single
    data class Outcome(val created: List<TestMeta>, val messages: List<String>)

    private val htmlExt = setOf("html", "htm", "xhtml")
    private val imgExt = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

    fun import(uris: List<Uri>, opt: Options, progress: (String) -> Unit): Outcome {
        val messages = ArrayList<String>()
        val created = ArrayList<TestMeta>()
        val work = File(ctx.cacheDir, "imp_" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        try {
            progress("Files copy ho rahi hain...")
            val pool = ArrayList<File>()
            uris.forEachIndexed { idx, uri ->
                val name = displayName(uri).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dest = File(work, "in$idx").apply { mkdirs() }
                val f = File(dest, name)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(f).use { input.copyTo(it) }
                } ?: run { messages.add("$name khul nahi payi"); return@forEachIndexed }
                if (name.lowercase().endsWith(".zip")) {
                    val zdir = File(dest, "zip_" + name.substringBeforeLast('.')).apply { mkdirs() }
                    try { unzip(f, zdir) } catch (e: Exception) { messages.add("$name unzip fail: ${e.message}") }
                    zdir.walkTopDown().filter { it.isFile }.forEach { pool.add(it) }
                } else pool.add(f)
            }

            val docs = pool.filter { val e = ext(it); e in htmlExt || e == "pdf" }
            val images = pool.filter { ext(it) in imgExt }
            if (docs.isEmpty()) {
                messages.add("Koi HTML ya PDF file nahi mili.")
                return Outcome(created, messages)
            }

            docs.forEachIndexed { di, doc ->
                val label = doc.nameWithoutExtension
                progress("Parse ho raha hai: $label")
                val testId = "t" + System.currentTimeMillis().toString(36) + di
                val mediaDir = store.mediaDir(testId)
                try {
                    // images: pool ki saari images media folder me (relative path preserve)
                    images.forEach { img ->
                        val rel = img.relativeTo(work).path.replace('\\', '/')
                        // "inN/" ya "inN/zip_x/" prefix hata do taaki html ke relative paths match karein
                        val clean = rel.replace(Regex("^in\\d+/"), "").replace(Regex("^zip_[^/]+/"), "")
                        val target = File(mediaDir, clean)
                        target.parentFile?.mkdirs()
                        if (!target.exists()) img.copyTo(target, overwrite = true)
                    }

                    val parsed: List<ParsedQuestion>
                    var keyText = ""
                    if (ext(doc) == "pdf") {
                        val res = PdfTestParser(ctx, testId).parse(doc, mediaDir) { d, t -> progress("PDF: question $d / $t") }
                        res.warning?.let { messages.add("$label: $it") }
                        parsed = res.questions
                        keyText = res.keyText
                    } else {
                        val parser = HtmlTestParser(testId, MediaIndex(mediaDir))
                        parsed = parser.parse(doc.readBytes())
                    }

                    if (parsed.isEmpty()) {
                        store.deleteTest(testId)
                        messages.add("$label: koi question detect nahi hua.")
                        return@forEachIndexed
                    }

                    val adjusted = applySubjects(parsed, opt.subjectMode)
                    var questions = adjusted.mapIndexed { i, p ->
                        Question(
                            id = "q${i + 1}", number = i + 1, subject = p.subject, type = p.type, html = p.html,
                            options = p.options, optionsAreLabels = p.labels, correct = p.correct
                        )
                    }
                    var keyNote = ""
                    if (keyText.isNotBlank()) {
                        val r = AnswerKeyParser.apply(questions, keyText)
                        questions = r.questions
                        keyNote = " | key: ${r.applied}/${questions.size}"
                    }

                    val name = when {
                        opt.name.isNotBlank() && docs.size == 1 -> opt.name.trim()
                        opt.name.isNotBlank() -> opt.name.trim() + " - " + label
                        else -> label
                    }
                    val meta = TestMeta(
                        id = testId, name = name, createdAt = System.currentTimeMillis() + di,
                        source = if (ext(doc) == "pdf") "pdf" else "html",
                        questionCount = questions.size,
                        subjects = questions.map { it.subject }.distinct(),
                        durationMin = opt.durationMin.coerceIn(1, 600),
                        hasKey = questions.any { it.correct.isNotEmpty() }
                    )
                    store.saveTest(TestPaper(meta, questions))
                    created.add(meta)
                    messages.add("$name: ${questions.size} questions$keyNote" + if (!meta.hasKey) " (answer key nahi mili - 'Answer Key' se daalo)" else "")
                } catch (e: Throwable) {
                    store.deleteTest(testId)
                    messages.add("$label: import fail (${e.javaClass.simpleName}: ${e.message})")
                }
            }
        } finally {
            work.deleteRecursively()
        }
        return Outcome(created, messages)
    }

    private fun applySubjects(list: List<ParsedQuestion>, mode: Int): List<ParsedQuestion> = when (mode) {
        1 -> {
            val per = ((list.size + 2) / 3).coerceAtLeast(1)
            val names = listOf("Physics", "Chemistry", "Mathematics")
            list.mapIndexed { i, q -> q.copy(subject = names[minOf(i / per, 2)]) }
        }
        2 -> list.map { it.copy(subject = "General") }
        else -> list
    }

    private fun ext(f: File): String = f.extension.lowercase()

    private fun displayName(uri: Uri): String {
        var n: String? = null
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(0)
            }
        } catch (_: Exception) {}
        return n ?: (uri.lastPathSegment ?: "file").substringAfterLast('/')
    }

    private fun unzip(zip: File, dest: File) {
        val destCanon = dest.canonicalPath
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (out.canonicalPath.startsWith(destCanon + File.separator)) {
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zin.copyTo(it) }
                    }
                }
                entry = zin.nextEntry
            }
        }
    }
}
