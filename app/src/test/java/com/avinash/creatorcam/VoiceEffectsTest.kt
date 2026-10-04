package com.avinash.creatorcam

import com.avinash.creatorcam.audio.VoiceEffectType
import com.avinash.creatorcam.audio.VoiceProcessor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEffectsTest {
    private val rate = 44100

    private fun sine(freq: Double, seconds: Double): ShortArray =
        ShortArray((rate * seconds).toInt()) { (sin(2 * PI * freq * it / rate) * 12000).toInt().toShort() }

    /** Frequency estimate from positive-going zero crossings over the last second. */
    private fun estimateHz(pcm: ShortArray): Double {
        val start = pcm.size - rate
        var crossings = 0
        for (i in start + 1 until pcm.size) if (pcm[i - 1] < 0 && pcm[i] >= 0) crossings++
        return crossings.toDouble()
    }

    private fun run(type: VoiceEffectType, input: ShortArray): ShortArray {
        val out = input.copyOf()
        val p = VoiceProcessor(type, rate)
        var i = 0
        while (i < out.size) {            // feed in odd-sized chunks like a real AudioRecord loop
            val n = minOf(1000, out.size - i)
            val chunk = out.copyOfRange(i, i + n)
            p.process(chunk, n)
            chunk.copyInto(out, i)
            i += n
        }
        return out
    }

    @Test fun normalIsUntouched() {
        val input = sine(440.0, 1.0)
        assertTrue(input.contentEquals(run(VoiceEffectType.NORMAL, input)))
    }

    @Test fun deepLowersPitch() {
        val hz = estimateHz(run(VoiceEffectType.DEEP, sine(440.0, 3.0)))
        assertEquals(440 * 0.72, hz, 440 * 0.72 * 0.08)
    }

    @Test fun highRaisesPitch() {
        val hz = estimateHz(run(VoiceEffectType.HIGH, sine(440.0, 3.0)))
        assertEquals(440 * 1.45, hz, 440 * 1.45 * 0.08)
    }

    @Test fun echoRepeatsImpulse() {
        val input = ShortArray(rate) { 0 }.also { it[0] = 20000 }
        val out = run(VoiceEffectType.ECHO, input)
        val echoAt = rate * 300 / 1000
        assertTrue(abs(out[echoAt].toInt()) > 4000)
    }

    @Test fun robotAndTricksterChangeSignalAndStayInRange() {
        val input = sine(300.0, 1.0)
        for (t in listOf(VoiceEffectType.ROBOT, VoiceEffectType.TRICKSTER)) {
            val out = run(t, input)
            assertTrue("$t should alter audio", !input.contentEquals(out))
            assertTrue(out.any { abs(it.toInt()) > 1000 })
        }
    }
}
