package com.exam.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.exam.app.R
import com.exam.app.auth.AuthManager
import com.exam.app.data.Session
import com.exam.app.data.UserStore
import com.exam.app.web.WebHost
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    private lateinit var auth: AuthManager
    private lateinit var store: UserStore
    private lateinit var container: FrameLayout
    private lateinit var tracker: TrackerScreen
    private lateinit var screens: Map<Int, Screen>
    private var current: Screen? = null

    private var pickCb: ((List<Uri>) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickCb?.invoke(uris ?: emptyList()); pickCb = null
    }

    /** Files chuno (multiple ho sakti hain). mimes me "*/*" ho to koi bhi file. */
    fun pickDocs(mimes: Array<String>, multiple: Boolean, cb: (List<Uri>) -> Unit) {
        pickCb = { list -> cb(if (multiple) list else list.take(1)) }
        picker.launch(mimes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UI.ensureSession(this)) return
        auth = AuthManager(this)
        store = UserStore(this)
        val host = WebHost(this, store)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = UI.hbox(this, 12).apply { setBackgroundColor(UI.color(context, R.color.primary)) }
        val title = UI.text(this, "JEE Saathi", 18f, android.graphics.Color.WHITE, true)
        val who = UI.text(this, Session.name, 12f, android.graphics.Color.parseColor("#DDE8FF"))
        val more = UI.text(this, "\u22EE", 22f, android.graphics.Color.WHITE, true).apply {
            setPadding(UI.dp(context, 14), 0, UI.dp(context, 4), 0)
            setOnClickListener { v -> showMenu(v) }
        }
        val titleBox = UI.vbox(this).apply { addView(title); addView(who) }
        header.addView(titleBox, UI.lp(0, UI.WRAP, 1f))
        header.addView(more)
        root.addView(header, UI.lp(UI.MATCH, UI.WRAP))

        container = FrameLayout(this)
        root.addView(container, UI.lp(UI.MATCH, 0, 1f))

        val nav = BottomNavigationView(this)
        nav.inflateMenu(R.menu.bottom_nav)
        root.addView(nav, UI.lp(UI.MATCH, UI.WRAP))
        setContentView(root)

        tracker = TrackerScreen(this, host, store, auth)
        screens = mapOf(
            R.id.nav_tracker to tracker,
            R.id.nav_tests to TestsScreen(this, store),
            R.id.nav_3d to StudioScreen(this),
            R.id.nav_results to ResultsScreen(this, store, auth)
        )
        nav.setOnItemSelectedListener { item ->
            show(item.itemId); true
        }
        show(R.id.nav_tracker)
    }

    private fun show(id: Int) {
        val next = screens[id] ?: return
        if (next === current) return
        current?.onHide()
        container.removeAllViews()
        container.addView(next.view, FrameLayout.LayoutParams(UI.MATCH, UI.MATCH))
        current = next
        next.onShow()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menuInflater.inflate(R.menu.main_menu, pm.menu)
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_account -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(Session.name)
                        .setMessage(if (Session.cloud) Session.email + "\n\nCloud sync: on" else "Guest mode - data sirf is phone me.")
                        .setPositiveButton("OK", null).show()
                }
                R.id.action_signout -> {
                    tracker.backup {
                        auth.signOut()
                        startActivity(android.content.Intent(this, LoginActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
            }
            true
        }
        pm.show()
    }

    override fun onPause() {
        super.onPause()
        if (::tracker.isInitialized) tracker.backup(null)
    }

    override fun onResume() {
        super.onResume()
        current?.onShow()
    }

    override fun onDestroy() {
        if (::tracker.isInitialized) tracker.destroy()
        super.onDestroy()
    }
}
