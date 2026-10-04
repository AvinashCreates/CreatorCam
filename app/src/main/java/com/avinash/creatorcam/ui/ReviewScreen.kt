package com.avinash.creatorcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Shown after every capture: plays/shows the exact file, then Retake / Save / Share / Delete. */
@Composable
fun ReviewScreen(
    take: Take,
    saving: Boolean,
    message: String?,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val saved = take.saved != null

    // Back must never silently destroy an unsaved take.
    BackHandler { if (saved) onRetake() else confirmLeave = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                if (take.isVideo) VideoPane(take.file) else PhotoPane(take.file)
            }
            if (take.isVideo && !take.hasAudio) {
                Text("This clip has no sound.", color = Color(0xFFB8C0C8), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center)
            }
            Row(
                Modifier.fillMaxWidth().background(Color(0xE6000000)).navigationBarsPadding().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ReviewAction(Icons.Default.Replay, "Retake", true, onRetake)
                ReviewAction(
                    if (saved) Icons.Default.Check else Icons.Default.Save,
                    if (saved) "Saved" else if (saving) "Saving…" else "Save",
                    enabled = !saved && !saving,
                    onClick = onSave,
                )
                ReviewAction(Icons.Default.Share, "Share", true, onShare)
                ReviewAction(Icons.Default.Delete, "Delete", true) { confirmDelete = true }
            }
        }
        message?.let { MessagePill(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${if (take.isVideo) "video" else "photo"}?") },
            text = { Text(if (saved) "It will also be removed from your gallery." else "It hasn't been saved yet and can't be recovered.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDiscard() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard this take?") },
            text = { Text("You haven't saved it yet.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onRetake() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ReviewAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = if (enabled) Color.White else Color.Gray)
        }
        Text(label, color = if (enabled) Color.White else Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoPane(file: File) {
    val bitmap by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), contentDescription = "Captured photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text("Couldn't load the photo.", color = Color.White)
    }
}

@Composable
private fun VideoPane(file: File) {
    val context = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) durationMs = player.duration.coerceAtLeast(0L)
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "This video couldn't be played (${e.errorCodeName})."
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(200)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).also { v -> v.useController = false; v.player = player } },
            )
            error?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                when {
                    ended -> { player.seekTo(0); player.play() }
                    isPlaying -> player.pause()
                    else -> player.play()
                }
            }) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            val shownMs = seekFraction?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatTime(shownMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = seekFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { seekFraction = it },
                onValueChangeFinished = {
                    seekFraction?.let { player.seekTo((it * durationMs).toLong()) }
                    seekFraction = null
                },
                enabled = durationMs > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
