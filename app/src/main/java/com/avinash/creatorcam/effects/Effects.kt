package com.avinash.creatorcam.effects

/** Everything the camera fragment shader needs. All values are applied on the GPU for BOTH preview and recording. */
data class EffectParams(
    val brightness: Float = 0f,   // additive, -0.5..0.5
    val contrast: Float = 1f,     // multiplier around mid-grey
    val saturation: Float = 1f,   // 0 = grey, 1 = unchanged
    val sharpen: Float = 0f,      // unsharp-mask amount
    val soft: Float = 0f,         // blend towards a blurred copy
    val vignette: Float = 0f,     // edge darkening
    val mono: Float = 0f,         // 1 = black & white
    val fade: Float = 0f,         // lifted/faded blacks
    val tintR: Float = 1f,
    val tintG: Float = 1f,
    val tintB: Float = 1f,
)

enum class FilterPreset(val label: String, val params: EffectParams) {
    ORIGINAL("Original", EffectParams()),
    WARM("Warm", EffectParams(saturation = 1.1f, tintR = 1.08f, tintG = 1.0f, tintB = 0.88f)),
    COOL("Cool", EffectParams(saturation = 1.05f, tintR = 0.9f, tintG = 1.0f, tintB = 1.12f)),
    VINTAGE(
        "Vintage",
        EffectParams(contrast = 0.9f, saturation = 0.7f, vignette = 0.35f, fade = 0.35f, tintR = 1.08f, tintG = 1.0f, tintB = 0.82f),
    ),
    NOIR("Noir", EffectParams(contrast = 1.3f, mono = 1f, vignette = 0.45f)),
    CINEMATIC(
        "Cinematic",
        EffectParams(brightness = -0.03f, contrast = 1.18f, saturation = 0.88f, vignette = 0.3f, tintR = 1.05f, tintG = 1.0f, tintB = 0.95f),
    ),
    HIGH_CONTRAST("High contrast", EffectParams(contrast = 1.5f, saturation = 1.2f)),
    SOFT("Soft", EffectParams(brightness = 0.04f, contrast = 0.95f, saturation = 1.05f, soft = 0.6f)),
    VIGNETTE("Vignette", EffectParams(vignette = 0.75f)),
}

/** User sliders. Each is -1..1 except sharpness which is 0..1. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isDefault get() = this == Adjustments()
}

fun combine(preset: FilterPreset, adj: Adjustments): EffectParams {
    val p = preset.params
    return p.copy(
        brightness = (p.brightness + adj.brightness * 0.3f).coerceIn(-0.5f, 0.5f),
        contrast = (p.contrast * (1f + adj.contrast * 0.5f)).coerceIn(0.4f, 2f),
        saturation = (p.saturation * (1f + adj.saturation)).coerceIn(0f, 2.5f),
        sharpen = (p.sharpen + adj.sharpness * 1.5f).coerceIn(0f, 2f),
    )
}
