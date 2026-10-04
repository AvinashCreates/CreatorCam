package com.avinash.creatorcam.storage

import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A finished item in the public gallery (Movies/CreatorCam or Pictures/CreatorCam). */
data class SavedMedia(val uri: Uri, val isVideo: Boolean)

/**
 * Takes live in the app cache until the user presses Save, then are copied into MediaStore.
 * Sharing always uses content:// URIs (FileProvider for unsaved takes, MediaStore for saved ones).
 */
class MediaStorage(private val context: Context) {

    private val takesDir get() = File(context.cacheDir, "takes").apply { mkdirs() }

    fun newTakeFile(extension: String): File = File(takesDir, "${baseName()}.$extension")

    /** Delete leftovers from earlier sessions (older than a day). */
    fun cleanOldTakes() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        takesDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    suspend fun saveVideo(file: File): SavedMedia = withContext(Dispatchers.IO) {
        val uri = insert(
            file = file,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            mime = "video/mp4",
            relativeDir = "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER",
            legacyDir = Environment.DIRECTORY_MOVIES,
        )
        SavedMedia(uri, isVideo = true)
    }

    suspend fun savePhoto(file: File): SavedMedia = withContext(Dispatchers.IO) {
        val uri = insert(
            file = file,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mime = "image/jpeg",
            relativeDir = "${Environment.DIRECTORY_PICTURES}/$APP_FOLDER",
            legacyDir = Environment.DIRECTORY_PICTURES,
        )
        SavedMedia(uri, isVideo = false)
    }

    @Suppress("DEPRECATION") // DATA is only used on Android 8/9, where RELATIVE_PATH doesn't exist.
    private fun insert(file: File, collection: Uri, mime: String, relativeDir: String, legacyDir: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(legacyDir), APP_FOLDER)
                if (!dir.exists() && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
                put(MediaStore.MediaColumns.DATA, File(dir, file.name).absolutePath)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Gallery refused the new file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Can't open gallery file")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun contentUriForTake(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(uri: Uri, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(media: SavedMedia): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, if (media.isVideo) "video/mp4" else "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Newest item CreatorCam saved. On Android 10+ apps can query their own MediaStore items without any
     * permission, so this survives restarts. On older versions we only know about this session's saves.
     */
    suspend fun latestSaved(): SavedMedia? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val video = queryNewest(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_MOVIES}/$APP_FOLDER")
        val image = queryNewest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${Environment.DIRECTORY_PICTURES}/$APP_FOLDER")
        when {
            video == null && image == null -> null
            video == null -> SavedMedia(image!!.first, false)
            image == null -> SavedMedia(video.first, true)
            video.second >= image.second -> SavedMedia(video.first, true)
            else -> SavedMedia(image.first, false)
        }
    }

    /** Returns (uri, dateAdded) of the newest row under [relativePath], or null. */
    private fun queryNewest(collection: Uri, relativePath: String): Pair<Uri, Long>? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        return try {
            context.contentResolver.query(collection, projection, selection, arrayOf("$relativePath%"), sort)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun thumbnail(media: SavedMedia, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(media.uri, Size(sizePx, sizePx), null)
            } else if (media.isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, media.uri)
                    r.getFrameAtTime(0)
                } finally {
                    r.release()
                }
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(media.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun baseName(): String =
        "CreatorCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private companion object {
        const val APP_FOLDER = "CreatorCam"
    }
}
