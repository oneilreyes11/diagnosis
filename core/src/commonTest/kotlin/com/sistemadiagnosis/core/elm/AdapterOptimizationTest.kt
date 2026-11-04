package com.sistemadiagnosis.core.elm

import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Optimizaciones del modo ELM327 a partir de los registros de los dos adaptadores del A3. */
class AdapterOptimizationTest {
    private val featured = listOf(0x0C, 0x05, 0x04, 0x0F, 0x0E, 0x07, 0x42, 0x34)

    private suspend fun connected(vehicle: SimulatedVehicle): Pair<MockTransport, DiagnosisSession> {
        val transport = MockTransport(vehicle)
        val session = DiagnosisSession(transport, Vehicles.AUDI_A3_8Y_DFYA)
        session.connect()
        return transport to session
    }

    @Test
    fun `prueba primero el protocolo del vehiculo`() = runTest {
        val (transport, _) = connected(SimulatedVehicle.audiA3())
        assertTrue("ATSPA6" in transport.receivedCommands)
        assertFalse("ATSP0" in transport.receivedCommands)
    }

    @Test
    fun `sin protocolo preferido vuelve a la busqueda automatica`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3().copy(supportsProtocolHint = false))
        val commands = transport.receivedCommands
        assertTrue(commands.indexOf("ATSPA6") < commands.indexOf("ATSP0"))
        assertTrue(session.connection != null)
    }

    @Test
    fun `datos en vivo piden una sola respuesta y caben en una trama`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3())
        val sample = session.liveData(featured).first()

        assertEquals(featured.toSet(), sample.values.keys)
        val live = transport.receivedCommands.filter { it.startsWith("01") && it.length % 2 == 1 }
        assertTrue(live.isNotEmpty(), "Ninguna petición con número de respuestas")
        // 01 + PIDs + "1": como mucho 3 PIDs de 1 byte o combinaciones que quepan en 7 bytes.
        assertTrue(live.all { it.length <= 9 })
    }

    @Test
    fun `adaptador sin numero de respuestas sigue leyendo todo`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3().copy(supportsResponseCount = false))
        val first = session.liveData(featured).first()
        val second = session.liveData(featured).first()

        assertEquals(featured.toSet(), first.values.keys)
        assertEquals(featured.toSet(), second.values.keys)
        // Se prueba una vez y se deja de usar.
        assertEquals(1, transport.receivedCommands.count { it.startsWith("01") && it.length % 2 == 1 })
    }

    @Test
    fun `escaneo con cabeceras ignora las tramas que se cuelan de otra centralita`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3().copy(leakyReceiveFilter = true))
        val results = session.scanModules().toList()

        assertTrue(results.all { it.error == null }, results.mapNotNull { it.error }.joinToString())
        val cluster = results.first { it.module.address == "17" }
        assertEquals("J285 Kombi", cluster.identity?.systemName)
        assertEquals("U012100", cluster.faults.single().code.displayCode)
        val commands = transport.receivedCommands
        assertTrue(commands.indexOf("ATH1") in 0 until commands.lastIndexOf("ATH0"))
        // Tras el escaneo, OBD-II sin cabeceras sigue funcionando.
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }

    @Test
    fun `el control de flujo completo se configura una vez por escaneo`() = runTest {
        val (transport, session) = connected(SimulatedVehicle.audiA3())
        session.scanModules().toList()
        val commands = transport.receivedCommands

        assertEquals(1, commands.count { it == "ATFCSD300000" })
        assertEquals(1, commands.count { it == "ATFCSM1" })
        assertEquals(Vehicles.AUDI_A3_8Y_DFYA.modules.size, commands.count { it.startsWith("ATFCSH") })
    }
}
