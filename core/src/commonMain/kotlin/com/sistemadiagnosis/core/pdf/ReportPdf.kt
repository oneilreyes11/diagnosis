package com.sistemadiagnosis.core.pdf

import com.sistemadiagnosis.core.ConnectionInfo
import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.DiagnosticReport
import com.sistemadiagnosis.core.ModuleScanResult
import com.sistemadiagnosis.core.history.HistoryEntry
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.pdf.PdfWriter.Color
import com.sistemadiagnosis.core.pdf.PdfWriter.Font
import com.sistemadiagnosis.core.vehicle.VehicleProfile

/** Todo lo que entra en el informe. */
data class ReportInput(
    val vehicle: VehicleProfile,
    val connection: ConnectionInfo?,
    val report: DiagnosticReport?,
    val modules: List<ModuleScanResult>,
    /** Lecturas anteriores, de la más antigua a la más reciente (sin la actual). */
    val history: List<HistoryEntry>,
    val dateText: String,
    val demo: Boolean,
    val formatDate: (Double) -> String,
)

/** Informe de diagnosis en PDF con la estética de la app: pizarra, gris claro y tonos de estado. */
object ReportPdf {
    private val SLATE = Color(0x1F, 0x24, 0x28)
    private val INK = Color(0x1A, 0x1E, 0x21)
    private val TEXT_2 = Color(0x45, 0x4E, 0x55)
    private val MUTED = Color(0x7A, 0x84, 0x8C)
    private val LINE = Color(0xD5, 0xDA, 0xDE)
    private val PANEL = Color(0xF2, 0xF4, 0xF5)
    private val ACCENT = Color(0xC9, 0xD1, 0xD7)
    private val OK = Color(0x2E, 0x8B, 0x47)
    private val WARN = Color(0xB7, 0x7F, 0x0A)
    private val DANGER = Color(0xC8, 0x2E, 0x33)

    private const val MARGIN = 40.0
    private const val RIGHT = PdfWriter.PAGE_WIDTH - MARGIN
    private const val CONTENT = RIGHT - MARGIN
    private const val BOTTOM = PdfWriter.PAGE_HEIGHT - 56

    fun build(input: ReportInput): ByteArray = Layout(input).render()

    private fun tone(severity: Severity) = when (severity) {
        Severity.HIGH -> DANGER
        Severity.MEDIUM -> WARN
        Severity.LOW -> MUTED
    }

    private class Layout(private val input: ReportInput) {
        private val pdf = PdfWriter()
        private var y = 0.0
        private val vehicle = input.vehicle

        fun render(): ByteArray {
            firstPage()
            summary()
            engineFaults()
            freezeFrame()
            modules()
            history()
            notes()
            footers()
            return pdf.toBytes()
        }

        private fun firstPage() {
            pdf.newPage()
            pdf.fillRect(0.0, 0.0, PdfWriter.PAGE_WIDTH, 112.0, SLATE)
            pdf.fillRect(0.0, 112.0, PdfWriter.PAGE_WIDTH, 3.0, ACCENT)
            pdf.text(MARGIN, 38.0, "INFORME DE DIAGNOSIS", 9.0, Font.BOLD, ACCENT)
            pdf.text(MARGIN, 66.0, "${vehicle.displayName} ${vehicle.generation}", 24.0, Font.BOLD, Color(0xF2, 0xF4, 0xF5))
            pdf.text(MARGIN, 88.0, "${vehicle.year} · ${vehicle.engine.name} ${vehicle.engine.code} · ${vehicle.engine.power}", 10.5, Font.REGULAR, ACCENT)
            pdf.textRight(RIGHT, 38.0, input.dateText, 9.0, Font.REGULAR, ACCENT)
            input.connection?.vin?.let { pdf.textRight(RIGHT, 88.0, "VIN ${it.value}", 9.5, Font.MONO, ACCENT) }
            y = 140.0
            if (input.demo) {
                callout("Informe de demostración", "Datos simulados: no corresponden a ningún coche real.", WARN)
            }
        }

        private fun summary() {
            val report = input.report
            val status = report?.monitorStatus
            val faults = allFaultCount()
            val odometer = report?.tripData?.get(DiagnosisSession.ODOMETER)
            val cells = listOf(
                Triple("ESTADO", if (faults == 0) "Sin averías" else "$faults ${if (faults == 1) "avería" else "averías"}", if (faults == 0) OK else tone(worstSeverity() ?: Severity.LOW)),
                Triple("TESTIGO MOTOR", when (status?.milOn) { true -> "Encendido"; false -> "Apagado"; null -> "—" }, if (status?.milOn == true) DANGER else INK),
                Triple("MONITORES ITV", status?.let { "${it.completeCount}/${it.monitors.size}" } ?: "—", if (status?.allComplete == false) WARN else INK),
                Triple("BATERÍA", input.connection?.batteryVoltage?.let { "${decimal(it, 1)} V" } ?: "—", INK),
            ) + listOfNotNull(odometer?.let { Triple("KILÓMETROS", "${thousands(it)} km", INK) })
            val width = CONTENT / cells.size
            ensure(58.0)
            pdf.fillRect(MARGIN, y, CONTENT, 54.0, PANEL)
            cells.forEachIndexed { i, (label, value, color) ->
                val x = MARGIN + i * width
                if (i > 0) pdf.line(x, y + 8, x, y + 46, LINE)
                pdf.text(x + 12, y + 20, label, 7.0, Font.BOLD, MUTED)
                pdf.text(x + 12, y + 40, value, 13.0, Font.BOLD, color)
            }
            y += 74
        }

        private fun engineFaults() {
            val report = input.report ?: return
            section("Motor · OBD-II", "Centralita ${vehicle.engineEcu.address} · averías de emisiones con su explicación")
            if (report.faults.isEmpty()) {
                paragraph("Sin averías registradas en la centralita del motor.", TEXT_2)
                y += 6
                return
            }
            for (fault in report.faults) {
                val d = fault.description
                val kinds = fault.kinds.sortedBy { it.ordinal }.joinToString(" · ") { it.label }
                val body = PdfWriter.wrap(d.explanation, 9.0, CONTENT - 24)
                val advice = PdfWriter.wrap("Qué hacer: ${d.advice}", 9.0, CONTENT - 24, Font.REGULAR)
                val note = d.vehicleNote?.let { PdfWriter.wrap("En tu ${vehicle.displayName}: $it", 9.0, CONTENT - 24) }.orEmpty()
                val height = 44 + (body.size + advice.size + note.size) * 12.5 + if (note.isEmpty()) 0.0 else 4.0
                ensure(height + 10)
                val top = y
                pdf.fillRect(MARGIN, top, 3.0, height, tone(fault.severity))
                pdf.text(MARGIN + 14, top + 14, fault.dtc.code, 12.0, Font.MONO, INK)
                pdf.text(MARGIN + 14 + PdfWriter.width(fault.dtc.code, 12.0, Font.MONO) + 10, top + 14, d.title, 11.0, Font.BOLD, INK)
                pdf.text(MARGIN + 14, top + 29, "Gravedad ${fault.severity.label.lowercase()} · $kinds", 8.5, Font.BOLD, tone(fault.severity))
                y = top + 44
                body.forEach { line(it, 9.0, TEXT_2, 14.0) }
                advice.forEach { line(it, 9.0, INK, 14.0) }
                if (note.isNotEmpty()) {
                    y += 4
                    note.forEach { line(it, 9.0, TEXT_2, 14.0) }
                }
                y = top + height + 12
            }
        }

        private fun freezeFrame() {
            val frame = input.report?.freezeFrame ?: return
            val values = frame.values.mapNotNull { (pid, value) -> PidCatalog[pid]?.let { it.name to it.formatWithUnitPdf(value) } }
            if (values.isEmpty()) return
            section("Datos congelados · ${frame.trigger.code}", "Los sensores en el instante en que se registró la avería")
            table(values)
        }

        private fun modules() {
            if (input.modules.isEmpty()) return
            val present = input.modules.filter { it.present }
            section(
                "Escaneo completo",
                "${present.size} centralitas encontradas de ${input.modules.size} direcciones · lectura UDS de toda la memoria de averías",
            )
            for (result in present) {
                val name = result.identity?.systemName ?: result.module.name
                val status = when {
                    result.error != null -> "Sin lectura"
                    result.faults.isEmpty() -> "OK"
                    else -> "${result.faults.size} ${if (result.faults.size == 1) "avería" else "averías"}"
                }
                val statusColor = when {
                    result.error != null -> MUTED
                    result.faults.isEmpty() -> OK
                    else -> tone(result.highestSeverity ?: Severity.LOW)
                }
                ensure(22.0 + result.faults.size * 26)
                pdf.text(MARGIN, y + 11, result.module.address, 9.5, Font.MONO, INK)
                pdf.text(MARGIN + 30, y + 11, result.module.name, 9.5, Font.BOLD, INK)
                val part = listOfNotNull(name.takeIf { it != result.module.name }, result.identity?.partNumber).joinToString(" · ")
                if (part.isNotEmpty()) pdf.text(MARGIN + 30, y + 22, part, 7.5, Font.REGULAR, MUTED)
                pdf.textRight(RIGHT, y + 11, status, 9.0, Font.BOLD, statusColor)
                y += if (part.isNotEmpty()) 28 else 18
                for (fault in result.faults) {
                    pdf.text(MARGIN + 30, y + 9, fault.code.displayCode, 9.0, Font.MONO, tone(fault.severity))
                    val title = PdfWriter.wrap(fault.description.title, 8.5, CONTENT - 120).first()
                    pdf.text(MARGIN + 100, y + 9, title, 8.5, Font.REGULAR, INK)
                    pdf.text(MARGIN + 100, y + 20, "${fault.code.state.label} · gravedad ${fault.severity.label.lowercase()}", 7.5, Font.REGULAR, MUTED)
                    y += 26
                }
                pdf.line(MARGIN, y, RIGHT, y, LINE)
                y += 6
            }
            val absent = input.modules.filterNot { it.present }
            if (absent.isNotEmpty()) {
                y += 4
                paragraph("Sin respuesta (no montadas en este coche o dormidas): " + absent.joinToString(", ") { "${it.module.address} ${it.module.name}" } + ".", MUTED, 8.0)
            }
            y += 8
        }

        private fun history() {
            val past = input.history.filter { it.batteryVoltage != null || it.faultCount > 0 }.takeLast(8)
            if (past.isEmpty()) return
            section("Lecturas anteriores", "Las últimas veces que se conectó la app a este coche")
            val rows = past.reversed().map { entry ->
                val parts = listOfNotNull(
                    entry.odometerKm?.let { "${thousands(it)} km" },
                    entry.batteryVoltage?.let { "${decimal(it, 1)} V" },
                    if (entry.faultCount == 0) "sin averías" else "${entry.faultCount} ${if (entry.faultCount == 1) "avería" else "averías"}: " +
                        entry.allFaults.take(4).joinToString(", ") { it.code } + if (entry.faultCount > 4) "…" else "",
                )
                input.formatDate(entry.timestamp) to parts.joinToString(" · ")
            }
            table(rows)
        }

        private fun notes() {
            ensure(70.0)
            y += 6
            pdf.line(MARGIN, y, RIGHT, y, LINE)
            y += 14
            paragraph(
                "Lectura hecha con un adaptador OBD Bluetooth: averías de emisiones por OBD-II (SAE J1979) y, en el escaneo completo, " +
                    "la memoria de averías de cada centralita por UDS (ISO 14229). Solo lectura: no se ha modificado ninguna codificación ni adaptación. " +
                    "Las explicaciones son orientativas y no sustituyen la revisión de un profesional.",
                MUTED, 7.5,
            )
        }

        private fun footers() {
            // Se pinta al final, cuando ya se sabe cuántas páginas hay.
            val total = pdf.pageCount
            for (page in 1..total) {
                pdf.goTo(page - 1)
                pdf.line(MARGIN, PdfWriter.PAGE_HEIGHT - 36, RIGHT, PdfWriter.PAGE_HEIGHT - 36, LINE)
                pdf.text(MARGIN, PdfWriter.PAGE_HEIGHT - 22, "Diagnosis · ${vehicle.displayName} ${vehicle.generation} · ${input.dateText}", 7.5, Font.REGULAR, MUTED)
                pdf.textRight(RIGHT, PdfWriter.PAGE_HEIGHT - 22, "Página $page de $total", 7.5, Font.REGULAR, MUTED)
            }
        }

        // --- Piezas -----------------------------------------------------------

        private fun section(title: String, subtitle: String) {
            ensure(60.0)
            pdf.text(MARGIN, y + 12, title.uppercase(), 9.0, Font.BOLD, INK)
            y += 18
            pdf.line(MARGIN, y, RIGHT, y, INK, 1.0)
            y += 13
            pdf.text(MARGIN, y, subtitle, 8.0, Font.REGULAR, MUTED)
            y += 14
        }

        private fun callout(title: String, text: String, color: Color) {
            pdf.fillRect(MARGIN, y, CONTENT, 34.0, PANEL)
            pdf.fillRect(MARGIN, y, 3.0, 34.0, color)
            pdf.text(MARGIN + 14, y + 14, title, 9.5, Font.BOLD, INK)
            pdf.text(MARGIN + 14, y + 26, text, 8.5, Font.REGULAR, TEXT_2)
            y += 48
        }

        private fun table(rows: List<Pair<String, String>>) {
            for ((label, value) in rows) {
                val lines = PdfWriter.wrap(value, 9.0, CONTENT * 0.6)
                ensure(lines.size * 12.0 + 8)
                pdf.text(MARGIN, y + 10, label, 9.0, Font.REGULAR, TEXT_2)
                lines.forEachIndexed { i, text -> pdf.text(MARGIN + CONTENT * 0.4, y + 10 + i * 12, text, 9.0, Font.BOLD, INK) }
                y += lines.size * 12 + 6
                pdf.line(MARGIN, y, RIGHT, y, LINE)
                y += 2
            }
            y += 14
        }

        private fun paragraph(text: String, color: Color, size: Double = 9.0) {
            PdfWriter.wrap(text, size, CONTENT).forEach { line(it, size, color) }
        }

        private fun line(text: String, size: Double, color: Color, indent: Double = 0.0) {
            ensure(size + 4)
            pdf.text(MARGIN + indent, y + size, text, size, Font.REGULAR, color)
            y += size + 3.5
        }

        private fun ensure(height: Double) {
            if (y + height <= BOTTOM) return
            pdf.newPage()
            y = 44.0
        }

        private fun allFaultCount(): Int {
            val engine = input.report?.faults?.size ?: 0
            val others = input.modules.filter { it.module != vehicle.engineEcu }.sumOf { it.faults.size }
            return engine + others
        }

        private fun worstSeverity(): Severity? =
            (input.report?.faults?.map { it.severity }.orEmpty() + input.modules.flatMap { m -> m.faults.map { it.severity } }).maxOrNull()
    }

    private fun com.sistemadiagnosis.core.obd.PidDefinition.formatWithUnitPdf(value: Double): String =
        decimal(value, decimals) + if (unit.isEmpty()) "" else " $unit"

    internal fun decimal(value: Double, decimals: Int): String {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val rounded = kotlin.math.round(kotlin.math.abs(value) * factor).toLong()
        val sign = if (value < 0 && rounded != 0L) "-" else ""
        if (decimals == 0) return sign + rounded
        val whole = rounded / factor.toLong()
        val fraction = (rounded % factor.toLong()).toString().padStart(decimals, '0')
        return "$sign$whole,$fraction"
    }

    internal fun thousands(value: Double): String =
        kotlin.math.round(value).toLong().toString().reversed().chunked(3).joinToString(".").reversed()
}
