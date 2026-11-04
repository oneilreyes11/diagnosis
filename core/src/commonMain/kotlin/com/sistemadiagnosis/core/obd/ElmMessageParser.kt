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
 *
 * Algunos adaptadores (p.ej. el de Carly) imprimen las tramas CAN en crudo, con el
 * byte de control ISO-TP y el relleno: `06 41 00 BE 3E A8 13 AA`, o `10 14 …`,
 * `21 …`, `22 …` en multitrama. Se reconocen porque ese primer byte (00-2F) nunca
 * es el de una respuesta real (40 o más, o 7F) y se reensamblan aquí.
 */
object ElmMessageParser {
    private val lengthLine = Regex("^[0-9A-F]{3}$")
    private val segmentLine = Regex("^[0-9A-F]:([0-9A-F]*)$")

    fun parse(lines: List<String>): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var expectedLength = -1
        val segments = StringBuilder()
        var rawExpected = -1
        val rawData = mutableListOf<Byte>()

        fun flushMultiFrame() {
            if (expectedLength < 0) return
            val data = segments.toString().decodeHex()
            messages += data.copyOf(minOf(expectedLength, data.size))
            expectedLength = -1
            segments.clear()
        }

        fun flushRaw() {
            if (rawExpected < 0) return
            messages += rawData.take(rawExpected).toByteArray()
            rawExpected = -1
            rawData.clear()
        }

        for (line in lines.map { it.replace(" ", "").uppercase() }) {
            val segment = segmentLine.matchEntire(line)
            when {
                lengthLine.matches(line) -> {
                    flushMultiFrame()
                    flushRaw()
                    expectedLength = line.toInt(16)
                }
                segment != null && expectedLength >= 0 -> segments.append(segment.groupValues[1])
                else -> {
                    flushMultiFrame()
                    val bytes = line.decodeHex()
                    val pci = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                    when {
                        // Trama simple cruda: 0L + L bytes de datos + relleno.
                        pci in 0x01..0x07 && bytes.size > pci -> {
                            flushRaw()
                            messages += bytes.copyOfRange(1, 1 + pci)
                        }
                        // Primera trama cruda: 1L LL + datos; la longitud total va en 12 bits.
                        pci in 0x10..0x1F && bytes.size >= 2 -> {
                            flushRaw()
                            rawExpected = ((pci and 0x0F) shl 8) or (bytes[1].toInt() and 0xFF)
                            rawData += bytes.drop(2)
                        }
                        // Trama consecutiva cruda: 2N + datos.
                        pci in 0x20..0x2F && rawExpected >= 0 -> {
                            rawData += bytes.drop(1)
                            if (rawData.size >= rawExpected) flushRaw()
                        }
                        else -> {
                            flushRaw()
                            messages += bytes
                        }
                    }
                }
            }
        }
        flushMultiFrame()
        flushRaw()
        return messages
    }
}
