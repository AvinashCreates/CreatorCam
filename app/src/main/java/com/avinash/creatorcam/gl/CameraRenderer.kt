package com.avinash.creatorcam.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import com.avinash.creatorcam.avatar.AvatarSpec
import com.avinash.creatorcam.effects.EffectParams
import com.avinash.creatorcam.face.FacePose
import com.avinash.creatorcam.face.FaceTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The single place where pixels are produced.
 *
 *   CameraX Preview -> SurfaceTexture (external OES) -> one fragment shader (colour effects)
 *                                                    -> layered avatar at the tracked face (procedural SDF shader)
 *                                                    -> [display surface]  (what the user sees)
 *                                                    -> [encoder surface]  (what ends up in the MP4)
 *                                                    -> [offscreen FBO]    (photo capture)
 *
 * Because every target is drawn by the same [drawScene] call, the preview, the recorded video and
 * captured photos always contain the same effects and avatar.
 *
 * All GL work happens on one dedicated thread. Public methods are safe to call from any thread.
 */
class CameraRenderer(private val tracker: FaceTracker) {

    private val thread = HandlerThread("cc-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    // ---- State written from other threads ----
    @Volatile var effect: EffectParams = EffectParams()
    /** True when the final image should be horizontally mirrored (front camera selfie view). */
    @Volatile var mirror: Boolean = false
    @Volatile var onError: ((String) -> Unit)? = null
    /** Avatar to draw at the tracked face, or null. Colours are resolved once here, not per frame. */
    @Volatile private var avatar: AvatarState? = null
    @Volatile private var rotationDegrees = 0

    // ---- GL-thread-only state ----
    private var egl: EglCore? = null
    private var dummySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var dispW = 0
    private var dispH = 0
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encW = 0
    private var encH = 0
    private var onEncodedFrame: ((Long) -> Unit)? = null
    private var photoCallback: ((Bitmap?) -> Unit)? = null
    private var released = false

    private var cameraTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var bufW = 1
    private var bufH = 1
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private var crop = Crop(1f, 1f)

    private var camProg = 0
    private var camLoc = CameraLocations()
    private var avatarProg = 0
    private var avatarLoc = AvatarLocations()

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    /** Hand this to CameraX `Preview.setSurfaceProvider`. */
    val surfaceProvider = Preview.SurfaceProvider { request -> handler.post { onSurfaceRequest(request) } }

    init {
        handler.post { initGl() }
    }

    // ------------------------------------------------------------------ public API

    fun setDisplaySurface(surface: Surface, width: Int, height: Int) {
        handler.post {
            val core = egl ?: return@post
            releaseDisplay()
            try {
                displaySurface = core.createWindowSurface(surface)
                dispW = width
                dispH = height
            } catch (e: Exception) {
                report("Couldn't attach the preview: ${e.message}")
            }
        }
    }

    /** Blocks (briefly) until the GL thread has let go of the display surface. Call from surfaceDestroyed. */
    fun clearDisplaySurface() {
        val latch = CountDownLatch(1)
        if (!handler.post { releaseDisplay(); latch.countDown() }) return
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    /** Start drawing every frame into [surface] (a MediaCodec input surface) as well. */
    fun startEncoding(surface: Surface, width: Int, height: Int, onFrame: (Long) -> Unit) {
        handler.post {
            val core = egl ?: run { report("Graphics not ready"); return@post }
            try {
                encoderSurface = core.createWindowSurface(surface)
                encW = width
                encH = height
                onEncodedFrame = onFrame
            } catch (e: Exception) {
                report("Couldn't start the video encoder surface: ${e.message}")
            }
        }
    }

    /** Stop drawing to the encoder. [afterDetached] runs on the GL thread once no more frames will be written. */
    fun stopEncoding(afterDetached: () -> Unit) {
        val ok = handler.post {
            releaseEncoder()
            afterDetached()
        }
        if (!ok) afterDetached()
    }

    /** Renders the next frame offscreen at 1080x1920 (with effects + avatar) and delivers it. Null on failure. */
    fun requestPhoto(callback: (Bitmap?) -> Unit) {
        if (!handler.post { photoCallback = callback }) callback(null)
    }

    /** Pass null to remove the avatar. Nothing is uploaded: the character is drawn procedurally by the shader. */
    fun setAvatar(spec: AvatarSpec?) {
        avatar = spec?.let {
            AvatarState(it.skinRgb, it.hairRgb, it.eyeRgb, it.hairStyle.code, it.accessory.code)
        }
    }

    fun release() {
        handler.post {
            ensureCurrent()
            released = true
            releaseDisplay()
            releaseEncoder()
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            if (camProg != 0) GLES20.glDeleteProgram(camProg)
            if (avatarProg != 0) GLES20.glDeleteProgram(avatarProg)
            GLES20.glDeleteTextures(1, intArrayOf(cameraTex), 0)
            egl?.let { core ->
                core.destroySurface(dummySurface)
                core.release()
            }
            egl = null
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ GL thread

    private fun initGl() {
        try {
            val core = EglCore()
            egl = core
            dummySurface = core.createPbufferSurface(1, 1)
            core.makeCurrent(dummySurface)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            cameraTex = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            camProg = Shaders.buildProgram(Shaders.CAMERA_VERTEX, Shaders.CAMERA_FRAGMENT)
            camLoc = CameraLocations(camProg)
            avatarProg = Shaders.buildProgram(Shaders.AVATAR_VERTEX, Shaders.AVATAR_FRAGMENT)
            avatarLoc = AvatarLocations(avatarProg)
        } catch (e: Exception) {
            Log.e(TAG, "GL init failed", e)
            report("Your device couldn't start the camera graphics (${e.message}).")
        }
    }

    private fun onSurfaceRequest(request: SurfaceRequest) {
        if (egl == null || released) {
            request.willNotProvideSurface()
            return
        }
        ensureCurrent()
        val size = request.resolution
        val st = SurfaceTexture(cameraTex)
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)

        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture = st
        bufW = size.width
        bufH = size.height
        st.setOnFrameAvailableListener({ onFrame(it) }, handler)

        request.setTransformationInfoListener(glExecutor) { info -> rotationDegrees = info.rotationDegrees }
        request.provideSurface(surface, glExecutor) {
            // Camera is done with this surface.
            st.setOnFrameAvailableListener(null)
            surface.release()
            st.release()
            if (surfaceTexture === st) surfaceTexture = null
        }
    }

    private fun onFrame(st: SurfaceTexture) {
        val core = egl
        if (released || core == null || st !== surfaceTexture) return
        try {
            core.makeCurrent(dummySurface)
            st.updateTexImage()
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        st.getTransformMatrix(stMatrix)
        val timestampNs = st.timestamp
        updateGeometry()
        val pose = tracker.advance(System.nanoTime())

        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(encoderSurface)
            drawScene(encW, encH, pose)
            core.setPresentationTime(encoderSurface, timestampNs)
            if (core.swapBuffers(encoderSurface)) {
                onEncodedFrame?.invoke(timestampNs)
            } else {
                releaseEncoder()
                report("The video encoder stopped accepting frames.")
            }
        }
        if (displaySurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(displaySurface)
            drawScene(dispW, dispH, pose)
            if (!core.swapBuffers(displaySurface)) releaseDisplay()
        }
        val photo = photoCallback
        if (photo != null) {
            photoCallback = null
            core.makeCurrent(dummySurface)
            photo(renderPhoto(pose))
        }
        core.makeCurrent(dummySurface)
    }

    /** One authoritative transform for orientation, mirroring and cropping: see [Orientation]. */
    private fun updateGeometry() {
        crop = Orientation.compute(stMatrix, rotationDegrees, mirror, bufW, bufH, OUT_ASPECT, texMatrix)
    }

    private fun drawScene(w: Int, h: Int, pose: FacePose?) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawCamera()
        val a = avatar
        if (pose != null && a != null) drawAvatar(w, h, pose, a)
    }

    private fun drawCamera() {
        val e = effect
        val l = camLoc
        GLES20.glUseProgram(camProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glUniform1i(l.tex, 0)
        GLES20.glUniformMatrix4fv(l.texMat, 1, false, texMatrix, 0)
        GLES20.glUniform2f(l.texel, 1f / bufW, 1f / bufH)
        GLES20.glUniform1f(l.brightness, e.brightness)
        GLES20.glUniform1f(l.contrast, e.contrast)
        GLES20.glUniform1f(l.saturation, e.saturation)
        GLES20.glUniform1f(l.sharpen, e.sharpen)
        GLES20.glUniform1f(l.soft, e.soft)
        GLES20.glUniform1f(l.vignette, e.vignette)
        GLES20.glUniform1f(l.mono, e.mono)
        GLES20.glUniform1f(l.fade, e.fade)
        GLES20.glUniform3f(l.tint, e.tintR, e.tintG, e.tintB)
        GLES20.glEnableVertexAttribArray(l.pos)
        GLES20.glVertexAttribPointer(l.pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(l.pos)
    }

    private fun drawAvatar(w: Int, h: Int, pose: FacePose, a: AvatarState) {
        // Detector coordinates -> final output coordinates, using the SAME crop + mirror as the camera image.
        val mir = mirror
        val (ox, oy) = FaceMapping.toOutput(pose.cx, pose.cy, crop, mir)
        val faceW = FaceMapping.widthToOutput(pose.width, crop)
        val unitPx = faceW * w * 0.5f * HEAD_SCALE          // 1 avatar unit = head half-width in pixels
        if (unitPx < 4f) return
        val rot = Math.toRadians(FaceMapping.rollForDisplay(pose.rollDeg, mir).toDouble()).toFloat()

        // The mirrored view swaps which side the eyes and the turn direction appear on.
        val (eyeLeft, eyeRight) = FaceMapping.eyesForDisplay(pose.eyeImageLeft, pose.eyeImageRight, mir)
        val look = FaceMapping.yawForDisplay(pose.yaw, mir)

        val l = avatarLoc
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // shader outputs premultiplied colour
        GLES20.glUseProgram(avatarProg)
        GLES20.glUniform2f(l.center, ox * 2f - 1f, 1f - oy * 2f)
        GLES20.glUniform1f(l.sizePx, unitPx * 4f)
        GLES20.glUniform1f(l.rot, rot)
        GLES20.glUniform2f(l.view, w.toFloat(), h.toFloat())
        GLES20.glUniform1f(l.aa, 1f / unitPx)
        GLES20.glUniform1f(l.alpha, pose.alpha)
        GLES20.glUniform3f(l.skin, a.skin[0], a.skin[1], a.skin[2])
        GLES20.glUniform3f(l.hair, a.hair[0], a.hair[1], a.hair[2])
        GLES20.glUniform3f(l.eye, a.eye[0], a.eye[1], a.eye[2])
        GLES20.glUniform1f(l.hairStyle, a.hairStyle)
        GLES20.glUniform1f(l.acc, a.accessory)
        GLES20.glUniform2f(l.look, look, pose.pitch)
        GLES20.glUniform2f(l.eyes, eyeLeft, eyeRight)
        GLES20.glUniform1f(l.smile, pose.smile)
        GLES20.glUniform1f(l.mouth, pose.mouthOpen)
        GLES20.glEnableVertexAttribArray(l.pos)
        GLES20.glVertexAttribPointer(l.pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(l.pos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun renderPhoto(pose: FacePose?): Bitmap? {
        val w = PHOTO_W
        val h = PHOTO_H
        val fbo = IntArray(1)
        val tex = IntArray(1)
        try {
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) return null

            drawScene(w, h, pose)

            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.rewind()
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(pixels)
            // GL rows run bottom-to-top.
            val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val upright = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
            raw.recycle()
            return upright
        } catch (e: Exception) {
            Log.e(TAG, "Photo render failed", e)
            return null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Photo render out of memory", e)
            return null
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(1, tex, 0)
        }
    }

    private fun releaseDisplay() {
        val core = egl ?: return
        if (displaySurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(dummySurface)
            core.destroySurface(displaySurface)
            displaySurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseEncoder() {
        val core = egl ?: return
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(dummySurface)
            core.destroySurface(encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
        onEncodedFrame = null
    }

    private fun ensureCurrent() {
        egl?.makeCurrent(dummySurface)
    }

    private fun report(message: String) {
        onError?.invoke(message)
    }

    private class CameraLocations(p: Int = 0) {
        val pos = if (p != 0) GLES20.glGetAttribLocation(p, "aPos") else 0
        val texMat = if (p != 0) GLES20.glGetUniformLocation(p, "uTexMat") else 0
        val tex = if (p != 0) GLES20.glGetUniformLocation(p, "uTex") else 0
        val texel = if (p != 0) GLES20.glGetUniformLocation(p, "uTexel") else 0
        val brightness = if (p != 0) GLES20.glGetUniformLocation(p, "uBrightness") else 0
        val contrast = if (p != 0) GLES20.glGetUniformLocation(p, "uContrast") else 0
        val saturation = if (p != 0) GLES20.glGetUniformLocation(p, "uSaturation") else 0
        val sharpen = if (p != 0) GLES20.glGetUniformLocation(p, "uSharpen") else 0
        val soft = if (p != 0) GLES20.glGetUniformLocation(p, "uSoft") else 0
        val vignette = if (p != 0) GLES20.glGetUniformLocation(p, "uVignette") else 0
        val mono = if (p != 0) GLES20.glGetUniformLocation(p, "uMono") else 0
        val fade = if (p != 0) GLES20.glGetUniformLocation(p, "uFade") else 0
        val tint = if (p != 0) GLES20.glGetUniformLocation(p, "uTint") else 0
    }

    private class AvatarLocations(private val p: Int = 0) {
        private fun u(name: String) = if (p != 0) GLES20.glGetUniformLocation(p, name) else 0
        val pos = if (p != 0) GLES20.glGetAttribLocation(p, "aPos") else 0
        val center = u("uCenter")
        val sizePx = u("uSizePx")
        val rot = u("uRot")
        val view = u("uView")
        val aa = u("uAA")
        val alpha = u("uAlpha")
        val skin = u("uSkin")
        val hair = u("uHair")
        val eye = u("uEye")
        val hairStyle = u("uHairStyle")
        val acc = u("uAcc")
        val look = u("uLook")
        val eyes = u("uEyes")
        val smile = u("uSmile")
        val mouth = u("uMouth")
    }

    private class AvatarState(
        val skin: FloatArray,
        val hair: FloatArray,
        val eye: FloatArray,
        val hairStyle: Float,
        val accessory: Float,
    )

    companion object {
        private const val TAG = "CameraRenderer"
        /** Output is always portrait 9:16, whatever the sensor aspect (aspect-fill crop). */
        const val OUT_ASPECT = 9f / 16f
        private const val PHOTO_W = 1080
        private const val PHOTO_H = 1920
        /** Avatar head width relative to the detected face-box width, so the character covers the real face. */
        private const val HEAD_SCALE = 1.2f
    }
}
