package com.exam.app.parser

import com.exam.app.models.Question

/**
 * Answer key text se correct answers lagata hai. Formats: "1-A 2-C 3-4.5", "1:B, 2:D", "1. 2  2. 4", ya sirf "ABCDABCD".
 * Agar pairs ki ginti questions ke barabar ho to order se (restart hone wale numbering ke liye safe),
 * warna question number se match karta hai.
 */
object AnswerKeyParser {
    private val pair = Regex("(\\d{1,3})\\s*[-\\u2013\\u2014.:)=\\s]\\s*\\(?\\s*([A-Da-d]|-?\\d+(?:\\.\\d+)?)\\s*\\)?")
    private val lettersOnly = Regex("^[\\sA-Da-d,;|]+$")

    data class Result(val questions: List<Question>, val applied: Int)

    fun apply(questions: List<Question>, text: String): Result {
        val t = text.trim()
        if (t.isEmpty() || questions.isEmpty()) return Result(questions, 0)

        val answers = ArrayList<Pair<Int, String>>()  // (number or -1, answer)
        val pairs = pair.findAll(t).toList()
        if (pairs.isNotEmpty()) {
            pairs.forEach { answers.add(Pair(it.groupValues[1].toInt(), it.groupValues[2])) }
        } else if (lettersOnly.matches(t)) {
            Regex("[A-Da-d]").findAll(t).forEach { answers.add(Pair(-1, it.value)) }
        } else return Result(questions, 0)

        val byOrder = answers.size == questions.size
        val byNum = HashMap<Int, String>()
        if (!byOrder) answers.forEach { if (it.first > 0) byNum[it.first] = it.second }

        var applied = 0
        val out = questions.mapIndexed { i, q ->
            val raw = if (byOrder) answers[i].second else byNum[q.number]
            val c = convert(q, raw)
            if (c.isNotEmpty()) { applied++; q.copy(correct = c) } else q
        }
        return Result(out, applied)
    }

    private fun convert(q: Question, raw: String?): String {
        if (raw == null) return ""
        val s = raw.trim()
        if (q.type == "MCQ") {
            val n = maxOf(q.options.size, 4)
            if (s.length == 1 && s[0].uppercaseChar() in 'A'..'D') {
                val i = s[0].uppercaseChar() - 'A'
                return if (i < n) i.toString() else ""
            }
            val d = s.toIntOrNull()
            return if (d != null && d in 1..n) (d - 1).toString() else ""
        }
        return if (s.toDoubleOrNull() != null) s else ""
    }
}
