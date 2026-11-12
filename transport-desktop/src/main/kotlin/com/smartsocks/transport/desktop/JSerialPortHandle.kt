package com.smartsocks.transport.desktop

import com.fazecast.jSerialComm.SerialPort
import java.io.IOException

internal class JSerialPortHandle(
    private val portName: String,
    private val baudRate: Int,
    private val readTimeoutMillis: Int,
) : PortHandle {
    @Volatile
    private var port: SerialPort? = null

    override fun open() {
        val p = try {
            SerialPort.getCommPort(portName)
        } catch (e: Exception) {
            throw IOException("Serial port '$portName' not found: ${e.message}", e)
        }
        p.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMillis, 0)
        if (!p.openPort()) {
            throw IOException("Cannot open serial port '$portName' (in use, unpaired or out of range?)")
        }
        port = p
    }

    override fun read(buffer: ByteArray): Int {
        val p = port ?: return -1
        if (!p.isOpen) return -1
        return p.readBytes(buffer, buffer.size)
    }

    override fun write(data: ByteArray) {
        val p = port ?: throw IOException("Serial port '$portName' is not open")
        var offset = 0
        while (offset < data.size) {
            val n = p.writeBytes(data, data.size - offset, offset)
            if (n < 0) throw IOException("Write to serial port '$portName' failed")
            offset += n
        }
    }

    override fun bytesAvailable(): Int = port?.takeIf { it.isOpen }?.bytesAvailable() ?: -1

    override fun close() {
        port?.closePort()
    }
}
