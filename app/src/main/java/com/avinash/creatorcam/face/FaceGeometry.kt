package com.avinash.creatorcam.face

import kotlin.math.atan2
import kotlin.math.hypot

data class Pt(val x: Float, val y: Float)

/** Plain-data copy of what ML Kit reports, in UPRIGHT analysis-image pixels (y down). Keeps the maths testable. */
data class RawFace(
    val boxCx: Float,
    val boxCy: Float,
    val boxW: Float,
    val eulerX: Float,
    val eulerZ: Float,
    val smileProb: Float?,
    val leftEyeProb: Float?,
    val rightEyeProb: Float?,
    val leftEye: Pt?,
    val rightEye: Pt?,
    val noseBase: Pt?,
    val mouthBottom: Pt?,
)

/** Learns the user's neutral mouth and reports how far beyond it the jaw has dropped (0..1). */
class MouthEstimator {
    private var neutral = -1f

    fun update(ratio: Float): Float {
        neutral = when {
            neutral < 0f || ratio < neutral -> ratio
            else -> neutral + 0.004f * (ratio - neutral) // creeps up very slowly so a held-open mouth stays open
        }
        return ((ratio - neutral - 0.03f) / 0.27f).coerceIn(0f, 1f)
    }
}

object FaceGeometry {
    /** ML Kit's pitch convention: positive Euler X = looking up. Flip here if a device test shows the avatar nodding backwards. */
    const val PITCH_SIGN = 1f

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Roll, turn and blink side are derived from LANDMARK GEOMETRY, which has no sign ambiguity:
     *  - roll  = angle of the line between the eyes
     *  - turn  = how far the nose sits off the eye midpoint along that line (positive = towards image right)
     *  - which eye is on the image's left is decided by x position, not by ML Kit's left/right naming.
     */
    fun measure(raw: RawFace, upW: Float, upH: Float, mouth: MouthEstimator): FaceMeasurement {
        var imgLeft = raw.leftEye
        var imgRight = raw.rightEye
        var probLeft = raw.leftEyeProb ?: 1f
        var probRight = raw.rightEyeProb ?: 1f
        if (imgLeft != null && imgRight != null && imgLeft.x > imgRight.x) {
            imgLeft = raw.rightEye; imgRight = raw.leftEye
            probLeft = raw.rightEyeProb ?: 1f; probRight = raw.leftEyeProb ?: 1f
        }

        var roll = raw.eulerZ
        var yaw = 0f
        var mouthOpen = 0f
        if (imgLeft != null && imgRight != null) {
            val dx = imgRight.x - imgLeft.x
            val dy = imgRight.y - imgLeft.y
            val eyeDist = hypot(dx, dy)
            if (eyeDist > 1f) {
                roll = Math.toDegrees(atan2((-dy).toDouble(), dx.toDouble())).toFloat() // y-up, counter-clockwise positive
                val nose = raw.noseBase
                if (nose != null) {
                    val mx = (imgLeft.x + imgRight.x) / 2f
                    val my = (imgLeft.y + imgRight.y) / 2f
                    val along = ((nose.x - mx) * dx + (nose.y - my) * dy) / eyeDist
                    yaw = (along / eyeDist / 0.40f).coerceIn(-1f, 1f)
                    raw.mouthBottom?.let { mb ->
                        mouthOpen = mouth.update(hypot(mb.x - nose.x, mb.y - nose.y) / eyeDist)
                    }
                }
            }
        }
        return FaceMeasurement(
            cx = raw.boxCx / upW,
            cy = raw.boxCy / upH,
            width = raw.boxW / upW,
            rollDeg = roll,
            yaw = yaw,
            pitch = (PITCH_SIGN * raw.eulerX / 25f).coerceIn(-1f, 1f),
            smile = smoothstep(0.25f, 0.90f, raw.smileProb ?: 0f),
            eyeImageLeft = probLeft,
            eyeImageRight = probRight,
            mouthOpen = mouthOpen,
        )
    }
}
