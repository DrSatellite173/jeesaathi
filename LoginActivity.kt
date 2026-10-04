package com.exam.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exam.app.R
import com.exam.app.auth.AuthManager

class LoginActivity : AppCompatActivity() {
    private lateinit var auth: AuthManager
    private var signupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = AuthManager(this)
        if (auth.restoreSession()) { goMain(); return }
        build()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }

    private fun build() {
        val sv = ScrollView(this)
        val box = UI.vbox(this, 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        sv.addView(box)

        box.addView(UI.gap(this, 40))
        box.addView(UI.text(this, "JEE Saathi", 28f, UI.color(this, R.color.primary_dark), true))
        box.addView(UI.text(this, "Tracker + NTA Test Series", 14f, UI.color(this, R.color.text_mid)))
        box.addView(UI.gap(this, 28))

        val name = field("Naam", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val email = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Password (min 6)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        name.visibility = android.view.View.GONE
        box.addView(name, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(email, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(pass, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(UI.gap(this, 16))

        val status = UI.text(this, "", 12f, UI.color(this, R.color.bad))
        val primary = UI.button(this, "Login") {}
        val toggle = UI.text(this, "Naya account banao", 14f, UI.color(this, R.color.primary), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(context, 14), 0, UI.dp(context, 14))
        }

        fun refresh() {
            name.visibility = if (signupMode) android.view.View.VISIBLE else android.view.View.GONE
            primary.text = if (signupMode) "Sign up" else "Login"
            toggle.text = if (signupMode) "Account hai? Login karo" else "Naya account banao"
        }

        primary.setOnClickListener {
            val e = email.text.toString().trim()
            val p = pass.text.toString()
            if (e.isEmpty() || p.length < 6) { status.text = "Email aur 6+ character password daalo"; return@setOnClickListener }
            primary.isEnabled = false
            status.text = ""
            val done: (Throwable?) -> Unit = { err ->
                runOnUiThread {
                    primary.isEnabled = true
                    if (err == null) goMain() else status.text = err.message ?: "Fail hua"
                }
            }
            if (signupMode) auth.signUp(name.text.toString().trim(), e, p, done) else auth.signIn(e, p, done)
        }
        toggle.setOnClickListener { signupMode = !signupMode; refresh() }

        box.addView(primary, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(toggle, UI.lp(UI.MATCH, UI.WRAP))
        box.addView(status, UI.lp(UI.MATCH, UI.WRAP))

        box.addView(UI.button(this, "Guest ki tarah chalao (sirf is phone me)", true) {
            auth.continueAsGuest()
            goMain()
        }, UI.lp(UI.MATCH, UI.WRAP))

        if (!auth.firebaseReady) {
            box.addView(UI.gap(this, 16))
            box.addView(UI.text(this,
                "Cloud login abhi off hai (app/google-services.json nahi mili). Guest mode me sab kuch chalta hai; multi-user cloud sync ke liye Firebase file daalo.",
                12f, UI.color(this, R.color.warn)))
        }
        setContentView(sv)
    }
}
