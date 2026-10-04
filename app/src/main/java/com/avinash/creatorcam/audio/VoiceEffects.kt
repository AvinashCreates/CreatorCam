package com.avinash.creatorcam.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tanh

enum class VoiceEffectType(val label: String) {
    NORMAL("Normal"),
    DEEP("Deep"),
    HIGH("High"),
    ROBOT("Robot"),
    ECHO("Echo"),
    TRICKSTER("Trickster"),
}

/**
 * Streaming pitch shifter (two crossfaded delay taps). Output length always equals input length,
 * so audio/video sync is untouched. Latency is a constant ~half the window (~20 ms).
 * ratio > 1 raises pitch, ratio < 1 lowers it.
 */
class PitchShifter(private val ratio: Float, sampleRate: Int) {
    private val window = (sampleRate * 0.04f).toInt()
    private val buf = FloatArray(window + 8)
    private var writePos = 0
    private var phase = 0f

    fun process(x: Float): Float {
        buf[writePos] = x
        val p2 = if (phase + 0.5f >= 1f) phase - 0.5f else phase + 0.5f
        val s = sin(PI.toFloat() * phase)
        val c = cos(PI.toFloat() * phase)
        val out = read(phase * window) * (s * s) + read(p2 * window) * (c * c)
        writePos = (writePos + 1) % buf.size
        phase += (1f - ratio) / window
        if (phase >= 1f) phase -= 1f
        if (phase < 0f) phase += 1f
        return out
    }

    private fun read(delay: Float): Float {
        val pos = writePos - delay
        val i0 = floor(pos).toInt()
        val frac = pos - i0
        val a = buf[Math.floorMod(i0, buf.size)]
        val b = buf[Math.floorMod(i0 + 1, buf.size)]
        return a + (b - a) * frac
    }
}

/** Feedback echo. */
class Echo(sampleRate: Int, delayMs: Int = 300, private val feedback: Float = 0.38f, private val mix: Float = 0.5f) {
    private val buf = FloatArray(sampleRate * delayMs / 1000)
    private var pos = 0

    fun process(x: Float): Float {
        val delayed = buf[pos]
        buf[pos] = x + delayed * feedback
        pos = (pos + 1) % buf.size
        return x + delayed * mix
    }
}

/** Ring modulator (metallic "robot" colour). */
class RingMod(private val sampleRate: Int, private val freq: Float) {
    private var n = 0L
    fun process(x: Float): Float {
        val carrier = sin(2.0 * PI * freq * n / sampleRate).toFloat()
        n++
        return x * carrier
    }
}

/**
 * Applies a [VoiceEffectType] to 16-bit mono PCM in place. One instance per recording
 * (the effects keep internal state). Not thread-safe; use from the audio thread only.
 */
class VoiceProcessor(val type: VoiceEffectType, sampleRate: Int) {
    private val deep = if (type == VoiceEffectType.DEEP) PitchShifter(0.72f, sampleRate) else null
    private val high = if (type == VoiceEffectType.HIGH) PitchShifter(1.45f, sampleRate) else null
    private val trickLow = if (type == VoiceEffectType.TRICKSTER) PitchShifter(0.78f, sampleRate) else null
    private val trickHigh = if (type == VoiceEffectType.TRICKSTER) PitchShifter(1.32f, sampleRate) else null
    private val trickRing = if (type == VoiceEffectType.TRICKSTER) RingMod(sampleRate, 70f) else null
    private val robotRing = if (type == VoiceEffectType.ROBOT) RingMod(sampleRate, 55f) else null
    private val echo = if (type == VoiceEffectType.ECHO) Echo(sampleRate) else null

    fun process(pcm: ShortArray, count: Int) {
        if (type == VoiceEffectType.NORMAL) return
        for (i in 0 until count) {
            val x = pcm[i] / 32768f
            val y = when (type) {
                VoiceEffectType.DEEP -> deep!!.process(x)
                VoiceEffectType.HIGH -> high!!.process(x)
                VoiceEffectType.ROBOT -> 0.25f * x + 0.95f * robotRing!!.process(x)
                VoiceEffectType.ECHO -> echo!!.process(x)
                VoiceEffectType.TRICKSTER -> {
                    val layered = 0.62f * trickLow!!.process(x) + 0.38f * trickHigh!!.process(x)
                    layered * 0.88f + 0.35f * trickRing!!.process(layered)
                }
                VoiceEffectType.NORMAL -> x
            }
            pcm[i] = (tanh(y.toDouble()) * 32767.0).toInt().toShort()
        }
    }
}
