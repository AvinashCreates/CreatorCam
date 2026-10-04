package com.avinash.creatorcam.gl

import kotlin.math.abs

/** How much of the upright source is visible in the 9:16 output (aspect-fill crop). Both factors are <= 1. */
data class Crop(val x: Float, val y: Float)

/**
 * THE one authoritative orientation transform. Pure math (no Android classes) so it is unit-tested.
 *
 * Coordinates used below:
 *  - "output" o: the final upright 9:16 frame, u right / v UP, 0..1. This is what is displayed AND encoded.
 *  - "raw buffer" v: the camera buffer as the sensor wrote it, viewed top-up, y UP, 0..1.
 *  - texture coords t: what the shader samples. t = (v.x, 1 - v.y) because row 0 of a buffer is its top.
 *  - ST: SurfaceTexture.getTransformMatrix(). Contract: t = ST * u, where u are the coordinates of the
 *    producer's *intended* image. The camera service may bake sensor rotation into that intended image
 *    (the "transform hint"), so u is NOT necessarily the raw buffer. The old code ignored this and then
 *    rotated again with CameraX's rotationDegrees => rotated twice.
 *
 * Physical truth (independent of ST): rotating the RAW buffer clockwise by [rotationDegrees]
 * (CameraX TransformationInfo, relative to the target/display rotation) gives the upright image.
 *   v_c = Rot(+theta) * s_c         (s = upright scene, c = centred coordinates, Rot = counter-clockwise)
 * ST's own linear part tells us how u relates to the raw buffer:  v = G u,  G = Flip * A(ST).
 * So:  u = G^-1 * Rot(theta) * S * o_c   and   t = ST * (u + 0.5)
 * where S = diag(+-cropX, cropY) does the aspect-fill crop and the selfie mirror, in upright space.
 */
object Orientation {

    /**
     * @param st      SurfaceTexture transform matrix (column-major 4x4)
     * @param rotationDegrees CameraX clockwise rotation from the raw buffer to upright for the current target rotation
     * @param mirror  mirror the upright image horizontally (front-camera selfie view)
     * @param bufW/bufH raw buffer size
     * @param outAspect output width/height (9/16)
     * @param out     receives the column-major 4x4 matrix to use as `vTex = out * vec4(vUv, 0, 1)`
     */
    fun compute(st: FloatArray, rotationDegrees: Int, mirror: Boolean, bufW: Int, bufH: Int, outAspect: Float, out: FloatArray): Crop {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        val upW = if (sideways) bufH else bufW
        val upH = if (sideways) bufW else bufH
        val srcAspect = upW.toFloat() / upH
        var cx = 1f
        var cy = 1f
        if (srcAspect > outAspect) cx = outAspect / srcAspect else cy = srcAspect / outAspect

        // G = Flip * A, where A is ST's 2x2 linear part. (A = diag(1,-1) for a plain buffer, so G = I.)
        val g00 = st[0]
        val g01 = st[4]
        val g10 = -st[1]
        val g11 = -st[5]
        val det = g00 * g11 - g01 * g10
        val i00: Float; val i01: Float; val i10: Float; val i11: Float
        if (abs(det) < 1e-6f) {
            i00 = 1f; i01 = 0f; i10 = 0f; i11 = 1f
        } else {
            i00 = g11 / det; i01 = -g01 / det; i10 = -g10 / det; i11 = g00 / det
        }

        val c: Float
        val s: Float
        when (rot) {
            0 -> { c = 1f; s = 0f }
            90 -> { c = 0f; s = 1f }
            180 -> { c = -1f; s = 0f }
            270 -> { c = 0f; s = -1f }
            else -> { val a = Math.toRadians(rot.toDouble()); c = Math.cos(a).toFloat(); s = Math.sin(a).toFloat() }
        }
        val sxs = if (mirror) -cx else cx
        // R * S
        val r00 = c * sxs
        val r01 = -s * cy
        val r10 = s * sxs
        val r11 = c * cy
        // M = G^-1 * R * S
        val m00 = i00 * r00 + i01 * r10
        val m01 = i00 * r01 + i01 * r11
        val m10 = i10 * r00 + i11 * r10
        val m11 = i10 * r01 + i11 * r11

        // N = T(0.5) * M * T(-0.5)
        val n = FloatArray(16)
        n[0] = m00; n[1] = m10
        n[4] = m01; n[5] = m11
        n[10] = 1f; n[15] = 1f
        n[12] = 0.5f - (m00 + m01) * 0.5f
        n[13] = 0.5f - (m10 + m11) * 0.5f
        multiply(st, n, out)
        return Crop(cx, cy)
    }

    /** out = a * b for column-major 4x4 matrices. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        System.arraycopy(r, 0, out, 0, 16)
    }

    /** Texture coordinate the shader would sample for output position (u, v) (v up). */
    fun sample(m: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        Pair(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
}

/**
 * Maps the face detector's coordinates into the SAME final output frame the shader produces,
 * so the avatar and the camera image always share one coordinate system.
 * Input: upright analysis image, x right 0..1, y DOWN 0..1. Output: final frame, x right, y DOWN.
 */
object FaceMapping {
    fun toOutput(x: Float, y: Float, crop: Crop, mirror: Boolean): Pair<Float, Float> {
        val ox = (x - 0.5f) / crop.x + 0.5f
        val oy = (y - 0.5f) / crop.y + 0.5f
        return Pair(if (mirror) 1f - ox else ox, oy)
    }

    fun widthToOutput(width: Float, crop: Crop): Float = width / crop.x

    /** Head roll (CCW positive, y-up) as it should rotate the avatar in the final frame. Mirroring reverses the sense. */
    fun rollForDisplay(rollDeg: Float, mirror: Boolean): Float = if (mirror) -rollDeg else rollDeg

    /** Turn (positive = towards image right) as seen in the final frame. */
    fun yawForDisplay(yaw: Float, mirror: Boolean): Float = if (mirror) -yaw else yaw

    /** Eye-open values for the avatar's screen-left and screen-right eyes. A mirrored image swaps the sides. */
    fun eyesForDisplay(imageLeft: Float, imageRight: Float, mirror: Boolean): Pair<Float, Float> =
        if (mirror) Pair(imageRight, imageLeft) else Pair(imageLeft, imageRight)
}
