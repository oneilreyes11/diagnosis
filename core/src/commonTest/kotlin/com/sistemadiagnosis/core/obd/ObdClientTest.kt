package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ElmCommandException
import com.sistemadiagnosis.core.elm.ElmError
import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ObdClientTest {
    private suspend fun connectedClient(vehicle: SimulatedVehicle): Pair<MockTransport, ObdClient> {
        val transport = MockTransport(vehicle)
        val elm = Elm327Driver(transport)
        elm.initialize()
        return transport to ObdClient(elm)
    }

    @Test
    fun `inicializa el adaptador con la secuencia AT esperada`() = runTest {
        val transport = MockTransport()
        val info = Elm327Driver(transport).initialize()

        assertEquals("ELM327 v1.5", info.version)
        assertEquals(emptyList(), info.warnings)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP0", "ATS0"), transport.receivedCommands)
    }

    @Test
    fun `lee la tension de bateria`() = runTest {
        val (_, obd) = connectedClient(SimulatedVehicle(batteryVoltage = 12.4))
        assertEquals(12.4, obd.readBatteryVoltage())
    }

    @Test
    fun `conecta y lee el VIN por CAN`() = runTest {
        val (_, obd) = connectedClient(SimulatedVehicle())
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, obd.connect())
        assertEquals("WVWZZZ1KZ6W000001", obd.readVin()?.value)
    }

    @Test
    fun `lee el VIN por KWP2000`() = runTest {
        val (_, obd) = connectedClient(
            SimulatedVehicle(protocol = ObdProtocol.ISO_14230_4_KWP_FAST, vin = "VF1BB05CF26010203"),
        )
        assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, obd.connect())
        assertEquals("VF1BB05CF26010203", obd.readVin()?.value)
    }

    @Test
    fun `vehiculo sin VIN devuelve null`() = runTest {
        val (_, obd) = connectedClient(SimulatedVehicle(protocol = ObdProtocol.ISO_9141_2, vin = null))
        obd.connect()
        assertNull(obd.readVin())
    }

    @Test
    fun `con el contacto quitado no conecta`() = runTest {
        val (_, obd) = connectedClient(SimulatedVehicle(ignitionOn = false))
        val error = assertFailsWith<ElmCommandException> { obd.connect() }
        assertEquals(ElmError.UNABLE_TO_CONNECT, error.response.error)
    }
}
