package com.sistemadiagnosis.core.elm

/** Respuesta de un comando, ya sin eco, prompt ni mensajes de progreso. */
sealed interface ElmResponse {
    val lines: List<String>

    data class Ok(override val lines: List<String>) : ElmResponse

    data class Error(val error: ElmError, override val lines: List<String>) : ElmResponse

    companion object {
        fun parse(command: String, raw: String): ElmResponse {
            val compactCommand = command.replace(" ", "")
            val lines = raw
                .replace("\u0000", "")
                .split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.replace(" ", "").equals(compactCommand, ignoreCase = true) }
                .filterNot { it.isProgressMessage() }
            val error = lines.firstNotNullOfOrNull(ElmError::match)
            return if (error != null) Error(error, lines) else Ok(lines)
        }

        private fun String.isProgressMessage() =
            startsWith("SEARCHING") || (startsWith("BUS INIT") && !contains("ERROR"))
    }
}

/** Mensajes de error del ELM327 (manual ELM327 DS, sección "Messages and Their Meanings"). */
enum class ElmError(val description: String) {
    UNKNOWN_COMMAND("comando no reconocido por el adaptador"),
    NO_DATA("el vehículo no respondió a la petición"),
    UNABLE_TO_CONNECT("no se detectó ningún protocolo OBD (¿contacto puesto?)"),
    BUS_INIT_ERROR("falló la inicialización del bus"),
    BUS_ERROR("error eléctrico en el bus"),
    CAN_ERROR("error en el bus CAN (velocidad o cableado)"),
    DATA_ERROR("respuesta corrupta (checksum o trama)"),
    FEEDBACK_ERROR("el adaptador no se lee a sí mismo en el bus"),
    BUFFER_FULL("búfer del adaptador lleno"),
    STOPPED("operación interrumpida por el adaptador"),
    LOW_VOLTAGE_RESET("reinicio por baja tensión"),
    ACTIVITY_ALERT("sin actividad en el bus"),
    INTERNAL_ERROR("error interno del adaptador");

    companion object {
        private val internalError = Regex("^ERR\\d+")

        fun match(line: String): ElmError? {
            val text = line.uppercase()
            return when {
                text == "?" -> UNKNOWN_COMMAND
                text.startsWith("NO DATA") -> NO_DATA
                text.startsWith("UNABLE TO CONNECT") -> UNABLE_TO_CONNECT
                text.startsWith("BUS INIT") -> BUS_INIT_ERROR
                text.startsWith("BUS ERROR") || text.startsWith("BUS BUSY") -> BUS_ERROR
                text.startsWith("CAN ERROR") -> CAN_ERROR
                text.contains("DATA ERROR") || text.contains("RX ERROR") -> DATA_ERROR
                text.startsWith("FB ERROR") -> FEEDBACK_ERROR
                text.startsWith("BUFFER FULL") -> BUFFER_FULL
                text.startsWith("STOPPED") -> STOPPED
                text.startsWith("LV RESET") -> LOW_VOLTAGE_RESET
                text.startsWith("ACT ALERT") -> ACTIVITY_ALERT
                internalError.containsMatchIn(text) -> INTERNAL_ERROR
                else -> null
            }
        }
    }
}
