package com.sistemadiagnosis.core.elm

import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.util.toHex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

data class ElmTimeouts(
    val resetMs: Long = 5_000,
    val commandMs: Long = 2_000,
    /** La primera petición OBD con protocolo automático puede tardar varios segundos en buscar. */
    val searchMs: Long = 15_000,
    val drainMs: Long = 200,
)

data class AdapterInfo(val version: String, val warnings: List<String>)

/**
 * Driver de la capa de comandos AT del ELM327 (y clones).
 *
 * Envía un comando terminado en CR y lee hasta el prompt '>'. Serializa los
 * comandos con un mutex: el ELM327 solo atiende uno cada vez.
 */
class Elm327Driver(
    private val transport: Transport,
    val timeouts: ElmTimeouts = ElmTimeouts(),
) {
    private val mutex = Mutex()

    var adapterInfo: AdapterInfo? = null
        private set

    /**
     * Reinicia y configura el adaptador. Con [preferredProtocol] (el del vehículo, si
     * se conoce) se prueba ese primero con búsqueda automática de respaldo (ATSPAx):
     * evita la búsqueda completa, que en algunos adaptadores tarda unos 5 segundos.
     */
    suspend fun initialize(preferredProtocol: ObdProtocol? = null): AdapterInfo {
        if (!transport.isOpen) transport.open()
        // Descarta lo que el adaptador haya enviado al conectar (banners, prompts sueltos).
        transport.read(timeouts.drainMs)

        val resetLines = sendOrThrow("ATZ", timeouts.resetMs)
        // Algunos clones responden solo "OK" a ATZ; la versión se pide aparte.
        val version = resetLines.firstOrNull { "ELM" in it.uppercase() }
            ?: sendOrThrow("ATI").firstOrNull().orEmpty()

        REQUIRED_SETTINGS.forEach { sendOrThrow(it) }
        val protocolHinted = preferredProtocol != null && send("ATSPA${preferredProtocol.code}") is ElmResponse.Ok
        if (!protocolHinted) sendOrThrow("ATSP0")
        val warnings = OPTIONAL_SETTINGS.mapNotNull { setting ->
            (send(setting) as? ElmResponse.Error)?.let { "$setting no soportado: ${it.error.description}" }
        }
        return AdapterInfo(version, warnings).also { adapterInfo = it }
    }

    /**
     * Envía un comando y espera su respuesta completa. Si quien lo pidió se cancela
     * (por ejemplo, al salir de "En vivo"), el intercambio termina igualmente: si no,
     * su respuesta llegaría tarde y se tomaría por la del comando siguiente.
     */
    suspend fun send(command: String, timeoutMs: Long = timeouts.commandMs): ElmResponse =
        mutex.withLock {
            withContext(NonCancellable) {
                discardStaleInput()
                transport.write("$command\r".encodeToByteArray())
                ElmResponse.parse(command, readUntilPrompt(command, timeoutMs))
            }
        }

    /** Descarta restos de respuestas anteriores (p.ej. una que llegó tras un timeout). */
    private suspend fun discardStaleInput() {
        repeat(MAX_STALE_CHUNKS) {
            if (transport.read(0).isEmpty()) return
        }
    }

    suspend fun sendOrThrow(command: String, timeoutMs: Long = timeouts.commandMs): List<String> =
        when (val response = send(command, timeoutMs)) {
            is ElmResponse.Ok -> response.lines
            is ElmResponse.Error -> throw ElmCommandException(command, response)
        }

    /** Tensión en el pin 16 del conector OBD (batería), medida por el propio adaptador. */
    suspend fun readVoltage(): Double {
        val line = sendOrThrow("ATRV").firstOrNull().orEmpty()
        return VOLTAGE.find(line)?.groupValues?.get(1)?.toDouble()
            ?: throw ElmException("Respuesta de tensión no reconocida: '$line'")
    }

    /**
     * Dirige las peticiones a una sola centralita (direccionamiento físico CAN de
     * 11 bits) en lugar de a todas. [ATCRA] filtra las respuestas; algunos clones no
     * lo tienen y entonces se omite.
     */
    suspend fun addressEcu(requestId: Int, responseId: Int) {
        sendOrThrow("ATSH${requestId.toHex(3)}")
        send("ATCRA${responseId.toHex(3)}")
    }

    /** Protocolo activo (ATDPN responde p.ej. "A6": automático, protocolo 6). */
    suspend fun currentProtocol(): ObdProtocol? {
        val line = sendOrThrow("ATDPN").firstOrNull().orEmpty()
        return line.lastOrNull()?.let(ObdProtocol::fromCode)
    }

    private suspend fun readUntilPrompt(command: String, timeoutMs: Long): String {
        val received = StringBuilder()
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
        while (true) {
            val remaining = -deadline.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) throw ElmTimeoutException(command, received.toString())
            val chunk = transport.read(remaining)
            if (chunk.isEmpty()) continue
            received.append(chunk.decodeToString())
            val prompt = received.indexOf(PROMPT)
            if (prompt >= 0) return received.substring(0, prompt)
        }
    }

    private companion object {
        const val PROMPT = '>'
        const val MAX_STALE_CHUNKS = 64

        /** Sin eco, sin saltos de línea y sin cabeceras; después se fija el protocolo. */
        val REQUIRED_SETTINGS = listOf("ATE0", "ATL0", "ATH0")

        /**
         * Quitar espacios (ATS0) ahorra ancho de banda; los ELM327 v1.0-1.2 no lo tienen.
         * ATCAF1 (formato CAN automático) es el valor de fábrica, pero hay adaptadores que
         * vienen sin él y dan las tramas en crudo (el parser también sabe leerlas).
         */
        val OPTIONAL_SETTINGS = listOf("ATS0", "ATCAF1")

        /** "12.4V" o, en algunos adaptadores, "13.48" sin unidad. */
        val VOLTAGE = Regex("(\\d+(?:\\.\\d+)?)\\s*V?", RegexOption.IGNORE_CASE)
    }
}
