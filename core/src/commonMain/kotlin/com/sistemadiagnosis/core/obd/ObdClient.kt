package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ElmCommandException
import com.sistemadiagnosis.core.elm.ElmError
import com.sistemadiagnosis.core.elm.ElmException
import com.sistemadiagnosis.core.elm.ElmResponse
import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.util.toHex
import kotlinx.coroutines.delay

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

    /** PIDs por petición que admiten la centralita y el adaptador; se ajusta al primer fallo y se recuerda. */
    private var pidsPerRequest = PIDS_PER_REQUEST_STEPS.first()

    /**
     * Las peticiones van a una sola centralita (ATSH). Entonces se puede decir al
     * adaptador cuántas respuestas esperar (`010C1`) y no se queda esperando más.
     */
    var singleResponder = false

    /** null hasta probarlo; false si el adaptador no entiende el número de respuestas. */
    private var responseCountSupported: Boolean? = null

    /**
     * Con las cabeceras CAN activas (ATH1), solo se aceptan tramas de este CAN ID:
     * algunos adaptadores no filtran con ATCRA y se cuelan respuestas de otras centralitas.
     */
    var responseIdFilter: Int? = null

    private val useResponseCount: Boolean
        get() = singleResponder && responseCountSupported != false && responseIdFilter == null

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
     * Lee varios PIDs del servicio 01 y los devuelve decodificados. En CAN pide varios
     * por petición (6, o 3 o 2 si el adaptador no admite tantos), lo que multiplica
     * la velocidad de refresco.
     */
    suspend fun readPids(pids: Collection<Int>): Map<Int, Double> {
        val readable = pids.filter { PidCatalog.byteCount(it) != null && PidCatalog[it] != null }
        val raw = mutableMapOf<Int, IntArray>()
        if (activeProtocol?.isCan == true) {
            var remaining = readable
            while (remaining.size > 1 && pidsPerRequest > 1) {
                // Con número de respuestas, cada petición se limita a lo que cabe en una trama.
                val packed = useResponseCount
                val chunk = if (packed) singleFrameChunk(remaining) else remaining.take(pidsPerRequest)
                if (chunk.size < 2) {
                    // Un PID grande que no se puede agrupar: va solo y se sigue con el resto.
                    readPidData(0x01, chunk.first())?.let { raw[chunk.first()] = it }
                    remaining = remaining.drop(1)
                    continue
                }
                val result = readMultiple(chunk, singleFrame = packed)
                if (result == null) {
                    // Muchos clones solo aceptan unos pocos PIDs por petición: se prueba con menos.
                    pidsPerRequest = PIDS_PER_REQUEST_STEPS.firstOrNull { it < pidsPerRequest } ?: 1
                    continue
                }
                raw += result
                remaining = remaining.drop(chunk.size)
            }
        }
        for (pid in readable.filterNot { it in raw }) {
            readPidData(0x01, pid)?.let { raw[pid] = it }
        }
        return raw.mapValues { (pid, data) -> PidCatalog[pid]!!.decode(data) }
    }

    /** Los primeros PIDs cuya respuesta (41 + PID y datos de cada uno) cabe en una trama CAN de 7 bytes. */
    private fun singleFrameChunk(pids: List<Int>): List<Int> {
        val chunk = mutableListOf<Int>()
        var size = 1
        for (pid in pids) {
            val next = 1 + (PidCatalog.byteCount(pid) ?: break)
            if (size + next > SINGLE_FRAME_BYTES || chunk.size == pidsPerRequest) break
            chunk += pid
            size += next
        }
        return chunk
    }

    /**
     * Petición genérica. Devuelve solo las respuestas positivas (primer byte =
     * servicio + 0x40); lista vacía si nadie respondió (NO DATA).
     *
     * Si llega una respuesta que no es de esta petición (el adaptador iba desfasado),
     * se repite una vez; si vuelve a pasar, se avisa en lugar de dar un resultado falso.
     */
    suspend fun request(
        service: Int,
        vararg parameters: Int,
        timeoutMs: Long = elm.timeouts.commandMs,
        /** La respuesta cabe en una trama: se puede pedir al adaptador que no espere más. */
        singleFrame: Boolean = false,
    ): List<ByteArray> {
        val base = (intArrayOf(service) + parameters).joinToString("") { it.toHex(2) }
        val withCount = singleFrame && useResponseCount
        val command = if (withCount) "${base}1" else base
        repeat(2) { attempt ->
            val lines = when (val response = elm.send(command, timeoutMs)) {
                is ElmResponse.Ok -> response.lines
                is ElmResponse.Error -> when {
                    response.error == ElmError.NO_DATA -> return emptyList()
                    // El adaptador no entiende el número de respuestas: se deja de usar.
                    withCount && response.error == ElmError.UNKNOWN_COMMAND -> {
                        responseCountSupported = false
                        return request(service, *parameters, timeoutMs = timeoutMs)
                    }
                    else -> throw ElmCommandException(command, response)
                }
            }
            val messages = try {
                ElmMessageParser.parse(lines, responseIdFilter)
            } catch (e: IllegalArgumentException) {
                throw ElmException("Respuesta no reconocida a '$command': ${lines.joinToString(" / ")}")
            }
            val positive = messages.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == service + 0x40 }
            if (withCount && responseCountSupported == null) {
                // Primera vez: solo se da por bueno si contesta lo que se pidió.
                if (positive.isEmpty()) {
                    responseCountSupported = false
                    return request(service, *parameters, timeoutMs = timeoutMs)
                }
                responseCountSupported = true
            }
            if (positive.isNotEmpty()) return positive
            messages.firstOrNull { it.size >= 3 && (it[0].toInt() and 0xFF) == 0x7F && (it[2].toInt() and 0xFF) != 0x78 }
                ?.let { throw ObdNegativeResponseException(service, it[2].toInt() and 0xFF) }
            // Solo "respuesta pendiente" (7F xx 78): la centralita sigue trabajando. Se le da
            // tiempo antes de repetir; repetir en el acto solo consigue un NO DATA.
            if (messages.isNotEmpty() && messages.all { it.size >= 3 && (it[0].toInt() and 0xFF) == 0x7F }) {
                if (attempt == 1) throw ElmException("La centralita no terminó de responder a '$command'")
                delay(RESPONSE_PENDING_WAIT_MS)
                return@repeat
            }
            if (messages.isEmpty() || attempt == 1) {
                if (messages.isEmpty()) return emptyList()
                throw ElmException("El adaptador respondió a otra petición en lugar de '$command'")
            }
        }
        return emptyList()
    }

    /** Datos de un PID de los servicios 01 o 02 (este último con la trama 0). */
    private suspend fun readPidData(service: Int, pid: Int): IntArray? {
        val parameters = if (service == 0x02) intArrayOf(pid, 0x00) else intArrayOf(pid)
        val offset = 1 + parameters.size
        val matches = { message: ByteArray -> message.size > offset && (message[1].toInt() and 0xFF) == pid }
        // Un PID (o un mapa de soportados) cabe siempre en una trama CAN.
        var messages = request(service, *parameters, singleFrame = true)
        // Respuesta del mismo servicio pero de otro PID: era la de una petición anterior.
        if (messages.isNotEmpty() && messages.none(matches)) messages = request(service, *parameters, singleFrame = true)
        val message = messages.firstOrNull(matches) ?: return null
        return message.copyOfRange(offset, message.size).toUnsignedInts()
    }

    /** null si la centralita no responde a tantos PIDs juntos. */
    private suspend fun readMultiple(pids: List<Int>, singleFrame: Boolean): Map<Int, IntArray>? {
        val messages = try {
            request(0x01, *pids.toIntArray(), timeoutMs = MULTI_PID_TIMEOUT_MS, singleFrame = singleFrame)
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
        // Si solo ha respondido al primero o trae PIDs no pedidos, no se fía de la respuesta.
        return result.takeIf { (it.size > 1 || pids.size == 1) && it.keys.all { pid -> pid in pids } }
    }

    private fun ByteArray.toUnsignedInts() = IntArray(size) { this[it].toInt() and 0xFF }

    private companion object {
        val PIDS_PER_REQUEST_STEPS = listOf(6, 3, 2)

        /** Una respuesta normal llega en ~150 ms; si un clon no admite la petición, se calla. */
        const val MULTI_PID_TIMEOUT_MS = 800L

        /** Datos útiles de una trama CAN simple (8 bytes menos el de control ISO-TP). */
        const val SINGLE_FRAME_BYTES = 7

        /** Tiempo que se deja a una centralita que ha contestado "respuesta pendiente". */
        const val RESPONSE_PENDING_WAIT_MS = 1_500L
    }
}
