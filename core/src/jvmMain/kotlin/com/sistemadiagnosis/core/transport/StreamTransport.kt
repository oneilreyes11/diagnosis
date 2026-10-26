package com.sistemadiagnosis.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Base para transportes que exponen un par de streams (socket TCP, puerto serie,
 * socket RFCOMM de Android).
 *
 * La lectura sondea [InputStream.available] porque no todos esos streams admiten
 * timeout de lectura (los sockets Bluetooth de Android bloquean indefinidamente).
 */
abstract class StreamTransport : Transport {
    private var streams: Pair<InputStream, OutputStream>? = null

    override val isOpen: Boolean get() = streams != null

    /** Abre la conexión de forma bloqueante; se ejecuta en [Dispatchers.IO]. */
    protected abstract fun connect(): Pair<InputStream, OutputStream>

    protected abstract fun disconnect()

    override suspend fun open() {
        if (streams != null) return
        withContext(Dispatchers.IO) {
            streams = try {
                connect()
            } catch (e: IOException) {
                throw TransportException("No se pudo abrir $displayName: ${e.message}", e)
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        val output = requireStreams().second
        withContext(Dispatchers.IO) {
            try {
                output.write(data)
                output.flush()
            } catch (e: IOException) {
                throw TransportException("Error escribiendo en $displayName: ${e.message}", e)
            }
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        val input = requireStreams().first
        return withContext(Dispatchers.IO) { pollRead(input, timeoutMs) }
    }

    private suspend fun pollRead(input: InputStream, timeoutMs: Long): ByteArray {
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
        while (true) {
            try {
                val available = input.available()
                if (available > 0) {
                    val buffer = ByteArray(available)
                    val count = input.read(buffer)
                    if (count < 0) throw TransportException("$displayName cerró la conexión")
                    return buffer.copyOf(count)
                }
            } catch (e: IOException) {
                throw TransportException("Error leyendo de $displayName: ${e.message}", e)
            }
            if (deadline.hasPassedNow()) return ByteArray(0)
            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun close() {
        if (streams == null) return
        streams = null
        withContext(Dispatchers.IO) { runCatching { disconnect() } }
    }

    private fun requireStreams() = streams ?: throw TransportException("$displayName no está abierto")

    private companion object {
        const val POLL_INTERVAL_MS = 5L
    }
}
