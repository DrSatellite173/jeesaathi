package com.exam.app.models

enum class QStatus { NOT_VISITED, NOT_ANSWERED, ANSWERED, MARKED, ANSWERED_MARKED }

/** type: "MCQ" ya "NUM" (numerical). correct: MCQ me option index ("0".."3"), NUM me number text, "" = key nahi. */
data class Question(
    val id: String,
    val number: Int,
    val subject: String,
    val type: String,
    val html: String,
    val options: List<String> = emptyList(),
    val optionsAreLabels: Boolean = false,
    val correct: String = ""
)

data class TestMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val source: String,
    val questionCount: Int,
    val subjects: List<String>,
    val durationMin: Int,
    val hasKey: Boolean,
    val plusMarks: Int = 4,
    val minusMcq: Int = 1,
    val minusNum: Int = 0
)

data class TestPaper(val meta: TestMeta, val questions: List<Question>)

data class Response(
    var selected: Int = -1,
    var numeric: String = "",
    var marked: Boolean = false,
    var visited: Boolean = false,
    var timeMs: Long = 0
) {
    fun hasAnswer(): Boolean = selected >= 0 || numeric.isNotBlank()

    fun status(): QStatus = when {
        !visited -> QStatus.NOT_VISITED
        hasAnswer() && marked -> QStatus.ANSWERED_MARKED
        hasAnswer() -> QStatus.ANSWERED
        marked -> QStatus.MARKED
        else -> QStatus.NOT_ANSWERED
    }
}

data class SubjectStat(
    val subject: String,
    var total: Int = 0,
    var attempted: Int = 0,
    var correct: Int = 0,
    var wrong: Int = 0,
    var score: Int = 0,
    var maxScore: Int = 0,
    var timeSec: Long = 0,
    var accuracy: Double = 0.0
)

/** outcome: CORRECT / WRONG / UNATTEMPTED / UNSCORED.  category: PERFECT / OVERTIME / WASTED / SKIPPED / UNSCORED */
data class QStat(
    val qid: String,
    val number: Int,
    val subject: String,
    val outcome: String,
    val category: String,
    val timeSec: Long
)

data class Analysis(
    val score: Int,
    val maxScore: Int,
    val attempted: Int,
    val correct: Int,
    val wrong: Int,
    val unattempted: Int,
    val unscored: Int,
    val accuracy: Double,
    val percentage: Double,
    val totalTimeSec: Long,
    val avgTimeSec: Double,
    val perfect: Int,
    val overtime: Int,
    val wasted: Int,
    val subjects: List<SubjectStat>,
    val questions: List<QStat>
)

data class Attempt(
    val id: String,
    val testId: String,
    val testName: String,
    val userId: String,
    val startedAt: Long,
    var elapsedSec: Long = 0,
    var currentIndex: Int = 0,
    val responses: MutableMap<String, Response> = mutableMapOf(),
    var finished: Boolean = false,
    var finishedAt: Long = 0,
    var analysis: Analysis? = null
)

/** Cloud se aaya result summary (per-question detail ke bina). */
data class CloudResult(
    val id: String,
    val testName: String,
    val score: Long,
    val maxScore: Long,
    val percentage: Double,
    val accuracy: Double,
    val finishedAt: Long
)
