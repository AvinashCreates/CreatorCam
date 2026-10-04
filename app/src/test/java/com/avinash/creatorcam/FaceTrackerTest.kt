package com.avinash.creatorcam

import com.avinash.creatorcam.face.FaceMeasurement
import com.avinash.creatorcam.face.FaceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackerTest {
    private fun ms(v: Long) = v * 1_000_000L

    @Test fun noFaceMeansNoPose() {
        assertNull(FaceTracker().advance(ms(100)))
    }

    @Test fun followsTargetSmoothlyAndConverges() {
        val t = FaceTracker()
        t.onMeasurement(FaceMeasurement(0.3f, 0.4f, 0.2f, 0f), ms(0))
        var now = 0L
        repeat(10) { now += ms(33); t.advance(now) }
        t.onMeasurement(FaceMeasurement(0.7f, 0.4f, 0.2f, 10f), now)
        now += ms(33)
        val first = t.advance(now)!!
        assertTrue("moves toward target but not instantly", first.cx > 0.3f && first.cx < 0.7f)
        repeat(30) { now += ms(33); t.onMeasurement(FaceMeasurement(0.7f, 0.4f, 0.2f, 10f), now); t.advance(now) }
        val last = t.advance(now + ms(33))!!
        assertEquals(0.7f, last.cx, 0.02f)
        assertEquals(10f, last.rollDeg, 0.5f)
    }

    @Test fun fadesOutAfterFaceIsLost() {
        val t = FaceTracker()
        t.onMeasurement(FaceMeasurement(0.5f, 0.5f, 0.3f, 0f), ms(0))
        var now = 0L
        repeat(8) { now += ms(33); t.advance(now) }          // ~264 ms: still inside the 400 ms hold time
        assertNotNull(t.advance(now + ms(33)))
        repeat(60) { now += ms(33); t.advance(now) }   // ~2 s with no measurements
        assertNull(t.advance(now + ms(33)))
    }

    @Test fun blinkAndExpressionAreTrackedWithShortLatency() {
        val t = FaceTracker()
        var now = 0L
        val open = FaceMeasurement(0.5f, 0.5f, 0.3f, 0f, eyeImageLeft = 1f, eyeImageRight = 1f, mouthOpen = 0f, smile = 0f)
        t.onMeasurement(open, now)
        repeat(5) { now += ms(33); t.advance(now) }
        t.onMeasurement(open.copy(eyeImageLeft = 0f, eyeImageRight = 0f, mouthOpen = 1f, smile = 1f), now)
        repeat(4) { now += ms(33); t.advance(now) }                // ~130 ms later
        val p = t.advance(now + ms(33))!!
        assertTrue("eyes are mostly closed within ~160 ms", p.eyeImageLeft < 0.15f)
        assertTrue("mouth is mostly open", p.mouthOpen > 0.7f)
        assertTrue("smile follows", p.smile > 0.7f)
    }
}
