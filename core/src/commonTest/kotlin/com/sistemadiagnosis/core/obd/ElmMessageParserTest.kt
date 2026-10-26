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
    fun `dos respuestas multitrama seguidas`() {
        val messages = ElmMessageParser.parse(
            listOf("008", "0:490201414243", "1:4445AAAAAAAAAA", "007", "0:490201585959", "1:5A"),
        )
        assertEquals(listOf("4902014142434445", "4902015859595A"), messages.map { it.toHex() })
    }
}
