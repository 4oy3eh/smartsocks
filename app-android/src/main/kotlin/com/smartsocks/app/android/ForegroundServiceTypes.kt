package com.smartsocks.app.android

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import com.smartsocks.app.recording.KeepAliveType

/** First SDK level (Android 14) that enforces foreground service types: from it on a type is passed. */
internal const val FOREGROUND_TYPES_SDK = 34

/**
 * The foreground service types to try for [type] on [sdkInt], in order (`ServiceInfo.FOREGROUND_SERVICE_TYPE_*`):
 * `connectedDevice` falls back to `specialUse` (its prerequisite may be missing after all, e.g. the Bluetooth
 * permission revoked). Before Android 14 types are not enforced: one attempt without a type (null: the manifest's
 * types apply).
 */
@SuppressLint("InlinedApi") // the types are only returned for Android 14+
internal fun foregroundServiceAttempts(type: KeepAliveType, sdkInt: Int): List<Int?> = when {
    sdkInt < FOREGROUND_TYPES_SDK -> listOf(null)

    type == KeepAliveType.ConnectedDevice -> listOf(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )

    else -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
}
