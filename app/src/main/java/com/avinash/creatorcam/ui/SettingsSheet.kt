package com.avinash.creatorcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avinash.creatorcam.settings.VideoQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: CameraViewModel) {
    val ui = vm.ui
    ModalBottomSheet(onDismissRequest = { vm.showSettings(false) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mirror front camera", style = MaterialTheme.typography.titleMedium)
                    Text("Selfie view in the preview, photos and videos.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = ui.mirrorFront, onCheckedChange = vm::setMirrorFront)
            }

            Spacer(Modifier.height(20.dp))
            Text("Video quality", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                VideoQuality.entries.forEach { q ->
                    FilterChip(selected = ui.quality == q, onClick = { vm.setQuality(q) }, label = { Text(q.label) })
                }
            }
            Text(
                "720p is smoothest on most phones. 1080p looks sharper but may drop frames on low-end devices.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
