package com.sistemadiagnosis.core.uds

import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.transport.EngineUds
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedDtc
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Casos del escaneo real del A3 (4 nov 2026) con el adaptador "OBDII" y el de Carly. */
class RealCarLogTest {
    private suspend fun connected(vehicle: SimulatedVehicle): Pair<MockTransport, DiagnosisSession> {
        val transport = MockTransport(vehicle)
        val session = DiagnosisSession(transport, Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()
        return transport to session
    }

    @Test
    fun `el adaptador de Carly con tramas crudas conecta y lee averias`() = runTest {
        val (_, session) = connected(SimulatedVehicle.audiA3().copy(rawCanFormatting = true, batteryVoltage = 13.5))
        val info = session.connection!!

        assertEquals("WAUZZZGY5MA000001", info.vin?.value)
        assertEquals(13.5, info.batteryVoltage)
        assertTrue(0x0C in info.supportedPids)
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
        val cluster = session.scanModules().toList().first { it.module.address == "17" }
        assertEquals("J285 Kombi", cluster.identity?.systemName)
    }

    @Test
    fun `respuesta pendiente del cambio no se toma por un desfase`() = runTest {
        val base = SimulatedVehicle.audiA3()
        val gearbox = base.modules.first { it.requestId == 0x7E1 }
            .copy(pendingBeforeDtcs = true, dtcs = listOf(SimulatedDtc("P0700", 0x00, 0x09)))
        val (_, session) = connected(base.copy(modules = base.modules.map { if (it.requestId == 0x7E1) gearbox else it }))

        val results = session.scanModules().toList()
        val transmission = results.first { it.module.address == "02" }
        assertNull(transmission.error)
        assertEquals(listOf("P070000"), transmission.faults.map { it.code.displayCode })
        assertTrue(results.all { it.error == null })
    }

    @Test
    fun `motor que solo admite UDS en sesion extendida`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3().copy(engineUds = EngineUds.EXTENDED_SESSION))
        val engine = session.scanModules().toList().first { it.module.address == "01" }

        assertNull(engine.error)
        assertEquals("R4 1,5L TSI", engine.identity?.systemName)
        assertEquals(listOf("P030200", "P218100"), engine.faults.map { it.code.displayCode })
        val commands = transport.receivedCommands
        assertTrue(commands.indexOf("1003") in 0 until commands.indexOf("1001"))
    }

    @Test
    fun `motor que solo admite OBD-II lo explica sin romper el escaneo`() = runTest {
        val (_, session) = connected(SimulatedVehicle.audiA3().copy(engineUds = EngineUds.OBD_ONLY))
        val results = session.scanModules().toList()
        val engine = results.first { it.module.address == "01" }

        assertTrue(engine.present)
        assertNotNull(engine.error)
        assertTrue("OBD-II" in engine.error!!)
        assertEquals(1, results.first { it.module.address == "09" }.faults.size)
        // El OBD-II del motor sigue funcionando después.
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }

    @Test
    fun `el escaneo usa un tiempo de espera fijo y largo y luego lo restaura`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3())
        session.scanModules().toList()
        val commands = transport.receivedCommands

        val start = commands.indexOf("ATAT0")
        assertTrue(start >= 0 && commands[start + 1] == "ATSTFF")
        assertTrue(commands.lastIndexOf("ATST32") > commands.indexOf("ATSTFF"))
        assertTrue(commands.lastIndexOf("ATAT1") > start)
    }
}
