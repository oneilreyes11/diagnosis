package com.sistemadiagnosis.core.elm

import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.obd.ObdClient
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Casos vistos en el registro real del A3 (ELM327 v2.1 BLE): respuestas que
 * llegan tarde y se cruzan con la petición siguiente, y un clon que no contesta
 * a 6 PIDs en una petición pero sí a 3.
 */
class AdapterSyncTest {
    @Test
    fun `una respuesta tardia no se toma por la del comando siguiente`() = runTest {
        val transport = MockTransport(SimulatedVehicle.audiA3())
        val elm = Elm327Driver(transport)
        elm.initialize()
        transport.injectLateResponse("410C0000\r\r>")

        val lines = elm.sendOrThrow("03")
        assertTrue(lines.single().startsWith("43"), "Respuesta recibida: $lines")
    }

    @Test
    fun `cancelar un comando a medias no desfasa los siguientes`() = runTest {
        val transport = MockTransport(SimulatedVehicle.audiA3(), latencyMs = 50)
        val elm = Elm327Driver(transport)
        elm.initialize()

        val cancelled = launch { elm.send("0105") }
        advanceTimeBy(10)
        cancelled.cancel()

        val lines = elm.sendOrThrow("0104")
        assertTrue(lines.single().startsWith("4104"), "Respuesta recibida: $lines")
    }

    @Test
    fun `clon que solo admite 3 PIDs por peticion lee todos los datos`() = runTest {
        val transport = MockTransport(SimulatedVehicle.audiA3().copy(maxPidsPerRequest = 3))
        val elm = Elm327Driver(transport)
        elm.initialize()
        val obd = ObdClient(elm)
        obd.connect()

        val pids = listOf(0x0C, 0x05, 0x04, 0x0F, 0x0E, 0x07)
        assertEquals(pids.toSet(), obd.readPids(pids).keys)
        assertEquals(pids.toSet(), obd.readPids(pids).keys)
        // Tras el primer fallo con 6 sigue con peticiones de 3, sin volver a probar 6.
        val requests = transport.receivedCommands.filter { it.startsWith("01") && it.length > 4 }
        assertEquals(1, requests.count { it.length == 14 })
        assertTrue(requests.count { it.length == 8 } >= 4)
    }

    @Test
    fun `salir de en vivo a medias no estropea la diagnosis`() = runTest {
        val transport = MockTransport(SimulatedVehicle.audiA3().copy(maxPidsPerRequest = 3), latencyMs = 40)
        val session = DiagnosisSession(transport, Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()

        val live = launch { session.liveData(listOf(0x0C, 0x05, 0x04, 0x0F)).collect {} }
        advanceTimeBy(150)
        live.cancel()

        val report = session.runDiagnostics()
        assertEquals(listOf("P0302", "P2181"), report.faults.map { it.dtc.code })
        assertNotNull(report.monitorStatus)
    }
}
