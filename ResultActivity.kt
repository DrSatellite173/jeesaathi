package com.exam.app.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.exam.app.R
import com.exam.app.data.UserStore
import com.exam.app.models.Analysis
import com.exam.app.models.Attempt
import com.exam.app.models.Response
import com.exam.app.models.TestPaper
import com.exam.app.web.WebHost
import java.util.Locale

class ResultActivity : AppCompatActivity() {
    private lateinit var store: UserStore
    private var paper: TestPaper? = null
    private lateinit var attempt: Attempt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UI.ensureSession(this)) return
        store = UserStore(this)
        val a = intent.getStringExtra("attemptId")?.let { store.loadAttempt(it) }
        val an = a?.analysis
        if (a == null || an == null) { finish(); return }
        attempt = a
        paper = store.loadTest(a.testId)
        build(an)
    }

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

    private fun statBox(label: String, value: String, color: Int): LinearLayout =
        UI.card(this).apply {
            gravity = android.view.Gravity.CENTER
            addView(UI.text(context, value, 20f, color, true).apply { gravity = android.view.Gravity.CENTER })
            addView(UI.text(context, label, 11f, UI.color(context, R.color.text_mid)).apply { gravity = android.view.Gravity.CENTER })
        }

    private fun build(an: Analysis) {
        val sv = ScrollView(this)
        val root = UI.vbox(this, 12)
        sv.addView(root)

        root.addView(UI.text(this, attempt.testName, 18f, UI.color(this, R.color.text_dark), true))
        root.addView(UI.gap(this, 8))

        val hero = UI.card(this).apply { gravity = android.view.Gravity.CENTER }
        hero.addView(UI.text(this, "${an.score} / ${an.maxScore}", 34f, UI.color(this, R.color.primary_dark), true).apply { gravity = android.view.Gravity.CENTER })
        hero.addView(UI.text(this, "Score  |  ${f1(an.percentage)}%", 13f, UI.color(this, R.color.text_mid)).apply { gravity = android.view.Gravity.CENTER })
        root.addView(hero, UI.lp(UI.MATCH, UI.WRAP))
        root.addView(UI.gap(this, 8))

        val r1 = UI.hbox(this)
        r1.addView(statBox("Correct", an.correct.toString(), UI.color(this, R.color.ok)), UI.lp(0, UI.WRAP, 1f).apply { rightMargin = UI.dp(this@ResultActivity, 6) })
        r1.addView(statBox("Wrong", an.wrong.toString(), UI.color(this, R.color.bad)), UI.lp(0, UI.WRAP, 1f).apply { rightMargin = UI.dp(this@ResultActivity, 6) })
        r1.addView(statBox("Skipped", an.unattempted.toString(), UI.color(this, R.color.text_mid)), UI.lp(0, UI.WRAP, 1f))
        root.addView(r1, UI.lp(UI.MATCH, UI.WRAP))
        root.addView(UI.gap(this, 8))

        val r2 = UI.hbox(this)
        r2.addView(statBox("Accuracy", f1(an.accuracy) + "%", UI.color(this, R.color.primary_dark)), UI.lp(0, UI.WRAP, 1f).apply { rightMargin = UI.dp(this@ResultActivity, 6) })
        r2.addView(statBox("Time", UI.fmtTime(an.totalTimeSec), UI.color(this, R.color.primary_dark)), UI.lp(0, UI.WRAP, 1f).apply { rightMargin = UI.dp(this@ResultActivity, 6) })
        r2.addView(statBox("Avg / attempt", f1(an.avgTimeSec) + "s", UI.color(this, R.color.primary_dark)), UI.lp(0, UI.WRAP, 1f))
        root.addView(r2, UI.lp(UI.MATCH, UI.WRAP))

        if (an.unscored > 0) {
            root.addView(UI.text(this, "Note: ${an.unscored} attempted questions ka answer key nahi tha, isliye score me count nahi hue.", 12f, UI.color(this, R.color.warn)).apply {
                setPadding(0, UI.dp(context, 8), 0, 0)
            })
        }

        // subject-wise
        root.addView(UI.gap(this, 14))
        root.addView(UI.text(this, "Subject-wise", 16f, UI.color(this, R.color.text_dark), true))
        root.addView(UI.gap(this, 6))
        an.subjects.forEach { s ->
            val c = UI.card(this)
            c.addView(UI.text(this, s.subject, 15f, UI.color(this, R.color.primary_dark), true))
            c.addView(UI.text(this,
                "Score ${s.score}/${s.maxScore}   |   Correct ${s.correct}   Wrong ${s.wrong}   Attempted ${s.attempted}/${s.total}\n" +
                    "Accuracy ${f1(s.accuracy)}%   |   Time ${UI.fmtTime(s.timeSec)}",
                12.5f, UI.color(this, R.color.text_mid)).apply { setPadding(0, UI.dp(context, 4), 0, 0) })
            root.addView(c, UI.lp(UI.MATCH, UI.WRAP).apply { bottomMargin = UI.dp(this@ResultActivity, 6) })
        }

        // time analysis
        root.addView(UI.gap(this, 10))
        root.addView(UI.text(this, "Time analysis", 16f, UI.color(this, R.color.text_dark), true))
        root.addView(UI.gap(this, 6))
        val t = UI.card(this)
        t.addView(UI.text(this, "Perfect (sahi + time pe): ${an.perfect}", 13f, UI.color(this, R.color.ok)))
        t.addView(UI.text(this, "Overtime (sahi par dheere): ${an.overtime}", 13f, UI.color(this, R.color.warn)))
        t.addView(UI.text(this, "Wasted (galat attempt): ${an.wasted}", 13f, UI.color(this, R.color.bad)))
        t.addView(UI.text(this, "Skipped: ${an.unattempted}", 13f, UI.color(this, R.color.text_mid)))
        root.addView(t, UI.lp(UI.MATCH, UI.WRAP))

        // question-wise
        root.addView(UI.gap(this, 14))
        root.addView(UI.text(this, "Question-wise" + if (paper != null) " (tap = review)" else "", 16f, UI.color(this, R.color.text_dark), true))
        root.addView(UI.gap(this, 6))
        val grid = GridLayout(this).apply { columnCount = 6 }
        an.questions.forEachIndexed { i, qs ->
            val col = when (qs.outcome) {
                "CORRECT" -> UI.color(this, R.color.nta_a)
                "WRONG" -> UI.color(this, R.color.nta_na)
                "UNSCORED" -> UI.color(this, R.color.nta_m)
                else -> UI.color(this, R.color.nta_nv)
            }
            val cell = TextView(this).apply {
                text = qs.number.toString()
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setTextColor(if (qs.outcome == "UNATTEMPTED") Color.BLACK else Color.WHITE)
                background = UI.circle(col)
                setOnClickListener { review(i) }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = UI.dp(this@ResultActivity, 46); height = UI.dp(this@ResultActivity, 46)
                setMargins(UI.dp(this@ResultActivity, 4), UI.dp(this@ResultActivity, 4), UI.dp(this@ResultActivity, 4), UI.dp(this@ResultActivity, 4))
            })
        }
        root.addView(grid)
        root.addView(UI.gap(this, 16))
        root.addView(UI.button(this, "Close") { finish() }, UI.lp(UI.MATCH, UI.WRAP))
        setContentView(sv)
    }

    private fun review(i: Int) {
        val p = paper ?: return
        val q = p.questions.getOrNull(i) ?: return
        val r = attempt.responses[q.id] ?: Response()
        val host = WebHost(this, store)
        val nta = NtaView(this, host)
        val review = mapOf<String, Any?>("correct" to q.correct)
        nta.render(q, p.questions.size, r, review)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Q${q.number}  (${q.subject})")
            .setView(nta.web)
            .setPositiveButton("Close", null)
            .create()
        dlg.setOnDismissListener { nta.destroy() }
        dlg.show()
        dlg.window?.setLayout(UI.MATCH, (resources.displayMetrics.heightPixels * 0.85).toInt())
    }
}
