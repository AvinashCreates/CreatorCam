package com.avinash.creatorcam

import com.avinash.creatorcam.gl.FaceMapping
import com.avinash.creatorcam.gl.Orientation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive check of the orientation transform against a physical model of the camera:
 * for every sensor rotation, every possible "transform hint" baked into the SurfaceTexture matrix
 * (rotated and/or mirrored), mirror on/off and several buffer shapes, the pixel shown at output
 * position o must be the scene point that is at o in the upright (optionally mirrored, cropped) image.
 */
class OrientationTest {
    private val outAspect = 9f / 16f
    private val rotations = listOf(0, 90, 180, 270)
    private val buffers = listOf(1280 to 720, 1920 to 1080, 640 to 480, 800 to 600)

    /** SurfaceTexture matrix for a producer whose intended image = raw buffer rotated CW by [hint] (optionally mirrored). */
    private fun simulatedSt(hint: Int, hintMirror: Boolean): FloatArray {
        val a = Math.toRadians(hint.toDouble())
        val c = Math.round(cos(a)).toFloat()
        val s = Math.round(sin(a)).toFloat()
        // G = Rot(hint) * (hintMirror ? diag(-1,1) : I)
        val mx = if (hintMirror) -1f else 1f
        val g00 = c * mx; val g01 = -s; val g10 = s * mx; val g11 = c
        // A = diag(1,-1) * G   (so that Flip * A = G)
        val a00 = g00; val a01 = g01; val a10 = -g10; val a11 = -g11
        val m = FloatArray(16)
        m[0] = a00; m[1] = a10; m[4] = a01; m[5] = a11; m[10] = 1f; m[15] = 1f
        m[12] = 0.5f - (a00 + a01) * 0.5f
        m[13] = 0.5f - (a10 + a11) * 0.5f
        return m
    }

    /** Physical model: raw buffer rotated CLOCKWISE by theta is upright. Returns scene (upright, y up) for texture coords t. */
    private fun scenePoint(tx: Float, ty: Float, theta: Int): Pair<Float, Float> {
        val vx = tx - 0.5f
        val vy = (1f - ty) - 0.5f
        val a = Math.toRadians(-theta.toDouble())       // rotate back clockwise by theta
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return Pair(0.5f + c * vx - s * vy, 0.5f + s * vx + c * vy)
    }

    private val probes = listOf(0.1f to 0.1f, 0.9f to 0.2f, 0.5f to 0.5f, 0.25f to 0.8f, 0.8f to 0.9f)

    @Test fun outputIsUprightForEveryRotationHintMirrorAndBufferShape() {
        var cases = 0
        for (theta in rotations) for (hint in rotations) for (hintMirror in listOf(false, true))
            for (mirror in listOf(false, true)) for ((bw, bh) in buffers) {
                val st = simulatedSt(hint, hintMirror)
                val out = FloatArray(16)
                val crop = Orientation.compute(st, theta, mirror, bw, bh, outAspect, out)
                for ((u, v) in probes) {
                    val (tx, ty) = Orientation.sample(out, u, v)
                    val (sx, sy) = scenePoint(tx, ty, theta)
                    val ex = 0.5f + (if (mirror) -crop.x else crop.x) * (u - 0.5f)
                    val ey = 0.5f + crop.y * (v - 0.5f)
                    assertEquals("x theta=$theta hint=$hint hm=$hintMirror mirror=$mirror ${bw}x$bh", ex, sx, 1e-4f)
                    assertEquals("y theta=$theta hint=$hint hm=$hintMirror mirror=$mirror ${bw}x$bh", ey, sy, 1e-4f)
                }
                cases++
            }
        assertEquals(4 * 4 * 2 * 2 * 4, cases)
    }

    /** Documents the original bug: rotating by rotationDegrees on top of a matrix that already holds the rotation. */
    @Test fun legacyDoubleRotationWouldHaveBeenWrong() {
        val theta = 90
        val st = simulatedSt(hint = 90, hintMirror = false)
        // Legacy = skip the G^-1 correction, i.e. behave as if ST were a plain flip.
        val plain = simulatedSt(hint = 0, hintMirror = false)
        val legacy = FloatArray(16)
        val crop = Orientation.compute(plain, theta, false, 1280, 720, outAspect, legacy)
        // Feed the legacy matrix the ST that really contains a 90 degree hint: compose manually.
        val composed = FloatArray(16)
        Orientation.multiply(st, legacyLinearOnly(legacy, plain), composed)
        val (tx, ty) = Orientation.sample(composed, 0.9f, 0.2f)
        val (sx, sy) = scenePoint(tx, ty, theta)
        val ex = 0.5f + crop.x * 0.4f
        val ey = 0.5f + crop.y * -0.3f
        assertTrue("legacy path must NOT land on the upright point", abs(ex - sx) > 0.05f || abs(ey - sy) > 0.05f)
    }

    /** legacy = ST * N, so N = ST^-1 * legacy; for a plain flip ST, N = flip * legacy. */
    private fun legacyLinearOnly(legacy: FloatArray, plain: FloatArray): FloatArray {
        // plain is its own inverse (flip about the centre)
        val n = FloatArray(16)
        Orientation.multiply(plain, legacy, n)
        return n
    }

    @Test fun faceAndImageShareOneCoordinateSystem() {
        for (theta in rotations) for (hint in rotations) for (mirror in listOf(false, true)) for ((bw, bh) in buffers) {
            val out = FloatArray(16)
            val crop = Orientation.compute(simulatedSt(hint, false), theta, mirror, bw, bh, outAspect, out)
            // A face at several places in the upright analysis image (x right, y DOWN).
            for ((fx, fy) in listOf(0.3f to 0.4f, 0.55f to 0.45f, 0.5f to 0.5f)) {
                val (ox, oyDown) = FaceMapping.toOutput(fx, fy, crop, mirror)
                if (ox < 0f || ox > 1f || oyDown < 0f || oyDown > 1f) continue   // cropped out of frame
                val (tx, ty) = Orientation.sample(out, ox, 1f - oyDown)
                val (sx, sy) = scenePoint(tx, ty, theta)
                assertEquals("face x", fx, sx, 1e-4f)
                assertEquals("face y", fy, 1f - sy, 1e-4f)
            }
        }
    }

    @Test fun leftStaysLeftAndMirrorFlipsOnlyWhenAsked() {
        val st = simulatedSt(90, false)
        val out = FloatArray(16)
        val crop = Orientation.compute(st, 90, false, 1280, 720, outAspect, out)
        assertTrue(FaceMapping.toOutput(0.2f, 0.5f, crop, false).first < FaceMapping.toOutput(0.8f, 0.5f, crop, false).first)
        assertTrue(FaceMapping.toOutput(0.2f, 0.5f, crop, true).first > FaceMapping.toOutput(0.8f, 0.5f, crop, true).first)
        assertEquals(1f, FaceMapping.widthToOutput(0.2f, crop) / 0.2f, 1e-5f) // 16:9 source, 9:16 output: no crop
    }
}
