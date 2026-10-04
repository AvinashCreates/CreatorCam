package com.avinash.creatorcam.face

import kotlin.math.exp

/**
 * One detector result, already converted to the UPRIGHT analysis image (x right, y down), unmirrored:
 *  cx/cy/width are normalised 0..1; angles in degrees; everything else 0..1.
 *  rollDeg  : counter-clockwise tilt of the head (y-up convention, as OpenGL rotates)
 *  yaw      : -1..1, positive = nose displaced towards the image's right (face turned that way)
 *  pitch    : -1..1, positive = looking up
 *  eyeImageLeft/Right : eye-open amount of the eye that appears on the image's left / right
 */
data class FaceMeasurement(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val rollDeg: Float,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val smile: Float = 0f,
    val eyeImageLeft: Float = 1f,
    val eyeImageRight: Float = 1f,
    val mouthOpen: Float = 0f,
)

/** Smoothed state handed to the renderer, plus a fade-in/out alpha. Same conventions as [FaceMeasurement]. */
data class FacePose(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val rollDeg: Float,
    val yaw: Float,
    val pitch: Float,
    val smile: Float,
    val eyeImageLeft: Float,
    val eyeImageRight: Float,
    val mouthOpen: Float,
    val alpha: Float,
)

/**
 * Bridges the slow (~10 Hz) face detector and the fast (30 fps) renderer: the detector pushes measurements,
 * the renderer calls [advance] once per frame and gets a smoothly interpolated pose. Eyes use a much shorter
 * time constant than position so blinks stay visible.
 */
class FaceTracker(
    private val holdMs: Float = 400f,
    private val tauMs: Float = 70f,
    private val eyeTauMs: Float = 35f,
    private val mouthTauMs: Float = 55f,
    private val fadeMs: Float = 150f,
) {
    private val lock = Any()
    private var target: FaceMeasurement? = null
    private var current: FaceMeasurement? = null
    private var lastSeenNs = 0L
    private var lastAdvanceNs = 0L
    private var visibility = 0f

    fun onMeasurement(m: FaceMeasurement?, nowNs: Long) {
        if (m == null) return
        synchronized(lock) {
            target = m
            lastSeenNs = nowNs
        }
    }

    fun reset() = synchronized(lock) {
        target = null; current = null; visibility = 0f; lastAdvanceNs = 0L
    }

    fun advance(nowNs: Long): FacePose? = synchronized(lock) {
        val dtMs = if (lastAdvanceNs == 0L) 16f else ((nowNs - lastAdvanceNs) / 1_000_000f).coerceIn(0f, 100f)
        lastAdvanceNs = nowNs

        val t = target
        val seen = t != null && (nowNs - lastSeenNs) / 1_000_000f < holdMs
        visibility = (visibility + (if (seen) dtMs else -dtMs) / fadeMs).coerceIn(0f, 1f)
        if (t == null || visibility <= 0f) {
            current = null
            return@synchronized null
        }
        val c = current
        val n = if (c == null) t else {
            val a = 1f - exp(-dtMs / tauMs)
            val ae = 1f - exp(-dtMs / eyeTauMs)
            val am = 1f - exp(-dtMs / mouthTauMs)
            FaceMeasurement(
                cx = c.cx + (t.cx - c.cx) * a,
                cy = c.cy + (t.cy - c.cy) * a,
                width = c.width + (t.width - c.width) * a,
                rollDeg = c.rollDeg + (t.rollDeg - c.rollDeg) * a,
                yaw = c.yaw + (t.yaw - c.yaw) * a,
                pitch = c.pitch + (t.pitch - c.pitch) * a,
                smile = c.smile + (t.smile - c.smile) * am,
                eyeImageLeft = c.eyeImageLeft + (t.eyeImageLeft - c.eyeImageLeft) * ae,
                eyeImageRight = c.eyeImageRight + (t.eyeImageRight - c.eyeImageRight) * ae,
                mouthOpen = c.mouthOpen + (t.mouthOpen - c.mouthOpen) * am,
            )
        }
        current = n
        FacePose(n.cx, n.cy, n.width, n.rollDeg, n.yaw, n.pitch, n.smile, n.eyeImageLeft, n.eyeImageRight, n.mouthOpen, visibility)
    }
}
