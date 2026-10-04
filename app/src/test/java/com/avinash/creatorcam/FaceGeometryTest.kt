package com.avinash.creatorcam

import com.avinash.creatorcam.face.FaceGeometry
import com.avinash.creatorcam.face.MouthEstimator
import com.avinash.creatorcam.face.Pt
import com.avinash.creatorcam.face.RawFace
import com.avinash.creatorcam.gl.FaceMapping
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGeometryTest {
    private fun face(
        left: Pt? = Pt(150f, 200f), right: Pt? = Pt(250f, 200f), nose: Pt? = Pt(200f, 260f), mouth: Pt? = Pt(200f, 330f),
        leftProb: Float? = 1f, rightProb: Float? = 1f, smile: Float? = 0f, eulerZ: Float = 0f, eulerX: Float = 0f,
    ) = RawFace(200f, 230f, 200f, eulerX, eulerZ, smile, leftProb, rightProb, left, right, nose, mouth)

    private fun measure(f: RawFace, mouth: MouthEstimator = MouthEstimator()) = FaceGeometry.measure(f, 400f, 640f, mouth)

    @Test fun levelEyesMeanNoRoll() = assertEquals(0f, measure(face()).rollDeg, 0.01f)

    @Test fun rollSignIsCounterClockwiseInYUpConvention() {
        // Image coordinates: y grows downward. Image-right eye LOWER on screen = head tilted clockwise = negative roll.
        assertTrue(measure(face(right = Pt(250f, 220f))).rollDeg < -5f)
        assertTrue(measure(face(right = Pt(250f, 180f))).rollDeg > 5f)
        assertEquals(-11.31f, measure(face(right = Pt(250f, 220f))).rollDeg, 0.05f)
    }

    @Test fun eyeSideIsDecidedByPositionNotByMlKitNaming() {
        // ML Kit's "left" eye reported at the image's RIGHT side and closed; the other open.
        val m = measure(face(left = Pt(250f, 200f), right = Pt(150f, 200f), leftProb = 0.05f, rightProb = 0.95f))
        assertEquals(0.95f, m.eyeImageLeft, 1e-6f)
        assertEquals(0.05f, m.eyeImageRight, 1e-6f)
        // Same faces, naming the other way round, must give the same answer.
        val n = measure(face(left = Pt(150f, 200f), right = Pt(250f, 200f), leftProb = 0.95f, rightProb = 0.05f))
        assertEquals(m.eyeImageLeft, n.eyeImageLeft, 1e-6f)
        assertEquals(m.eyeImageRight, n.eyeImageRight, 1e-6f)
    }

    @Test fun yawFollowsNoseOffsetTowardsImageRight() {
        assertEquals(0f, measure(face(nose = Pt(200f, 260f))).yaw, 1e-4f)
        assertEquals(0.5f, measure(face(nose = Pt(220f, 260f))).yaw, 1e-3f)   // 20 px right of centre, eye distance 100
        assertTrue(measure(face(nose = Pt(170f, 260f))).yaw < -0.5f)
        assertEquals(1f, measure(face(nose = Pt(260f, 260f))).yaw, 1e-6f)     // clamped
    }

    @Test fun yawIsNotContaminatedByHeadRoll() {
        // Eye line rotated 30 degrees, nose straight "below" the eye midpoint in the head's own frame.
        val a = Math.toRadians(30.0)
        fun rot(x: Float, y: Float) = Pt((200 + x * cos(a) - y * sin(a)).toFloat(), (200 + x * sin(a) + y * cos(a)).toFloat())
        val f = face(left = rot(-50f, 0f), right = rot(50f, 0f), nose = rot(0f, 60f), mouth = rot(0f, 130f))
        assertEquals(0f, measure(f).yaw, 1e-3f)
    }

    @Test fun missingLandmarksFallBackSafely() {
        val m = measure(face(left = null, right = null, nose = null, mouth = null, eulerZ = 12f))
        assertEquals(12f, m.rollDeg, 1e-6f)
        assertEquals(0f, m.yaw, 0f)
        assertEquals(0f, m.mouthOpen, 0f)
    }

    @Test fun smileProbabilityIsShaped() {
        assertEquals(0f, measure(face(smile = 0.1f)).smile, 1e-6f)
        assertEquals(1f, measure(face(smile = 0.95f)).smile, 1e-6f)
        assertTrue(measure(face(smile = 0.6f)).smile in 0.3f..0.8f)
    }

    @Test fun mouthEstimatorLearnsNeutralAndDetectsOpening() {
        val est = MouthEstimator()
        repeat(10) { assertEquals(0f, est.update(0.90f), 1e-6f) }
        assertTrue(est.update(1.20f) > 0.6f)
        assertEquals(1f, est.update(1.60f), 1e-6f)
        repeat(30) { est.update(1.20f) }
        assertTrue("a held-open mouth must stay open", est.update(1.20f) > 0.5f)
        assertEquals(0f, est.update(0.90f), 1e-6f)
    }

    @Test fun mirroredDisplaySwapsEyesAndFlipsTurnAndRoll() {
        assertEquals(Pair(0.9f, 0.1f), FaceMapping.eyesForDisplay(0.1f, 0.9f, mirror = true))
        assertEquals(Pair(0.1f, 0.9f), FaceMapping.eyesForDisplay(0.1f, 0.9f, mirror = false))
        assertEquals(-0.4f, FaceMapping.yawForDisplay(0.4f, true), 0f)
        assertEquals(0.4f, FaceMapping.yawForDisplay(0.4f, false), 0f)
        assertEquals(-15f, FaceMapping.rollForDisplay(15f, true), 0f)
    }

    @Test fun pitchIsClampedAndScaled() {
        assertEquals(1f, measure(face(eulerX = 40f)).pitch, 1e-6f)
        assertEquals(-0.4f, measure(face(eulerX = -10f)).pitch, 1e-6f)
    }
}
