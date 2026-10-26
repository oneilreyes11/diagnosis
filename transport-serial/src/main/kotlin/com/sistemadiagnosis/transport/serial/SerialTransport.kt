package com.sistemadiagnosis.transport.serial

import com.fazecast.jSerialComm.SerialPort
import com.sistemadiagnosis.core.transport.StreamTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Puerto serie del sistema. Cubre:
 * - ELM327 USB (FTDI/CH340), normalmente a 38400 baudios.
 * - ELM327 Bluetooth clásico emparejado en Windows/Linux/macOS, que el sistema
 *   expone como puerto COM virtual (SPP); ahí los baudios no influyen.
 */
class SerialTransport(
    private val portName: String,
    private val baudRate: Int = DEFAULT_BAUD_RATE,
) : StreamTransport() {
    private var port: SerialPort? = null

    override val displayName: String get() = "puerto serie $portName"

    override fun connect(): Pair<InputStream, OutputStream> {
        val serialPort = SerialPort.getCommPort(portName)
        serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)
        if (!serialPort.openPort()) throw IOException("el sistema no permitió abrir $portName")
        port = serialPort
        return serialPort.inputStream to serialPort.outputStream
    }

    override fun disconnect() {
        port?.closePort()
        port = null
    }

    companion object {
        const val DEFAULT_BAUD_RATE = 38400

        /** Puertos disponibles como "COM5 — Standard Serial over Bluetooth link". */
        fun availablePorts(): List<String> =
            SerialPort.getCommPorts().map { "${it.systemPortName} — ${it.portDescription}" }
    }
}
