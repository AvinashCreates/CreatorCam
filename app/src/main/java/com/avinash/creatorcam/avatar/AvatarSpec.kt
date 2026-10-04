package com.avinash.creatorcam.avatar

/**
 * Description of the layered avatar. The character is drawn procedurally on the GPU
 * (see `Shaders.AVATAR_FRAGMENT`): there are no avatar bitmaps. Every field maps to a real shader layer.
 *
 * Implementation level: expression-reactive 2.5D layered avatar (not a 3D mesh). It follows the face
 * position, scale and roll, shifts its facial-feature layer with head turn/nod (parallax), blinks with your eyes,
 * smiles when you smile and opens its mouth when you do.
 * Original artwork; not Apple's Memoji.
 */
enum class HairStyle(val label: String, val code: Float) {
    SHORT("Short", 0f), LONG("Long", 1f), CURLY("Curly", 2f), BUN("Bun", 3f), BALD("Bald", 4f),
}

enum class Accessory(val label: String, val code: Float) {
    NONE("None", 0f), GLASSES("Glasses", 1f), SUNGLASSES("Shades", 2f), HEADPHONES("Headphones", 3f), BEANIE("Beanie", 4f),
}

object AvatarPalette {
    val skin = listOf(0xFDDBB4, 0xF1C27D, 0xE0AC69, 0xC68642, 0x8D5524, 0x5C3A21)
    val hair = listOf(0x1E1A1A, 0x4A2E1F, 0x8A3B1C, 0xE3B85B, 0xC9CED6, 0xE0568F, 0x3E7BDB)
    val eyes = listOf(0x5B3A22, 0x3B82C4, 0x3F8F5A, 0x7A8590)

    fun rgb(color: Int) = floatArrayOf(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
}

/** Indices point into [AvatarPalette]; out-of-range values are clamped so a spec can never crash the renderer. */
data class AvatarSpec(
    val name: String,
    val skin: Int,
    val hairStyle: HairStyle,
    val hairColor: Int,
    val eyeColor: Int,
    val accessory: Accessory,
) {
    val skinRgb: FloatArray get() = AvatarPalette.rgb(AvatarPalette.skin[skin.coerceIn(0, AvatarPalette.skin.lastIndex)])
    val hairRgb: FloatArray get() = AvatarPalette.rgb(AvatarPalette.hair[hairColor.coerceIn(0, AvatarPalette.hair.lastIndex)])
    val eyeRgb: FloatArray get() = AvatarPalette.rgb(AvatarPalette.eyes[eyeColor.coerceIn(0, AvatarPalette.eyes.lastIndex)])
}

object AvatarPresets {
    val all = listOf(
        AvatarSpec("Alex", skin = 0, hairStyle = HairStyle.SHORT, hairColor = 1, eyeColor = 0, accessory = Accessory.NONE),
        AvatarSpec("Sam", skin = 3, hairStyle = HairStyle.LONG, hairColor = 0, eyeColor = 0, accessory = Accessory.GLASSES),
        AvatarSpec("Riley", skin = 2, hairStyle = HairStyle.CURLY, hairColor = 2, eyeColor = 2, accessory = Accessory.NONE),
        AvatarSpec("Jordan", skin = 0, hairStyle = HairStyle.BUN, hairColor = 3, eyeColor = 1, accessory = Accessory.HEADPHONES),
        AvatarSpec("Casey", skin = 4, hairStyle = HairStyle.SHORT, hairColor = 6, eyeColor = 0, accessory = Accessory.SUNGLASSES),
        AvatarSpec("Robin", skin = 5, hairStyle = HairStyle.BALD, hairColor = 0, eyeColor = 3, accessory = Accessory.BEANIE),
    )
}
