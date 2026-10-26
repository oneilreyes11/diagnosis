package com.sistemadiagnosis.core.obd

/** Monitores de autodiagnóstico de emisiones (preparación para la inspección / ITV). */
enum class ReadinessMonitor(val label: String, val description: String) {
    MISFIRE("Fallos de encendido", "Vigila que todos los cilindros quemen bien la mezcla."),
    FUEL_SYSTEM("Sistema de combustible", "Comprueba que la centralita mantiene la mezcla bajo control."),
    COMPONENTS("Componentes", "Sensores y actuadores relacionados con las emisiones."),
    CATALYST("Catalizador", "Mide la eficiencia del catalizador con la sonda posterior."),
    HEATED_CATALYST("Catalizador calefactado", "Calentamiento eléctrico del catalizador."),
    EVAP("Sistema EVAP", "Estanqueidad del depósito y del circuito de vapores de gasolina."),
    SECONDARY_AIR("Aire secundario", "Inyección de aire en el escape durante el arranque en frío."),
    AC_REFRIGERANT("Refrigerante A/C", "Fugas del aire acondicionado (obsoleto)."),
    OXYGEN_SENSOR("Sondas lambda", "Respuesta y señal de las sondas de oxígeno."),
    OXYGEN_SENSOR_HEATER("Calefacción de sondas", "Resistencias que calientan las sondas lambda."),
    EGR_VVT("EGR / distribución variable", "Recirculación de gases y variadores de árbol de levas."),
    NMHC_CATALYST("Catalizador NMHC", "Catalizador de hidrocarburos (diésel)."),
    NOX_AFTERTREATMENT("Tratamiento NOx / SCR", "Reducción de óxidos de nitrógeno (diésel)."),
    BOOST_PRESSURE("Presión de sobrealimentación", "Control del turbo (diésel)."),
    EXHAUST_GAS_SENSOR("Sensor de gases de escape", "Sensores de escape (diésel)."),
    PARTICULATE_FILTER("Filtro de partículas", "Filtro de partículas (diésel)."),
}

data class MonitorState(val monitor: ReadinessMonitor, val complete: Boolean)

/** Servicio 01 PID 01: testigo de motor, nº de averías y monitores (SAE J1979). */
data class MonitorStatus(
    val milOn: Boolean,
    val storedDtcCount: Int,
    val compressionIgnition: Boolean,
    /** Solo los monitores que el vehículo soporta. */
    val monitors: List<MonitorState>,
) {
    val completeCount: Int get() = monitors.count { it.complete }
    val incomplete: List<MonitorState> get() = monitors.filterNot { it.complete }
    val allComplete: Boolean get() = monitors.all { it.complete }

    companion object {
        private val SPARK = listOf(
            ReadinessMonitor.CATALYST, ReadinessMonitor.HEATED_CATALYST, ReadinessMonitor.EVAP,
            ReadinessMonitor.SECONDARY_AIR, ReadinessMonitor.AC_REFRIGERANT, ReadinessMonitor.OXYGEN_SENSOR,
            ReadinessMonitor.OXYGEN_SENSOR_HEATER, ReadinessMonitor.EGR_VVT,
        )
        private val COMPRESSION = listOf(
            ReadinessMonitor.NMHC_CATALYST, ReadinessMonitor.NOX_AFTERTREATMENT, null,
            ReadinessMonitor.BOOST_PRESSURE, null, ReadinessMonitor.EXHAUST_GAS_SENSOR,
            ReadinessMonitor.PARTICULATE_FILTER, ReadinessMonitor.EGR_VVT,
        )
        private val CONTINUOUS = listOf(ReadinessMonitor.MISFIRE, ReadinessMonitor.FUEL_SYSTEM, ReadinessMonitor.COMPONENTS)

        /** [data] son los 4 bytes A B C D de la respuesta. */
        fun decode(data: IntArray): MonitorStatus {
            require(data.size >= 4) { "PID 01 necesita 4 bytes" }
            val (a, b, c, d) = data
            val compression = b and 0x08 != 0
            val monitors = buildList {
                CONTINUOUS.forEachIndexed { bit, monitor ->
                    if (b and (1 shl bit) != 0) add(MonitorState(monitor, b and (0x10 shl bit) == 0))
                }
                (if (compression) COMPRESSION else SPARK).forEachIndexed { bit, monitor ->
                    if (monitor != null && c and (1 shl bit) != 0) add(MonitorState(monitor, d and (1 shl bit) == 0))
                }
            }
            return MonitorStatus(a and 0x80 != 0, a and 0x7F, compression, monitors)
        }
    }
}
