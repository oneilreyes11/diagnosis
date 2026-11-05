package com.sistemadiagnosis.core

import com.sistemadiagnosis.core.analysis.EngineHealth
import com.sistemadiagnosis.core.history.AlertKind
import com.sistemadiagnosis.core.obd.MonitorTests
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

    @Test
    fun `autopruebas del motor detectan el cilindro y el catalizador`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        session.connect()
        val report = session.runDiagnostics()
        val tests = report.monitorTests!!
        assertTrue(tests.any { it.mid == 0x21 })
        val cyl2 = EngineHealth.misfires(tests).first { it.cylinder == 2 }
        assertEquals(14, cyl2.lastTrip)
        assertEquals(6, cyl2.average)
        val titles = report.healthFindings.map { it.title }
        assertTrue(titles.any { "Cilindro 2" in it }, "$titles")
        assertTrue(titles.any { "Catalizador banco 1" in it }, "$titles")
        assertTrue(titles.none { "Cilindro 4" in it })
        // El catalizador pasa: 189/205 de una razón con escalado 1/256.
        val cat = tests.first { it.mid == 0x21 }
        assertTrue(cat.passed)
        assertEquals(189 / 256.0, cat.value)
        // OBD-II sigue igual después.
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }

    @Test
    fun `registro del modo 06 con signo`() {
        val bytes = listOf(0x46, 0x01, 0x81, 0x8A, 0xFF, 0x9C, 0xFF, 0x38, 0x00, 0x64).map { it.toByte() }.toByteArray()
        val t = MonitorTests.parse(bytes).single()
        assertEquals(-100, t.rawValue)
        assertEquals(-200, t.rawMin)
        assertEquals(100, t.rawMax)
        assertTrue(t.passed)
        assertEquals("mV", t.unit)
    }

    @Test
    fun `deteccion de borrado reciente`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        session.connect()
        val before = session.runDiagnostics().clearCheck!!
        assertNull(before.severity)
        assertTrue("1.240 km" in before.detail)
        session.clearFaults()
        val after = session.runDiagnostics()
        // P0302 sigue como permanente: borrada pero no reparada.
        assertEquals(Severity.HIGH, after.clearCheck!!.severity)
        assertTrue("P0302" in after.clearCheck!!.detail)
        // Tras borrar, las autopruebas están a cero: ningún aviso falso.
        assertTrue(after.healthFindings.isEmpty())
    }

    @Test
    fun `busqueda de 29 bits encuentra centralitas y vuelve a OBD`() = runTest {
        val session = DiagnosisSession(MockTransport(SimulatedVehicle.audiA3()), vehicle)
        session.connect()
        val progress = session.discoverModules().toList()
        assertEquals(255 + 1 + 256, progress.size)
        val hits = progress.mapNotNull { it.hit?.responseId }
        assertTrue(0x77E in hits, "cuadro en 11 bits: $hits")
        assertTrue(hits.containsAll(listOf(0x17FE0076, 0x17FE0080)))
        assertTrue(progress.first { it.hit?.responseId == 0x17FE0076 }.hit!!.response.startsWith("065001"))
        assertEquals(listOf("P0302", "P2181"), session.runDiagnostics().faults.map { it.dtc.code })
    }
}
