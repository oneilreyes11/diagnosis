package com.sistemadiagnosis.core.transport

/** Decorador que registra todo el tráfico; útil para depurar adaptadores reales. */
class LoggingTransport(
    private val inner: Transport,
    private val log: (String) -> Unit,
) : Transport by inner {
    override suspend fun write(data: ByteArray) {
        log("→ ${data.printable()}")
        inner.write(data)
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        inner.read(timeoutMs).also { if (it.isNotEmpty()) log("← ${it.printable()}") }

    private fun ByteArray.printable() = decodeToString().replace("\r", "\\r").replace("\n", "\\n")
}
