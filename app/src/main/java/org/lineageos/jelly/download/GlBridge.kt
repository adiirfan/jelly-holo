/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.download

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passes decoded frames to the encoder through the graphics chip: the
 * decoder draws into [decoderSurface], and [draw] paints that picture onto
 * the encoder's input surface. Nothing is copied by the processor.
 *
 * Create, use and close it on one thread: the drawing state belongs to it.
 */
internal class GlBridge(encoderSurface: Surface, private val width: Int, private val height: Int) : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val thread = HandlerThread("frames")
    private val lock = Object()
    private var available = false
    private var program = 0
    private var texture = 0
    private val matrix = FloatArray(16)

    /** The whole picture: x, y, then u, v, for each corner. */
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the decoder draws its frames. */
    lateinit var decoderSurface: Surface
        private set

    init {
        try {
            setUp(encoderSurface)
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    private fun setUp(encoderSurface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IOException("no graphics display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) fail("eglInitialize")
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) || count[0] == 0) fail("eglChooseConfig")
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext")
        surface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) fail("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) fail("eglMakeCurrent")

        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("texture")

        // The "frame ready" signal arrives on a thread of its own, so waiting for it can't block it
        thread.start()
        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                available = true
                lock.notifyAll()
            }
        }, Handler(thread.looper))
        decoderSurface = Surface(surfaceTexture)
    }

    /** Waits for the frame the decoder was just told to draw; false if it never came. */
    fun awaitFrame(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (!available) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                lock.wait(left)
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        return true
    }

    /** Paints the latest frame onto the encoder's surface, stamped with [timeUs]. */
    fun draw(timeUs: Long) {
        surfaceTexture.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl("draw")
        EGLExt.eglPresentationTimeANDROID(display, surface, timeUs * 1000)
        if (!EGL14.eglSwapBuffers(display, surface)) fail("eglSwapBuffers")
    }

    override fun close() {
        if (::decoderSurface.isInitialized) decoderSurface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        thread.quitSafely()
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IOException("shader: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IOException("program: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    private fun fail(what: String): Nothing = throw IOException("$what failed: 0x${Integer.toHexString(EGL14.eglGetError())}")

    private fun checkGl(what: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw IOException("$what: GL error 0x${Integer.toHexString(e)}")
    }

    private companion object {
        /** Lets the encoder take what's drawn (missing from EGL14's constants). */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * aTex).xy;
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTex);
            }
        """
    }
}
