package com.sistemadiagnosis.core

import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.obd.ReadinessMonitor
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Flujo completo de la fase 2 contra el simulador del A3. */
class DiagnosticFlowTest {
    private suspend fun connectedA3(): Pair<MockTransport, DiagnosisSession> {
        val transport = MockTransport(SimulatedVehicle.audiA3())
        val session = DiagnosisSession(transport, Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()
        return transport to session
    }

    @Test
    fun `conecta reconoce el A3 y se dirige solo al motor`() = runTest {
        val (transport, session) = connectedA3()
        val connection = session.connection!!

        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, connection.obdProtocol)
        assertEquals(true, connection.vehicleMatch)
        assertEquals(2021, connection.vin?.modelYear)
        assertTrue(0x0C in connection.supportedPids && 0x5C in connection.supportedPids)
        assertTrue("ATSH7E0" in transport.receivedCommands)
    }

    @Test
    fun `diagnostico completo con averias explicadas y datos congelados`() = runTest {
        val (_, session) = connectedA3()
        val report = session.runDiagnostics()

        assertEquals(listOf("P0302", "P2181"), report.faults.map { it.dtc.code })
        assertEquals(setOf(DtcKind.STORED, DtcKind.PERMANENT), report.faults[0].kinds)
        assertEquals(Severity.HIGH, report.highestSeverity)
        assertNotNull(report.faults[0].description.vehicleNote)
        assertEquals(1, report.confirmedCount)

        val status = report.monitorStatus!!
        assertTrue(status.milOn)
        assertEquals(listOf(ReadinessMonitor.EVAP), status.incomplete.map { it.monitor })

        val freeze = report.freezeFrame!!
        assertEquals("P0302", freeze.trigger.code)
        assertEquals(1840.0, freeze.values[0x0C])
        assertEquals(41.0, freeze.values[0x05])
        assertEquals(1240.0, report.tripData[0x31])
    }

    @Test
    fun `borrar averias deja solo la permanente y reinicia los monitores`() = runTest {
        val (_, session) = connectedA3()
        session.clearFaults()
        val report = session.runDiagnostics()

        assertEquals(listOf("P0302"), report.faults.map { it.dtc.code })
        assertEquals(setOf(DtcKind.PERMANENT), report.faults[0].kinds)
        assertFalse(report.monitorStatus!!.milOn)
        assertFalse(report.monitorStatus!!.allComplete)
        assertNull(report.freezeFrame)
    }

    @Test
    fun `datos en vivo con varios PIDs por peticion y turbo derivado`() = runTest {
        val (transport, session) = connectedA3()
        val sample = session.liveData(listOf(0x0C, 0x05, PidCatalog.BOOST, 0x42)).first()

        assertTrue(sample.values.getValue(0x0C) in 600.0..4000.0)
        assertTrue(sample.values.getValue(0x42) in 13.0..15.0)
        assertTrue(sample.values.getValue(PidCatalog.BOOST) in -1.0..1.5)
        assertTrue(sample.updatesPerSecond > 0)
        assertTrue(transport.receivedCommands.any { it.startsWith("01") && it.length > 6 })
    }

    @Test
    fun `coche sin averias da un informe limpio`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle()), Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()
        val report = session.runDiagnostics()

        assertTrue(report.faults.isEmpty())
        assertNull(report.highestSeverity)
        assertEquals(false, session.connection?.vehicleMatch)
    }
}
