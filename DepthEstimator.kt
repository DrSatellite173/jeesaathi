package com.exam.app.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Photo ka depth-map (bright = paas, dark = door) banata hai.
 * 1) assets/models/midas_small.tflite ho to AI monocular depth (MiDaS) use hota hai.
 * 2) warna built-in estimator: center-bias + neeche = paas + brightness + edge se smooth (approximate, AI jaisa accurate nahi).
 */
object DepthEstimator {

    fun estimate(ctx: Context, src: Bitmap): Pair<Bitmap, Boolean> {
        val ai = try { midas(ctx, src) } catch (e: Throwable) { null }
        if (ai != null) return Pair(ai, true)
        return Pair(heuristic(src), false)
    }

    private fun gray(v: Float): Int {
        val g = (v.coerceIn(0f, 1f) * 255f).toInt()
        return Color.rgb(g, g, g)
    }

    // ------------------------------------------------------------------ AI (optional)
    private fun midas(ctx: Context, src: Bitmap): Bitmap? {
        val names = ctx.assets.list("models") ?: return null
        if (!names.contains("midas_small.tflite")) return null
        val afd = ctx.assets.openFd("models/midas_small.tflite")
        val interp: Interpreter
        try {
            val map = FileInputStream(afd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interp = Interpreter(map)
        } finally { afd.close() }
        try {
            val inShape = interp.getInputTensor(0).shape()          // [1, H, W, 3]
            val h = inShape[1]; val w = inShape[2]
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            val inBuf = ByteBuffer.allocateDirect(4 * w * h * 3).order(ByteOrder.nativeOrder())
            // ImageNet mean/std normalisation (MiDaS v2.1 small ke liye). Model alag ho to yahin badlo.
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (p in px) {
                inBuf.putFloat((((p shr 16) and 0xFF) / 255f - mean[0]) / std[0])
                inBuf.putFloat((((p shr 8) and 0xFF) / 255f - mean[1]) / std[1])
                inBuf.putFloat(((p and 0xFF) / 255f - mean[2]) / std[2])
            }
            inBuf.rewind()
            val outShape = interp.getOutputTensor(0).shape()
            var count = 1
            for (d in outShape) count *= d
            val dims = outShape.filter { it > 1 }
            val oh = if (dims.size >= 2) dims[dims.size - 2] else h
            val ow = if (dims.size >= 2) dims[dims.size - 1] else w
            val outBuf = ByteBuffer.allocateDirect(4 * count).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val fb = outBuf.asFloatBuffer()
            val arr = FloatArray(oh * ow)
            fb.get(arr, 0, min(arr.size, fb.remaining()))
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (v in arr) { if (v < lo) lo = v; if (v > hi) hi = v }
            val rng = if (hi - lo < 1e-6f) 1f else hi - lo
            val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val out = IntArray(ow * oh)
            for (i in out.indices) out[i] = gray((arr[i] - lo) / rng)
            bmp.setPixels(out, 0, ow, 0, 0, ow, oh)
            return bmp
        } finally {
            interp.close()
        }
    }

    // ------------------------------------------------------------------ built-in
    private fun heuristic(src: Bitmap): Bitmap {
        val tw = 160
        val th = max(40, (tw * src.height.toFloat() / src.width).toInt().coerceAtMost(320))
        val s = Bitmap.createScaledBitmap(src, tw, th, true)
        val px = IntArray(tw * th)
        s.getPixels(px, 0, tw, 0, 0, tw, th)

        val lum = FloatArray(tw * th)
        for (i in px.indices) {
            val p = px[i]
            lum[i] = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
        }
        val lumB = blur(blur(lum, tw, th, 3), tw, th, 3)

        val d = FloatArray(tw * th)
        for (y in 0 until th) {
            val ny = y / (th - 1f)
            for (x in 0 until tw) {
                val nx = x / (tw - 1f)
                val dx = nx - 0.5f; val dy = ny - 0.5f
                val center = 1f - min(1f, sqrt(dx * dx + dy * dy) * 1.6f)
                d[y * tw + x] = 0.42f * center + 0.28f * ny + 0.30f * lumB[y * tw + x]
            }
        }
        val sm = blur(d, tw, th, 2)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in sm) { if (v < lo) lo = v; if (v > hi) hi = v }
        val rng = if (hi - lo < 1e-6f) 1f else hi - lo
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val out = IntArray(tw * th)
        for (i in out.indices) out[i] = gray((sm[i] - lo) / rng)
        bmp.setPixels(out, 0, tw, 0, 0, tw, th)
        s.recycle()
        return bmp
    }

    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { sum += src[y * w + xx]; n++ } }
            tmp[y * w + x] = sum / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { sum += tmp[yy * w + x]; n++ } }
            out[y * w + x] = sum / n
        }
        return out
    }
}
