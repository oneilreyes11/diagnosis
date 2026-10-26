package com.sistemadiagnosis.core.transport

import com.sistemadiagnosis.core.DiagnosisSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Imita lo que hacen los transportes Swift: callbacks, trozos de 20 bytes como en BLE. */
private class FakeLink(
    private val simulator: Elm327Simulator? = Elm327Simulator(),
    private val openError: String? = null,
) : ByteLink {
    private var receiver: ByteLinkReceiver? = null
    var closed = false

    override val displayName = "enlace de prueba"

    override fun open(receiver: ByteLinkReceiver) {
        this.receiver = receiver
        if (openError != null) receiver.onClosed(openError) else receiver.onOpened()
    }

    override fun write(data: ByteArray) {
        val reply = simulator?.feed(data.decodeToString())?.encodeToByteArray() ?: return
        reply.toList().chunked(20).forEach { receiver?.onBytes(it.toByteArray()) }
    }

    override fun close() {
        closed = true
    }

    fun dropConnection(reason: String) = receiver?.onClosed(reason)
}

class CallbackTransportTest {
    @Test
    fun `escaneo completo a traves del puente de callbacks`() = runTest {
        val result = DiagnosisSession(CallbackTransport(FakeLink())).quickScan()

        assertEquals("ELM327 v1.5", result.adapter.version)
        assertEquals(12.6, result.batteryVoltage)
        assertEquals("WVWZZZ1KZ6W000001", result.vin?.value)
        assertNull(result.vehicleError)
    }

    @Test
    fun `fallo al abrir se convierte en TransportException`() = runTest {
        val error = assertFailsWith<TransportException> {
            CallbackTransport(FakeLink(openError = "Bluetooth apagado")).open()
        }
        assertTrue("Bluetooth apagado" in error.message.orEmpty())
    }

    @Test
    fun `sin respuesta al abrir hay timeout y se cierra el enlace`() = runTest {
        val silent = object : ByteLink {
            var closed = false
            override val displayName = "mudo"
            override fun open(receiver: ByteLinkReceiver) = Unit
            override fun write(data: ByteArray) = Unit
            override fun close() {
                closed = true
            }
        }
        assertFailsWith<TransportException> { CallbackTransport(silent, openTimeoutMs = 1_000).open() }
        assertTrue(silent.closed)
    }

    @Test
    fun `desconexion durante la sesion se notifica al leer`() = runTest {
        val link = FakeLink(simulator = null)
        val transport = CallbackTransport(link)
        transport.open()
        link.dropConnection("adaptador apagado")

        val error = assertFailsWith<TransportException> { transport.read(100) }
        assertTrue("adaptador apagado" in error.message.orEmpty())
    }

    @Test
    fun `cerrar cierra el enlace nativo`() = runTest {
        val link = FakeLink()
        val transport = CallbackTransport(link)
        transport.open()
        transport.close()
        assertTrue(link.closed)
    }
}
