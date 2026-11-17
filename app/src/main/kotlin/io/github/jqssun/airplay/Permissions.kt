package io.github.jqssun.airplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

// Anzhon fork: Build.VERSION_CODES.CINNAMON_BUN (API 37) is not in the API 36
// SDK this fork compiles against. 37 is the API level of CINNAMON_BUN, so the
// runtime guards below behave identically.
internal const val CINNAMON_BUN = 37

// Anzhon fork: Manifest.permission.ACCESS_LOCAL_NETWORK was added in API 37
// (Android 17), so it is not in the API 36 SDK either. The permission name
// is a plain string, identical at runtime.
internal const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < CINNAMON_BUN ||
    ContextCompat.checkSelfPermission(this, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
