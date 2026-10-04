package com.exam.app.graphics

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 depth-parallax renderer.
 *  IMAGE mode: color texture + depth texture -> fragment shader depth ke hisaab se UV displace karta hai (tilt/touch se).
 *  VIDEO mode: MediaPlayer ka frame (OES texture); depth har frame ki luminance + position prior se shader me hi nikalta hai.
 */
class ParallaxRenderer(private val videoMode: Boolean) : GLSurfaceView.Renderer {

    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    var onVideoSurface: ((Surface) -> Unit)? = null
    var onVideoSize: (() -> Unit)? = null

    private var curX = 0f
    private var curY = 0f
    private var viewW = 1
    private var viewH = 1
    private var contentAspect = 1f

    private val lock = Any()
    private var colorBmp: Bitmap? = null
    private var depthBmp: Bitmap? = null
    private var needUpload = false

    private var program = 0
    private var colorTex = 0
    private var depthTex = 0
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var frameReady = false
    private val texMat = FloatArray(16)

    private var aPos = 0; private var aUv = 0
    private var uScale = 0; private var uTilt = 0; private var uStrength = 0
    private var uTex = 0; private var uDepth = 0; private var uTexMat = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v  (v=0 image ka top)
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    fun setImage(color: Bitmap, depth: Bitmap) {
        synchronized(lock) {
            colorBmp = color; depthBmp = depth
            needUpload = true
            contentAspect = color.width.toFloat() / color.height
        }
    }

    /** Activity destroy par bitmaps free karo. */
    fun release() {
        synchronized(lock) {
            colorBmp?.recycle(); depthBmp?.recycle()
            colorBmp = null; depthBmp = null
            needUpload = false
        }
    }

    fun setVideoAspect(a: Float) { synchronized(lock) { contentAspect = a } }

    // ------------------------------------------------------------------ shaders
    private val vs = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        uniform vec2 uScale;
        varying vec2 vUv;
        void main() {
            gl_Position = vec4(aPos * uScale, 0.0, 1.0);
            vUv = aUv;
        }
    """.trimIndent()

    private val fsImage = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform sampler2D uDepth;
        uniform vec2 uTilt;
        uniform float uStrength;
        void main() {
            vec2 uv = (vUv - 0.5) / 1.14 + 0.5;
            float d0 = texture2D(uDepth, uv).r;
            vec2 disp = uTilt * (d0 - 0.5) * uStrength;
            float d1 = texture2D(uDepth, uv + disp).r;
            disp = uTilt * (d1 - 0.5) * uStrength;
            vec4 c = texture2D(uTex, clamp(uv + disp, 0.0, 1.0));
            float shade = 0.90 + 0.18 * d1;
            gl_FragColor = vec4(c.rgb * shade, 1.0);
        }
    """.trimIndent()

    private val fsVideo = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uTex;
        uniform mat4 uTexMat;
        uniform vec2 uTilt;
        uniform float uStrength;
        vec2 tc(vec2 u) { return (uTexMat * vec4(u.x, 1.0 - u.y, 0.0, 1.0)).xy; }
        float depthAt(vec2 u) {
            vec3 c = texture2D(uTex, tc(u)).rgb;
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            float r = distance(u, vec2(0.5, 0.5));
            return clamp(0.42 * (1.0 - r * 1.6) + 0.28 * u.y + 0.30 * l, 0.0, 1.0);
        }
        void main() {
            vec2 uv = (vUv - 0.5) / 1.14 + 0.5;
            float d0 = depthAt(uv);
            vec2 disp = uTilt * (d0 - 0.5) * uStrength;
            float d1 = depthAt(uv + disp);
            disp = uTilt * (d1 - 0.5) * uStrength;
            vec3 c = texture2D(uTex, tc(clamp(uv + disp, 0.0, 1.0))).rgb;
            gl_FragColor = vec4(c * (0.90 + 0.18 * d1), 1.0);
        }
    """.trimIndent()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader compile error: $log")
        }
        return s
    }

    private fun link(v: String, f: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, v))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, f))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program link error: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun newTex(target: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    // ------------------------------------------------------------------ GLSurfaceView.Renderer
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = link(vs, if (videoMode) fsVideo else fsImage)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uTilt = GLES20.glGetUniformLocation(program, "uTilt")
        uStrength = GLES20.glGetUniformLocation(program, "uStrength")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uDepth = GLES20.glGetUniformLocation(program, "uDepth")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")

        if (videoMode) {
            oesTex = newTex(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener { frameReady = true }
            surfaceTexture = st
            onVideoSurface?.invoke(Surface(st))
        } else {
            colorTex = newTex(GLES20.GL_TEXTURE_2D)
            depthTex = newTex(GLES20.GL_TEXTURE_2D)
            hasImage = false
            // GL context kho jaye (app background) to bitmaps dobara upload honge
            synchronized(lock) { if (colorBmp != null && depthBmp != null) needUpload = true }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        var aspect = 1f
        synchronized(lock) {
            aspect = contentAspect
            val c = colorBmp
            val d = depthBmp
            if (!videoMode && needUpload && c != null && d != null && !c.isRecycled && !d.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, c, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, d, 0)
                needUpload = false
                hasImage = true
            }
        }
        if (videoMode) {
            val st = surfaceTexture ?: return
            if (frameReady) { frameReady = false; st.updateTexImage(); st.getTransformMatrix(texMat); hasFrame = true }
            if (!hasFrame) return
        } else if (!hasImage) return

        // smooth tilt
        curX += (tiltX - curX) * 0.12f
        curY += (tiltY - curY) * 0.12f

        val va = viewW.toFloat() / viewH
        val sx: Float; val sy: Float
        if (aspect > va) { sx = 1f; sy = va / aspect } else { sx = aspect / va; sy = 1f }

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uScale, sx, sy)
        GLES20.glUniform2f(uTilt, curX, curY)
        GLES20.glUniform1f(uStrength, 0.075f)

        if (videoMode) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
            GLES20.glUniform1i(uDepth, 1)
        }

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private var hasImage = false
    private var hasFrame = false
}
