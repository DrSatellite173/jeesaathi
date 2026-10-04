package com.exam.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.exam.app.models.Question
import com.exam.app.models.Response
import com.exam.app.web.WebHost
import com.google.gson.Gson

/** NTA-style question WebView (MathJax + images). Test aur Review dono me use hota hai. */
@SuppressLint("SetJavaScriptEnabled")
class NtaView(ctx: Context, private val host: WebHost) {
    val web = WebView(ctx)
    var selectCb: (Int) -> Unit = {}
    var numericCb: (String) -> Unit = {}
    var imageCb: (String) -> Unit = {}

    private var ready = false
    private var pending: String? = null
    private val gson = Gson()

    private inner class Bridge {
        @JavascriptInterface fun select(i: Int) { web.post { selectCb(i) } }
        @JavascriptInterface fun numeric(v: String) { web.post { numericCb(v) } }
        @JavascriptInterface fun image(src: String) { web.post { imageCb(src) } }
    }

    init {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.builtInZoomControls = false
        web.setBackgroundColor(android.graphics.Color.WHITE)
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                host.intercept(request)

            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pending?.let { run(it) }
                pending = null
            }
        }
        web.loadUrl(WebHost.NTA_URL)
    }

    private fun run(json: String) {
        web.evaluateJavascript("NTA.render($json)", null)
    }

    fun render(q: Question, total: Int, r: Response, review: Map<String, Any?>? = null) {
        val m = HashMap<String, Any?>()
        m["n"] = q.number
        m["total"] = total
        m["subject"] = q.subject
        m["type"] = q.type
        m["html"] = q.html
        m["options"] = q.options
        m["labels"] = q.optionsAreLabels
        m["selected"] = r.selected
        m["numeric"] = r.numeric
        m["review"] = review
        val json = gson.toJson(m)
        if (ready) run(json) else pending = json
    }

    fun destroy() {
        web.stopLoading()
        web.removeJavascriptInterface("AndroidBridge")
        web.destroy()
    }
}
