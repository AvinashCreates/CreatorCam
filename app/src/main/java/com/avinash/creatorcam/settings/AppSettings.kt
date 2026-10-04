package com.avinash.creatorcam.settings

import android.content.Context

enum class VideoQuality(val label: String, val width: Int, val height: Int, val bitrate: Int) {
    HD_720("720p", 720, 1280, 6_000_000),
    FHD_1080("1080p", 1080, 1920, 10_000_000),
}

/** Tiny SharedPreferences wrapper. Only settings that really change behaviour live here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("creatorcam", Context.MODE_PRIVATE)

    var mirrorFront: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, true)
        set(value) = prefs.edit().putBoolean(KEY_MIRROR, value).apply()

    var quality: VideoQuality
        get() = VideoQuality.entries.firstOrNull { it.name == prefs.getString(KEY_QUALITY, null) } ?: VideoQuality.HD_720
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    private companion object {
        const val KEY_MIRROR = "mirror_front"
        const val KEY_QUALITY = "quality"
    }
}
