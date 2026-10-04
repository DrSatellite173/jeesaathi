package com.exam.app.parser

import android.net.Uri
import java.io.File

data class ParsedQuestion(
    val subject: String,
    val type: String,               // "MCQ" / "NUM"
    val html: String,
    val options: List<String>,
    val correct: String,            // MCQ: index text, NUM: number text, "" unknown
    val labels: Boolean = false     // true => options sirf labels hain (PDF image mode)
)

object Subjects {
    private val HEAD = Regex(
        "(?i)^\\W*(?:section\\s*\\w?\\W+)?(physics|chemistry|mathematics|maths?)\\b" +
            "(?:\\W+(?:section|part)\\s*\\w{1,2})?" +
            "(?:\\W+(?:single|multiple|numerical|integer|mcq)[\\w\\s()-]{0,30})?\\W*$"
    )
    private val CLASS_HINT = Regex("(?i)\\b(physics|chemistry|mathematics|maths?)\\b")

    private fun norm(s: String): String = when (s.lowercase()) {
        "physics" -> "Physics"
        "chemistry" -> "Chemistry"
        else -> "Mathematics"
    }

    /** Chhoti heading line (jaise "Physics", "Section A - Chemistry") se subject nikaalta hai. */
    fun detect(text: String): String? {
        val t = text.trim()
        if (t.length < 3 || t.length > 60) return null
        HEAD.find(t)?.let { return norm(it.groupValues[1]) }
        return null
    }

    fun fromClass(s: String): String? = CLASS_HINT.find(s)?.let { norm(it.groupValues[1]) }
}

/** Import ki hui files (html ke images) ko dhoondhta hai - relative path, ../ , ya sirf file-name se. */
class MediaIndex(private val dir: File) {
    private val byPath = HashMap<String, String>()
    private val byName = HashMap<String, String>()

    init {
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                byPath[rel.lowercase()] = rel
                byName.putIfAbsent(f.name.lowercase(), rel)
            }
        }
    }

    fun find(src: String): String? {
        var s = src.trim().substringBefore('?').substringBefore('#')
        if (s.isEmpty()) return null
        s = Uri.decode(s).replace('\\', '/')
        s = s.removePrefix("file:///").removePrefix("file://")
        while (s.startsWith("../") || s.startsWith("./") || s.startsWith("/")) {
            s = if (s.startsWith("../")) s.substring(3) else if (s.startsWith("./")) s.substring(2) else s.substring(1)
        }
        val k = s.lowercase()
        byPath[k]?.let { return it }
        byPath.entries.firstOrNull { it.key.endsWith("/$k") }?.let { return it.value }
        return byName[k.substringAfterLast('/')]
    }
}
