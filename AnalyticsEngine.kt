package com.exam.app.analytics

import com.exam.app.models.Analysis
import com.exam.app.models.Attempt
import com.exam.app.models.QStat
import com.exam.app.models.Response
import com.exam.app.models.SubjectStat
import com.exam.app.models.TestPaper
import kotlin.math.abs

/** Mathongo-style analysis: score, accuracy, subject-wise, time analysis (perfect / overtime / wasted). */
object AnalyticsEngine {

    fun isCorrect(type: String, correct: String, r: Response): Boolean {
        if (type == "NUM") {
            val a = r.numeric.trim().toDoubleOrNull()
            val b = correct.trim().toDoubleOrNull()
            return a != null && b != null && abs(a - b) <= 0.01
        }
        return r.selected >= 0 && r.selected.toString() == correct
    }

    fun analyze(paper: TestPaper, attempt: Attempt): Analysis {
        val meta = paper.meta
        val n = paper.questions.size.coerceAtLeast(1)
        val targetSec = (meta.durationMin * 60.0) / n
        val slowSec = targetSec * 1.5

        var score = 0; var attempted = 0; var correct = 0; var wrong = 0; var unattempted = 0; var unscored = 0
        var perfect = 0; var overtime = 0; var wasted = 0
        var sumTimeSec = 0L
        val subjects = LinkedHashMap<String, SubjectStat>()
        val qStats = ArrayList<QStat>()

        for (q in paper.questions) {
            val r = attempt.responses[q.id] ?: Response()
            val st = subjects.getOrPut(q.subject) { SubjectStat(q.subject) }
            st.total++
            st.maxScore += meta.plusMarks
            val tSec = r.timeMs / 1000
            st.timeSec += tSec
            sumTimeSec += tSec

            val has = r.hasAnswer()
            var outcome: String
            var category: String
            if (!has) {
                unattempted++
                outcome = "UNATTEMPTED"
                category = "SKIPPED"
            } else {
                attempted++
                st.attempted++
                if (q.correct.isEmpty()) {
                    unscored++
                    outcome = "UNSCORED"
                    category = "UNSCORED"
                } else if (isCorrect(q.type, q.correct, r)) {
                    correct++; st.correct++
                    score += meta.plusMarks; st.score += meta.plusMarks
                    outcome = "CORRECT"
                    if (tSec > slowSec) { overtime++; category = "OVERTIME" } else { perfect++; category = "PERFECT" }
                } else {
                    wrong++; st.wrong++
                    val minus = if (q.type == "NUM") meta.minusNum else meta.minusMcq
                    score -= minus; st.score -= minus
                    wasted++
                    outcome = "WRONG"
                    category = "WASTED"
                }
            }
            qStats.add(QStat(q.id, q.number, q.subject, outcome, category, tSec))
        }

        subjects.values.forEach { s ->
            val denom = s.correct + s.wrong
            s.accuracy = if (denom > 0) s.correct * 100.0 / denom else 0.0
        }
        val denom = correct + wrong
        val accuracy = if (denom > 0) correct * 100.0 / denom else 0.0
        val maxScore = n * meta.plusMarks
        val pct = if (maxScore > 0) score * 100.0 / maxScore else 0.0
        val total = if (attempt.elapsedSec > 0) attempt.elapsedSec else sumTimeSec
        val avg = if (attempted > 0) sumTimeSec.toDouble() / attempted else 0.0

        return Analysis(
            score = score, maxScore = maxScore, attempted = attempted, correct = correct, wrong = wrong,
            unattempted = unattempted, unscored = unscored, accuracy = accuracy, percentage = pct,
            totalTimeSec = total, avgTimeSec = avg, perfect = perfect, overtime = overtime, wasted = wasted,
            subjects = subjects.values.toList(), questions = qStats
        )
    }
}
