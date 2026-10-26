package com.sistemadiagnosis.core.vehicle

import com.sistemadiagnosis.core.obd.Vin

/** Centralita direccionable por CAN de 11 bits. [address] es la dirección de diagnosis del fabricante. */
data class EcuDefinition(
    val address: String,
    val name: String,
    val requestId: Int,
    val responseId: Int,
)

data class EngineSpec(
    val code: String,
    val name: String,
    val family: String,
    val power: String,
    val fuel: String,
    val features: List<String>,
)

/**
 * Lo que sabemos de un vehículo concreto. Todo dato específico (direcciones,
 * notas de averías) debe comprobarse con el coche real.
 */
data class VehicleProfile(
    val id: String,
    val brand: String,
    val model: String,
    val generation: String,
    val platform: String,
    val year: Int,
    val engine: EngineSpec,
    val engineEcu: EcuDefinition,
    /** Prefijos de fabricante (WMI) válidos en el VIN. */
    val vinWmis: Set<String>,
    /** Código de modelo del grupo VAG: posiciones 7 y 8 del VIN. */
    val vinModelCodes: Set<String>,
    /** Notas de averías propias de este vehículo, por código. */
    val dtcNotes: Map<String, String>,
) {
    val displayName: String get() = "$brand $model"
    val subtitle: String get() = "$generation · $year · ${engine.name}"

    /** true si el VIN encaja con el fabricante y el modelo; null si no hay VIN. */
    fun matches(vin: Vin?): Boolean? = vin?.let { it.wmi in vinWmis && it.value.substring(6, 8) in vinModelCodes }
}
