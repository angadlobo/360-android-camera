package com.angadlobo.camera360

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders an equirectangular image on the inside of a sphere.
 * The camera sits at the centre and looks along [forward] with [up] (panorama frame:
 * x = right of the panorama centre, y = panorama centre, z = up).
 */
class SphereRenderer : GLSurfaceView.Renderer {

    @Volatile var fovDegrees = 75f
    private val lookLock = Any()
    private val forward = floatArrayOf(0f, 1f, 0f)
    private val up = floatArrayOf(0f, 0f, 1f)

    @Volatile private var pendingBitmap: Bitmap? = null
    private var textureId = 0
    private var program = 0
    private var aspect = 1f

    private lateinit var vertices: FloatBuffer
    private lateinit var indices: ShortBuffer
    private var indexCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setBitmap(bmp: Bitmap) {
        pendingBitmap = bmp
    }

    fun setLook(fx: Float, fy: Float, fz: Float, ux: Float, uy: Float, uz: Float) = synchronized(lookLock) {
        forward[0] = fx; forward[1] = fy; forward[2] = fz
        up[0] = ux; up[1] = uy; up[2] = uz
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram()
        buildSphere(stacks = 48, slices = 96)
        textureId = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { uploadTexture(it); pendingBitmap = null }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (textureId == 0) return

        // Vertical FOV derived so that the *shorter* screen side shows fovDegrees.
        val vFov = if (aspect >= 1f) fovDegrees else {
            Math.toDegrees(2 * kotlin.math.atan(kotlin.math.tan(Math.toRadians(fovDegrees / 2.0)) / aspect)).toFloat()
        }
        Matrix.perspectiveM(proj, 0, vFov, aspect, 0.05f, 10f)
        synchronized(lookLock) {
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, forward[0], forward[1], forward[2], up[0], up[1], up[2])
        }
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        val uTex = GLES20.glGetUniformLocation(program, "uTex")

        vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 5 * 4, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        vertices.position(3)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 5 * 4, vertices)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)

        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    private fun uploadTexture(src: Bitmap) {
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val limit = maxSize[0].coerceAtLeast(2048)
        val bmp = if (src.width > limit || src.height > limit) {
            val s = minOf(limit.toFloat() / src.width, limit.toFloat() / src.height)
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src

        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        if (bmp !== src) bmp.recycle()
    }

    private fun buildSphere(stacks: Int, slices: Int) {
        val verts = FloatArray((stacks + 1) * (slices + 1) * 5)
        var k = 0
        for (j in 0..stacks) {
            val v = j.toFloat() / stacks
            val lat = PI / 2 - v * PI
            for (i in 0..slices) {
                val u = i.toFloat() / slices
                val lon = (u - 0.5) * 2 * PI
                verts[k++] = (cos(lat) * sin(lon)).toFloat()
                verts[k++] = (cos(lat) * cos(lon)).toFloat()
                verts[k++] = sin(lat).toFloat()
                verts[k++] = u
                verts[k++] = v
            }
        }
        val idx = ShortArray(stacks * slices * 6)
        k = 0
        for (j in 0 until stacks) for (i in 0 until slices) {
            val a = (j * (slices + 1) + i).toShort()
            val b = (a + slices + 1).toShort()
            idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort()
            idx[k++] = (a + 1).toShort(); idx[k++] = b; idx[k++] = (b + 1).toShort()
        }
        indexCount = idx.size
        vertices = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        indices = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx)
    }

    private fun buildProgram(): Int {
        val vs = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * aPos; }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """.trimIndent()
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
