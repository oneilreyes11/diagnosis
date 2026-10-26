package com.sistemadiagnosis.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Conexión nativa basada en callbacks, implementable desde Swift (Network.framework,
 * CoreBluetooth) u otras plataformas. Sin funciones suspend: Swift no puede
 * implementarlas.
 */
interface ByteLink {
    val displayName: String

    /** Inicia la conexión y avisa a [receiver] del resultado y de los datos recibidos. */
    fun open(receiver: ByteLinkReceiver)

    fun write(data: ByteArray)

    fun close()
}

/** Eventos de un [ByteLink]; se pueden llamar desde cualquier hilo. */
interface ByteLinkReceiver {
    fun onOpened()

    fun onBytes(data: ByteArray)

    /** Fallo al abrir o desconexión. [error] es null si se cerró sin error. */
    fun onClosed(error: String?)
}

/** Adapta un [ByteLink] de callbacks al [Transport] que usa el driver ELM327. */
class CallbackTransport(
    private val link: ByteLink,
    private val openTimeoutMs: Long = 15_000,
) : Transport {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var disconnectReason: String? = null

    override val displayName: String get() = link.displayName

    override var isOpen = false
        private set

    override suspend fun open() {
        if (isOpen) return
        disconnectReason = null
        val opened = CompletableDeferred<Unit>()
        link.open(object : ByteLinkReceiver {
            override fun onOpened() {
                if (opened.complete(Unit)) isOpen = true
            }

            override fun onBytes(data: ByteArray) {
                incoming.trySend(data)
            }

            override fun onClosed(error: String?) {
                val reason = error ?: "conexión cerrada"
                isOpen = false
                disconnectReason = reason
                opened.completeExceptionally(TransportException("No se pudo abrir $displayName: $reason"))
            }
        })
        withTimeoutOrNull(openTimeoutMs) { opened.await() } ?: run {
            link.close()
            throw TransportException("$displayName no respondió en ${openTimeoutMs / 1000} s")
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw disconnected()
        link.write(data)
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        incoming.tryReceive().getOrNull()?.let { return it }
        if (!isOpen) throw disconnected()
        return withTimeoutOrNull(timeoutMs) { incoming.receive() } ?: ByteArray(0)
    }

    override suspend fun close() {
        if (!isOpen) return
        isOpen = false
        link.close()
        while (incoming.tryReceive().isSuccess) {
            // Descarta datos pendientes para que una reapertura empiece limpia.
        }
    }

    private fun disconnected() =
        TransportException("$displayName desconectado" + (disconnectReason?.let { ": $it" } ?: ""))
}
