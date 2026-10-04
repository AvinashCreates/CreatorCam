package com.avinash.creatorcam.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: CameraViewModel = viewModel()) {
    val ui = vm.ui
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val perms = rememberAppPermissions()

    // Camera runs only while the camera screen is showing and permission is granted.
    val cameraWanted = perms.camera && ui.take == null
    LaunchedEffect(cameraWanted, ui.front, ui.quality) {
        if (cameraWanted) vm.bindCamera(lifecycleOwner) else vm.unbindCamera()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAppStopped() }

    // Android 8/9 need a runtime permission to write into public Movies/Pictures. Android 10+ does not.
    val legacyWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.saveTake() else vm.showMessage("Allow storage access to save on this Android version.")
    }
    val onSave: () -> Unit = {
        val needsLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsLegacy) legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.saveTake()
    }

    val take = ui.take
    if (take == null) {
        CameraScreen(vm, perms, onOpenGallery = { vm.galleryIntent()?.let { launch(context, it, vm) } })
        if (ui.showSettings) SettingsSheet(vm)
    } else {
        ReviewScreen(
            take = take,
            saving = ui.saving,
            message = ui.message,
            onRetake = vm::retake,
            onSave = onSave,
            onShare = { vm.shareIntent()?.let { launch(context, it, vm) } },
            onDiscard = vm::discard,
        )
    }
}

private fun launch(context: Context, intent: Intent, vm: CameraViewModel) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        vm.showMessage("No app on this phone can open that.")
    }
}
