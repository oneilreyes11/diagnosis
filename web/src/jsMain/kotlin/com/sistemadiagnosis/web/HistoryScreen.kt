package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.history.AlertKind
import com.sistemadiagnosis.core.history.HistoryAlert
import com.sistemadiagnosis.core.history.HistoryEntry
import com.sistemadiagnosis.core.knowledge.Severity
import org.w3c.dom.HTMLElement

internal fun App.renderHistory(screen: HTMLElement) {
    val entries = history
    screen.div("section-head") {
        div {
            p("eyebrow", if (isDemo) "Demostración · lecturas inventadas" else "Guardado en este móvil")
            h1("section-title", "Historial")
        }
    }

    if (entries.isEmpty()) {
        screen.div("empty") {
            div("empty-icon") { icon("clock") }
            h2("empty-title", "Aún no hay lecturas")
            p("empty-text", "Cada vez que conectes el adaptador se guarda lo que se lee: averías, batería y kilómetros. Así verás si una avería vuelve o si la batería se va apagando.")
        }
        return
    }

    val alerts = historyAlerts
    if (alerts.isNotEmpty()) {
        screen.div("alert-list") { alerts.forEach { alertCard(it) } }
    }

    batteryCard(screen, entries)

    screen.div("group-head") {
        h2("group-title", plural(entries.size, "lectura", "lecturas"))
    }
    screen.div("card list-card") {
        entries.reversed().forEach { entry -> historyRow(entry) }
    }

    screen.div("button-row") {
        button("btn btn-ghost btn-sm", "Borrar historial", { confirmClearHistory() }) { icon("trash") }
    }
    screen.p("footnote", "Se guarda solo en este navegador. Si borras los datos de Bluefy, se pierde.")
}

internal fun HTMLElement.alertCard(alert: HistoryAlert) {
    // Un aviso nunca es "bueno": las leves van en gris, no en verde.
    div("callout tone-${if (alert.severity == Severity.LOW) "accent" else alert.severity.tone}") {
        icon(
            when (alert.kind) {
                AlertKind.FAULT_RETURNED -> "refresh"
                AlertKind.NEW_FAULT -> "alert"
                AlertKind.BATTERY_LOW, AlertKind.BATTERY_DROPPING -> "battery"
            },
        )
        div {
            p("callout-title", alert.title)
            p("", alert.detail)
        }
    }
}

/** Gráfica de la tensión con el motor parado (la que dice algo de la batería). */
private fun App.batteryCard(screen: HTMLElement, entries: List<HistoryEntry>) {
    val points = entries.mapNotNull { e -> e.restingVoltage?.let { e.timestamp to it } }
    screen.div("card") {
        h2("card-title", "Batería con el motor parado")
        if (points.size < 2) {
            p("muted", "Hacen falta al menos dos lecturas con el motor parado (contacto puesto, sin arrancar) para ver la evolución.")
            return@div
        }
        val latest = points.last().second
        div("big-reading") {
            span("big-value", formatNumber(latest, 2))
            span("big-unit", "V")
        }
        p("muted", "Última lectura · sana y cargada: 12,4-12,7 V")
        el("div", "chart") {
            innerHTML = voltageChart(points)
        }
        div("chart-axis") {
            span("", formatDate(points.first().first))
            span("", formatDate(points.last().first))
        }
    }
}

private fun voltageChart(points: List<Pair<Double, Double>>): String {
    val w = 320.0
    val h = 110.0
    val minV = minOf(11.8, points.minOf { it.second } - 0.1)
    val maxV = maxOf(12.9, points.maxOf { it.second } + 0.1)
    val t0 = points.first().first
    val span = (points.last().first - t0).takeIf { it > 0 } ?: 1.0
    fun x(t: Double) = 6 + (t - t0) / span * (w - 12)
    fun y(v: Double) = h - 8 - (v - minV) / (maxV - minV) * (h - 16)
    val line = points.joinToString(" ") { "${x(it.first)},${y(it.second)}" }
    val lowY = y(12.2)
    val dots = points.joinToString("") { """<rect x="${x(it.first) - 2.5}" y="${y(it.second) - 2.5}" width="5" height="5" class="chart-dot"/>""" }
    return """<svg viewBox="0 0 $w $h" preserveAspectRatio="none" class="chart-svg" role="img" aria-label="Evolución de la tensión de la batería">""" +
        """<rect x="0" y="$lowY" width="$w" height="${h - lowY}" class="chart-low"/>""" +
        """<line x1="0" x2="$w" y1="$lowY" y2="$lowY" class="chart-limit"/>""" +
        """<polyline points="$line" class="chart-line"/>$dots</svg>""" +
        """<span class="chart-label" style="top:${lowY / h * 100}%">12,2 V</span>"""
}

private fun HTMLElement.historyRow(entry: HistoryEntry) {
    div("history-row") {
        div("history-date") {
            p("history-day", formatDate(entry.timestamp))
            p("history-time", formatDateTime(entry.timestamp).substringAfter(", ", ""))
        }
        div("action-text") {
            val faults = entry.allFaults
            p("action-title", if (faults.isEmpty()) "Sin averías" else plural(faults.size, "avería", "averías"))
            p(
                "action-detail",
                listOfNotNull(
                    entry.odometerKm?.let { "${formatThousands(it)} km" },
                    entry.batteryVoltage?.let { "${formatNumber(it, 1)} V" },
                    if (entry.moduleFaults != null) "escaneo completo" else "solo motor",
                ).joinToString(" · "),
            )
            if (faults.isNotEmpty()) {
                div("chip-row history-codes") {
                    faults.take(6).forEach { span("chip tone-${it.severity.tone}", it.code) }
                    if (faults.size > 6) span("chip", "+${faults.size - 6}")
                }
            }
        }
    }
}

private fun App.confirmClearHistory() {
    showSheet("Borrar historial") { close ->
        div("sheet-icon tone-danger") { icon("trash") }
        h2("sheet-title", "¿Borrar todas las lecturas?")
        p("sheet-text", "Se pierde la evolución de la batería y el aviso de averías que vuelven. No afecta al coche.")
        div("sheet-actions") {
            button("btn btn-danger", "Borrar historial", { close(); clearHistory() }) { icon("trash") }
            button("btn btn-ghost", "Cancelar", { close() })
        }
    }
}

fun formatThousands(value: Double): String =
    kotlin.math.round(value).toLong().toString().reversed().chunked(3).joinToString(".").reversed()
