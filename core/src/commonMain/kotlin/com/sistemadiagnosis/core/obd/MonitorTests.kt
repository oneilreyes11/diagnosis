package com.sistemadiagnosis.core.obd

/**
 * Resultado de una autoprueba del motor (servicio 06, SAE J1979 / ISO 15031-5 en CAN).
 * La centralita guarda la última medida de cada prueba con sus límites: si queda
 * fuera, acabará encendiendo el testigo. Valor y límites van en bruto, con el
 * escalado que indica [unitId].
 */
data class MonitorTestResult(
    /** Monitor (OBDMID): qué sistema se prueba. */
    val mid: Int,
    /** Prueba dentro del monitor (TID); 80-FF son del fabricante. */
    val tid: Int,
    /** Unidad y escalado (UASID). */
    val unitId: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
) {
    private val scaling: UnitScaling? get() = MonitorTests.scaling(unitId)
    val signed: Boolean get() = unitId >= 0x80

    val value: Double get() = scaling?.apply(rawValue) ?: rawValue.toDouble()
    val min: Double get() = scaling?.apply(rawMin) ?: rawMin.toDouble()
    val max: Double get() = scaling?.apply(rawMax) ?: rawMax.toDouble()
    val unit: String get() = scaling?.unit ?: ""

    val systemName: String get() = MonitorTests.monitorName(mid)
    val testName: String get() = MonitorTests.testName(mid, tid)

    /** Todo a cero: tras borrar averías la prueba aún no se ha hecho. */
    val notRunYet: Boolean get() = rawValue == 0 && rawMin == 0 && rawMax == 0

    val isMisfire: Boolean get() = mid in 0xA1..0xAD
    val cylinder: Int? get() = if (mid in 0xA2..0xAD) mid - 0xA1 else null

    private val typeMin: Int get() = if (signed) -32768 else 0
    private val typeMax: Int get() = if (signed) 32767 else 0xFFFF
    private val lowerLimit: Boolean get() = rawMin > typeMin
    private val upperLimit: Boolean get() = rawMax < typeMax

    /** Tiene límites que comprobar (no es un contador o un dato informativo). */
    val hasLimits: Boolean get() = !notRunYet && !isMisfire && rawMin <= rawMax && (lowerLimit || upperLimit)

    val passed: Boolean get() = !hasLimits || rawValue in rawMin..rawMax

    /**
     * Cuánto del margen hasta el límite se ha gastado: 0 lejos, 1 en el límite.
     * null si no tiene sentido (sin límites, booleano o un solo valor válido).
     */
    val limitUsage: Double?
        get() {
            if (!hasLimits || rawMin == rawMax || unitId == 0x2E || unitId == 0x2B) return null
            return when {
                lowerLimit && upperLimit -> {
                    val half = (rawMax - rawMin) / 2.0
                    1 - minOf(rawValue - rawMin, rawMax - rawValue) / half
                }
                upperLimit -> if (rawMax <= 0) null else rawValue.toDouble() / rawMax
                else -> if (rawValue <= 0) 1.0 else rawMin.toDouble() / rawValue
            }?.coerceAtLeast(0.0)
        }
}

/** Escalado de un UASID: valor = bruto × factor + offset. */
data class UnitScaling(val factor: Double, val unit: String, val offset: Double = 0.0) {
    fun apply(raw: Int): Double = raw * factor + offset
}

object MonitorTests {
    /**
     * Respuesta CAN a 06 xx: 46 y registros de 9 bytes
     * (OBDMID, TID, UASID, valor, mínimo, máximo; los tres de 16 bits).
     */
    fun parse(message: ByteArray): List<MonitorTestResult> {
        val bytes = IntArray(message.size) { message[it].toInt() and 0xFF }
        if (bytes.isEmpty() || bytes[0] != 0x46) return emptyList()
        val results = mutableListOf<MonitorTestResult>()
        var i = 1
        while (i + 9 <= bytes.size) {
            val mid = bytes[i]
            // 06 00, 06 20…: mapa de monitores soportados, no un resultado.
            if (mid % 0x20 == 0) return results
            val unitId = bytes[i + 2]
            fun word(at: Int): Int {
                val raw = (bytes[at] shl 8) or bytes[at + 1]
                return if (unitId >= 0x80 && raw >= 0x8000) raw - 0x10000 else raw
            }
            results += MonitorTestResult(mid, bytes[i + 1], unitId, word(i + 3), word(i + 5), word(i + 7))
            i += 9
        }
        return results
    }

    fun scaling(unitId: Int): UnitScaling? = SCALINGS[unitId]

    fun monitorName(mid: Int): String = when (mid) {
        in 0x01..0x08 -> "Sonda lambda banco ${(mid - 1) / 4 + 1} sensor ${(mid - 1) % 4 + 1}"
        in 0x09..0x0C -> "Sonda lambda banco ${mid - 0x08 + 2}"
        0x21, 0x22, 0x23, 0x24 -> "Catalizador banco ${mid - 0x20}"
        in 0x31..0x34 -> "EGR banco ${mid - 0x30}"
        in 0x35..0x38 -> "Distribución variable banco ${mid - 0x34}"
        0x39 -> "EVAP (fuga de 0,150\")"
        0x3A -> "EVAP (fuga de 0,090\")"
        0x3B -> "EVAP (fuga de 0,040\")"
        0x3C -> "EVAP (fuga de 0,020\")"
        0x3D -> "EVAP (válvula de purga)"
        in 0x41..0x48 -> "Calefacción sonda banco ${(mid - 0x41) / 4 + 1} sensor ${(mid - 0x41) % 4 + 1}"
        in 0x61..0x64 -> "Catalizador calefactado banco ${mid - 0x60}"
        in 0x71..0x74 -> "Aire secundario ${mid - 0x70}"
        in 0x81..0x84 -> "Sistema de combustible banco ${mid - 0x80}"
        0x85, 0x86 -> "Presión del turbo banco ${mid - 0x84}"
        0x90, 0x91 -> "Catalizador NOx"
        0x98, 0x99 -> "Sensor de NOx"
        0xA1 -> "Fallos de encendido (total)"
        in 0xA2..0xAD -> "Fallos de encendido cilindro ${mid - 0xA1}"
        0xB0, 0xB1 -> "Filtro de partículas"
        0xB2, 0xB3 -> "Filtro de partículas de gasolina"
        else -> "Monitor ${mid.toString(16).uppercase().padStart(2, '0')}"
    }

    fun testName(mid: Int, tid: Int): String {
        if (mid in 0xA1..0xAD) return when (tid) {
            0x0B -> "Media de los últimos 10 trayectos"
            0x0C -> "Último trayecto"
            else -> "Prueba ${tid.hex()}"
        }
        if (mid in 0x01..0x0C) O2_TESTS[tid]?.let { return it }
        return if (tid >= 0x80) "Prueba del fabricante ${tid.hex()}" else "Prueba ${tid.hex()}"
    }

    /** Pruebas normalizadas de las sondas lambda (TID 01-0A). */
    private val O2_TESTS = mapOf(
        0x01 to "Umbral de rico a pobre",
        0x02 to "Umbral de pobre a rico",
        0x03 to "Tensión baja para el tiempo de cambio",
        0x04 to "Tensión alta para el tiempo de cambio",
        0x05 to "Tiempo de cambio de rico a pobre",
        0x06 to "Tiempo de cambio de pobre a rico",
        0x07 to "Tensión mínima",
        0x08 to "Tensión máxima",
        0x09 to "Tiempo entre transiciones",
        0x0A to "Periodo de la señal",
    )

    private fun Int.hex() = toString(16).uppercase().padStart(2, '0')

    /** Tabla de unidades de ISO 15031-5 (las más habituales; las demás se muestran en bruto). */
    private val SCALINGS: Map<Int, UnitScaling> = buildMap {
        put(0x01, UnitScaling(1.0, ""))
        put(0x02, UnitScaling(0.1, ""))
        put(0x03, UnitScaling(0.01, ""))
        put(0x04, UnitScaling(0.001, ""))
        put(0x05, UnitScaling(0.0000305, ""))
        put(0x06, UnitScaling(0.000305, ""))
        put(0x07, UnitScaling(0.25, "rpm"))
        put(0x08, UnitScaling(0.01, "km/h"))
        put(0x09, UnitScaling(1.0, "km/h"))
        put(0x0A, UnitScaling(0.122, "mV"))
        put(0x0B, UnitScaling(0.001, "V"))
        put(0x0C, UnitScaling(0.01, "V"))
        put(0x0D, UnitScaling(0.00390625, "mA"))
        put(0x0E, UnitScaling(0.001, "A"))
        put(0x0F, UnitScaling(0.01, "A"))
        put(0x10, UnitScaling(1.0, "ms"))
        put(0x11, UnitScaling(100.0, "ms"))
        put(0x12, UnitScaling(1.0, "s"))
        put(0x13, UnitScaling(1.0, "mΩ"))
        put(0x14, UnitScaling(1.0, "Ω"))
        put(0x15, UnitScaling(1.0, "kΩ"))
        put(0x16, UnitScaling(0.1, "°C", -40.0))
        put(0x17, UnitScaling(0.01, "kPa"))
        put(0x18, UnitScaling(0.0117, "kPa"))
        put(0x19, UnitScaling(0.079, "kPa"))
        put(0x1A, UnitScaling(1.0, "kPa"))
        put(0x1B, UnitScaling(10.0, "kPa"))
        put(0x1C, UnitScaling(0.01, "°"))
        put(0x1D, UnitScaling(0.5, "°"))
        put(0x1E, UnitScaling(0.0000305, ""))
        put(0x1F, UnitScaling(0.05, ""))
        put(0x20, UnitScaling(0.00390625, ""))
        put(0x21, UnitScaling(1.0, "mHz"))
        put(0x22, UnitScaling(1.0, "Hz"))
        put(0x23, UnitScaling(1.0, "kHz"))
        put(0x24, UnitScaling(1.0, ""))
        put(0x25, UnitScaling(1.0, "km"))
        put(0x26, UnitScaling(0.1, "mV/ms"))
        put(0x27, UnitScaling(0.01, "g/s"))
        put(0x28, UnitScaling(1.0, "g/s"))
        put(0x29, UnitScaling(0.25, "Pa/s"))
        put(0x2A, UnitScaling(0.001, "kg/h"))
        put(0x2B, UnitScaling(1.0, ""))
        put(0x2C, UnitScaling(0.01, "g/cil"))
        put(0x2D, UnitScaling(0.01, "mg/carrera"))
        put(0x2E, UnitScaling(1.0, ""))
        put(0x2F, UnitScaling(0.01, "%"))
        put(0x30, UnitScaling(0.001526, "%"))
        put(0x31, UnitScaling(0.001, "L"))
        put(0x34, UnitScaling(1.0, "min"))
        put(0x35, UnitScaling(10.0, "ms"))
        put(0x36, UnitScaling(0.01, "g"))
        put(0x37, UnitScaling(0.1, "g"))
        put(0x38, UnitScaling(1.0, "g"))
        put(0x39, UnitScaling(0.01, "%", -327.68))
        put(0x3A, UnitScaling(0.001, "g"))
        put(0x3B, UnitScaling(0.0001, "g"))
        put(0x3C, UnitScaling(0.1, "µs"))
        put(0x3D, UnitScaling(0.01, "mA"))
        put(0x3F, UnitScaling(0.01, "L"))
        put(0x40, UnitScaling(1.0, "ppm"))
        put(0x41, UnitScaling(0.01, "µA"))
        // Con signo.
        put(0x81, UnitScaling(1.0, ""))
        put(0x82, UnitScaling(0.1, ""))
        put(0x83, UnitScaling(0.01, ""))
        put(0x84, UnitScaling(0.001, ""))
        put(0x85, UnitScaling(0.0000305, ""))
        put(0x86, UnitScaling(0.000305, ""))
        put(0x8A, UnitScaling(0.122, "mV"))
        put(0x8B, UnitScaling(0.001, "V"))
        put(0x8C, UnitScaling(0.01, "V"))
        put(0x8D, UnitScaling(0.00390625, "mA"))
        put(0x8E, UnitScaling(0.001, "A"))
        put(0x90, UnitScaling(1.0, "ms"))
        put(0x96, UnitScaling(0.1, "°C"))
        put(0x9C, UnitScaling(0.01, "°"))
        put(0x9D, UnitScaling(0.5, "°"))
        put(0xA8, UnitScaling(1.0, "g/s"))
        put(0xA9, UnitScaling(0.25, "Pa/s"))
        put(0xAD, UnitScaling(0.01, "mg/carrera"))
        put(0xAE, UnitScaling(0.1, "mg/carrera"))
        put(0xAF, UnitScaling(0.01, "%"))
        put(0xB0, UnitScaling(0.003052, "%"))
        put(0xB1, UnitScaling(2.0, "mV/s"))
        put(0xFC, UnitScaling(0.01, "kPa"))
        put(0xFD, UnitScaling(0.001, "kPa"))
        put(0xFE, UnitScaling(0.25, "Pa"))
    }
}
