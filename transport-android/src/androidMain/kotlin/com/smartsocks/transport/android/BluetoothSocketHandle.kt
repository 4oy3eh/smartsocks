package com.smartsocks.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class BluetoothSocketHandle(private val socket: BluetoothSocket) : SocketHandle {
    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    // The endpoint checks BLUETOOTH_CONNECT before any socket is created.
    @SuppressLint("MissingPermission")
    override fun connect() {
        socket.connect()
        input = socket.inputStream
        output = socket.outputStream
    }

    override fun read(buffer: ByteArray): Int {
        val stream = input ?: throw IOException("Socket is not connected")
        return stream.read(buffer)
    }

    override fun write(data: ByteArray) {
        val stream = output ?: throw IOException("Socket is not connected")
        stream.write(data)
        stream.flush()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed or never connected: nothing left to release.
        }
    }
}
