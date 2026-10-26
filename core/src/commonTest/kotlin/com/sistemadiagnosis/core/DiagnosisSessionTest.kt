package com.sistemadiagnosis.core

import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.transport.TransportException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiagnosisSessionTest {
    @Test
    fun `escaneo rapido completo y cierra el transporte`() = runTest {
        val transport = MockTransport()
        val result = DiagnosisSession(transport).quickScan()

        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, result.obdProtocol)
        assertEquals("WVWZZZ1KZ6W000001", result.vin?.value)
        assertNull(result.vehicleError)
        assertFalse(transport.isOpen)
    }

    @Test
    fun `con el contacto quitado devuelve adaptador y bateria y explica el fallo`() = runTest {
        val result = DiagnosisSession(MockTransport(SimulatedVehicle(ignitionOn = false, batteryVoltage = 11.9))).quickScan()

        assertEquals(11.9, result.batteryVoltage)
        assertNull(result.obdProtocol)
        assertNotNull(result.vehicleError)
    }

    @Test
    fun `si el adaptador no abre se lanza DiagnosisException`() = runTest {
        val unreachable = object : Transport {
            override val displayName = "inalcanzable"
            override val isOpen = false
            override suspend fun open() = throw TransportException("sin ruta")
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun read(timeoutMs: Long) = ByteArray(0)
            override suspend fun close() = Unit
        }
        assertFailsWith<DiagnosisException> { DiagnosisSession(unreachable).quickScan() }
    }

    @Test
    fun `errores inesperados se envuelven en DiagnosisException`() = runTest {
        val broken = object : Transport {
            override val displayName = "roto"
            override val isOpen = true
            override suspend fun open() = Unit
            override suspend fun write(data: ByteArray) = throw IllegalStateException("fallo interno")
            override suspend fun read(timeoutMs: Long) = ByteArray(0)
            override suspend fun close() = Unit
        }
        assertFailsWith<DiagnosisException> { DiagnosisSession(broken).quickScan() }
    }
}
