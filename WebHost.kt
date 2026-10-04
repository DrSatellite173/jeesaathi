package com.exam.app.web

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.exam.app.data.UserStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * https://exam.local/ ko app ke andar serve karta hai (koi network server nahi):
 *   /tm/<testId>/<path>  -> import kiye test ki images (user ke private folder se)
 *   /tpl/<file>          -> assets/tpl (NTA template)
 *   baaki sab            -> assets/web (JEE Saathi tracker build)
 */
class WebHost(private val ctx: Context, private val store: UserStore) {

    companion object {
        const val HOST = "exam.local"
        const val ROOT = "https://exam.local/"
        const val NTA_URL = "https://exam.local/tpl/nta.html"
    }

    fun intercept(req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        val path = Uri.decode(u.path ?: "/")
        val res: WebResourceResponse? = try {
            when {
                path.startsWith("/tm/") -> media(path)
                path.startsWith("/tpl/") -> asset("tpl/" + path.removePrefix("/tpl/"))
                else -> {
                    val p = path.trimStart('/').ifEmpty { "index.html" }
                    asset("web/$p") ?: if (!p.substringAfterLast('/').contains('.')) asset("web/index.html") else null
                }
            }
        } catch (e: Exception) { null }
        return res ?: WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /** https://exam.local/tm/... URL ko local File me badalta hai (3D viewer ke liye). */
    fun resolveMedia(url: String): File? {
        val path = Uri.decode(Uri.parse(url).path ?: return null)
        if (!path.startsWith("/tm/")) return null
        val parts = path.removePrefix("/tm/").split('/', limit = 2)
        if (parts.size < 2) return null
        val base = store.mediaPath(parts[0])
        val f = File(base, parts[1])
        return if (f.isFile && f.canonicalPath.startsWith(base.canonicalPath + File.separator)) f else null
    }

    private fun media(path: String): WebResourceResponse? {
        val parts = path.removePrefix("/tm/").split('/', limit = 2)
        if (parts.size < 2) return null
        val base = store.mediaPath(parts[0])
        val f = File(base, parts[1])
        if (!f.isFile || !f.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        return response(mime(f.name), FileInputStream(f))
    }

    private fun asset(rel: String): WebResourceResponse? = try {
        response(mime(rel), ctx.assets.open(rel))
    } catch (e: Exception) { null }

    private fun response(mime: String, stream: java.io.InputStream): WebResourceResponse {
        val enc = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")) "utf-8" else null
        return WebResourceResponse(mime, enc, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"), stream)
    }

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
