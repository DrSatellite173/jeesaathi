package com.exam.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.exam.app.R
import com.exam.app.auth.AuthManager
import com.google.android.material.button.MaterialButton

object UI {
    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun color(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    fun rounded(fill: Int, radiusPx: Int, strokePx: Int = 0, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun circle(fill: Int, strokePx: Int = 0, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun text(c: Context, s: CharSequence, sp: Float = 14f, color: Int = color(c, R.color.text_dark), bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(c: Context, label: String, outlined: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(c)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun vbox(c: Context, pad: Int = 0): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
    }

    fun hbox(c: Context, pad: Int = 0): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
    }

    fun card(c: Context): LinearLayout = vbox(c, 14).apply {
        background = rounded(Color.WHITE, dp(c, 12), dp(c, 1), Color.parseColor("#E3E7EF"))
        elevation = dp(c, 1).toFloat()
    }

    fun gap(c: Context, h: Int): View = View(c).apply { layoutParams = lp(MATCH, dp(c, h)) }

    /** Process kill ke baad session wapas load karta hai; login nahi hai to LoginActivity bhejta hai. */
    fun ensureSession(a: Activity): Boolean {
        val ok = AuthManager(a).restoreSession()
        if (!ok) {
            a.startActivity(Intent(a, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            a.finish()
        }
        return ok
    }

    fun fmtTime(sec: Long): String {
        val s = if (sec < 0) 0 else sec
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }
}
