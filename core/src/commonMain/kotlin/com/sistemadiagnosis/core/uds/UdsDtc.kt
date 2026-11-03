package com.sistemadiagnosis.core.uds

import com.sistemadiagnosis.core.obd.Dtc
import com.sistemadiagnosis.core.util.toHex

/** Situación de una avería según su byte de estado (ISO 14229-1). */
enum class UdsDtcState(val label: String, val description: String) {
    ACTIVE("Activa", "El fallo está presente ahora mismo."),
    INTERMITTENT("Intermitente", "Se detectó y se confirmó, pero ahora mismo no está presente."),
    PENDING("Pendiente", "Detectada en el ciclo actual o el último; aún no confirmada."),
    STORED("Registrada", "Guardada en la memoria de averías de la centralita."),
}

/**
 * Avería leída por UDS (servicio 19 02): código SAE de 2 bytes, tipo de fallo
 * (Failure Type Byte, SAE J2012-DA) y byte de estado.
 */
data class UdsDtc(val dtc: Dtc, val failureType: Int, val status: Int) {
    /** Como lo muestran los equipos de taller: U012100. */
    val displayCode: String get() = dtc.code + failureType.toHex(2)

    val activeNow: Boolean get() = status and 0x01 != 0
    val pending: Boolean get() = status and 0x04 != 0
    val confirmed: Boolean get() = status and 0x08 != 0

    /** La centralita pide encender un testigo en el cuadro. */
    val warningLamp: Boolean get() = status and 0x80 != 0

    val state: UdsDtcState
        get() = when {
            activeNow -> UdsDtcState.ACTIVE
            confirmed -> UdsDtcState.INTERMITTENT
            pending -> UdsDtcState.PENDING
            else -> UdsDtcState.STORED
        }

    /** Qué le pasa exactamente al componente (circuito abierto, sin señal…); null si no se indica. */
    val failureTypeDescription: String? get() = FailureTypes.describe(failureType)

    companion object {
        /** Respuesta positiva a 19 02: 59 02 <máscara disponible> y registros de 4 bytes. */
        fun parseReport(response: ByteArray): List<UdsDtc> {
            val bytes = IntArray(response.size) { response[it].toInt() and 0xFF }
            return (3 until bytes.size - 3 step 4).mapNotNull { i ->
                Dtc.fromBytes(bytes[i], bytes[i + 1])?.let { UdsDtc(it, bytes[i + 2], bytes[i + 3]) }
            }
        }
    }
}

/** Tipos de fallo (Failure Type Byte) de SAE J2012-DA, en lenguaje claro. */
object FailureTypes {
    private val descriptions = mapOf(
        0x01 to "Fallo eléctrico",
        0x02 to "Fallo de la señal",
        0x03 to "Fallo de frecuencia o PWM de la señal",
        0x04 to "Fallo interno del sistema",
        0x05 to "Fallo de programación",
        0x06 to "Fallo del algoritmo",
        0x07 to "Fallo mecánico",
        0x08 to "Fallo en los mensajes del bus",
        0x09 to "Fallo del componente",
        0x11 to "Cortocircuito a masa",
        0x12 to "Cortocircuito a positivo",
        0x13 to "Circuito abierto",
        0x14 to "Cortocircuito a masa o circuito abierto",
        0x15 to "Cortocircuito a positivo o circuito abierto",
        0x16 to "Tensión del circuito por debajo del límite",
        0x17 to "Tensión del circuito por encima del límite",
        0x18 to "Corriente del circuito por debajo del límite",
        0x19 to "Corriente del circuito por encima del límite",
        0x1A to "Resistencia del circuito por debajo del límite",
        0x1B to "Resistencia del circuito por encima del límite",
        0x1C to "Tensión del circuito fuera de rango",
        0x1D to "Corriente del circuito fuera de rango",
        0x1E to "Resistencia del circuito fuera de rango",
        0x1F to "Circuito intermitente",
        0x21 to "Amplitud de la señal por debajo del mínimo",
        0x22 to "Amplitud de la señal por encima del máximo",
        0x23 to "Señal bloqueada en nivel bajo",
        0x24 to "Señal bloqueada en nivel alto",
        0x25 to "Forma de la señal incorrecta",
        0x26 to "La señal cambia demasiado despacio",
        0x27 to "La señal cambia demasiado deprisa",
        0x28 to "Desviación de la señal fuera de rango",
        0x29 to "Señal no válida",
        0x2F to "Señal errática",
        0x31 to "Sin señal",
        0x36 to "Frecuencia de la señal demasiado baja",
        0x37 to "Frecuencia de la señal demasiado alta",
        0x41 to "Error de suma de comprobación",
        0x42 to "Error de memoria",
        0x44 to "Error de la memoria de datos",
        0x45 to "Error de la memoria de programa",
        0x46 to "Error de la memoria de calibración o parámetros",
        0x47 to "Fallo del supervisor (watchdog)",
        0x49 to "Fallo electrónico interno",
        0x4A to "Componente incorrecto instalado",
        0x4B to "Temperatura excesiva",
        0x51 to "Sin programar",
        0x52 to "Sin activar",
        0x53 to "Desactivado",
        0x54 to "Falta la calibración",
        0x55 to "Sin configurar (codificar)",
        0x56 to "Configuración no válida",
        0x57 to "Software no válido o incompatible",
        0x61 to "Fallo al calcular la señal",
        0x62 to "Las señales comparadas no coinciden",
        0x63 to "Tiempo de respuesta excedido",
        0x64 to "Señal no plausible",
        0x67 to "Señal incorrecta tras un evento",
        0x71 to "Actuador bloqueado",
        0x72 to "Actuador bloqueado abierto",
        0x73 to "Actuador bloqueado cerrado",
        0x74 to "Actuador que patina",
        0x76 to "Posición de montaje incorrecta",
        0x77 to "No alcanza la posición ordenada",
        0x78 to "Alineación o ajuste incorrecto",
        0x79 to "Fallo de la unión mecánica",
        0x7A to "Fuga de líquido o fallo de estanqueidad",
        0x7B to "Nivel de líquido bajo",
        0x81 to "Datos recibidos por el bus no válidos",
        0x82 to "Contador de secuencia de mensajes incorrecto",
        0x83 to "Protección del mensaje incorrecta",
        0x84 to "Señal por debajo del rango permitido",
        0x85 to "Señal por encima del rango permitido",
        0x86 to "Señal recibida no válida",
        0x87 to "Mensaje no recibido",
        0x88 to "Bus desconectado (bus off)",
        0x92 to "Funcionamiento incorrecto o rendimiento bajo",
        0x93 to "Sin funcionamiento",
        0x94 to "Funcionamiento inesperado",
        0x95 to "Montaje incorrecto",
        0x96 to "Fallo interno del componente",
        0x97 to "Funcionamiento obstruido o bloqueado",
        0x98 to "Temperatura excesiva del componente",
    )

    fun describe(failureType: Int): String? = descriptions[failureType]
}
