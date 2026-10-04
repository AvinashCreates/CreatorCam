package com.avinash.creatorcam.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.avinash.creatorcam.audio.VoiceEffectType
import com.avinash.creatorcam.audio.VoiceProcessor
import com.avinash.creatorcam.gl.CameraRenderer
import com.avinash.creatorcam.settings.VideoQuality
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Records the renderer's output into an MP4 file:
 *   video: GL frames (effects + avatar already drawn) -> H.264 via the encoder's input surface
 *   audio: AudioRecord -> [VoiceProcessor] -> AAC
 *   both muxed with MediaMuxer, on a timeline derived from the camera's own frame timestamps.
 *
 * Listener callbacks are always delivered on the main thread.
 */
class Mp4Recorder(private val renderer: CameraRenderer) {

    interface Listener {
        fun onFinished(file: File, hasAudio: Boolean)
        fun onError(message: String)
    }

    private var session: RecordingSession? = null

    val isRecording: Boolean get() = session != null

    /** Returns false (and reports nothing) if recording could not even be prepared. */
    fun start(file: File, quality: VideoQuality, voice: VoiceEffectType, micAllowed: Boolean, listener: Listener): Boolean {
        if (session != null) return false
        val s = RecordingSession(renderer, file, quality, voice, micAllowed, listener) { session = null }
        return try {
            s.start()
            session = s
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start recording", e)
            s.cleanupAfterFailedStart()
            false
        }
    }

    fun stop() {
        session?.requestStop()
    }

    /** Throws the take away without any callback. */
    fun cancel() {
        session?.abortSilently()
        session = null
    }

    private companion object {
        const val TAG = "Mp4Recorder"
    }
}

private class RecordingSession(
    private val renderer: CameraRenderer,
    private val file: File,
    private val quality: VideoQuality,
    voice: VoiceEffectType,
    private val micAllowed: Boolean,
    private val listener: Mp4Recorder.Listener,
    private val onDone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val voiceProcessor = VoiceProcessor(voice, SAMPLE_RATE)

    private var videoCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var muxThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile private var firstVideoTsNs = 0L
    @Volatile private var videoClockIsRealtime = false
    @Volatile private var stopRequested = false
    @Volatile private var stopAtMs = 0L
    @Volatile private var aborted = false
    @Volatile private var silent = false
    @Volatile private var failure: String? = null
    @Volatile private var audioFailed = false

    // Muxer-thread state
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var videoSamples = 0
    private var audioSamples = 0
    private val pending = ArrayList<PendingSample>()

    private class PendingSample(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val vf = MediaFormat.createVideoFormat(MIME_VIDEO, quality.width, quality.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val vc = MediaCodec.createEncoderByType(MIME_VIDEO)
        videoCodec = vc
        vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vc.createInputSurface()
        inputSurface = surface
        vc.start()

        if (micAllowed) setupAudio()

        file.parentFile?.mkdirs()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxThread = Thread({ muxLoop() }, "cc-mux").also { it.start() }
        renderer.startEncoding(surface, quality.width, quality.height) { ts -> onVideoFrame(ts) }
    }

    fun requestStop() {
        if (stopRequested || aborted) return
        if (firstVideoTsNs == 0L) {
            fail("That clip was too short to save. Hold the button a little longer.")
            return
        }
        stopRequested = true
        stopAtMs = SystemClock.elapsedRealtime()
        renderer.stopEncoding {
            try {
                videoCodec?.signalEndOfInputStream()
            } catch (e: Exception) {
                fail("Video encoder error while stopping.")
            }
        }
    }

    fun abortSilently() {
        silent = true
        aborted = true
        stopRequested = true
        if (muxThread == null) cleanupAfterFailedStart()
    }

    private fun fail(message: String) {
        if (failure == null) failure = message
        aborted = true
        stopRequested = true
    }

    fun cleanupAfterFailedStart() {
        runCatching { videoCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { audioCodec?.release() }
        runCatching { audioRecord?.release() }
        runCatching { muxer?.release() }
        file.delete()
    }

    // ------------------------------------------------------------------ audio

    @SuppressLint("MissingPermission") // Caller only passes micAllowed=true after checking RECORD_AUDIO.
    private fun setupAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioRecord = rec
            audioCodec = codec
        } catch (e: Exception) {
            Log.w(TAG, "Microphone unavailable, recording without audio", e)
            runCatching { audioRecord?.release() }
            audioRecord = null
            audioCodec = null
        }
    }

    /** Called on the GL thread for each frame handed to the encoder. */
    private fun onVideoFrame(timestampNs: Long) {
        if (firstVideoTsNs != 0L) return
        val rt = SystemClock.elapsedRealtimeNanos()
        val mono = System.nanoTime()
        videoClockIsRealtime = abs(timestampNs - rt) < abs(timestampNs - mono)
        firstVideoTsNs = timestampNs
        val rec = audioRecord
        val codec = audioCodec
        if (rec != null && codec != null) {
            audioThread = Thread({ audioLoop(rec, codec) }, "cc-audio").also { it.start() }
        }
    }

    private fun audioLoop(rec: AudioRecord, codec: MediaCodec) {
        val pcm = ShortArray(1024)
        var written = 0L
        var startNs = 0L
        var lastPts = 0L
        try {
            rec.startRecording()
            while (!stopRequested && !aborted) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) throw IOException("Microphone read error $n")
                if (n == 0) continue
                if (startNs == 0L) {
                    val now = if (videoClockIsRealtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()
                    startNs = now - n * 1_000_000_000L / SAMPLE_RATE
                }
                voiceProcessor.process(pcm, n)
                val ptsUs = maxOf(0L, (startNs - firstVideoTsNs) / 1000 + written * 1_000_000L / SAMPLE_RATE)
                feedAudio(codec, pcm, n, ptsUs)
                written += n
                lastPts = ptsUs
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture failed", e)
            audioFailed = true
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            sendAudioEos(codec, lastPts)
        }
    }

    private fun feedAudio(codec: MediaCodec, pcm: ShortArray, n: Int, ptsUs: Long) {
        var offset = 0
        var misses = 0
        while (offset < n && !aborted) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                if (++misses > 100) return // encoder stalled for ~1 s; drop this chunk rather than hang
                continue
            }
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            val chunk = minOf(n - offset, buf.capacity() / 2)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, chunk)
            codec.queueInputBuffer(idx, 0, chunk * 2, ptsUs + offset * 1_000_000L / SAMPLE_RATE, 0)
            offset += chunk
            misses = 0
        }
    }

    private fun sendAudioEos(codec: MediaCodec, ptsUs: Long) {
        repeat(20) {
            try {
                val idx = codec.dequeueInputBuffer(50_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
            } catch (e: Exception) {
                return
            }
        }
    }

    // ------------------------------------------------------------------ muxing

    private fun muxLoop() {
        val vc = videoCodec!!
        val ac = audioCodec
        val info = MediaCodec.BufferInfo()
        var videoEos = false
        var audioEos = ac == null
        var audioDropped = ac == null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (!(videoEos && audioEos) && !aborted) {
                if (stopAtMs != 0L && SystemClock.elapsedRealtime() - stopAtMs > FINISH_TIMEOUT_MS) {
                    Log.w(TAG, "Encoders didn't finish in time; finalising what we have")
                    break
                }
                if (!audioDropped && (audioFailed || (audioTrack < 0 && SystemClock.elapsedRealtime() - startedAt > AUDIO_FORMAT_TIMEOUT_MS && firstVideoTsNs != 0L))) {
                    audioDropped = true
                    audioEos = true
                }
                if (!videoEos) videoEos = drain(vc, info, isVideo = true)
                if (ac != null && !audioEos) audioEos = drain(ac, info, isVideo = false)
                maybeStartMuxer(audioDropped)
            }
            if (!aborted) finishMuxer()
        } catch (e: Exception) {
            Log.e(TAG, "Muxing failed", e)
            fail("Saving the video failed: ${e.message ?: "encoder error"}")
        } finally {
            val hasAudio = audioSamples > 0
            teardown()
            deliver(hasAudio)
        }
    }

    /** Pulls one output buffer from [codec]. Returns true once end-of-stream has been seen. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean): Boolean {
        val index = codec.dequeueOutputBuffer(info, 5_000)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val track = muxer!!.addTrack(codec.outputFormat)
                if (isVideo) videoTrack = track else audioTrack = track
                return false
            }
            index < 0 -> return false
        }
        val data = codec.getOutputBuffer(index)
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (data != null && info.size > 0 && !isConfig) {
            if (isVideo) {
                val pts = maxOf(info.presentationTimeUs - firstVideoTsNs / 1000, lastVideoUs + 1)
                lastVideoUs = pts
                videoSamples++
                writeOrQueue(videoTrack, data, info, pts)
            } else {
                val pts = maxOf(info.presentationTimeUs, lastAudioUs + 1)
                lastAudioUs = pts
                audioSamples++
                writeOrQueue(audioTrack, data, info, pts)
            }
        }
        codec.releaseOutputBuffer(index, false)
        return eos
    }

    private fun maybeStartMuxer(audioDropped: Boolean) {
        if (muxerStarted || videoTrack < 0) return
        if (audioCodec != null && audioTrack < 0 && !audioDropped) return
        muxer!!.start()
        muxerStarted = true
        for (p in pending) muxer!!.writeSampleData(p.track, p.data, p.info)
        pending.clear()
    }

    private fun writeOrQueue(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) {
        if (track < 0) return
        if (muxerStarted) {
            val bi = MediaCodec.BufferInfo()
            bi.set(info.offset, info.size, ptsUs, info.flags)
            data.position(info.offset)
            data.limit(info.offset + info.size)
            muxer!!.writeSampleData(track, data, bi)
        } else {
            val copy = ByteBuffer.allocate(info.size)
            data.position(info.offset)
            data.limit(info.offset + info.size)
            copy.put(data)
            copy.flip()
            val bi = MediaCodec.BufferInfo()
            bi.set(0, info.size, ptsUs, info.flags)
            pending.add(PendingSample(track, copy, bi))
        }
    }

    private fun finishMuxer() {
        maybeStartMuxer(audioDropped = true)
        if (!muxerStarted || videoSamples == 0) throw IOException("No video frames were recorded")
        muxer!!.stop()
        muxerStarted = false
    }

    private fun teardown() {
        aborted = true // stops the audio loop if it's still running
        val latch = CountDownLatch(1)
        renderer.stopEncoding { latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
        runCatching { audioThread?.join(1500) }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
    }

    private fun deliver(hasAudio: Boolean) {
        val message = failure
        main.post {
            onDone()
            when {
                silent -> file.delete()
                message != null -> {
                    file.delete()
                    listener.onError(message)
                }
                else -> listener.onFinished(file, hasAudio)
            }
        }
    }

    private companion object {
        const val TAG = "RecordingSession"
        const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC
        const val SAMPLE_RATE = 44100
        const val FINISH_TIMEOUT_MS = 4000L
        const val AUDIO_FORMAT_TIMEOUT_MS = 3000L
    }
}
