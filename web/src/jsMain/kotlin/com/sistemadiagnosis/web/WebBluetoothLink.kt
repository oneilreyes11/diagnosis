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

    private var closing = false

    private val onDisconnected: (dynamic) -> Unit = {
        DebugLog.add("BLE: evento gattserverdisconnected (${if (closing) "lo pidió la app" else "NO lo pidió la app: se cortó solo"})")
        receiver?.onClosed("El adaptador se desconectó")
    }
    private val onValue: (dynamic) -> Unit = { event ->
        val bytes = event.target.value.unsafeCast<Any>().toBytes()
        DebugLog.add("BLE ← ${bytes.size} B  \"${bytes.toPrintable()}\"  [${bytes.toHexDump()}]")
        receiver?.onBytes(bytes)
    }

    override val displayName: String = "Bluetooth ${(device.name as? String) ?: "LE"}"

    override fun open(receiver: ByteLinkReceiver) {
        this.receiver = receiver
        closing = false
        DebugLog.add("BLE: dispositivo elegido: nombre='${device.name}' id='${device.id}' (ya conectado: ${device.gatt?.connected})")
        device.addEventListener("gattserverdisconnected", onDisconnected)
        scope.launch {
            try {
                DebugLog.add("BLE: gatt.connect()…")
                val server: dynamic = device.gatt.connect().unsafeCast<Promise<dynamic>>().await()
                DebugLog.add("BLE: GATT conectado (connected=${server.connected})")
                val channel = findChannel(server)
                if (channel == null) {
                    DebugLog.add("BLE: ningún servicio tiene a la vez una característica de notificación y otra de escritura")
                    fail("No se encontró el canal serie del adaptador. ¿Es un adaptador OBD Bluetooth LE?")
                    return@launch
                }
                notifyCharacteristic = channel.first
                writeCharacteristic = channel.second
                DebugLog.add("BLE: canal elegido: notificación=${channel.first.uuid} escritura=${channel.second.uuid} (${describe(channel.second)})")
                notifyCharacteristic.addEventListener("characteristicvaluechanged", onValue)
                DebugLog.add("BLE: startNotifications()…")
                notifyCharacteristic.startNotifications().unsafeCast<Promise<dynamic>>().await()
                DebugLog.add("BLE: notificaciones activas; canal listo")
                receiver.onOpened()
            } catch (e: Throwable) {
                DebugLog.error("abriendo el Bluetooth", e)
                fail(e.message ?: "No se pudo conectar")
            }
        }
    }

    override fun write(data: ByteArray) {
        val characteristic = writeCharacteristic ?: return
        val withoutResponse = characteristic.properties.writeWithoutResponse == true
        DebugLog.add("BLE → ${data.size} B  \"${data.toPrintable()}\"  [${data.toHexDump()}] (${if (withoutResponse) "sin respuesta" else "con respuesta"})")
        // Web Bluetooth no admite dos operaciones GATT a la vez: se encadenan.
        for (start in data.indices step CHUNK_SIZE) {
            val chunk = data.copyOfRange(start, minOf(start + CHUNK_SIZE, data.size))
            writeQueue = writeQueue.then { writeChunk(characteristic, chunk, withoutResponse) }.unsafeCast<Promise<Any?>>()
        }
        writeQueue = writeQueue.catch { e ->
            DebugLog.add("ERROR al escribir en el adaptador: ${e.asDynamic().name}: ${e.message}")
            receiver?.onClosed("Error al enviar al adaptador: ${e.message}")
            null
        }
    }

    override fun close() {
        DebugLog.add("BLE: la app cierra la conexión (connected=${device.gatt.connected})")
        closing = true
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
        DebugLog.add("BLE: servicios accesibles: ${if (services.isEmpty()) "ninguno" else services.joinToString { it.uuid as String }}")
        for (service in services) {
            val characteristics = try {
                service.getCharacteristics().unsafeCast<Promise<Array<dynamic>>>().await()
            } catch (e: Throwable) {
                DebugLog.error("leyendo características de ${service.uuid}", e)
                continue
            }
            DebugLog.add("BLE:   servicio ${service.uuid}: " +
                characteristics.joinToString { "${it.uuid} (${describe(it)})" }.ifEmpty { "sin características" })
            val notify = characteristics.firstOrNull { it.properties.notify == true || it.properties.indicate == true }
            val write = characteristics.firstOrNull { it.properties.write == true || it.properties.writeWithoutResponse == true }
            // Sin `to`: sobre un valor dynamic, Kotlin lo compila como llamada a un método JS `.to()` inexistente.
            if (notify != null && write != null) return Pair<dynamic, dynamic>(notify, write)
        }
        return null
    }

    private suspend fun primaryServices(server: dynamic): List<dynamic> =
        try {
            server.getPrimaryServices().unsafeCast<Promise<Array<dynamic>>>().await().toList()
        } catch (e: Throwable) {
            DebugLog.error("getPrimaryServices(); se prueban los servicios uno a uno", e)
            // Algunos navegadores no listan servicios sin UUID: se piden uno a uno.
            WebBluetooth.SERVICE_UUIDS.mapNotNull { uuid ->
                try {
                    server.getPrimaryService(uuid).unsafeCast<Promise<dynamic>>().await()
                } catch (e: Throwable) {
                    null
                }
            }
        }

    private fun describe(characteristic: dynamic): String {
        val props = characteristic.properties
        return listOf("read", "write", "writeWithoutResponse", "notify", "indicate")
            .filter { props[it] == true }
            .joinToString("+")
            .ifEmpty { "sin propiedades" }
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
