package com.exam.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.exam.app.graphics.DepthEstimator
import com.exam.app.graphics.ParallaxRenderer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

class Viewer3DActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: ParallaxRenderer
    private lateinit var info: TextView
    private var player: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private var sm: SensorManager? = null
    private var baseX = 0f; private var baseY = 0f; private var baseInit = false
    private var touching = false
    private var video = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = intent.getBooleanExtra("video", false)
        val path = intent.getStringExtra("path")
        val uri: Uri? = if (path != null) Uri.fromFile(File(path)) else intent.data
        if (uri == null) { finish(); return }

        renderer = ParallaxRenderer(video)
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val root = FrameLayout(this)
        root.addView(gl, FrameLayout.LayoutParams(UI.MATCH, UI.MATCH))
        info = UI.text(this, if (video) "Video load ho raha hai..." else "Depth ban raha hai...", 13f, android.graphics.Color.WHITE).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#88000000"))
            setPadding(UI.dp(context, 12), UI.dp(context, 8), UI.dp(context, 12), UI.dp(context, 8))
        }
        root.addView(info, FrameLayout.LayoutParams(UI.MATCH, UI.WRAP, android.view.Gravity.TOP))
        setContentView(root)

        gl.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touching = true
                    renderer.tiltX = ((e.x / gl.width) - 0.5f) * 2f
                    renderer.tiltY = ((e.y / gl.height) - 0.5f) * 2f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; renderer.tiltX = 0f; renderer.tiltY = 0f }
            }
            true
        }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        if (video) startVideo(uri) else loadImage(uri)
    }

    // ------------------------------------------------------------------ image
    private fun loadImage(uri: Uri) {
        bg.execute {
            try {
                val bmp = decode(uri) ?: throw IllegalStateException("Image decode nahi hui")
                val (depth, ai) = DepthEstimator.estimate(applicationContext, bmp)
                renderer.setImage(bmp, depth)
                ui.post {
                    if (destroyed) return@post
                    info.text = if (ai) "3D (AI depth) - phone tilt karo ya ungli se ghumao" else "3D (built-in depth) - phone tilt karo ya ungli se ghumao"
                    ui.postDelayed({ info.visibility = View.GONE }, 3500)
                }
            } catch (e: Throwable) {
                ui.post { Toast.makeText(this, "Image khuli nahi: ${e.message}", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }

    private fun open(uri: Uri): InputStream? =
        if (uri.scheme == "file") FileInputStream(File(uri.path!!)) else contentResolver.openInputStream(uri)

    private fun decode(uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        val maxSide = 2048
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = open(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        var rot = 0
        try {
            open(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rot = 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> rot = 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> rot = 270
                }
            }
        } catch (_: Exception) {}
        if (rot == 0) return raw
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    // ------------------------------------------------------------------ video
    private fun startVideo(uri: Uri) {
        renderer.onVideoSurface = { surface -> ui.post { prepareVideo(uri, surface) } }
    }

    private fun prepareVideo(uri: Uri, surface: Surface) {
        if (destroyed) return
        try { player?.release() } catch (_: Exception) {}
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(this, uri)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setOnVideoSizeChangedListener { _, w, h -> if (h > 0) renderer.setVideoAspect(w.toFloat() / h) }
            mp.setOnPreparedListener {
                it.start()
                info.text = "3D video - phone tilt karo ya ungli se ghumao"
                ui.postDelayed({ info.visibility = View.GONE }, 3500)
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Video play nahi hua", Toast.LENGTH_LONG).show(); finish(); true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(this, "Video khuli nahi: ${e.message}", Toast.LENGTH_LONG).show(); finish()
        }
    }

    // ------------------------------------------------------------------ sensors (accelerometer tilt, auto re-center)
    override fun onSensorChanged(e: SensorEvent) {
        if (touching) return
        val ax = e.values[0]; val ay = e.values[1]
        if (!baseInit) { baseX = ax; baseY = ay; baseInit = true }
        baseX = baseX * 0.995f + ax * 0.005f
        baseY = baseY * 0.995f + ay * 0.005f
        renderer.tiltX = (-(ax - baseX) / 3.5f).coerceIn(-1f, 1f)
        renderer.tiltY = ((ay - baseY) / 3.5f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        gl.onResume()
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        player?.let { try { it.start() } catch (_: Exception) {} }
    }

    override fun onPause() {
        super.onPause()
        sm?.unregisterListener(this)
        player?.let { try { if (it.isPlaying) it.pause() } catch (_: Exception) {} }
        gl.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        renderer.release()
        bg.shutdownNow()
        super.onDestroy()
    }
}
