package com.sistemadiagnosis.core.uds

import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ElmResponse
import com.sistemadiagnosis.core.obd.ObdClient
import com.sistemadiagnosis.core.obd.ObdNegativeResponseException
import com.sistemadiagnosis.core.transport.TransportException
import com.sistemadiagnosis.core.util.toHex
import com.sistemadiagnosis.core.vehicle.EcuDefinition

/** Identificación de una centralita (DIDs estándar del grupo VAG). */
data class ModuleIdentity(
    val partNumber: String?,
    val softwareVersion: String?,
    val hardwareNumber: String?,
    /** Nombre que da la propia centralita, p.ej. "J533  Gateway". */
    val systemName: String?,
)

/**
 * Diagnosis UDS (ISO 14229) sobre CAN a través del ELM327. Solo lectura y
 * borrado de averías: nada de codificación ni adaptaciones.
 */
class UdsClient(private val elm: Elm327Driver, private val obd: ObdClient) {
    /** null hasta probarlo; false si el adaptador no admite fijar el control de flujo. */
    private var manualFlowControl: Boolean? = null

    /** Modo escaneo activo: tiempos fijos y cabeceras (ver [select]). */
    private var scanMode = false

    /** Cabeceras CAN activas (ATH1): las respuestas se filtran por CAN ID en la app. */
    private var headers = false

    /** Datos y modo del control de flujo ya enviados; por centralita solo cambia la cabecera. */
    private var flowControlConfigured = false

    /** Sesión de diagnosis extendida abierta en la centralita actual. */
    private var extendedSession = false

    /**
     * Dirige las peticiones a [module]. Las respuestas largas (multitrama) exigen
     * contestar con un control de flujo dirigido a la propia centralita: el ELM327
     * solo lo hace solo para las direcciones OBD estándar (7E0-7E7).
     *
     * La primera vez prepara el modo escaneo:
     * - Tiempo de espera fijo y largo: con el adaptativo, el ELM327 aprende lo rápido
     *   que contesta el motor y da NO DATA antes de que llegue la respuesta de una
     *   centralita que va a través del gateway.
     * - Cabeceras CAN (ATH1): hay adaptadores en los que ATCRA no filtra y se cuelan
     *   respuestas de otras centralitas; con la cabecera se sabe quién envía cada trama.
     */
    suspend fun select(module: EcuDefinition) {
        leaveSession()
        if (!scanMode) {
            elm.send("ATAT0")
            elm.send("ATSTFF")
            headers = elm.send("ATH1") is ElmResponse.Ok
            scanMode = true
        }
        elm.addressEcu(module.requestId, module.responseId)
        obd.responseIdFilter = if (headers) module.responseId else null
        if (manualFlowControl == false) return
        val commands = listOf("ATFCSH${module.requestId.toHex(3)}") +
            if (flowControlConfigured) emptyList() else listOf("ATFCSD300000", "ATFCSM1")
        val supported = commands.all { elm.send(it) is ElmResponse.Ok }
        if (manualFlowControl == null) manualFlowControl = supported
        flowControlConfigured = supported
    }

    /** Vuelve a la centralita del motor, al control de flujo y a los tiempos normales para OBD-II. */
    suspend fun restore(engine: EcuDefinition) {
        leaveSession()
        if (manualFlowControl == true) elm.send("ATFCSM0")
        flowControlConfigured = false
        if (scanMode) {
            if (headers) elm.send("ATH0")
            elm.send("ATST32")
            elm.send("ATAT1")
            headers = false
            scanMode = false
        }
        obd.responseIdFilter = null
        elm.addressEcu(engine.requestId, engine.responseId)
    }

    /** Si se abrió la sesión extendida en la centralita actual, la devuelve a la normal. */
    suspend fun leaveSession() {
        if (!extendedSession) return
        extendedSession = false
        try {
            obd.request(0x10, 0x01)
        } catch (e: DiagnosisException) {
            if (e is TransportException) throw e
        }
    }

    /**
     * Algunas centralitas solo atienden ciertas lecturas en la sesión extendida
     * (10 03), que también es de solo lectura. Si rechazan el servicio, se abre una
     * vez y se repite.
     */
    private suspend fun <T> withSessionFallback(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ObdNegativeResponseException) {
            if (extendedSession || e.code !in SESSION_NRCS) throw e
            try {
                obd.request(0x10, 0x03)
            } catch (sessionError: ObdNegativeResponseException) {
                throw e
            }
            extendedSession = true
            block()
        }

    /**
     * Comprueba si la centralita seleccionada responde leyendo su referencia.
     * Una respuesta negativa también cuenta: la centralita está ahí.
     */
    suspend fun isPresent(): Boolean =
        try {
            obd.request(0x22, PART_NUMBER shr 8, PART_NUMBER and 0xFF).isNotEmpty()
        } catch (e: ObdNegativeResponseException) {
            true
        }

    suspend fun readIdentity(): ModuleIdentity = ModuleIdentity(
        partNumber = readText(PART_NUMBER),
        softwareVersion = readText(SOFTWARE_VERSION),
        hardwareNumber = readText(HARDWARE_NUMBER),
        systemName = readText(SYSTEM_NAME),
    )

    /** Toda la memoria de averías (19 02 con todos los bits de estado). */
    suspend fun readDtcs(): List<UdsDtc> {
        val response = try {
            withSessionFallback { obd.request(0x19, 0x02, 0xFF) }
        } catch (e: ObdNegativeResponseException) {
            // Algunas centralitas no aceptan la máscara completa: solo activas, pendientes y confirmadas.
            if (e.code != 0x31 && e.code != 0x12) throw e
            obd.request(0x19, 0x02, 0x0D)
        }
        return response.firstOrNull { it.size >= 3 && it[1].toInt() == 0x02 }?.let(UdsDtc::parseReport).orEmpty()
    }

    /** Borra la memoria de averías de la centralita seleccionada (14 FF FF FF). */
    suspend fun clearDtcs() {
        obd.request(0x14, 0xFF, 0xFF, 0xFF)
    }

    /** Texto ASCII de un identificador; null si la centralita no lo ofrece. */
    private suspend fun readText(did: Int): String? {
        val data = try {
            withSessionFallback { obd.request(0x22, did shr 8, did and 0xFF) }
                .firstOrNull { it.size >= 3 && (it[1].toInt() and 0xFF) == did shr 8 && (it[2].toInt() and 0xFF) == did and 0xFF }
                ?.let { it.copyOfRange(3, it.size) }
        } catch (e: ObdNegativeResponseException) {
            null
        } ?: return null
        return data.filter { it.toInt() in 0x20..0x7E }.map { it.toInt().toChar() }.joinToString("").trim().ifEmpty { null }
    }

    private companion object {
        /** Servicio no admitido (11) o no admitido en esta sesión (7F). */
        val SESSION_NRCS = setOf(0x11, 0x7F)

        const val PART_NUMBER = 0xF187
        const val SOFTWARE_VERSION = 0xF189
        const val HARDWARE_NUMBER = 0xF191
        const val SYSTEM_NAME = 0xF197
    }
}
