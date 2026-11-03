package com.sistemadiagnosis.core.uds

import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ElmResponse
import com.sistemadiagnosis.core.obd.ObdClient
import com.sistemadiagnosis.core.obd.ObdNegativeResponseException
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

    /**
     * Dirige las peticiones a [module]. Las respuestas largas (multitrama) exigen
     * contestar con un control de flujo dirigido a la propia centralita: el ELM327
     * solo lo hace solo para las direcciones OBD estándar (7E0-7E7).
     */
    suspend fun select(module: EcuDefinition) {
        elm.addressEcu(module.requestId, module.responseId)
        if (manualFlowControl == false) return
        val supported = listOf("ATFCSH${module.requestId.toHex(3)}", "ATFCSD300000", "ATFCSM1")
            .all { elm.send(it) is ElmResponse.Ok }
        if (manualFlowControl == null) manualFlowControl = supported
    }

    /** Vuelve a la centralita del motor y al control de flujo automático para OBD-II. */
    suspend fun restore(engine: EcuDefinition) {
        if (manualFlowControl == true) elm.send("ATFCSM0")
        elm.addressEcu(engine.requestId, engine.responseId)
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
            obd.request(0x19, 0x02, 0xFF)
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
            obd.request(0x22, did shr 8, did and 0xFF)
                .firstOrNull { it.size >= 3 && (it[1].toInt() and 0xFF) == did shr 8 && (it[2].toInt() and 0xFF) == did and 0xFF }
                ?.let { it.copyOfRange(3, it.size) }
        } catch (e: ObdNegativeResponseException) {
            null
        } ?: return null
        return data.filter { it.toInt() in 0x20..0x7E }.map { it.toInt().toChar() }.joinToString("").trim().ifEmpty { null }
    }

    private companion object {
        const val PART_NUMBER = 0xF187
        const val SOFTWARE_VERSION = 0xF189
        const val HARDWARE_NUMBER = 0xF191
        const val SYSTEM_NAME = 0xF197
    }
}
