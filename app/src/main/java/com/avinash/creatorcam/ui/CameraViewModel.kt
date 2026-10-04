package com.avinash.creatorcam.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.avinash.creatorcam.audio.VoiceEffectType
import com.avinash.creatorcam.avatar.AvatarSpec
import com.avinash.creatorcam.camera.BindResult
import com.avinash.creatorcam.camera.CameraController
import com.avinash.creatorcam.effects.Adjustments
import com.avinash.creatorcam.effects.FilterPreset
import com.avinash.creatorcam.effects.combine
import com.avinash.creatorcam.face.FaceTracker
import com.avinash.creatorcam.gl.CameraRenderer
import com.avinash.creatorcam.record.Mp4Recorder
import com.avinash.creatorcam.settings.AppSettings
import com.avinash.creatorcam.settings.VideoQuality
import com.avinash.creatorcam.storage.MediaStorage
import com.avinash.creatorcam.storage.SavedMedia
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CaptureMode { VIDEO, PHOTO }
enum class RecordUiState { READY, RECORDING, PROCESSING, SAVED, ERROR }
enum class Panel { FILTERS, ADJUST, AVATARS, VOICE }

/** A finished capture waiting in the review screen. [saved] is non-null once it is in the gallery. */
data class Take(val file: File, val isVideo: Boolean, val hasAudio: Boolean = true, val saved: SavedMedia? = null)

data class CameraUiState(
    val quality: VideoQuality,
    val mirrorFront: Boolean,
    val mode: CaptureMode = CaptureMode.VIDEO,
    val front: Boolean = true,
    val cameraReady: Boolean = false,
    val hasFlash: Boolean = false,
    val torchOn: Boolean = false,
    val hasFront: Boolean = true,
    val hasBack: Boolean = true,
    val filter: FilterPreset = FilterPreset.ORIGINAL,
    val adjustments: Adjustments = Adjustments(),
    val avatar: AvatarSpec? = null,
    val voice: VoiceEffectType = VoiceEffectType.NORMAL,
    val panel: Panel? = null,
    val recordState: RecordUiState = RecordUiState.READY,
    val elapsedMs: Long = 0,
    val message: String? = null,
    val showSettings: Boolean = false,
    val take: Take? = null,
    val saving: Boolean = false,
    val lastSaved: SavedMedia? = null,
    val lastThumb: Bitmap? = null,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val storage = MediaStorage(app)
    private val tracker = FaceTracker()
    val renderer = CameraRenderer(tracker)
    private val controller = CameraController(app, renderer, tracker) { showMessage(it) }
    private val recorder = Mp4Recorder(renderer)

    var ui by mutableStateOf(CameraUiState(quality = settings.quality, mirrorFront = settings.mirrorFront))
        private set

    private var timerJob: Job? = null
    private var messageJob: Job? = null
    private var photoTimeoutJob: Job? = null
    private var recordStartMs = 0L
    private var photoToken = 0

    init {
        renderer.onError = { msg -> viewModelScope.launch { showMessage(msg) } }
        applyEffects()
        viewModelScope.launch(Dispatchers.IO) { storage.cleanOldTakes() }
        refreshLatest()
    }

    // ------------------------------------------------------------------ camera

    fun bindCamera(owner: LifecycleOwner) {
        renderer.mirror = ui.front && ui.mirrorFront
        controller.bind(owner, ui.front, ui.quality) { result ->
            when (result) {
                is BindResult.Bound -> {
                    ui = ui.copy(
                        cameraReady = true,
                        hasFlash = result.hasFlash,
                        hasFront = result.hasFront,
                        hasBack = result.hasBack,
                        torchOn = ui.torchOn && result.hasFlash,
                    )
                    if (ui.torchOn) controller.setTorch(true)
                }
                is BindResult.Failed -> {
                    ui = ui.copy(cameraReady = false)
                    showMessage(result.message)
                }
            }
        }
    }

    fun unbindCamera() {
        controller.unbind()
        if (ui.cameraReady) ui = ui.copy(cameraReady = false)
    }

    fun switchCamera() {
        if (ui.recordState != RecordUiState.READY && ui.recordState != RecordUiState.SAVED && ui.recordState != RecordUiState.ERROR) return
        val target = !ui.front
        if ((target && !ui.hasFront) || (!target && !ui.hasBack)) return
        ui = ui.copy(front = target, torchOn = false)
    }

    fun toggleTorch() {
        if (!ui.hasFlash) return
        val on = !ui.torchOn
        if (controller.setTorch(on)) ui = ui.copy(torchOn = on)
    }

    // ------------------------------------------------------------------ look & sound

    fun setMode(mode: CaptureMode) {
        if (ui.recordState == RecordUiState.RECORDING || ui.recordState == RecordUiState.PROCESSING) return
        ui = ui.copy(mode = mode, panel = if (mode == CaptureMode.PHOTO && ui.panel == Panel.VOICE) null else ui.panel)
    }

    fun togglePanel(panel: Panel) {
        ui = ui.copy(panel = if (ui.panel == panel) null else panel)
    }

    fun selectFilter(filter: FilterPreset) {
        ui = ui.copy(filter = filter)
        applyEffects()
    }

    fun setAdjustments(adjustments: Adjustments) {
        ui = ui.copy(adjustments = adjustments)
        applyEffects()
    }

    /** Pass null to remove the avatar. Face detection only runs while an avatar is selected. */
    fun selectAvatar(spec: AvatarSpec?) {
        ui = ui.copy(avatar = spec)
        renderer.setAvatar(spec)
        controller.setFaceTracking(spec != null)
    }

    /** Customise the current avatar (hair, skin, eyes, accessory). The change shows immediately in preview and recordings. */
    fun updateAvatar(change: (AvatarSpec) -> AvatarSpec) {
        val current = ui.avatar ?: return
        selectAvatar(change(current))
    }

    fun selectVoice(voice: VoiceEffectType) {
        if (ui.recordState == RecordUiState.RECORDING) return
        ui = ui.copy(voice = voice)
    }

    private fun applyEffects() {
        renderer.effect = combine(ui.filter, ui.adjustments)
    }

    // ------------------------------------------------------------------ settings

    fun showSettings(show: Boolean) {
        ui = ui.copy(showSettings = show)
    }

    fun setMirrorFront(mirror: Boolean) {
        settings.mirrorFront = mirror
        ui = ui.copy(mirrorFront = mirror)
        renderer.mirror = ui.front && mirror
    }

    fun setQuality(quality: VideoQuality) {
        if (ui.recordState == RecordUiState.RECORDING) return
        settings.quality = quality
        ui = ui.copy(quality = quality) // the UI rebinds the camera when quality changes
    }

    // ------------------------------------------------------------------ capture

    fun onShutter(micGranted: Boolean) {
        when (ui.mode) {
            CaptureMode.PHOTO -> takePhoto()
            CaptureMode.VIDEO -> when (ui.recordState) {
                RecordUiState.RECORDING -> stopRecording(force = false)
                RecordUiState.PROCESSING -> Unit
                else -> startRecording(micGranted)
            }
        }
    }

    private fun startRecording(micGranted: Boolean) {
        if (!ui.cameraReady) {
            showMessage("The camera isn't ready yet.")
            return
        }
        if (!micGranted && ui.voice != VoiceEffectType.NORMAL) {
            showMessage("Voice effects need the microphone. Allow it first, or pick Normal.")
            return
        }
        val file = storage.newTakeFile("mp4")
        val started = recorder.start(file, ui.quality, ui.voice, micGranted, object : Mp4Recorder.Listener {
            override fun onFinished(file: File, hasAudio: Boolean) = handleRecorded(file, hasAudio)
            override fun onError(message: String) = handleRecordError(message)
        })
        if (!started) {
            showMessage("Couldn't start recording on this device.")
            flashErrorState()
            return
        }
        recordStartMs = SystemClock.elapsedRealtime()
        ui = ui.copy(recordState = RecordUiState.RECORDING, elapsedMs = 0, panel = null)
        if (!micGranted) showMessage("Microphone is off, so this video will have no sound.")
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                val elapsed = SystemClock.elapsedRealtime() - recordStartMs
                ui = ui.copy(elapsedMs = elapsed)
                if (elapsed >= MAX_RECORD_MS) {
                    stopRecording(force = true)
                    break
                }
            }
        }
    }

    /** Taps in the first moments are ignored so an accidental double-tap can't destroy a take. */
    private fun stopRecording(force: Boolean) {
        if (ui.recordState != RecordUiState.RECORDING) return
        if (!force && SystemClock.elapsedRealtime() - recordStartMs < MIN_STOP_MS) return
        timerJob?.cancel()
        ui = ui.copy(recordState = RecordUiState.PROCESSING)
        recorder.stop()
    }

    private fun handleRecorded(file: File, hasAudio: Boolean) {
        ui = ui.copy(recordState = RecordUiState.READY, elapsedMs = 0, take = Take(file, isVideo = true, hasAudio = hasAudio))
    }

    private fun handleRecordError(message: String) {
        timerJob?.cancel()
        showMessage(message)
        flashErrorState()
    }

    private fun flashErrorState() {
        ui = ui.copy(recordState = RecordUiState.ERROR, elapsedMs = 0)
        viewModelScope.launch {
            delay(1500)
            if (ui.recordState == RecordUiState.ERROR) ui = ui.copy(recordState = RecordUiState.READY)
        }
    }

    private fun takePhoto() {
        if (!ui.cameraReady || ui.recordState == RecordUiState.PROCESSING) {
            if (!ui.cameraReady) showMessage("The camera isn't ready yet.")
            return
        }
        val token = ++photoToken
        ui = ui.copy(recordState = RecordUiState.PROCESSING, panel = null)
        renderer.requestPhoto { bitmap -> viewModelScope.launch { handlePhoto(token, bitmap) } }
        photoTimeoutJob?.cancel()
        photoTimeoutJob = viewModelScope.launch {
            delay(4000)
            if (token == photoToken && ui.recordState == RecordUiState.PROCESSING) {
                photoToken++ // ignore a late result
                showMessage("Couldn't capture the photo. Try again.")
                flashErrorState()
            }
        }
    }

    private suspend fun handlePhoto(token: Int, bitmap: Bitmap?) {
        if (token != photoToken) {
            bitmap?.recycle()
            return
        }
        photoTimeoutJob?.cancel()
        if (bitmap == null) {
            showMessage("Couldn't capture the photo. Try again.")
            flashErrorState()
            return
        }
        val file = storage.newTakeFile("jpg")
        val ok = withContext(Dispatchers.IO) {
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } catch (e: Exception) {
                false
            }
        }
        bitmap.recycle()
        if (!ok) {
            file.delete()
            showMessage("Couldn't write the photo (is the phone's storage full?).")
            flashErrorState()
        } else {
            ui = ui.copy(recordState = RecordUiState.READY, take = Take(file, isVideo = false))
        }
    }

    /** The app left the foreground: finish the recording instead of silently capturing nothing. */
    fun onAppStopped() {
        if (ui.recordState == RecordUiState.RECORDING) stopRecording(force = true)
    }

    // ------------------------------------------------------------------ review

    fun saveTake() {
        val take = ui.take ?: return
        if (take.saved != null || ui.saving) return
        ui = ui.copy(saving = true)
        viewModelScope.launch {
            try {
                val saved = if (take.isVideo) storage.saveVideo(take.file) else storage.savePhoto(take.file)
                ui = ui.copy(saving = false, take = ui.take?.copy(saved = saved), lastSaved = saved)
                showMessage(if (take.isVideo) "Saved to Movies/CreatorCam" else "Saved to Pictures/CreatorCam")
                ui = ui.copy(lastThumb = storage.thumbnail(saved, 160) ?: ui.lastThumb)
            } catch (e: Exception) {
                ui = ui.copy(saving = false)
                showMessage("Couldn't save: ${e.message ?: "storage error"}")
            }
        }
    }

    /** Back to the camera. A take already saved to the gallery stays there. */
    fun retake() {
        val wasSaved = ui.take?.saved != null
        ui.take?.file?.delete()
        leaveReview(wasSaved)
    }

    /** Throws the take away, including its gallery copy if it was already saved. */
    fun discard() {
        val take = ui.take ?: return
        take.saved?.let { runCatching { getApplication<Application>().contentResolver.delete(it.uri, null, null) } }
        take.file.delete()
        leaveReview(wasSaved = false)
        refreshLatest()
    }

    /** Back to the camera. If the take was saved, the shutter briefly shows the SAVED state. */
    private fun leaveReview(wasSaved: Boolean) {
        ui = ui.copy(take = null, saving = false, recordState = if (wasSaved) RecordUiState.SAVED else RecordUiState.READY)
        if (wasSaved) {
            viewModelScope.launch {
                delay(1500)
                if (ui.recordState == RecordUiState.SAVED) ui = ui.copy(recordState = RecordUiState.READY)
            }
        }
    }

    fun shareIntent(): Intent? {
        val take = ui.take ?: return null
        val uri = take.saved?.uri ?: storage.contentUriForTake(take.file)
        return storage.shareIntent(uri, if (take.isVideo) "video/mp4" else "image/jpeg")
    }

    fun galleryIntent(): Intent? = ui.lastSaved?.let { storage.viewIntent(it) }

    // ------------------------------------------------------------------ misc

    fun showMessage(text: String) {
        ui = ui.copy(message = text)
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(3500)
            if (ui.message == text) ui = ui.copy(message = null)
        }
    }

    private fun refreshLatest() {
        viewModelScope.launch {
            val latest = storage.latestSaved() ?: ui.lastSaved ?: return@launch
            ui = ui.copy(lastSaved = latest, lastThumb = storage.thumbnail(latest, 160) ?: ui.lastThumb)
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        recorder.cancel()
        controller.shutdown()
        renderer.release()
    }

    private companion object {
        const val MIN_STOP_MS = 800L
        const val MAX_RECORD_MS = 3 * 60 * 1000L
    }
}
