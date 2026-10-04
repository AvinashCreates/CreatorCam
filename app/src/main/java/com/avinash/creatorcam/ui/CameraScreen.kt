package com.avinash.creatorcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.avinash.creatorcam.gl.CameraRenderer

@Composable
fun CameraScreen(vm: CameraViewModel, perms: AppPermissions, onOpenGallery: () -> Unit) {
    val ui = vm.ui
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!perms.camera) {
            CameraPermissionGate(perms)
            return@Box
        }

        // The 9:16 preview is exactly what gets recorded.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PreviewSurface(vm.renderer, Modifier.aspectRatio(9f / 16f))
        }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(vm)
            if (!perms.mic) MicBanner(perms)
            ui.message?.let { MessagePill(it, Modifier.fillMaxWidth().padding(top = 8.dp)) }
            Spacer(Modifier.weight(1f))
            BottomControls(vm, perms, onOpenGallery)
        }
    }
}

@Composable
private fun PreviewSurface(renderer: CameraRenderer, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        renderer.setDisplaySurface(holder.surface, width, height)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        renderer.clearDisplaySurface()
                    }
                })
            }
        },
    )
}

private fun scrim(): Modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)

@Composable
private fun TopBar(vm: CameraViewModel) {
    val ui = vm.ui
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.hasFlash) {
            IconButton(onClick = vm::toggleTorch, modifier = scrim()) {
                Icon(
                    if (ui.torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = if (ui.torchOn) "Turn flash off" else "Turn flash on",
                    tint = if (ui.torchOn) Color(0xFFFFD43B) else Color.White,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }

        if (ui.recordState == RecordUiState.RECORDING) {
            Row(
                Modifier.background(Color(0xCC000000), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(Accent, CircleShape))
                Text("  REC  ${formatTime(ui.elapsedMs)}", color = Color.White, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier)
        }

        IconButton(
            onClick = { vm.showSettings(true) },
            enabled = ui.recordState != RecordUiState.RECORDING,
            modifier = scrim(),
        ) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White) }
    }
}

@Composable
private fun MicBanner(perms: AppPermissions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("  Microphone off: videos will be silent", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (perms.micBlocked) perms.openSettings() else perms.request() }) {
            Text(if (perms.micBlocked) "Settings" else "Allow")
        }
    }
}

@Composable
private fun BottomControls(vm: CameraViewModel, perms: AppPermissions, onOpenGallery: () -> Unit) {
    val ui = vm.ui
    val recording = ui.recordState == RecordUiState.RECORDING
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = ui.panel != null) {
            when (ui.panel) {
                Panel.FILTERS -> FiltersPanel(ui.filter, vm::selectFilter)
                Panel.ADJUST -> AdjustPanel(ui.adjustments, vm::setAdjustments)
                Panel.AVATARS -> AvatarsPanel(ui.avatar, vm::selectAvatar, vm::updateAvatar)
                Panel.VOICE -> VoicePanel(ui.voice, enabled = !recording, onSelect = vm::selectVoice)
                null -> Unit
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            ToolChip("Filters", ui.panel == Panel.FILTERS) { vm.togglePanel(Panel.FILTERS) }
            ToolChip("Adjust", ui.panel == Panel.ADJUST) { vm.togglePanel(Panel.ADJUST) }
            ToolChip("Avatars", ui.panel == Panel.AVATARS) { vm.togglePanel(Panel.AVATARS) }
            if (ui.mode == CaptureMode.VIDEO) {
                ToolChip("Voice", ui.panel == Panel.VOICE) { vm.togglePanel(Panel.VOICE) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryThumb(vm, onOpenGallery)
            ShutterButton(ui.mode, ui.recordState) { vm.onShutter(perms.mic) }
            val canFlip = !recording && ui.recordState != RecordUiState.PROCESSING && ui.hasFront && ui.hasBack
            IconButton(onClick = vm::switchCamera, enabled = canFlip, modifier = scrim().size(48.dp)) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = if (canFlip) Color.White else Color.Gray)
            }
        }

        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ModeLabel("VIDEO", ui.mode == CaptureMode.VIDEO, enabled = !recording) { vm.setMode(CaptureMode.VIDEO) }
            ModeLabel("PHOTO", ui.mode == CaptureMode.PHOTO, enabled = !recording) { vm.setMode(CaptureMode.PHOTO) }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text,
            color = if (selected) Color.White else Color(0xFF9AA3AB),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun GalleryThumb(vm: CameraViewModel, onOpen: () -> Unit) {
    val ui = vm.ui
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.15f))
            .border(1.dp, Color.White.copy(alpha = 0.6f), shape)
            .clickable(enabled = ui.lastSaved != null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = ui.lastThumb
        if (thumb != null) {
            Image(thumb.asImageBitmap(), contentDescription = "Open latest capture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White)
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
