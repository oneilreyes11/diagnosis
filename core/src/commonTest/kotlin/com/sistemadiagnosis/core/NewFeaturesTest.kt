package com.sistemadiagnosis.core

import com.sistemadiagnosis.core.history.AlertKind
import com.sistemadiagnosis.core.history.HistoryAnalysis
import com.sistemadiagnosis.core.history.HistoryEntry
import com.sistemadiagnosis.core.history.HistoryFault
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.pdf.PdfWriter
import com.sistemadiagnosis.core.pdf.ReportInput
import com.sistemadiagnosis.core.pdf.ReportPdf
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewFeaturesTest {
    private val vehicle = Vehicles.AUDI_A3_8Y_DFYA

    private fun entry(
        day: Int,
        volts: Double? = null,
        engine: List<String> = emptyList(),
        modules: List<String>? = null,
    ) = HistoryEntry.start("e$day", day * 86_400_000.0).copy(
        batteryVoltage = volts,
        engineFaults = engine.map { HistoryFault("01", it, "Avería $it", Severity.HIGH) },
        moduleFaults = modules?.map { HistoryFault("09", it, "Avería $it", Severity.LOW) },
    )

    private val date: (Double) -> String = { "día ${(it / 86_400_000).toInt()}" }

    @Test
    fun `una averia borrada que vuelve se avisa`() {
        val alerts = HistoryAnalysis.alerts(
            listOf(entry(1, engine = listOf("P0302")), entry(2), entry(3, engine = listOf("P0302"))),
            date,
        )
        val alert = alerts.single()
        assertEquals(AlertKind.FAULT_RETURNED, alert.kind)
        assertTrue("día 2" in alert.detail)
    }

    @Test
    fun `averia nueva solo si la centralita se leyo antes`() {
        // La 09 no se escaneó en la lectura anterior: no se puede decir que sea nueva.
        val sinEscaneo = HistoryAnalysis.alerts(listOf(entry(1), entry(2, modules = listOf("B10B513"))), date)
        assertTrue(sinEscaneo.isEmpty())
        val conEscaneo = HistoryAnalysis.alerts(listOf(entry(1, modules = emptyList()), entry(2, modules = listOf("B10B513"))), date)
        assertEquals(AlertKind.NEW_FAULT, conEscaneo.single().kind)
    }

    @Test
    fun `bateria baja y tendencia con el motor parado`() {
        val entries = listOf(12.7, 14.2, 12.65, 12.6, 12.4, 12.3, 12.15).mapIndexed { i, v -> entry(i, volts = v) }
        val kinds = HistoryAnalysis.alerts(entries, date).map { it.kind }.toSet()
        // 14,2 V es con el motor en marcha y no cuenta.
        assertEquals(setOf(AlertKind.BATTERY_LOW, AlertKind.BATTERY_DROPPING), kinds)
    }

    @Test
    fun `bateria estable no avisa`() {
        val entries = listOf(12.6, 12.55, 12.62, 12.58, 12.6).mapIndexed { i, v -> entry(i, volts = v) }
        assertTrue(HistoryAnalysis.alerts(entries, date).isEmpty())
    }

    @Test
    fun `pdf valido con tildes en WinAnsi`() {
        val pdf = PdfWriter()
        pdf.newPage()
        pdf.text(40.0, 40.0, "Batería · Averías ¿ñ? 91 °C (ok)", 10.0)
        val bytes = pdf.toBytes()
        val text = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue("Batería · Averías ¿ñ? 91 °C \\(ok\\)" in text)
        // La tabla xref apunta al inicio real de cada objeto.
        val xrefStart = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        val offsets = text.substring(xrefStart).lines().drop(3).takeWhile { it.endsWith(" n ") }.map { it.take(10).toInt() }
        offsets.forEachIndexed { i, offset -> assertTrue(text.startsWith("${i + 1} 0 obj", offset), "objeto ${i + 1}") }
    }

    @Test
    fun `texto largo se parte en lineas que caben`() {
        val lines = PdfWriter.wrap("La centralita ha detectado que el cilindro 2 no quema bien la mezcla durante varios ciclos.", 9.0, 150.0)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { PdfWriter.width(it, 9.0) <= 150.0 })
    }

    @Test
    fun `informe completo de la demostracion`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        val info = session.connect()
        val report = session.runDiagnostics()
        val modules = session.scanModules().toList()
        assertEquals(41_236.0, report.tripData[DiagnosisSession.ODOMETER])

        val bytes = ReportPdf.build(
            ReportInput(vehicle, info, report, modules, listOf(entry(1, volts = 12.6)), "04/11/2026", demo = true, formatDate = date),
        )
        val text = bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        assertTrue(text.startsWith("%PDF"))
        assertTrue("P0302" in text)
        assertTrue("41.236 km" in text)
        assertTrue("J285 Kombi" in text || "Cuadro" in text)
    }

    @Test
    fun `explorador lee identificadores y deja el motor listo`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        session.connect()
        val cluster = vehicle.modules.first { it.address == "17" }
        val readings = session.exploreDids(cluster, (0x2200..0x2220).toList()).toList()

        assertEquals(0x21, readings.size)
        assertEquals(listOf(0x2203, 0x2216), readings.filter { it.found }.map { it.did })
        assertEquals(0x31, readings.first { !it.found }.nrc)
        assertNull(readings.first { it.found }.error)
        // Tras explorar, OBD-II del motor sigue funcionando.
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }

    @Test
    fun `historial se rellena con diagnosis y escaneo`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        val info = session.connect()
        val entry = HistoryEntry.start("a", 0.0)
            .withDiagnosis(info, session.runDiagnostics())
            .withModuleScan(session.scanModules().toList())
        assertEquals(41_236.0, entry.odometerKm)
        assertEquals(true, entry.milOn)
        assertTrue(entry.allFaults.any { it.source == "17" && it.code == "U012100" })
        assertTrue(entry.allFaults.any { it.source == "01" && it.code == "P0302" })
    }
}
