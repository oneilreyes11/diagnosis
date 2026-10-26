package com.sistemadiagnosis.core.obd

/** Número de bastidor (ISO 3779). */
data class Vin(val value: String) {
    init {
        require(PATTERN.matches(value)) { "VIN no válido: '$value'" }
    }

    /** World Manufacturer Identifier: fabricante y región. */
    val wmi: String get() = value.substring(0, 3)

    /** Vehicle Descriptor Section: modelo, motor, carrocería (según fabricante). */
    val vds: String get() = value.substring(3, 9)

    /** Vehicle Identifier Section: año, planta y número de serie. */
    val vis: String get() = value.substring(9)

    /**
     * Dígito de control (posición 9). Obligatorio en Norteamérica; los fabricantes
     * europeos suelen poner otra cosa, así que un valor falso no invalida el VIN.
     */
    val checkDigitValid: Boolean
        get() {
            val sum = value.withIndex().sumOf { (i, c) -> transliterate(c) * WEIGHTS[i] }
            val expected = (sum % 11).let { if (it == 10) 'X' else '0' + it }
            return value[8] == expected
        }

    /**
     * Año de modelo (posición 10). Las letras se repiten cada 30 años; se toma el
     * ciclo 2010-2030 y las cifras como 2001-2009.
     */
    val modelYear: Int?
        get() {
            val code = value[9]
            val letter = YEAR_LETTERS.indexOf(code)
            return when {
                letter >= 0 -> 2010 + letter
                code in '1'..'9' -> 2000 + code.digitToInt()
                else -> null
            }
        }

    override fun toString() = value

    companion object {
        private val PATTERN = Regex("^[A-HJ-NPR-Z0-9]{17}$")
        private const val YEAR_LETTERS = "ABCDEFGHJKLMNPRSTVWXY"
        private val WEIGHTS =intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        /** Valor de cada letra A..Z; I, O y Q no se usan en un VIN. */
        private const val LETTER_VALUES = "12345678_12345_7_923456789"

        fun parseOrNull(text: String): Vin? = text.trim().uppercase().takeIf(PATTERN::matches)?.let(::Vin)

        private fun transliterate(c: Char): Int =
            if (c.isDigit()) c.digitToInt() else LETTER_VALUES[c - 'A'].digitToInt()

        /**
         * Extrae el VIN de las respuestas al servicio 09 PID 02.
         *
         * - CAN: un mensaje `49 02 01` + 17 bytes ASCII.
         * - J1850 / ISO 9141 / KWP: cinco mensajes `49 02 <n>` + 4 bytes, el primero
         *   con tres bytes de relleno a cero.
         *
         * Si responden varias centralitas, devuelve el primer VIN válido.
         */
        fun fromService09(messages: List<ByteArray>): Vin? {
            val responses = messages.filter { it.size >= 3 && it[0] == 0x49.toByte() && it[1] == 0x02.toByte() }
            if (responses.isEmpty()) return null

            val canCandidates = responses.filter { it.size > 7 }.map { it.copyOfRange(3, it.size) }
            val legacyCandidate = responses.filter { it.size == 7 }
                .sortedBy { it[2].toInt() and 0xFF }
                .fold(ByteArray(0)) { acc, frame -> acc + frame.copyOfRange(3, 7) }

            return (canCandidates + listOf(legacyCandidate))
                .firstNotNullOfOrNull { bytes -> parseOrNull(bytes.toAsciiText()) }
        }

        private fun ByteArray.toAsciiText() =
            filter { it.toInt() in 0x20..0x7E }.map { it.toInt().toChar() }.joinToString("")
    }
}
