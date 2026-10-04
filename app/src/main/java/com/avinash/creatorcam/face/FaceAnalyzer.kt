package com.avinash.creatorcam.face

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * ML Kit face detection (bundled model, offline) on a small analysis stream.
 *
 * Requests what the avatar needs: bounding box, Euler angles, landmarks (eyes, nose, mouth) and
 * classification (smile + eye-open probabilities). That is the heavier ACCURATE configuration, so:
 *  - it only runs while an avatar is selected ([enabled]); otherwise frames are dropped immediately,
 *  - only one frame is ever in flight, so a slow phone simply gets a lower detection rate,
 *  - the renderer interpolates the results at 30 fps ([FaceTracker]).
 */
class FaceAnalyzer(
    private val tracker: FaceTracker,
    private val onUnavailable: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var enabled = false

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build(),
    )
    private val mouth = MouthEstimator()
    private var lastRunNs = 0L
    @Volatile private var inFlight = false
    private var consecutiveFailures = 0
    private var reported = false

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = System.nanoTime()
        val media = image.image
        if (!enabled || inFlight || media == null || now - lastRunNs < MIN_INTERVAL_NS) {
            image.close()
            return
        }
        lastRunNs = now
        inFlight = true

        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation % 180 != 0
        val upW = (if (sideways) image.height else image.width).toFloat()
        val upH = (if (sideways) image.width else image.height).toFloat()

        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { faces ->
                consecutiveFailures = 0
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@addOnSuccessListener
                tracker.onMeasurement(FaceGeometry.measure(raw(face), upW, upH, mouth), System.nanoTime())
            }
            .addOnFailureListener {
                if (++consecutiveFailures >= FAILURE_LIMIT && !reported) {
                    reported = true
                    onUnavailable("Face tracking isn't available right now. The camera and effects still work.")
                }
            }
            .addOnCompleteListener {
                inFlight = false
                image.close()
            }
    }

    private fun raw(face: Face): RawFace {
        fun lm(type: Int): Pt? = face.getLandmark(type)?.position?.let { Pt(it.x, it.y) }
        val box = face.boundingBox
        return RawFace(
            boxCx = box.exactCenterX(),
            boxCy = box.exactCenterY(),
            boxW = box.width().toFloat(),
            eulerX = face.headEulerAngleX,
            eulerZ = face.headEulerAngleZ,
            smileProb = face.smilingProbability,
            leftEyeProb = face.leftEyeOpenProbability,
            rightEyeProb = face.rightEyeOpenProbability,
            leftEye = lm(FaceLandmark.LEFT_EYE),
            rightEye = lm(FaceLandmark.RIGHT_EYE),
            noseBase = lm(FaceLandmark.NOSE_BASE),
            mouthBottom = lm(FaceLandmark.MOUTH_BOTTOM),
        )
    }

    fun close() = detector.close()

    private companion object {
        const val MIN_INTERVAL_NS = 80_000_000L // at most ~12 detections/s
        const val FAILURE_LIMIT = 10
    }
}
