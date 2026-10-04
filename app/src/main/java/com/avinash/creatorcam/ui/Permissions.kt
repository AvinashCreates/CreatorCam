package com.avinash.creatorcam.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Camera is required; microphone is optional (videos are then silent). "Blocked" = needs the Settings app. */
class AppPermissions(
    val camera: Boolean,
    val mic: Boolean,
    val cameraBlocked: Boolean,
    val micBlocked: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun rememberAppPermissions(): AppPermissions {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var mic by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    var cameraBlocked by remember { mutableStateOf(false) }
    var micBlocked by remember { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    fun missing(): Array<String> = listOfNotNull(
        Manifest.permission.CAMERA.takeUnless { camera },
        Manifest.permission.RECORD_AUDIO.takeUnless { mic },
    ).toTypedArray()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camera = granted(context, Manifest.permission.CAMERA)
        mic = granted(context, Manifest.permission.RECORD_AUDIO)
        val activity = context.findActivity()
        // After a denial, "no rationale" means the system will not show the dialog again.
        cameraBlocked = !camera && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        micBlocked = !mic && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    }

    // Coming back from the system Settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        camera = granted(context, Manifest.permission.CAMERA)
        mic = granted(context, Manifest.permission.RECORD_AUDIO)
        if (camera) cameraBlocked = false
        if (mic) micBlocked = false
    }

    LaunchedEffect(Unit) {
        if (!askedOnce && missing().isNotEmpty()) {
            askedOnce = true
            launcher.launch(missing())
        }
    }

    return AppPermissions(
        camera = camera,
        mic = mic,
        cameraBlocked = cameraBlocked,
        micBlocked = micBlocked,
        request = { val m = missing(); if (m.isNotEmpty()) launcher.launch(m) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

/** Full-screen explanation shown while the camera permission is missing. */
@Composable
fun CameraPermissionGate(perms: AppPermissions) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            if (perms.cameraBlocked) "Camera permission was turned off for CreatorCam. Open Settings > Permissions and allow Camera."
            else "CreatorCam needs the camera to show your preview and record. Nothing leaves your phone.",
            color = Color(0xFFB8C0C8),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { if (perms.cameraBlocked) perms.openSettings() else perms.request() }) {
            Text(if (perms.cameraBlocked) "Open Settings" else "Allow camera")
        }
    }
}
