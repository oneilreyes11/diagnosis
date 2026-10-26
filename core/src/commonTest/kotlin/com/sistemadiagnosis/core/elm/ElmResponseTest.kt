package com.sistemadiagnosis.core.elm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ElmResponseTest {
    @Test
    fun `quita eco progreso y lineas vacias`() {
        val response = ElmResponse.parse("0100", "0100\r\nSEARCHING...\r\n41 00 BE 3F A8 13\r\n\r\n")
        assertEquals(ElmResponse.Ok(listOf("41 00 BE 3F A8 13")), response)
    }

    @Test
    fun `quita los nulos que algunos adaptadores envian antes del prompt`() {
        val response = ElmResponse.parse("ATRV", "\u000012.6V\r\r")
        assertEquals(listOf("12.6V"), response.lines)
    }

    @Test
    fun `detecta errores del adaptador`() {
        assertEquals(ElmError.NO_DATA, (ElmResponse.parse("0902", "NO DATA\r") as ElmResponse.Error).error)
        assertEquals(ElmError.UNKNOWN_COMMAND, (ElmResponse.parse("ATXX", "?\r") as ElmResponse.Error).error)
        assertEquals(
            ElmError.UNABLE_TO_CONNECT,
            (ElmResponse.parse("0100", "SEARCHING...\rUNABLE TO CONNECT\r") as ElmResponse.Error).error,
        )
        assertEquals(ElmError.BUS_INIT_ERROR, (ElmResponse.parse("0100", "BUS INIT: ...ERROR\r") as ElmResponse.Error).error)
        assertEquals(ElmError.INTERNAL_ERROR, (ElmResponse.parse("0100", "ERR94\r") as ElmResponse.Error).error)
    }

    @Test
    fun `bus init correcto no es error`() {
        assertIs<ElmResponse.Ok>(ElmResponse.parse("0100", "BUS INIT: ...OK\r4100BE3FA813\r"))
    }
}
