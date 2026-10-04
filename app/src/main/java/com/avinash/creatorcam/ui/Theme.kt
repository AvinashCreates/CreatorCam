package com.avinash.creatorcam.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFFF3B4E)

@Composable
fun CreatorCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Color(0xFF8AB4FF),
            background = Color.Black,
            surface = Color(0xFF14181C),
        ),
        content = content,
    )
}

/** Short, non-blocking status message (errors, "Saved…"). */
@Composable
fun MessagePill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xE6202428), shape = RoundedCornerShape(20.dp)) {
            Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
