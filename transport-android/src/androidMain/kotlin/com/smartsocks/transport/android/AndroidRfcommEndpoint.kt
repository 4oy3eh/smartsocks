package com.smartsocks.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.lang.reflect.InvocationTargetException

internal fun Context.bluetoothAdapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

internal class AndroidRfcommEndpoint(private val context: Context, address: String) : RfcommEndpoint {
    private val address = address.trim().uppercase()

    // Permissions are checked explicitly right below; lint cannot see that.
    @SuppressLint("MissingPermission")
    override fun prepare(): List<ConnectAttempt> {
        val adapter = context.bluetoothAdapter() ?: throw IOException("This device has no Bluetooth")
        BluetoothPermissions.requirePermissions(context)
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("Invalid Bluetooth address '$address'")
        val device = adapter.getRemoteDevice(address)
        // An ongoing discovery slows down or breaks RFCOMM connects. Android 12+ needs BLUETOOTH_SCAN for this,
        // which the app is not required to have.
        if (BluetoothPermissions.canCancelDiscovery(context)) {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Permission revoked in between: connect anyway.
            }
        }
        val uuid = RfcommSockTransport.SPP_UUID
        return listOf(
            ConnectAttempt("secure SPP") { BluetoothSocketHandle(device.createRfcommSocketToServiceRecord(uuid)) },
            ConnectAttempt("insecure SPP") {
                BluetoothSocketHandle(device.createInsecureRfcommSocketToServiceRecord(uuid))
            },
            ConnectAttempt("insecure channel ${RfcommSockTransport.FALLBACK_RFCOMM_CHANNEL}") {
                BluetoothSocketHandle(insecureSocketOnChannel(device, RfcommSockTransport.FALLBACK_RFCOMM_CHANNEL))
            },
        )
    }

    /** `BluetoothDevice.createInsecureRfcommSocket(int)` is hidden API, so it is reached by reflection. */
    private fun insecureSocketOnChannel(device: BluetoothDevice, channel: Int): BluetoothSocket {
        val method = try {
            device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
        } catch (e: ReflectiveOperationException) {
            throw IOException("Fixed-channel RFCOMM is not available on this Android version", e)
        }
        return try {
            method.invoke(device, channel) as BluetoothSocket
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is SecurityException) throw cause
            throw IOException(cause?.message ?: "createInsecureRfcommSocket failed", cause)
        } catch (e: ReflectiveOperationException) {
            throw IOException("Fixed-channel RFCOMM is not available on this Android version", e)
        }
    }
}
