package com.sistemadiagnosis.core.uds

import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.util.decodeHex
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleScanTest {
    @Test
    fun `informe de averias UDS con tipo de fallo y estado`() {
        val dtcs = UdsDtc.parseReport("5902FF".decodeHex() + "C1210028".decodeHex() + "90B51309".decodeHex())

        assertEquals(listOf("U012100", "B10B513"), dtcs.map { it.displayCode })
        assertEquals(UdsDtcState.INTERMITTENT, dtcs[0].state)
        assertEquals(UdsDtcState.ACTIVE, dtcs[1].state)
        assertEquals("Circuito abierto", dtcs[1].failureTypeDescription)
        assertNull(dtcs[0].failureTypeDescription)
        assertTrue(UdsDtc.parseReport("5902FF".decodeHex()).isEmpty())
    }

    private suspend fun connectedA3(): Pair<MockTransport, DiagnosisSession> {
        val transport = MockTransport(SimulatedVehicle.audiA3())
        val session = DiagnosisSession(transport, Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()
        return transport to session
    }

    @Test
    fun `escaneo completo encuentra las centralitas montadas y sus averias`() = runTest {
        val (_, session) = connectedA3()
        val results = session.scanModules().toList()

        assertEquals(Vehicles.AUDI_A3_8Y_DFYA.modules.size, results.size)
        val absent = results.filterNot { it.present }.map { it.module.address }
        assertEquals(listOf("10", "13"), absent)

        val engine = results.first { it.module.address == "01" }
        assertEquals(listOf("P030200", "P218100"), engine.faults.map { it.code.displayCode })
        assertEquals("R4 1,5L TSI", engine.identity?.systemName)

        val cluster = results.first { it.module.address == "17" }
        assertEquals("J285 Kombi", cluster.identity?.systemName)
        assertEquals("8Y0920320", cluster.identity?.partNumber)
        assertEquals(UdsDtcState.INTERMITTENT, cluster.faults.single().code.state)
        assertEquals("Sin comunicación con la centralita del ABS", cluster.faults.single().description.title)

        // Código sin ficha en la electrónica central: gravedad según la centralita, tipo de fallo explicado.
        val bcm = results.first { it.module.address == "09" }.faults.single()
        assertEquals(Severity.MEDIUM, bcm.severity)
        assertEquals("Circuito abierto", bcm.code.failureTypeDescription)
        assertTrue(results.all { it.error == null })
    }

    @Test
    fun `tras el escaneo el adaptador vuelve al motor y los datos en vivo siguen funcionando`() = runTest {
        val (transport, session) = connectedA3()
        session.scanModules().toList()

        assertTrue(transport.receivedCommands.takeLast(3).any { it == "ATSH7E0" })
        assertTrue("ATFCSM1" in transport.receivedCommands && "ATFCSM0" in transport.receivedCommands)
        val sample = session.liveData(listOf(0x0C, 0x05)).first()
        assertTrue(0x0C in sample.values && 0x05 in sample.values)
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }

    @Test
    fun `borrar las averias de una centralita`() = runTest {
        val (_, session) = connectedA3()
        val cluster = Vehicles.AUDI_A3_8Y_DFYA.modules.first { it.address == "17" }
        session.clearModuleFaults(cluster)

        val results = session.scanModules().toList()
        assertTrue(results.first { it.module.address == "17" }.faults.isEmpty())
        assertFalse(results.first { it.module.address == "09" }.faults.isEmpty())
    }
}
