package com.sistemadiagnosis.core.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PidDecodingTest {
    private fun decode(pid: Int, vararg bytes: Int) = PidCatalog[pid]!!.decode(bytes)

    @Test
    fun `formulas SAE J1979`() {
        assertEquals(1726.0, decode(0x0C, 0x1A, 0xF8))
        assertEquals(83.0, decode(0x05, 0x7B))
        assertEquals(-40.0, decode(0x05, 0x00))
        assertEquals(100.0, decode(0x04, 0xFF))
        assertEquals(0.0, decode(0x06, 0x80))
        assertEquals(12.5, decode(0x0E, 0x99))
        assertEquals(14.2, decode(0x42, 0x37, 0x78))
        assertEquals(1.0, decode(0x34, 0x80, 0x00, 0x80, 0x00))
        assertEquals(12.34, decode(0x10, 0x04, 0xD2))
    }

    @Test
    fun `presion de turbo derivada`() {
        val derived = PidCatalog.derive(mapOf(0x0B to 181.0, 0x33 to 101.0))
        assertEquals(0.8, derived[PidCatalog.BOOST]!!, 1e-9)
        assertTrue(PidCatalog.derive(mapOf(0x0B to 181.0)).isEmpty())
    }

    @Test
    fun `monitores de preparacion de un motor de gasolina`() {
        // MIL encendido con 1 avería; continuos completos; catalizador, EVAP, sondas y calefacción; EVAP pendiente.
        val status = MonitorStatus.decode(intArrayOf(0x81, 0x07, 0x65, 0x04))

        assertTrue(status.milOn)
        assertEquals(1, status.storedDtcCount)
        assertFalse(status.compressionIgnition)
        assertEquals(7, status.monitors.size)
        assertEquals(listOf(ReadinessMonitor.EVAP), status.incomplete.map { it.monitor })
        assertEquals(6, status.completeCount)
    }

    @Test
    fun `monitores sin testigo y todos completos`() {
        val status = MonitorStatus.decode(intArrayOf(0x00, 0x07, 0x65, 0x00))
        assertFalse(status.milOn)
        assertTrue(status.allComplete)
    }
}
