package com.sistemadiagnosis.core.util

/** Convierte "4902AB" en bytes; ignora espacios. */
fun String.decodeHex(): ByteArray {
    val clean = replace(" ", "")
    require(clean.length % 2 == 0) { "Hex con longitud impar: '$this'" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte()
            ?: throw IllegalArgumentException("Hex no válido: '$this'")
    }
}

fun ByteArray.toHex(separator: String = ""): String = joinToString(separator) { (it.toInt() and 0xFF).toHex(2) }

/** Hex en mayúsculas con ceros a la izquierda: 0x9.toHex(2) == "09". */
fun Int.toHex(digits: Int): String = toString(16).uppercase().padStart(digits, '0')
