package com.sistemadiagnosis.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.sistemadiagnosis.core.transport.StreamTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327 Bluetooth clásico (SPP/RFCOMM) en Android.
 *
 * El llamador debe tener BLUETOOTH_CONNECT concedido (Android 12+) y el
 * dispositivo emparejado.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : StreamTransport() {
    private var socket: BluetoothSocket? = null

    override val displayName: String get() = "Bluetooth ${device.name ?: device.address}"

    override fun connect(): Pair<InputStream, OutputStream> {
        // Una búsqueda de dispositivos en curso ralentiza o rompe la conexión RFCOMM.
        adapter?.cancelDiscovery()
        val connected = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        } catch (e: IOException) {
            // Muchos clones baratos solo aceptan conexiones no autenticadas.
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).apply { connect() }
        }
        socket = connected
        return connected.inputStream to connected.outputStream
    }

    override fun disconnect() {
        socket?.close()
        socket = null
    }

    companion object {
        /** Serial Port Profile. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
