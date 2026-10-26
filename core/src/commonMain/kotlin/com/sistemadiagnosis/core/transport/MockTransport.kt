package com.sistemadiagnosis.core.transport

import kotlinx.coroutines.delay

/** Transporte en memoria contra un [Elm327Simulator]: tests y desarrollo sin coche. */
class MockTransport(
    vehicle: SimulatedVehicle = SimulatedVehicle(),
    private val latencyMs: Long = 0,
) : Transport {
    val simulator = Elm327Simulator(vehicle)
    private val pendingOutput = StringBuilder()

    /** Comandos recibidos, en orden. */
    val receivedCommands: List<String> get() = simulator.receivedCommands

    override val displayName = "simulador ELM327"
    override var isOpen = false
        private set

    override suspend fun open() {
        isOpen = true
    }

    override suspend fun close() {
        isOpen = false
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw TransportException("$displayName no está abierto")
        pendingOutput.append(simulator.feed(data.decodeToString()))
    }

    override suspend fun read(timeoutMs: Long): ByteArray {
        if (pendingOutput.isEmpty()) {
            delay(minOf(timeoutMs, 10))
            return ByteArray(0)
        }
        if (latencyMs > 0) delay(latencyMs)
        return pendingOutput.toString().encodeToByteArray().also { pendingOutput.clear() }
    }
}
