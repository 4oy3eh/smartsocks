package com.smartsocks.transport.desktop

import com.fazecast.jSerialComm.SerialPort

data class SerialPortInfo(val systemName: String, val description: String, val looksLikeBluetooth: Boolean)

object SerialPorts {
    /** Serial ports present now, for a device picker. Never throws; returns an empty list if enumeration fails. */
    fun list(): List<SerialPortInfo> = try {
        SerialPort.getCommPorts().map {
            val description = it.portDescription.orEmpty()
            SerialPortInfo(it.systemPortName, description, looksLikeBluetooth(description, it.descriptivePortName))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    internal fun looksLikeBluetooth(vararg descriptions: String?): Boolean =
        descriptions.any { it?.contains("bluetooth", ignoreCase = true) == true }
}
