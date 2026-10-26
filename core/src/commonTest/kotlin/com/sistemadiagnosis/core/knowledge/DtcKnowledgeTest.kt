package com.sistemadiagnosis.core.knowledge

import com.sistemadiagnosis.core.obd.Dtc
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DtcKnowledgeTest {
    @Test
    fun `codigo documentado con explicacion causas y gravedad`() {
        val description = DtcKnowledge.describe(Dtc("P0302"))

        assertTrue(description.isDocumented)
        assertEquals("Fallo de encendido en el cilindro 2", description.title)
        assertEquals(Severity.HIGH, description.severity)
        assertTrue(description.causes.isNotEmpty())
        assertTrue(description.causes.all { "cilindro 2" in it || "admisión" in it })
    }

    @Test
    fun `nota especifica del vehiculo`() {
        assertNotNull(DtcKnowledge.describe(Dtc("P0302"), Vehicles.AUDI_A3_8Y_DFYA).vehicleNote)
        assertNull(DtcKnowledge.describe(Dtc("P0302")).vehicleNote)
    }

    @Test
    fun `sondas lambda numeradas en decimal`() {
        assertEquals("Sonda lambda después del catalizador (banco 1, sensor 2): sin actividad", DtcKnowledge.describe(Dtc("P0140")).title)
        assertTrue(DtcKnowledge.describe(Dtc("P0141")).title.startsWith("Calefacción"))
        assertTrue(DtcKnowledge.describe(Dtc("P0133")).title.endsWith("respuesta lenta"))
    }

    @Test
    fun `codigo desconocido cae en su familia`() {
        val description = DtcKnowledge.describe(Dtc("P0399"))
        assertFalse(description.isDocumented)
        assertEquals("Encendido y fallos de combustión", description.title)
    }

    @Test
    fun `todas las notas del vehiculo apuntan a codigos validos`() {
        Vehicles.AUDI_A3_8Y_DFYA.dtcNotes.keys.forEach { Dtc(it) }
        assertTrue(DtcCatalogEs.size > 100)
    }
}
