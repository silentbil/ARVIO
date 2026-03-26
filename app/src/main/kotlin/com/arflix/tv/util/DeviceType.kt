package com.arflix.tv.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

enum class DeviceType {
    TV,
    TABLET,
    PHONE;

    fun isTouchDevice(): Boolean = this == PHONE || this == TABLET

    fun isMobile(): Boolean = isTouchDevice()
}

val LocalDeviceType = compositionLocalOf { DeviceType.TV }

/** True if the physical device has a touchscreen. Use this to decide navigation style. */
val LocalHasTouchScreen = compositionLocalOf { true }

fun deviceHasTouchScreen(context: Context): Boolean {
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/** Key for the user's UI mode override in settingsDataStore */
val DEVICE_MODE_OVERRIDE_KEY = stringPreferencesKey("device_mode_override")

/** Key for skipping profile selection on startup */
val SKIP_PROFILE_SELECTION_KEY = booleanPreferencesKey("skip_profile_selection")

/** Values: "auto" (default), "tv", "tablet", "phone" */
fun detectDeviceType(context: Context): DeviceType {
    // Check for user override first
    val override = try {
        runBlocking { context.settingsDataStore.data.first()[DEVICE_MODE_OVERRIDE_KEY] }
    } catch (_: Exception) { null }

    when (override) {
        "tv" -> return DeviceType.TV
        "tablet" -> return DeviceType.TABLET
        "phone" -> return DeviceType.PHONE
        // "auto" or null -> fall through to auto-detection
    }

    val packageManager = context.packageManager

    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    ) {
        return DeviceType.TV
    }

    // No touchscreen = it's a TV even if Android thinks otherwise
    // (Chinese TVs, projectors, Fire Stick sideloads, etc.)
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
        return DeviceType.TV
    }

    val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
    if (smallestWidthDp >= 600) {
        return DeviceType.TABLET
    }

    return DeviceType.PHONE
}
