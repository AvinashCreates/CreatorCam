package com.avinash.creatorcam.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.avinash.creatorcam.face.FaceAnalyzer
import com.avinash.creatorcam.face.FaceTracker
import com.avinash.creatorcam.gl.CameraRenderer
import com.avinash.creatorcam.settings.VideoQuality
import java.util.concurrent.Executors

sealed interface BindResult {
    data class Bound(val hasFlash: Boolean, val hasFront: Boolean, val hasBack: Boolean) : BindResult
    data class Failed(val message: String) : BindResult
}

/**
 * Owns the CameraX session: a Preview that feeds [CameraRenderer] and a low-res ImageAnalysis that
 * feeds face detection. CameraX is not used for encoding, so effects/avatars/voice can be baked in.
 */
class CameraController(
    private val context: Context,
    private val renderer: CameraRenderer,
    private val tracker: FaceTracker,
    private val onFaceUnavailable: (String) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analyzer: FaceAnalyzer? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var faceTracking = false

    private val displayManager = context.getSystemService(DisplayManager::class.java)

    /**
     * CameraX computes TransformationInfo.rotationDegrees relative to this target rotation, so it must be the
     * real display rotation. If the display rotates (e.g. portrait <-> reverse portrait), CameraX re-issues the
     * transform and the renderer picks it up, keeping the output upright with no extra code path.
     */
    private fun displayRotation(): Int = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = displayRotation()
            preview?.targetRotation = rotation
            analysis?.targetRotation = rotation
        }
    }
    private var listening = false

    /** Guards against stale async results when the user flips the camera quickly. */
    private var generation = 0

    fun bind(owner: LifecycleOwner, front: Boolean, quality: VideoQuality, onResult: (BindResult) -> Unit) {
        val gen = ++generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (gen != generation) return@addListener
            try {
                val p = future.get()
                provider = p
                val hasFront = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val hasBack = p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!(if (front) hasFront else hasBack)) {
                    onResult(BindResult.Failed(if (front) "This phone has no front camera." else "This phone has no rear camera."))
                    return@addListener
                }

                p.unbindAll()
                analyzer?.close()
                tracker.reset()

                val rotation = displayRotation()
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(selector16x9(if (quality == VideoQuality.FHD_1080) Size(1920, 1080) else Size(1280, 720)))
                    .build()
                    .also { it.setSurfaceProvider(renderer.surfaceProvider) }

                val faceAnalyzer = FaceAnalyzer(tracker, onFaceUnavailable).also {
                    it.enabled = faceTracking
                    analyzer = it
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(selector16x9(Size(640, 360)))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, faceAnalyzer) }

                this.preview = preview
                this.analysis = analysis
                val cam = p.bindToLifecycle(owner, selector, preview, analysis)
                camera = cam
                if (!listening) {
                    displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
                    listening = true
                }
                onResult(BindResult.Bound(cam.cameraInfo.hasFlashUnit(), hasFront, hasBack))
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                onResult(BindResult.Failed("The camera couldn't start. Close other camera apps and try again."))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        generation++
        provider?.unbindAll()
        camera = null
        preview = null
        analysis = null
        if (listening) {
            displayManager?.unregisterDisplayListener(displayListener)
            listening = false
        }
    }

    /** Face detection is only worth its CPU cost while an avatar is on screen. */
    fun setFaceTracking(enabled: Boolean) {
        faceTracking = enabled
        analyzer?.enabled = enabled
        if (!enabled) tracker.reset()
    }

    /** Flash for a creator camera = torch (continuous light). Returns false if this lens has none. */
    fun setTorch(on: Boolean): Boolean {
        val cam = camera ?: return false
        if (!cam.cameraInfo.hasFlashUnit()) return false
        cam.cameraControl.enableTorch(on)
        return true
    }

    fun shutdown() {
        unbind()
        analyzer?.close()
        analysisExecutor.shutdown()
    }

    private fun selector16x9(target: Size) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

    private companion object {
        const val TAG = "CameraController"
    }
}
