package com.sistemadiagnosis.core.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DtcTest {
    @Test
    fun `decodifica los dos bytes segun SAE J2012`() {
        assertEquals("P0133", Dtc.fromBytes(0x01, 0x33)?.code)
        assertEquals("P0302", Dtc.fromBytes(0x03, 0x02)?.code)
        assertEquals("C0035", Dtc.fromBytes(0x40, 0x35)?.code)
        assertEquals("B1A12", Dtc.fromBytes(0x9A, 0x12)?.code)
        assertEquals("U0100", Dtc.fromBytes(0xC1, 0x00)?.code)
        assertNull(Dtc.fromBytes(0x00, 0x00))
    }

    @Test
    fun `codifica y decodifica sin perdida`() {
        for (code in listOf("P0302", "P2181", "U0155", "C1234", "B2AFF", "P3081")) {
            val (high, low) = Dtc(code).toBytes()
            assertEquals(code, Dtc.fromBytes(high, low)?.code)
        }
    }

    @Test
    fun `distingue codigos estandar y del fabricante`() {
        assertFalse(Dtc("P0302").isManufacturerSpecific)
        assertFalse(Dtc("P2181").isManufacturerSpecific)
        assertTrue(Dtc("P1234").isManufacturerSpecific)
        assertTrue(Dtc("P3081").isManufacturerSpecific)
        assertFalse(Dtc("P3400").isManufacturerSpecific)
        assertTrue(Dtc("U1123").isManufacturerSpecific)
        assertEquals(DtcSystem.NETWORK, Dtc("U0100").system)
    }
}
