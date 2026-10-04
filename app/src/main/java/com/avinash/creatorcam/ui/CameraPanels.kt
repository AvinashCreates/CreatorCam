package com.avinash.creatorcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.avinash.creatorcam.audio.VoiceEffectType
import com.avinash.creatorcam.avatar.Accessory
import com.avinash.creatorcam.avatar.AvatarPalette
import com.avinash.creatorcam.avatar.AvatarPresets
import com.avinash.creatorcam.avatar.AvatarSpec
import com.avinash.creatorcam.avatar.HairStyle
import com.avinash.creatorcam.effects.Adjustments
import com.avinash.creatorcam.effects.FilterPreset

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Surface(
        color = Color(0xB3000000),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Column(Modifier.padding(vertical = 8.dp)) { content() } }
}

@Composable
fun FiltersPanel(selected: FilterPreset, onSelect: (FilterPreset) -> Unit) = PanelCard {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FilterPreset.entries.toList()) { preset ->
            FilterChip(selected = preset == selected, onClick = { onSelect(preset) }, label = { Text(preset.label) })
        }
    }
}

@Composable
fun AdjustPanel(adjustments: Adjustments, onChange: (Adjustments) -> Unit) = PanelCard {
    SliderRow("Brightness", adjustments.brightness, -1f..1f) { onChange(adjustments.copy(brightness = it)) }
    SliderRow("Contrast", adjustments.contrast, -1f..1f) { onChange(adjustments.copy(contrast = it)) }
    SliderRow("Saturation", adjustments.saturation, -1f..1f) { onChange(adjustments.copy(saturation = it)) }
    SliderRow("Sharpness", adjustments.sharpness, 0f..1f) { onChange(adjustments.copy(sharpness = it)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onChange(Adjustments()) }, enabled = !adjustments.isDefault) { Text("Reset") }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

private enum class AvatarTab(val label: String) { CHARACTER("Character"), HAIR("Hair"), FACE("Face"), EXTRAS("Extras") }

/**
 * Avatar picker + customiser. Every control edits the real [AvatarSpec] that the GPU renderer draws,
 * so changes show instantly in the preview and are recorded into videos and photos.
 */
@Composable
fun AvatarsPanel(spec: AvatarSpec?, onSelect: (AvatarSpec?) -> Unit, onChange: ((AvatarSpec) -> AvatarSpec) -> Unit) = PanelCard {
    var tab by remember { mutableStateOf(AvatarTab.CHARACTER) }
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AvatarTab.entries.toList()) { t ->
            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
        }
    }
    Spacer(Modifier.height(8.dp))
    val contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
    if (tab != AvatarTab.CHARACTER && spec == null) {
        Text("Pick a character first.", color = Color(0xFFB8C0C8), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        return@PanelCard
    }
    when (tab) {
        AvatarTab.CHARACTER -> LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                AvatarCell("None", spec == null, { onSelect(null) }) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                }
            }
            items(AvatarPresets.all, key = { it.name }) { preset ->
                // Selecting a preset keeps nothing from a previous customisation; it is a fresh character.
                AvatarCell(preset.name, spec?.name == preset.name, { onSelect(preset) }) { AvatarIcon(preset) }
            }
        }
        AvatarTab.HAIR -> {
            val s = spec ?: return@PanelCard
            LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HairStyle.entries.toList()) { style ->
                    FilterChip(selected = s.hairStyle == style, onClick = { onChange { it.copy(hairStyle = style) } }, label = { Text(style.label) })
                }
            }
            Swatches(AvatarPalette.hair, s.hairColor, enabled = s.hairStyle != HairStyle.BALD) { i -> onChange { it.copy(hairColor = i) } }
        }
        AvatarTab.FACE -> {
            val s = spec ?: return@PanelCard
            Text("Skin tone", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Swatches(AvatarPalette.skin, s.skin) { i -> onChange { it.copy(skin = i) } }
            Text("Eye colour", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Swatches(AvatarPalette.eyes, s.eyeColor) { i -> onChange { it.copy(eyeColor = i) } }
        }
        AvatarTab.EXTRAS -> {
            val s = spec ?: return@PanelCard
            LazyRow(contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Accessory.entries.toList()) { acc ->
                    FilterChip(selected = s.accessory == acc, onClick = { onChange { it.copy(accessory = acc) } }, label = { Text(acc.label) })
                }
            }
        }
    }
    Text(
        "Animated 2.5D avatar: follows your face and head tilt, blinks, smiles and opens its mouth with you.",
        color = Color(0xFFB8C0C8),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, enabled: Boolean = true, onPick: (Int) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(colors.indices.toList()) { i ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(colors[i] or 0xFF000000.toInt()).copy(alpha = if (enabled) 1f else 0.35f))
                    .border(if (i == selected) 3.dp else 1.dp, if (i == selected) Accent else Color(0x66FFFFFF), CircleShape)
                    .clickable(enabled = enabled) { onPick(i) },
            )
        }
    }
}

/** Small flat picker icon (NOT the in-camera renderer): skin, hair, eyes, and accessory hint. */
@Composable
private fun AvatarIcon(spec: AvatarSpec) {
    val skin = Color(AvatarPalette.skin[spec.skin.coerceIn(0, AvatarPalette.skin.lastIndex)] or 0xFF000000.toInt())
    val hair = Color(AvatarPalette.hair[spec.hairColor.coerceIn(0, AvatarPalette.hair.lastIndex)] or 0xFF000000.toInt())
    Canvas(Modifier.size(52.dp)) {
        val w = size.width
        if (spec.hairStyle == HairStyle.LONG || spec.hairStyle == HairStyle.CURLY) drawCircle(hair, radius = w * 0.46f)
        drawCircle(skin, radius = w * 0.34f, center = Offset(w / 2, w * 0.54f))
        if (spec.hairStyle != HairStyle.BALD) drawArc(hair, 180f, 180f, true, Offset(w * 0.16f, w * 0.18f), Size(w * 0.68f, w * 0.5f))
        drawCircle(Color(0xFF2B2B2B), radius = w * 0.035f, center = Offset(w * 0.40f, w * 0.52f))
        drawCircle(Color(0xFF2B2B2B), radius = w * 0.035f, center = Offset(w * 0.60f, w * 0.52f))
        drawArc(Color(0xFF8A3B3B), 20f, 140f, false, Offset(w * 0.40f, w * 0.56f), Size(w * 0.20f, w * 0.12f), style = Stroke(width = w * 0.025f))
        when (spec.accessory) {
            Accessory.GLASSES, Accessory.SUNGLASSES -> {
                val c = if (spec.accessory == Accessory.SUNGLASSES) Color(0xFF111111) else Color(0xFF222233)
                drawCircle(c, radius = w * 0.075f, center = Offset(w * 0.40f, w * 0.52f), style = Stroke(width = w * 0.03f))
                drawCircle(c, radius = w * 0.075f, center = Offset(w * 0.60f, w * 0.52f), style = Stroke(width = w * 0.03f))
            }
            Accessory.HEADPHONES -> drawArc(Color(0xFF2A2C35), 180f, 180f, false, Offset(w * 0.10f, w * 0.14f), Size(w * 0.80f, w * 0.64f), style = Stroke(width = w * 0.05f))
            Accessory.BEANIE -> drawArc(Color(0xFFDB4A4C), 180f, 180f, true, Offset(w * 0.15f, w * 0.12f), Size(w * 0.70f, w * 0.52f))
            Accessory.NONE -> Unit
        }
    }
}

@Composable
private fun AvatarCell(name: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) Accent else Color(0x66FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun VoicePanel(selected: VoiceEffectType, enabled: Boolean, onSelect: (VoiceEffectType) -> Unit) = PanelCard {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(VoiceEffectType.entries.toList()) { type ->
            FilterChip(selected = type == selected, enabled = enabled, onClick = { onSelect(type) }, label = { Text(type.label) })
        }
    }
    Text(
        "Applied to the recorded sound. You won't hear it live; play the clip back to hear it.",
        color = Color(0xFFB8C0C8),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
