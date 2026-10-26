package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.util.decodeHex

/**
 * Convierte las líneas que imprime el ELM327 (cabeceras desactivadas) en mensajes.
 *
 * En CAN, una respuesta multitrama llega ya reensamblada por el ELM así:
 * ```
 * 014                 ← longitud total en hex (20 bytes)
 * 0: 49 02 01 31 47 31
 * 1: 4A 43 35 34 34 34
 * 2: ...
 * ```
 * Cada línea normal es un mensaje completo (trama simple o protocolos no CAN).
 */
object ElmMessageParser {
    private val lengthLine = Regex("^[0-9A-F]{3}$")
    private val segmentLine = Regex("^[0-9A-F]:([0-9A-F]*)$")

    fun parse(lines: List<String>): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var expectedLength = -1
        val segments = StringBuilder()

        fun flushMultiFrame() {
            if (expectedLength < 0) return
            val data = segments.toString().decodeHex()
            messages += data.copyOf(minOf(expectedLength, data.size))
            expectedLength = -1
            segments.clear()
        }

        for (line in lines.map { it.replace(" ", "").uppercase() }) {
            val segment = segmentLine.matchEntire(line)
            when {
                lengthLine.matches(line) -> {
                    flushMultiFrame()
                    expectedLength = line.toInt(16)
                }
                segment != null && expectedLength >= 0 -> segments.append(segment.groupValues[1])
                else -> {
                    flushMultiFrame()
                    messages += line.decodeHex()
                }
            }
        }
        flushMultiFrame()
        return messages
    }
}
