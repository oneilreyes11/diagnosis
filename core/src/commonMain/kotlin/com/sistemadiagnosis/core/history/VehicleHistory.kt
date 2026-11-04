package com.sistemadiagnosis.core.history

import com.sistemadiagnosis.core.ConnectionInfo
import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.DiagnosticReport
import com.sistemadiagnosis.core.ModuleScanResult
import com.sistemadiagnosis.core.knowledge.Severity
import kotlin.math.abs

/** Una avería guardada en el historial. [source] es la dirección de la centralita ("01" = motor). */
data class HistoryFault(
    val source: String,
    val code: String,
    val title: String,
    val severity: Severity,
) {
    val key: String get() = "$source:$code"
}

/**
 * Una lectura del coche: lo que se vio en una conexión. Se rellena con la
 * diagnosis del motor y, si se hizo, con el escaneo completo.
 */
data class HistoryEntry(
    val id: String,
    /** Milisegundos desde 1970. */
    val timestamp: Double,
    val vin: String?,
    val odometerKm: Double?,
    /** Tensión en el conector al conectar. */
    val batteryVoltage: Double?,
    val milOn: Boolean?,
    val monitorsComplete: Int?,
    val monitorsTotal: Int?,
    val engineFaults: List<HistoryFault>,
    /** null si en esa lectura no se hizo el escaneo completo. */
    val moduleFaults: List<HistoryFault>?,
    val modulesFound: Int?,
) {
    /** Averías de todas las centralitas leídas; las del motor por OBD-II y por UDS sin repetir. */
    val allFaults: List<HistoryFault>
        get() = (engineFaults + moduleFaults.orEmpty()).distinctBy { it.key }

    val faultCount: Int get() = allFaults.size

    /** Motor parado: sin la carga del alternador la tensión refleja el estado de la batería. */
    val restingVoltage: Double? get() = batteryVoltage?.takeIf { it < RESTING_MAX_VOLTS }

    fun withDiagnosis(info: ConnectionInfo, report: DiagnosticReport): HistoryEntry = copy(
        vin = info.vin?.value ?: vin,
        batteryVoltage = info.batteryVoltage ?: batteryVoltage,
        odometerKm = report.tripData[DiagnosisSession.ODOMETER] ?: odometerKm,
        milOn = report.monitorStatus?.milOn ?: milOn,
        monitorsComplete = report.monitorStatus?.completeCount ?: monitorsComplete,
        monitorsTotal = report.monitorStatus?.monitors?.size ?: monitorsTotal,
        engineFaults = report.faults.map { HistoryFault(ENGINE_SOURCE, it.dtc.code, it.description.title, it.severity) },
    )

    fun withModuleScan(results: List<ModuleScanResult>): HistoryEntry = copy(
        moduleFaults = results.flatMap { result ->
            result.faults.map { HistoryFault(result.module.address, it.code.displayCode, it.description.title, it.severity) }
        },
        modulesFound = results.count { it.present },
    )

    companion object {
        const val ENGINE_SOURCE = "01"
        const val RESTING_MAX_VOLTS = 13.0

        fun start(id: String, timestamp: Double) =
            HistoryEntry(id, timestamp, null, null, null, null, null, null, emptyList(), null, null)
    }
}

enum class AlertKind { FAULT_RETURNED, NEW_FAULT, BATTERY_LOW, BATTERY_DROPPING }

data class HistoryAlert(val kind: AlertKind, val severity: Severity, val title: String, val detail: String)

/** Avisos a partir de las lecturas guardadas. */
object HistoryAnalysis {
    /** Por debajo de esto, con el motor parado, la batería está floja. */
    const val LOW_RESTING_VOLTS = 12.2

    /** Caída media que se considera tendencia (V). */
    const val DROP_VOLTS = 0.25

    /**
     * [entries] de la más antigua a la más reciente; la última es la lectura actual.
     * [formatDate] convierte una marca de tiempo en texto para los mensajes.
     */
    fun alerts(entries: List<HistoryEntry>, formatDate: (Double) -> String): List<HistoryAlert> {
        val current = entries.lastOrNull() ?: return emptyList()
        val previous = entries.dropLast(1)
        return faultAlerts(current, previous, formatDate) + batteryAlerts(entries)
    }

    private fun faultAlerts(current: HistoryEntry, previous: List<HistoryEntry>, formatDate: (Double) -> String): List<HistoryAlert> {
        val alerts = mutableListOf<HistoryAlert>()
        for (fault in current.allFaults) {
            // Solo cuentan las lecturas en que esa centralita se leyó de verdad.
            val comparable = previous.filter { it.covers(fault.source) }
            if (comparable.isEmpty()) continue
            val lastSeen = comparable.indexOfLast { entry -> entry.allFaults.any { it.key == fault.key } }
            when {
                lastSeen < 0 -> alerts += HistoryAlert(
                    AlertKind.NEW_FAULT, fault.severity, "Avería nueva: ${fault.code}",
                    "${fault.title}. No aparecía en las lecturas anteriores.",
                )
                lastSeen < comparable.lastIndex -> {
                    val clearedAt = comparable[lastSeen + 1].timestamp
                    alerts += HistoryAlert(
                        AlertKind.FAULT_RETURNED, maxOf(fault.severity, Severity.MEDIUM), "Ha vuelto: ${fault.code}",
                        "${fault.title}. Había desaparecido el ${formatDate(clearedAt)} y se ha vuelto a registrar: " +
                            "la causa sigue ahí, borrarla no la arregla.",
                    )
                }
            }
        }
        return alerts.sortedByDescending { it.severity }
    }

    private fun batteryAlerts(entries: List<HistoryEntry>): List<HistoryAlert> {
        val resting = entries.mapNotNull { it.restingVoltage }
        val latest = entries.last().restingVoltage
        val alerts = mutableListOf<HistoryAlert>()
        if (latest != null && latest < LOW_RESTING_VOLTS) {
            alerts += HistoryAlert(
                AlertKind.BATTERY_LOW, Severity.MEDIUM, "Batería baja",
                "${format(latest)} V con el motor parado. Una batería sana y cargada da 12,4-12,7 V. " +
                    "Si el coche ha estado parado varios días, haz un trayecto largo y vuelve a medir.",
            )
        }
        if (resting.size >= 4) {
            val recent = resting.takeLast(3).average()
            val before = resting.dropLast(3).average()
            if (before - recent >= DROP_VOLTS) {
                alerts += HistoryAlert(
                    AlertKind.BATTERY_DROPPING, Severity.MEDIUM, "La batería pierde tensión",
                    "Media de las 3 últimas lecturas con el motor parado: ${format(recent)} V; antes: ${format(before)} V. " +
                        "Puede estar envejeciendo o haber un consumo con el coche apagado.",
                )
            }
        }
        return alerts
    }

    private fun HistoryEntry.covers(source: String): Boolean =
        if (source == HistoryEntry.ENGINE_SOURCE) true else moduleFaults != null

    private fun format(volts: Double): String {
        val hundredths = kotlin.math.round(volts * 100).toLong()
        return "${hundredths / 100},${(abs(hundredths) % 100).toString().padStart(2, '0')}"
    }
}
