package com.exam.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.exam.app.R
import com.exam.app.analytics.AnalyticsEngine
import com.exam.app.auth.AuthManager
import com.exam.app.data.UserStore
import com.exam.app.models.Attempt
import com.exam.app.models.QStatus
import com.exam.app.models.Response
import com.exam.app.models.TestPaper
import com.exam.app.web.WebHost
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.UUID

class TestActivity : AppCompatActivity() {

    private lateinit var store: UserStore
    private lateinit var host: WebHost
    private lateinit var auth: AuthManager
    private lateinit var paper: TestPaper
    private lateinit var attempt: Attempt
    private lateinit var nta: NtaView

    private lateinit var tvTimer: TextView
    private lateinit var tvInfo: TextView
    private lateinit var subjectRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var shownAt = 0L
    private var finishing = false

    private val ticker = object : Runnable {
        override fun run() {
            attempt.elapsedSec++
            updateTimer()
            if (attempt.elapsedSec % 5L == 0L) persist()
            if (remainingSec() <= 0) { finishTest(true); return }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UI.ensureSession(this)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = UserStore(this)
        host = WebHost(this, store)
        auth = AuthManager(this)

        val testId = intent.getStringExtra("testId")
        val loaded = testId?.let { store.loadTest(it) }
        if (loaded == null || loaded.questions.isEmpty()) {
            Toast.makeText(this, "Test load nahi hua", Toast.LENGTH_LONG).show()
            finish(); return
        }
        paper = loaded
        val fresh = intent.getBooleanExtra("fresh", false)
        var existing = store.findUnfinished(paper.meta.id)
        if (fresh && existing != null) { existing.finished = true; store.saveAttempt(existing); existing = null }
        attempt = existing ?: Attempt(
            id = UUID.randomUUID().toString(), testId = paper.meta.id, testName = paper.meta.name,
            userId = com.exam.app.data.Session.uid, startedAt = System.currentTimeMillis()
        )
        index = attempt.currentIndex.coerceIn(0, paper.questions.size - 1)

        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmExit() }
        })
        enter(index)
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        val top = UI.hbox(this, 10).apply { setBackgroundColor(UI.color(context, R.color.primary)) }
        val title = UI.text(this, paper.meta.name, 15f, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvTimer = UI.text(this, "--:--", 16f, UI.color(this, R.color.accent), true)
        top.addView(title, UI.lp(0, UI.WRAP, 1f))
        top.addView(tvTimer)
        root.addView(top, UI.lp(UI.MATCH, UI.WRAP))

        val subjects = paper.questions.map { it.subject }.distinct()
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Color.parseColor("#EEF2FA")) }
        subjectRow = UI.hbox(this, 6)
        subjects.forEach { s ->
            val chip = UI.text(this, s, 13f, UI.color(this, R.color.text_dark), true).apply {
                setPadding(UI.dp(context, 14), UI.dp(context, 6), UI.dp(context, 14), UI.dp(context, 6))
                tag = s
                setOnClickListener {
                    val i = paper.questions.indexOfFirst { q -> q.subject == s }
                    if (i >= 0) enter(i)
                }
            }
            subjectRow.addView(chip, UI.lp(UI.WRAP, UI.WRAP).apply { setMargins(UI.dp(this@TestActivity, 4), 0, UI.dp(this@TestActivity, 4), 0) })
        }
        hs.addView(subjectRow)
        if (subjects.size > 1) root.addView(hs, UI.lp(UI.MATCH, UI.WRAP))

        tvInfo = UI.text(this, "", 13f, UI.color(this, R.color.text_mid)).apply {
            setPadding(UI.dp(context, 12), UI.dp(context, 6), UI.dp(context, 12), UI.dp(context, 6))
        }
        root.addView(tvInfo, UI.lp(UI.MATCH, UI.WRAP))

        nta = NtaView(this, host)
        nta.selectCb = { i ->
            val r = resp(index)
            r.selected = i
            persistSoon()
            updateInfo()
        }
        nta.numericCb = { v ->
            resp(index).numeric = v.trim()
            persistSoon()
        }
        nta.imageCb = { src -> openIn3D(src) }
        root.addView(nta.web, UI.lp(UI.MATCH, 0, 1f))

        val row1 = UI.hbox(this, 6)
        row1.addView(UI.button(this, "Mark & Next", true) { markNext() }, UI.lp(0, UI.WRAP, 1f))
        row1.addView(UI.button(this, "Clear", true) { clearResponse() }, UI.lp(0, UI.WRAP, 1f))
        row1.addView(UI.button(this, "Palette", true) { showPalette() }, UI.lp(0, UI.WRAP, 1f))
        root.addView(row1, UI.lp(UI.MATCH, UI.WRAP))

        val row2 = UI.hbox(this, 6)
        row2.addView(UI.button(this, "Previous", true) { if (index > 0) enter(index - 1) }, UI.lp(0, UI.WRAP, 1f))
        row2.addView(UI.button(this, "Save & Next") { saveNext() }, UI.lp(0, UI.WRAP, 1.3f))
        row2.addView(UI.button(this, "Submit") { confirmSubmit() }.apply {
            setBackgroundColor(UI.color(context, R.color.bad))
        }, UI.lp(0, UI.WRAP, 1f))
        root.addView(row2, UI.lp(UI.MATCH, UI.WRAP))

        setContentView(root)
        updateTimer()
    }

    // ------------------------------------------------------------------ state
    private fun resp(i: Int): Response = attempt.responses.getOrPut(paper.questions[i].id) { Response() }

    private fun remainingSec(): Long = paper.meta.durationMin * 60L - attempt.elapsedSec

    private fun accumulate() {
        val now = SystemClock.elapsedRealtime()
        if (shownAt > 0) resp(index).timeMs += now - shownAt
        shownAt = now
    }

    private fun enter(i: Int) {
        accumulate()
        index = i.coerceIn(0, paper.questions.size - 1)
        attempt.currentIndex = index
        val r = resp(index)
        r.visited = true
        nta.render(paper.questions[index], paper.questions.size, r)
        updateInfo()
        highlightSubject()
        persistSoon()
    }

    private fun saveNext() {
        if (index < paper.questions.size - 1) enter(index + 1)
        else { persist(); Toast.makeText(this, "Ye aakhri question hai", Toast.LENGTH_SHORT).show() }
    }

    private fun markNext() {
        resp(index).marked = true
        saveNext()
        if (index == paper.questions.size - 1) updateInfo()
    }

    private fun clearResponse() {
        val r = resp(index)
        r.selected = -1
        r.numeric = ""
        r.marked = false
        nta.render(paper.questions[index], paper.questions.size, r)
        updateInfo()
        persistSoon()
    }

    private fun updateTimer() {
        val rem = remainingSec()
        tvTimer.text = UI.fmtTime(rem)
        tvTimer.setTextColor(if (rem < 300) Color.parseColor("#FF8A80") else UI.color(this, R.color.accent))
    }

    private fun updateInfo() {
        val q = paper.questions[index]
        val m = paper.meta
        val neg = if (q.type == "NUM") m.minusNum else m.minusMcq
        val st = resp(index).status()
        val label = when (st) {
            QStatus.ANSWERED -> "Answered"
            QStatus.MARKED -> "Marked"
            QStatus.ANSWERED_MARKED -> "Answered + Marked"
            QStatus.NOT_ANSWERED -> "Not answered"
            else -> ""
        }
        tvInfo.text = "Q ${index + 1} / ${paper.questions.size}  |  ${q.subject}  |  ${if (q.type == "NUM") "Numerical" else "MCQ"}  |  +${m.plusMarks} / -$neg  |  $label"
    }

    private fun highlightSubject() {
        val cur = paper.questions[index].subject
        for (i in 0 until subjectRow.childCount) {
            val v = subjectRow.getChildAt(i) as TextView
            val on = v.tag == cur
            v.background = UI.rounded(if (on) UI.color(this, R.color.primary) else Color.WHITE, UI.dp(this, 16), UI.dp(this, 1), Color.parseColor("#C5CCDB"))
            v.setTextColor(if (on) Color.WHITE else UI.color(this, R.color.text_dark))
        }
    }

    // ------------------------------------------------------------------ persistence
    private var saveQueued = false
    private fun persistSoon() {
        if (saveQueued) return
        saveQueued = true
        handler.postDelayed({ saveQueued = false; persist() }, 1500)
    }

    private fun persist() {
        if (finishing) return
        try { store.saveAttempt(attempt) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        shownAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (::paper.isInitialized && !finishing) {
            accumulate()
            shownAt = 0
            persist()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::nta.isInitialized) nta.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ palette
    private fun statusColor(s: QStatus): Int = when (s) {
        QStatus.NOT_VISITED -> UI.color(this, R.color.nta_nv)
        QStatus.NOT_ANSWERED -> UI.color(this, R.color.nta_na)
        QStatus.ANSWERED -> UI.color(this, R.color.nta_a)
        QStatus.MARKED, QStatus.ANSWERED_MARKED -> UI.color(this, R.color.nta_m)
    }

    private fun counts(): IntArray {
        val c = IntArray(5)
        for (i in paper.questions.indices) c[resp(i).status().ordinal]++
        return c
    }

    private fun showPalette() {
        val dlg = BottomSheetDialog(this)
        val sv = ScrollView(this)
        val box = UI.vbox(this, 14)
        sv.addView(box)

        val c = counts()
        box.addView(UI.text(this, "Question Palette", 17f, UI.color(this, R.color.text_dark), true))
        box.addView(UI.text(this,
            "Answered ${c[QStatus.ANSWERED.ordinal]}   Not answered ${c[QStatus.NOT_ANSWERED.ordinal]}   Not visited ${c[QStatus.NOT_VISITED.ordinal]}\n" +
                "Marked ${c[QStatus.MARKED.ordinal]}   Answered+Marked ${c[QStatus.ANSWERED_MARKED.ordinal]}",
            12f, UI.color(this, R.color.text_mid)).apply { setPadding(0, UI.dp(context, 4), 0, UI.dp(context, 10)) })

        val subjects = paper.questions.map { it.subject }.distinct()
        for (s in subjects) {
            if (subjects.size > 1) box.addView(UI.text(this, s, 14f, UI.color(this, R.color.primary_dark), true).apply {
                setPadding(0, UI.dp(context, 8), 0, UI.dp(context, 6))
            })
            val grid = GridLayout(this).apply { columnCount = 6 }
            paper.questions.forEachIndexed { i, q ->
                if (q.subject != s) return@forEachIndexed
                val st = resp(i).status()
                val cell = TextView(this).apply {
                    text = (i + 1).toString()
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(if (st == QStatus.NOT_VISITED) Color.BLACK else Color.WHITE)
                    background = if (st == QStatus.ANSWERED_MARKED)
                        UI.circle(statusColor(st), UI.dp(context, 3), UI.color(context, R.color.nta_a))
                    else UI.circle(statusColor(st), if (i == index) UI.dp(context, 3) else 0, Color.BLACK)
                    setOnClickListener { dlg.dismiss(); enter(i) }
                }
                val p = GridLayout.LayoutParams().apply {
                    width = UI.dp(this@TestActivity, 46); height = UI.dp(this@TestActivity, 46)
                    setMargins(UI.dp(this@TestActivity, 4), UI.dp(this@TestActivity, 4), UI.dp(this@TestActivity, 4), UI.dp(this@TestActivity, 4))
                }
                grid.addView(cell, p)
            }
            box.addView(grid)
        }
        dlg.setContentView(sv)
        dlg.show()
    }

    // ------------------------------------------------------------------ submit / exit
    private fun confirmSubmit() {
        val c = counts()
        val msg = "Answered: ${c[QStatus.ANSWERED.ordinal] + c[QStatus.ANSWERED_MARKED.ordinal]}\n" +
            "Not answered: ${c[QStatus.NOT_ANSWERED.ordinal] + c[QStatus.MARKED.ordinal]}\n" +
            "Not visited: ${c[QStatus.NOT_VISITED.ordinal]}\n\nSubmit karna hai?"
        AlertDialog.Builder(this).setTitle("Submit Test").setMessage(msg)
            .setPositiveButton("Submit") { _, _ -> finishTest(false) }
            .setNegativeButton("Wapas", null).show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this).setTitle("Test se bahar?")
            .setMessage("Progress save ho jayegi. Timer sirf tab chalta hai jab test khula ho; baad me Resume kar sakte ho.")
            .setPositiveButton("Bahar jao") { _, _ -> persist(); finish() }
            .setNegativeButton("Test me raho", null).show()
    }

    private fun finishTest(auto: Boolean) {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        accumulate()
        attempt.finished = true
        attempt.finishedAt = System.currentTimeMillis()
        attempt.analysis = AnalyticsEngine.analyze(paper, attempt)
        try { store.saveAttempt(attempt) } catch (_: Exception) {}
        auth.pushResult(attempt)
        if (auto) Toast.makeText(this, "Time khatam - test auto-submit ho gaya", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, ResultActivity::class.java).putExtra("attemptId", attempt.id))
        finish()
    }

    // ------------------------------------------------------------------ 3D
    private fun openIn3D(src: String) {
        val f = host.resolveMedia(src)
        if (f != null) {
            startActivity(Intent(this, Viewer3DActivity::class.java).putExtra("path", f.absolutePath))
        } else if (src.startsWith("data:image")) {
            Toast.makeText(this, "Embedded image 3D me abhi support nahi (file image tap karo)", Toast.LENGTH_SHORT).show()
        }
    }
}
