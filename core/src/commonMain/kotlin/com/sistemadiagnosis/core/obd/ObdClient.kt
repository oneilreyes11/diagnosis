package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ElmCommandException
import com.sistemadiagnosis.core.elm.ElmError
import com.sistemadiagnosis.core.elm.ElmException
import com.sistemadiagnosis.core.elm.ElmResponse
import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.util.toHex

class ObdNegativeResponseException(val service: Int, val code: Int) :
    ElmException("El vehículo rechazó la petición ${service.toHex(2)}: ${describeNegativeResponse(code)}")

/** Códigos de respuesta negativa (ISO 14229 / ISO 15031) más habituales, en lenguaje claro. */
fun describeNegativeResponse(code: Int): String = when (code) {
    0x11 -> "la centralita no admite este servicio"
    0x12 -> "la centralita no admite esta función"
    0x22 -> "no se dan las condiciones (prueba con el motor parado y el contacto puesto)"
    0x31 -> "dato fuera de rango o no disponible"
    0x33 -> "acceso protegido por seguridad"
    0x78 -> "la centralita sigue procesando la petición"
    else -> "código ${code.toHex(2)}"
} + " (${code.toHex(2)})"

/** Datos congelados: la foto que guarda la centralita cuando confirma una avería. */
data class FreezeFrame(val trigger: Dtc, val values: Map<Int, Double>)

/** Servicios OBD-II (SAE J1979) sobre un adaptador ELM327. */
class ObdClient(private val elm: Elm327Driver) {
    var activeProtocol: ObdProtocol? = null
        private set

    /** Algunas centralitas no aceptan varios PIDs en una petición; se detecta y se recuerda. */
    private var multiPidSupported = true

    /**
     * Establece comunicación con el vehículo. El ELM327 no busca protocolo hasta
     * la primera petición OBD, así que se pide 01 00 (PIDs soportados), que todo
     * vehículo OBD-II debe responder.
     */
    suspend fun connect(): ObdProtocol {
        elm.sendOrThrow("0100", elm.timeouts.searchMs)
        return (elm.currentProtocol() ?: throw ElmException("El adaptador no informó el protocolo"))
            .also { activeProtocol = it }
    }

    suspend fun readBatteryVoltage(): Double = elm.readVoltage()

    /** VIN por servicio 09 PID 02; null si el vehículo no lo ofrece (habitual antes de ~2005). */
    suspend fun readVin(): Vin? = Vin.fromService09(request(0x09, 0x02))

    /** PIDs que la centralita declara soportar en el servicio 01 (datos en vivo) o 02 (datos congelados). */
    suspend fun supportedPids(service: Int = 0x01): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val bitmap = readPidData(service, base) ?: break
            for (bit in 0 until 32) {
                if (bitmap[bit / 8] and (0x80 shr (bit % 8)) != 0) supported += base + bit + 1
            }
            if (base + 0x20 !in supported) break
            base += 0x20
        }
        return supported
    }

    /** Testigo de motor, nº de averías y monitores de emisiones (servicio 01 PID 01). */
    suspend fun readMonitorStatus(): MonitorStatus? = readPidData(0x01, 0x01)?.let(MonitorStatus::decode)

    /** Averías de un tipo, sin repetir. */
    suspend fun readDtcs(kind: DtcKind): List<Dtc> {
        // En CAN la respuesta incluye un byte con el número de averías tras el código de servicio.
        val skip = if (activeProtocol?.isCan != false) 2 else 1
        return request(kind.service).flatMap { message ->
            (skip until message.size - 1 step 2).mapNotNull { i ->
                Dtc.fromBytes(message[i].toInt() and 0xFF, message[i + 1].toInt() and 0xFF)
            }
        }.distinct()
    }

    /**
     * Borra averías y datos congelados (servicio 04). También reinicia los monitores
     * de emisiones; las averías permanentes no se borran.
     */
    suspend fun clearDtcs() {
        request(0x04)
    }

    /** Datos congelados de la trama 0; null si la centralita no tiene ninguna guardada. */
    suspend fun readFreezeFrame(): FreezeFrame? {
        val triggerBytes = readPidData(0x02, 0x02) ?: return null
        val trigger = Dtc.fromBytes(triggerBytes[0], triggerBytes[1]) ?: return null
        val values = mutableMapOf<Int, Double>()
        for (pid in supportedPids(0x02)) {
            val definition = PidCatalog[pid] ?: continue
            val data = readPidData(0x02, pid) ?: continue
            if (data.size >= definition.bytes) values[pid] = definition.decode(data)
        }
        return FreezeFrame(trigger, values + PidCatalog.derive(values))
    }

    /**
     * Lee varios PIDs del servicio 01 y los devuelve decodificados. En CAN pide hasta
     * seis por petición, lo que multiplica la velocidad de refresco.
     */
    suspend fun readPids(pids: Collection<Int>): Map<Int, Double> {
        val readable = pids.filter { PidCatalog.byteCount(it) != null && PidCatalog[it] != null }
        val raw = mutableMapOf<Int, IntArray>()
        if (multiPidSupported && activeProtocol?.isCan == true && readable.size > 1) {
            for (chunk in readable.chunked(MAX_PIDS_PER_REQUEST)) {
                val result = readMultiple(chunk)
                if (result == null) {
                    multiPidSupported = false
                    break
                }
                raw += result
            }
        }
        for (pid in readable.filterNot { it in raw }) {
            readPidData(0x01, pid)?.let { raw[pid] = it }
        }
        return raw.mapValues { (pid, data) -> PidCatalog[pid]!!.decode(data) }
    }

    /**
     * Petición genérica. Devuelve solo las respuestas positivas (primer byte =
     * servicio + 0x40); lista vacía si nadie respondió (NO DATA).
     */
    suspend fun request(service: Int, vararg parameters: Int): List<ByteArray> {
        val command = (intArrayOf(service) + parameters).joinToString("") { it.toHex(2) }
        val lines = when (val response = elm.send(command)) {
            is ElmResponse.Ok -> response.lines
            is ElmResponse.Error ->
                if (response.error == ElmError.NO_DATA) return emptyList()
                else throw ElmCommandException(command, response)
        }
        val messages = try {
            ElmMessageParser.parse(lines)
        } catch (e: IllegalArgumentException) {
            throw ElmException("Respuesta no reconocida a '$command': ${lines.joinToString(" / ")}")
        }
        val positive = messages.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == service + 0x40 }
        if (positive.isEmpty()) {
            messages.firstOrNull { it.size >= 3 && (it[0].toInt() and 0xFF) == 0x7F && (it[2].toInt() and 0xFF) != 0x78 }
                ?.let { throw ObdNegativeResponseException(service, it[2].toInt() and 0xFF) }
        }
        return positive
    }

    /** Datos de un PID de los servicios 01 o 02 (este último con la trama 0). */
    private suspend fun readPidData(service: Int, pid: Int): IntArray? {
        val parameters = if (service == 0x02) intArrayOf(pid, 0x00) else intArrayOf(pid)
        val offset = 1 + parameters.size
        val message = request(service, *parameters).firstOrNull { it.size > offset && (it[1].toInt() and 0xFF) == pid }
            ?: return null
        return message.copyOfRange(offset, message.size).toUnsignedInts()
    }

    /** null si la centralita no entiende peticiones con varios PIDs. */
    private suspend fun readMultiple(pids: List<Int>): Map<Int, IntArray>? {
        val messages = try {
            request(0x01, *pids.toIntArray())
        } catch (e: ElmException) {
            return null
        }
        if (messages.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, IntArray>()
        for (message in messages) {
            val bytes = message.toUnsignedInts()
            var index = 1
            while (index < bytes.size) {
                val pid = bytes[index]
                val size = PidCatalog.byteCount(pid) ?: break
                if (index + 1 + size > bytes.size) break
                result[pid] = bytes.copyOfRange(index + 1, index + 1 + size)
                index += 1 + size
            }
        }
        // Si solo ha respondido al primero, probablemente no admite varios a la vez.
        return result.takeIf { it.size > 1 || pids.size == 1 }
    }

    private fun ByteArray.toUnsignedInts() = IntArray(size) { this[it].toInt() and 0xFF }

    private companion object {
        const val MAX_PIDS_PER_REQUEST = 6
    }
}
