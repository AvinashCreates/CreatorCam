package com.avinash.creatorcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shutter / record button. Every [RecordUiState] has a distinct look. */
@Composable
fun ShutterButton(mode: CaptureMode, state: RecordUiState, onClick: () -> Unit) {
    val description = when {
        state == RecordUiState.RECORDING -> "Stop recording"
        state == RecordUiState.PROCESSING -> "Processing"
        mode == CaptureMode.VIDEO -> "Start recording"
        else -> "Take photo"
    }
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, if (state == RecordUiState.ERROR) Accent else Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .clickable(enabled = state != RecordUiState.PROCESSING, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            RecordUiState.READY ->
                Box(Modifier.fillMaxSize().background(if (mode == CaptureMode.VIDEO) Accent else Color.White, CircleShape))
            RecordUiState.RECORDING ->
                Box(Modifier.size(28.dp).background(Accent, RoundedCornerShape(6.dp)))
            RecordUiState.PROCESSING ->
                CircularProgressIndicator(Modifier.size(34.dp), color = Color.White, strokeWidth = 3.dp)
            RecordUiState.SAVED ->
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CD964), modifier = Modifier.size(38.dp))
            RecordUiState.ERROR ->
                Icon(Icons.Default.Close, contentDescription = null, tint = Accent, modifier = Modifier.size(38.dp))
        }
    }
}
