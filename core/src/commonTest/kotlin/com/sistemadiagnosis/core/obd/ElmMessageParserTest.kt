package com.sistemadiagnosis.core.obd

import com.sistemadiagnosis.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

class ElmMessageParserTest {
    @Test
    fun `tramas simples con y sin espacios`() {
        val messages = ElmMessageParser.parse(listOf("41 00 BE 3F A8 13", "4100983B0011"))
        assertEquals(listOf("4100BE3FA813", "4100983B0011"), messages.map { it.toHex() })
    }

    @Test
    fun `multitrama CAN se reensambla y se recorta el relleno`() {
        val messages = ElmMessageParser.parse(
            listOf(
                "00A",
                "0: 49 02 01 31 32 33",
                "1: 34 35 36 37 00 00 00",
            ),
        )
        assertEquals(listOf("49020131323334353637"), messages.map { it.toHex() })
    }

    @Test
    fun `tramas CAN crudas del adaptador de Carly`() {
        // Del registro real: 0100 a todas las centralitas y 0902 en multitrama, con byte de control y relleno AA.
        val single = ElmMessageParser.parse(listOf("06 41 00 BE 3E A8 13 AA ", "06 41 00 98 18 00 01 AA "))
        assertEquals(listOf("4100BE3EA813", "410098180001"), single.map { it.toHex() })

        val vin = ElmMessageParser.parse(listOf("10 14 49 02 01 57 41 55 ", "21 5A 5A 5A 47 59 33 4D ", "22 41 30 36 30 31 30 36 "))
        assertEquals("4902015741555A5A5A4759334D41303630313036", vin.single().toHex())
        assertEquals("WAUZZZGY3MA060106", Vin.fromService09(vin)?.value)
    }

    @Test
    fun `dos respuestas multitrama seguidas`() {
        val messages = ElmMessageParser.parse(
            listOf("008", "0:490201414243", "1:4445AAAAAAAAAA", "007", "0:490201585959", "1:5A"),
        )
        assertEquals(listOf("4902014142434445", "4902015859595A"), messages.map { it.toHex() })
    }
}
