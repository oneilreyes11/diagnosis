package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.util.decodeHex
import com.sistemadiagnosis.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VinTest {
    @Test
    fun `digito de control segun ISO 3779`() {
        // Ejemplo de referencia de la norma norteamericana: dígito de control X.
        assertTrue(Vin("1M8GDM9AXKP042788").checkDigitValid)
        assertFalse(Vin("1M8GDM9A1KP042788").checkDigitValid)
        // Europeo: posición 9 sin dígito de control.
        assertFalse(Vin("WVWZZZ1KZ6W000001").checkDigitValid)
    }

    @Test
    fun `secciones WMI VDS VIS`() {
        val vin = Vin("WVWZZZ1KZ6W000001")
        assertEquals("WVW", vin.wmi)
        assertEquals("ZZZ1KZ", vin.vds)
        assertEquals("6W000001", vin.vis)
    }

    @Test
    fun `rechaza letras I O Q y longitudes incorrectas`() {
        assertNull(Vin.parseOrNull("WVWZZZ1KZ6WO00001"))
        assertNull(Vin.parseOrNull("WVWZZZ1KZ6W00001"))
        assertEquals("WVWZZZ1KZ6W000001", Vin.parseOrNull(" wvwzzz1kz6w000001 ")?.value)
    }

    @Test
    fun `VIN desde respuesta CAN`() {
        val message = ("490201" + "WVWZZZ1KZ6W000001".encodeToByteArray().toHex()).decodeHex()
        assertEquals("WVWZZZ1KZ6W000001", Vin.fromService09(listOf(message))?.value)
    }

    @Test
    fun `VIN desde respuestas ISO 9141 en cinco tramas desordenadas`() {
        val data = ByteArray(3) + "VF1BB05CF26010203".encodeToByteArray()
        val frames = (0 until 5).map { i -> byteArrayOf(0x49, 0x02, (i + 1).toByte()) + data.copyOfRange(i * 4, i * 4 + 4) }
        assertEquals("VF1BB05CF26010203", Vin.fromService09(frames.reversed())?.value)
    }

    @Test
    fun `sin respuestas no hay VIN`() {
        assertNull(Vin.fromService09(emptyList()))
        assertNull(Vin.fromService09(listOf("4100BE3FA813".decodeHex())))
    }
}
