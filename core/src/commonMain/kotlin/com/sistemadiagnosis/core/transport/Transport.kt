package com.sistemadiagnosis.core.transport

import com.sistemadiagnosis.core.DiagnosisException

/**
 * Canal de bytes hacia el adaptador (Bluetooth, WiFi, USB o simulado).
 *
 * No sabe nada de diagnosis: el driver ELM327 construye los comandos y
 * delimita las respuestas.
 */
interface Transport {
    val displayName: String
    val isOpen: Boolean

    suspend fun open()

    suspend fun write(data: ByteArray)

    /** Devuelve los bytes disponibles esperando como máximo [timeoutMs]; vacío si no llegó nada. */
    suspend fun read(timeoutMs: Long): ByteArray

    suspend fun close()
}

class TransportException(message: String, cause: Throwable? = null) : DiagnosisException(message, cause)
