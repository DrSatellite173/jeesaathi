package com.exam.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.exam.app.R
import com.exam.app.auth.AuthManager
import com.exam.app.data.UserStore
import com.exam.app.models.Attempt
import com.exam.app.models.CloudResult
import com.exam.app.models.TestMeta
import com.exam.app.parser.AnswerKeyParser
import com.exam.app.parser.TestImporter
import com.exam.app.web.WebHost
import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
}

// ====================================================================== Tracker (existing JEE Saathi web app)
@SuppressLint("SetJavaScriptEnabled")
class TrackerScreen(private val act: MainActivity, private val host: WebHost, private val store: UserStore, private val auth: AuthManager) : Screen {
    private val web = WebView(act)
    override val view: View get() = web

    private var loads = 0
    private var ready = false
    private var lastBackup: String? = null
    private val gson = Gson()

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest): WebResourceResponse? = host.intercept(r)

            override fun onPageFinished(v: WebView?, url: String?) {
                loads++
                if (loads == 1) restoreThenReload() else ready = true
            }

            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest): Boolean {
                if (r.url.host == WebHost.HOST) return false
                try { act.startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.loadUrl(WebHost.ROOT)
    }

    /** WebView ka localStorage sab users me common hota hai; isliye har user ka tracker data alag backup se restore hota hai. */
    private fun restoreThenReload() {
        val f = store.trackerBackupFile()
        if (f.isFile) {
            applyBackup(f.readText())
        } else if (com.exam.app.data.Session.cloud) {
            auth.fetchTrackerBackup { json -> act.runOnUiThread { applyBackup(json) } }
        } else applyBackup(null)
    }

    private fun applyBackup(json: String?) {
        val data = json ?: "{}"
        lastBackup = json
        val js = "(function(){var b=" + data + ";var k={v1:'jee-tracker-v1',v2:'jee-tracker-v2',t:'jee-tracker-theme'};" +
            "for(var x in k){if(b&&b[x]!=null){localStorage.setItem(k[x],b[x]);}else{localStorage.removeItem(k[x]);}}location.reload();})()"
        web.evaluateJavascript(js, null)
    }

    /** Tracker ka localStorage is user ke backup me save karta hai. done sab ho jaane ke baad (ya fail par bhi) call hota hai. */
    fun backup(done: (() -> Unit)? = null) {
        if (!ready) { done?.invoke(); return }
        val target = store.trackerBackupFile()   // abhi ke user ki file (sign-out ke baad badalni nahi chahiye)
        web.evaluateJavascript(
            "(function(){return JSON.stringify({v1:localStorage.getItem('jee-tracker-v1'),v2:localStorage.getItem('jee-tracker-v2'),t:localStorage.getItem('jee-tracker-theme')});})()"
        ) { raw ->
            try {
                val json = gson.fromJson(raw, String::class.java)
                if (json != null && json != lastBackup) {
                    lastBackup = json
                    target.writeText(json)
                    auth.pushTrackerBackup(json)
                }
            } catch (_: Exception) {
            } finally {
                done?.invoke()
            }
        }
    }

    override fun onHide() { backup(null) }
    fun destroy() { web.destroy() }
}

// ====================================================================== Tests (import + list)
class TestsScreen(private val act: MainActivity, private val store: UserStore) : Screen {
    private val root = FrameLayout(act)
    override val view: View get() = root
    private val list = RecyclerView(act)
    private val empty = UI.text(act, "Abhi koi test nahi.\n'Import Test' dabao aur HTML / PDF / ZIP chuno.", 14f, UI.color(act, R.color.text_mid)).apply {
        gravity = android.view.Gravity.CENTER
        setPadding(UI.dp(act, 24), UI.dp(act, 24), UI.dp(act, 24), UI.dp(act, 24))
    }
    private var items: List<TestMeta> = emptyList()
    private var unfinished: Map<String, Attempt> = emptyMap()
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): RecyclerView.ViewHolder {
            val c = UI.card(act)
            c.layoutParams = RecyclerView.LayoutParams(UI.MATCH, UI.WRAP).apply { setMargins(UI.dp(act, 10), UI.dp(act, 5), UI.dp(act, 10), UI.dp(act, 5)) }
            return object : RecyclerView.ViewHolder(c) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val m = items[pos]
            val c = h.itemView as LinearLayout
            c.removeAllViews()
            c.addView(UI.text(act, m.name, 16f, UI.color(act, R.color.text_dark), true))
            val att = unfinished[m.id]
            val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.createdAt))
            c.addView(UI.text(act,
                "${m.questionCount} questions  |  ${m.durationMin} min  |  ${m.subjects.joinToString(", ")}\n" +
                    "${m.source.uppercase()}  |  $dateStr  |  " + (if (m.hasKey) "Answer key: haan" else "Answer key: nahi"),
                12f, UI.color(act, R.color.text_mid)).apply { setPadding(0, UI.dp(act, 4), 0, 0) })
            if (att != null) c.addView(UI.text(act, "Chal raha attempt: Q${att.currentIndex + 1} par (resume kar sakte ho)", 12f, UI.color(act, R.color.warn), true))
            c.setOnClickListener { menu(m) }
        }
    }

    init {
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        list.setPadding(0, UI.dp(act, 6), 0, UI.dp(act, 90))
        list.clipToPadding = false
        root.addView(list, FrameLayout.LayoutParams(UI.MATCH, UI.MATCH))
        root.addView(empty, FrameLayout.LayoutParams(UI.MATCH, UI.MATCH))
        val fab = com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(act).apply {
            text = "Import Test"
            setOnClickListener { startImport() }
        }
        root.addView(fab, FrameLayout.LayoutParams(UI.WRAP, UI.WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            setMargins(0, 0, UI.dp(act, 16), UI.dp(act, 16))
        })
    }

    override fun onShow() = refresh()

    fun refresh() {
        items = store.listTests()
        unfinished = store.listAttempts().filter { !it.finished }.associateBy { it.testId }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun menu(m: TestMeta) {
        val resume = unfinished[m.id] != null
        val labels = ArrayList<String>()
        labels.add(if (resume) "Resume attempt" else "Test shuru karo")
        if (resume) labels.add("Naya attempt (restart)")
        labels.add("Answer key daalo / badlo")
        labels.add("Duration badlo")
        labels.add("Delete")
        AlertDialog.Builder(act).setTitle(m.name).setItems(labels.toTypedArray()) { _, i ->
            when (labels[i]) {
                "Resume attempt", "Test shuru karo" -> act.startActivity(Intent(act, TestActivity::class.java).putExtra("testId", m.id))
                "Naya attempt (restart)" -> act.startActivity(Intent(act, TestActivity::class.java).putExtra("testId", m.id).putExtra("fresh", true))
                "Answer key daalo / badlo" -> keyDialog(m)
                "Duration badlo" -> durationDialog(m)
                "Delete" -> AlertDialog.Builder(act).setMessage("'${m.name}' delete kare? (isse jude attempts/results bache rahenge)")
                    .setPositiveButton("Delete") { _, _ -> store.deleteTest(m.id); refresh() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun keyDialog(m: TestMeta) {
        val et = EditText(act).apply {
            hint = "1-A 2-C 3-B 4-4.5 ...   ya   1:A, 2:C   ya sirf  ABCDA..."
            minLines = 5; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(act).setTitle("Answer key").setView(et)
            .setPositiveButton("Apply") { _, _ ->
                val paper = store.loadTest(m.id) ?: return@setPositiveButton
                val r = AnswerKeyParser.apply(paper.questions, et.text.toString())
                if (r.applied == 0) { Toast.makeText(act, "Key samajh nahi aayi", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                store.saveTest(paper.copy(questions = r.questions, meta = paper.meta.copy(hasKey = true)))
                Toast.makeText(act, "${r.applied} / ${paper.questions.size} answers lag gaye", Toast.LENGTH_LONG).show()
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun durationDialog(m: TestMeta) {
        val et = EditText(act).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(m.durationMin.toString()) }
        AlertDialog.Builder(act).setTitle("Duration (minutes)").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val d = et.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: return@setPositiveButton
                val paper = store.loadTest(m.id) ?: return@setPositiveButton
                store.saveTest(paper.copy(meta = paper.meta.copy(durationMin = d)))
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startImport() {
        act.pickDocs(arrayOf("text/html", "application/pdf", "application/zip", "application/x-zip-compressed", "image/*", "*/*"), true) { uris ->
            if (uris.isNotEmpty()) optionsDialog(uris)
        }
    }

    private fun optionsDialog(uris: List<Uri>) {
        val box = UI.vbox(act, 20)
        val name = EditText(act).apply { hint = "Test ka naam (khali = file ka naam)"; setSingleLine() }
        val dur = EditText(act).apply { hint = "Duration (min)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("180") }
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item,
            listOf("Subject auto-detect (Physics/Chemistry/Maths heading se)", "Questions ko P/C/M me barabar baanto", "Sab ek hi subject"))
        box.addView(UI.text(act, "${uris.size} file(s) chuni. HTML ke saath uski saari images bhi chun lena (ya ZIP).", 12f, UI.color(act, R.color.text_mid)))
        box.addView(name); box.addView(dur); box.addView(sp)
        AlertDialog.Builder(act).setTitle("Import options").setView(box)
            .setPositiveButton("Import") { _, _ ->
                runImport(uris, TestImporter.Options(name.text.toString(), dur.text.toString().toIntOrNull() ?: 180, sp.selectedItemPosition))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun runImport(uris: List<Uri>, opt: TestImporter.Options) {
        val msg = UI.text(act, "Shuru...", 14f).apply { setPadding(UI.dp(act, 24), UI.dp(act, 20), UI.dp(act, 24), UI.dp(act, 20)) }
        val dlg = AlertDialog.Builder(act).setTitle("Import ho raha hai").setView(msg).setCancelable(false).show()
        bg.execute {
            val out = try {
                TestImporter(act, store).import(uris, opt) { p -> ui.post { msg.text = p } }
            } catch (e: Throwable) {
                TestImporter.Outcome(emptyList(), listOf("Import fail: ${e.javaClass.simpleName}: ${e.message}"))
            }
            ui.post {
                dlg.dismiss()
                refresh()
                AlertDialog.Builder(act).setTitle(if (out.created.isEmpty()) "Import nahi hua" else "Import complete")
                    .setMessage(out.messages.joinToString("\n\n")).setPositiveButton("OK", null).show()
            }
        }
    }
}

// ====================================================================== 3D Studio
class StudioScreen(private val act: MainActivity) : Screen {
    private val sv = ScrollView(act)
    override val view: View get() = sv

    init {
        val box = UI.vbox(act, 18)
        sv.addView(box)
        box.addView(UI.text(act, "3D Studio", 20f, UI.color(act, R.color.primary_dark), true))
        box.addView(UI.text(act,
            "Koi bhi photo ya video chuno. App usse depth-layers me todkar OpenGL shader se 3D parallax banata hai; phone tilt karo ya ungli se ghumao.",
            13f, UI.color(act, R.color.text_mid)).apply { setPadding(0, UI.dp(act, 6), 0, UI.dp(act, 14)) })
        box.addView(UI.button(act, "Photo ko 3D me kholo") {
            act.pickDocs(arrayOf("image/*"), false) { u -> u.firstOrNull()?.let { open(it, false) } }
        }, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(UI.gap(act, 8))
        box.addView(UI.button(act, "Video ko 3D me kholo") {
            act.pickDocs(arrayOf("video/*"), false) { u -> u.firstOrNull()?.let { open(it, true) } }
        }, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(UI.gap(act, 14))
        box.addView(UI.text(act, "Test ke andar kisi bhi question image par tap karo = wo image seedha 3D viewer me khulti hai.", 12.5f, UI.color(act, R.color.text_mid)))
        val ai = try { act.assets.list("models")?.contains("midas_small.tflite") == true } catch (e: Exception) { false }
        box.addView(UI.gap(act, 10))
        box.addView(UI.text(act,
            if (ai) "Depth engine: AI (MiDaS) active" else "Depth engine: built-in estimator. Zyada realistic depth ke liye app/src/main/assets/models/midas_small.tflite file daalo (README dekho).",
            12f, if (ai) UI.color(act, R.color.ok) else UI.color(act, R.color.warn)))
    }

    private fun open(uri: Uri, video: Boolean) {
        val i = Intent(act, Viewer3DActivity::class.java)
        i.data = uri
        i.putExtra("video", video)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        act.startActivity(i)
    }
}

// ====================================================================== Results
class ResultsScreen(private val act: MainActivity, private val store: UserStore, private val auth: AuthManager) : Screen {
    private val sv = ScrollView(act)
    override val view: View get() = sv
    private val box = UI.vbox(act, 10)

    init { sv.addView(box) }

    override fun onShow() = refresh()

    private fun refresh() {
        box.removeAllViews()
        val attempts = store.listAttempts().filter { it.finished && it.analysis != null }
        box.addView(UI.text(act, "Mere Results", 18f, UI.color(act, R.color.primary_dark), true))
        if (attempts.isEmpty()) box.addView(UI.text(act, "Abhi koi submitted test nahi.", 13f, UI.color(act, R.color.text_mid)).apply { setPadding(0, UI.dp(act, 8), 0, 0) })
        attempts.forEach { a ->
            val an = a.analysis!!
            val c = UI.card(act)
            c.addView(UI.text(act, a.testName, 15f, UI.color(act, R.color.text_dark), true))
            c.addView(UI.text(act,
                "Score ${an.score}/${an.maxScore}  |  Accuracy ${String.format(Locale.US, "%.1f", an.accuracy)}%  |  ${UI.fmtTime(an.totalTimeSec)}\n" +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.finishedAt)),
                12f, UI.color(act, R.color.text_mid)).apply { setPadding(0, UI.dp(act, 4), 0, 0) })
            c.setOnClickListener { act.startActivity(Intent(act, ResultActivity::class.java).putExtra("attemptId", a.id)) }
            box.addView(c, UI.lp(UI.MATCH, UI.WRAP).apply { topMargin = UI.dp(act, 8) })
        }
        if (com.exam.app.data.Session.cloud) {
            val localIds = attempts.map { it.id }.toSet()
            auth.fetchCloudResults { cloud ->
                act.runOnUiThread { addCloud(cloud.filter { it.id !in localIds }) }
            }
        }
    }

    private fun addCloud(list: List<CloudResult>) {
        if (list.isEmpty()) return
        box.addView(UI.text(act, "Cloud se (dusre device ke results)", 14f, UI.color(act, R.color.primary_dark), true).apply {
            setPadding(0, UI.dp(act, 16), 0, 0)
        })
        list.forEach { r ->
            val c = UI.card(act)
            c.addView(UI.text(act, r.testName, 15f, UI.color(act, R.color.text_dark), true))
            c.addView(UI.text(act, "Score ${r.score}/${r.maxScore}  |  Accuracy ${String.format(Locale.US, "%.1f", r.accuracy)}%\n" +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.finishedAt)),
                12f, UI.color(act, R.color.text_mid)))
            box.addView(c, UI.lp(UI.MATCH, UI.WRAP).apply { topMargin = UI.dp(act, 8) })
        }
    }
}
