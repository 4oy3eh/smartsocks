package com.smartsocks.app.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.transport.android.BluetoothPermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The system dialogs behind the settings' Bluetooth buttons. Create it while the activity is being created (the
 * result launchers must be registered before it starts). [onChanged] runs after every result, from [changed] (the
 * activity calls that when it comes back to the foreground, e.g. from the system Bluetooth settings) and whenever
 * Bluetooth is switched on or off between [onStart] and [onStop] (e.g. from the quick settings).
 */
class ActivityDeviceActions(private val activity: ComponentActivity, private val onChanged: () -> Unit) :
    DeviceActions {
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private val blocked = MutableStateFlow(false)
    override val bluetoothPermissionBlocked: StateFlow<Boolean> = blocked.asStateFlow()

    private val permissions =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Denied and no rationale to show: the system will not ask again ("Don't allow" twice, or "never ask").
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty() && denied.none { activity.shouldShowRequestPermissionRationale(it) }) {
                blocked.value = true
            }
            changed()
        }

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) changed()
        }
    }
    private var receiving = false

    private val enable =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { changed() }

    fun changed() {
        // Granted meanwhile (e.g. in the app's system settings): the request works again.
        if (BluetoothPermissions.hasPermissions(activity)) blocked.value = false
        _changes.update { it + 1 }
        onChanged()
    }

    /** Starts following Bluetooth being switched on and off; call from the activity's `onStart`. */
    fun onStart() {
        if (receiving) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // A protected system broadcast: delivered to a non-exported receiver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(adapterState, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(adapterState, filter)
        }
        receiving = true
    }

    fun onStop() {
        if (!receiving) return
        activity.unregisterReceiver(adapterState)
        receiving = false
    }

    override fun requestBluetoothPermission() {
        permissions.launch(BluetoothPermissions.requiredPermissions())
    }

    // Only offered once the permission is granted (the settings show "Grant permission" before "Turn on").
    @SuppressLint("MissingPermission")
    override fun enableBluetooth() {
        if (!BluetoothPermissions.hasPermissions(activity)) return grantBluetoothPermission()
        launchSafely { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }

    override fun openBluetoothSettings() {
        launchSafely { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun openAppSettings() {
        launchSafely {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null),
                ),
            )
        }
    }

    /** Some devices (and the emulator) lack some settings screens. */
    private inline fun launchSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
