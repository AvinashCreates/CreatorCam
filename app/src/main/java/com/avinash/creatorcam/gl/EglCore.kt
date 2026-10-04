package com.avinash.creatorcam.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** Minimal EGL wrapper. Must be used from a single (GL) thread. */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        check(ok && count[0] > 0 && configs[0] != null) { "No suitable EGL config" }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun createWindowSurface(nativeSurface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, nativeSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return s
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        return s
    }

    fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun destroySurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
