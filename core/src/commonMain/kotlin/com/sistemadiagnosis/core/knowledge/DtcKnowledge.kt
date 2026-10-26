package com.sistemadiagnosis.core.knowledge

import com.sistemadiagnosis.core.obd.Dtc
import com.sistemadiagnosis.core.obd.DtcSystem
import com.sistemadiagnosis.core.vehicle.VehicleProfile

enum class Severity(val label: String, val advice: String) {
    LOW("Leve", "Puedes seguir circulando con normalidad. Revísalo cuando te venga bien."),
    MEDIUM("Moderada", "Puedes circular, pero revísalo pronto: puede empeorar o subir el consumo y las emisiones."),
    HIGH("Grave", "Evita circular o hazlo con suavidad hasta el taller: hay riesgo de dañar el motor o el catalizador."),
}

/** Entrada de la base de conocimiento. */
class DtcEntry(
    val title: String,
    val severity: Severity,
    val explanation: String,
    val symptoms: List<String>,
    val causes: List<String>,
    val advice: String,
)

/** Todo lo que sabemos de una avería concreta, listo para mostrar. */
data class DtcDescription(
    val dtc: Dtc,
    val title: String,
    val severity: Severity,
    val explanation: String,
    val symptoms: List<String>,
    val causes: List<String>,
    val advice: String,
    /** Nota específica del vehículo, si la hay. */
    val vehicleNote: String?,
    /** false si solo se conoce la familia del código, no el código exacto. */
    val isDocumented: Boolean,
) {
    val systemLabel: String get() = dtc.system.label
    val origin: String get() = if (dtc.isManufacturerSpecific) "Código del fabricante" else "Código estándar SAE"
}

object DtcKnowledge {
    fun describe(dtc: Dtc, vehicle: VehicleProfile? = null): DtcDescription {
        val note = vehicle?.dtcNotes?.get(dtc.code)
        val entry = DtcCatalogEs[dtc.code]
            ?: return fallback(dtc, note)
        return DtcDescription(
            dtc, entry.title, entry.severity, entry.explanation, entry.symptoms, entry.causes, entry.advice,
            note, isDocumented = true,
        )
    }

    /** Para códigos sin entrada: descripción de su familia según SAE J2012. */
    private fun fallback(dtc: Dtc, note: String?): DtcDescription {
        val area = family(dtc)
        val origin = if (dtc.isManufacturerSpecific) "un código propio del fabricante" else "un código estándar"
        return DtcDescription(
            dtc = dtc,
            title = area,
            severity = Severity.MEDIUM,
            explanation = "Es $origin del área «${area.lowercase()}». Todavía no tenemos su descripción exacta en la base de datos. " +
                "Los datos congelados y el resto de averías ayudan a acotar el origen.",
            symptoms = emptyList(),
            causes = emptyList(),
            advice = "Anota el código y las condiciones en que apareció. Si el testigo de motor parpadea o notas tirones, " +
                "pérdida de potencia o temperatura alta, detente y no fuerces el motor.",
            vehicleNote = note,
            isDocumented = false,
        )
    }

    private fun family(dtc: Dtc): String {
        val group = dtc.code[2]
        return when (dtc.system) {
            DtcSystem.POWERTRAIN -> when (dtc.code[1]) {
                '0', '2' -> when (group) {
                    '0', '1', '2' -> "Mezcla aire-combustible y emisiones"
                    '3' -> "Encendido y fallos de combustión"
                    '4' -> "Control auxiliar de emisiones"
                    '5' -> "Velocidad, ralentí y entradas auxiliares"
                    '6' -> "Centralita y circuitos de salida"
                    '7', '8', '9' -> "Transmisión"
                    'A', 'B', 'C' -> "Propulsión híbrida"
                    else -> "Motor y transmisión"
                }
                else -> "Motor y transmisión (específico del fabricante)"
            }
            DtcSystem.NETWORK -> when {
                dtc.code[1] == '0' && group == '0' -> "Red de comunicación: problema eléctrico"
                dtc.code[1] == '0' && group in '1'..'2' -> "Red de comunicación: centralita sin respuesta"
                dtc.code[1] == '0' && group == '3' -> "Red de comunicación: software incompatible"
                dtc.code[1] == '0' && group == '4' -> "Red de comunicación: datos no válidos"
                else -> "Red de comunicación"
            }
            DtcSystem.BODY -> "Carrocería"
            DtcSystem.CHASSIS -> "Chasis"
        }
    }
}
