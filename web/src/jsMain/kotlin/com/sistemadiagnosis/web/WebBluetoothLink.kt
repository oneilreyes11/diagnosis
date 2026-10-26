package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.transport.ByteLink
import com.sistemadiagnosis.core.transport.ByteLinkReceiver
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise

/** Acceso a la API Web Bluetooth (Chrome/Edge en PC y Android, Bluefy en iPhone). */
object WebBluetooth {
    /**
     * Servicios tipo "UART" habituales en adaptadores ELM327 Bluetooth LE.
     * Web Bluetooth solo deja acceder a los servicios declarados al elegir el dispositivo.
     */
    val SERVICE_UUIDS = listOf(
        "0000fff0-0000-1000-8000-00805f9b34fb",
        "0000ffe0-0000-1000-8000-00805f9b34fb",
        "000018f0-0000-1000-8000-00805f9b34fb",
        "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
    )

    fun isAvailable(): Boolean = window.navigator.asDynamic().bluetooth != null

    /** Abre el selector de dispositivos del navegador. Debe llamarse desde un clic. */
    fun requestDevice(): Promise<dynamic> {
        val options: dynamic = js("({})")
        options.acceptAllDevices = true
        options.optionalServices = SERVICE_UUIDS.toTypedArray()
        return window.navigator.asDynamic().bluetooth.requestDevice(options).unsafeCast<Promise<dynamic>>()
    }
}

/**
 * ELM327 Bluetooth LE vía Web Bluetooth: una característica de notificación
 * (respuestas) y otra de escritura (comandos), elegidas por sus propiedades.
 */
class WebBluetoothLink(private val device: dynamic) : ByteLink {
    private val scope = MainScope()
    private var receiver: ByteLinkReceiver? = null
    private var notifyCharacteristic: dynamic = null
    private var writeCharacteristic: dynamic = null
    private var writeQueue: Promise<Any?> = Promise.resolve(null)

    private val onDisconnected: (dynamic) -> Unit = { receiver?.onClosed("El adaptador se desconectó") }
    private val onValue: (dynamic) -> Unit = { event -> receiver?.onBytes(event.target.value.unsafeCast<Any>().toBytes()) }

    override val displayName: String = "Bluetooth ${(device.name as? String) ?: "LE"}"

    override fun open(receiver: ByteLinkReceiver) {
        this.receiver = receiver
        device.addEventListener("gattserverdisconnected", onDisconnected)
        scope.launch {
            try {
                val server: dynamic = device.gatt.connect().unsafeCast<Promise<dynamic>>().await()
                val channel = findChannel(server)
                if (channel == null) {
                    fail("No se encontró el canal serie del adaptador. ¿Es un adaptador OBD Bluetooth LE?")
                    return@launch
                }
                notifyCharacteristic = channel.first
                writeCharacteristic = channel.second
                notifyCharacteristic.addEventListener("characteristicvaluechanged", onValue)
                notifyCharacteristic.startNotifications().unsafeCast<Promise<dynamic>>().await()
                receiver.onOpened()
            } catch (e: Throwable) {
                fail(e.message ?: "No se pudo conectar")
            }
        }
    }

    override fun write(data: ByteArray) {
        val characteristic = writeCharacteristic ?: return
        val withoutResponse = characteristic.properties.writeWithoutResponse == true
        // Web Bluetooth no admite dos operaciones GATT a la vez: se encadenan.
        for (start in data.indices step CHUNK_SIZE) {
            val chunk = data.copyOfRange(start, minOf(start + CHUNK_SIZE, data.size))
            writeQueue = writeQueue.then { writeChunk(characteristic, chunk, withoutResponse) }.unsafeCast<Promise<Any?>>()
        }
        writeQueue = writeQueue.catch { e ->
            receiver?.onClosed("Error al enviar al adaptador: ${e.message}")
            null
        }
    }

    override fun close() {
        device.removeEventListener("gattserverdisconnected", onDisconnected)
        notifyCharacteristic?.removeEventListener("characteristicvaluechanged", onValue)
        if (device.gatt.connected == true) device.gatt.disconnect()
    }

    private fun writeChunk(characteristic: dynamic, chunk: ByteArray, withoutResponse: Boolean): dynamic = when {
        withoutResponse && characteristic.writeValueWithoutResponse != null -> characteristic.writeValueWithoutResponse(chunk)
        !withoutResponse && characteristic.writeValueWithResponse != null -> characteristic.writeValueWithResponse(chunk)
        // Navegadores antiguos solo tienen writeValue.
        else -> characteristic.writeValue(chunk)
    }

    private suspend fun findChannel(server: dynamic): Pair<dynamic, dynamic>? {
        val services = primaryServices(server).sortedBy { rank(it.uuid as String) }
        for (service in services) {
            val characteristics = service.getCharacteristics().unsafeCast<Promise<Array<dynamic>>>().await()
            val notify = characteristics.firstOrNull { it.properties.notify == true || it.properties.indicate == true }
            val write = characteristics.firstOrNull { it.properties.write == true || it.properties.writeWithoutResponse == true }
            if (notify != null && write != null) return notify to write
        }
        return null
    }

    private suspend fun primaryServices(server: dynamic): List<dynamic> =
        try {
            server.getPrimaryServices().unsafeCast<Promise<Array<dynamic>>>().await().toList()
        } catch (e: Throwable) {
            // Algunos navegadores no listan servicios sin UUID: se piden uno a uno.
            WebBluetooth.SERVICE_UUIDS.mapNotNull { uuid ->
                try {
                    server.getPrimaryService(uuid).unsafeCast<Promise<dynamic>>().await()
                } catch (e: Throwable) {
                    null
                }
            }
        }

    private fun rank(uuid: String): Int = WebBluetooth.SERVICE_UUIDS.indexOf(uuid).let { if (it < 0) Int.MAX_VALUE else it }

    private fun fail(message: String) {
        receiver?.onClosed(message)
        close()
    }

    private companion object {
        /** Tamaño mínimo garantizado de una escritura BLE (MTU 23 − 3). */
        const val CHUNK_SIZE = 20
    }
}

/** Convierte el DataView de una notificación en bytes. */
private fun Any.toBytes(): ByteArray {
    val view = asDynamic()
    val size = view.byteLength as Int
    return ByteArray(size) { i -> (view.getUint8(i) as Int).toByte() }
}
