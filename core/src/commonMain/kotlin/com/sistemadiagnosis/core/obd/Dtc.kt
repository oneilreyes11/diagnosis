package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.util.toHex

/** Sistema al que pertenece un código de avería (primera letra). */
enum class DtcSystem(val letter: Char, val label: String) {
    POWERTRAIN('P', "Motor y transmisión"),
    CHASSIS('C', "Chasis"),
    BODY('B', "Carrocería"),
    NETWORK('U', "Red de comunicación"),
}

/** Dónde está guardada la avería en la centralita (servicios OBD 03, 07 y 0A). */
enum class DtcKind(val service: Int, val label: String, val description: String) {
    STORED(0x03, "Confirmada", "La centralita ha confirmado el fallo y lo tiene memorizado."),
    PENDING(0x07, "Pendiente", "Detectado en el ciclo de conducción actual o el último; aún no confirmado."),
    PERMANENT(0x0A, "Permanente", "No se borra con un equipo: desaparece sola cuando la centralita comprueba que el fallo se ha corregido."),
}

/** Código de avería SAE J2012, p.ej. P0301. */
data class Dtc(val code: String) {
    init {
        require(PATTERN.matches(code)) { "Código de avería no válido: '$code'" }
    }

    val system: DtcSystem get() = DtcSystem.entries.first { it.letter == code[0] }

    /** Códigos definidos por el fabricante (P1xxx, P30xx-P33xx, B1/B2, C1/C2, U1/U2) frente a los estándar SAE. */
    val isManufacturerSpecific: Boolean
        get() = when (code[0]) {
            'P' -> code[1] == '1' || (code[1] == '3' && code[2] in '0'..'3')
            else -> code[1] == '1' || code[1] == '2'
        }

    /** Los dos bytes con los que viaja por OBD. */
    fun toBytes(): Pair<Int, Int> {
        val high = (system.ordinal shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        return high to code.substring(3).toInt(16)
    }

    override fun toString() = code

    companion object {
        private val PATTERN = Regex("^[PCBU][0-3][0-9A-F]{3}$")

        /** Decodifica dos bytes; 00 00 es relleno y no es una avería. */
        fun fromBytes(high: Int, low: Int): Dtc? {
            if (high == 0 && low == 0) return null
            val letter = "PCBU"[(high shr 6) and 0x3]
            return Dtc("$letter${(high shr 4) and 0x3}${(high and 0xF).toHex(1)}${low.toHex(2)}")
        }
    }
}
