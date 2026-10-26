package com.sistemadiagnosis.core.transport

import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.elm.ObdProtocol
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** TCP real contra el emulador: el mismo camino que usa un adaptador WiFi. */
class WifiTransportTest {
    @Test
    fun `escaneo por TCP contra el emulador`() = runBlocking {
        val vehicle = SimulatedVehicle(protocol = ObdProtocol.ISO_14230_4_KWP_FAST, vin = "VF1BB05CF26010203")
        Elm327EmulatorServer(vehicle, port = 0).start().use { server ->
            val result = DiagnosisSession(WifiTransport("127.0.0.1", server.port)).quickScan()

            assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, result.obdProtocol)
            assertEquals("VF1BB05CF26010203", result.vin?.value)
        }
    }

    @Test
    fun `puerto cerrado da TransportException`() = runBlocking {
        val port = Elm327EmulatorServer(port = 0).use { it.port }
        assertFailsWith<TransportException> { WifiTransport("127.0.0.1", port, connectTimeoutMs = 1_000).open() }
        Unit
    }
}
