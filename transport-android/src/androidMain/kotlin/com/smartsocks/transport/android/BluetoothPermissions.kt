package com.smartsocks.transport.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Permissions this module needs. They are declared in the library manifest (merged into the app); requesting the
 * runtime ones is the app's job, e.g. with `ActivityResultContracts.RequestMultiplePermissions` and
 * [requiredPermissions].
 *
 * Only bonded devices are used, so `BLUETOOTH_SCAN` is never required. Without it, discovery started by another app
 * cannot be cancelled on Android 12+, which only makes connecting slower.
 */
object BluetoothPermissions {
    const val BLUETOOTH = "android.permission.BLUETOOTH"
    const val BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"

    /** First SDK level (Android 12) with the runtime `BLUETOOTH_CONNECT` permission. */
    const val RUNTIME_PERMISSIONS_SDK = 31

    /**
     * Permissions that must be granted on [sdkInt]. From Android 12 that is the runtime `BLUETOOTH_CONNECT`;
     * below it `BLUETOOTH` and `BLUETOOTH_ADMIN`, which are install-time permissions (requesting them is a no-op
     * that reports them as granted).
     */
    fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= RUNTIME_PERMISSIONS_SDK) arrayOf(BLUETOOTH_CONNECT) else arrayOf(BLUETOOTH, BLUETOOTH_ADMIN)

    /** True if everything from [requiredPermissions] for this device is granted. */
    fun hasPermissions(context: Context): Boolean = requiredPermissions().all { context.isGranted(it) }

    internal fun canCancelDiscovery(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        context.isGranted(if (sdkInt >= RUNTIME_PERMISSIONS_SDK) BLUETOOTH_SCAN else BLUETOOTH_ADMIN)

    internal fun requirePermissions(context: Context) {
        val missing = requiredPermissions().filterNot { context.isGranted(it) }
        if (missing.isNotEmpty()) {
            throw SecurityException("Bluetooth permission not granted: ${missing.joinToString()}")
        }
    }

    private fun Context.isGranted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
