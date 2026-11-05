package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.analysis.ClearCheck
import com.sistemadiagnosis.core.analysis.EngineHealth
import com.sistemadiagnosis.core.analysis.HealthFinding
import com.sistemadiagnosis.core.knowledge.Severity
import org.w3c.dom.HTMLElement

/** Tono de un aviso: las leves en gris, nunca en verde. */
private val Severity.alertTone: String get() = if (this == Severity.LOW) "accent" else tone

internal fun HTMLElement.findingCard(finding: HealthFinding, iconName: String = "gauge") {
    div("callout tone-${finding.severity.alertTone}") {
        icon(iconName)
        div {
            p("callout-title", finding.title)
            p("", finding.detail)
        }
    }
}

internal fun HTMLElement.clearCheckCard(check: ClearCheck) {
    div("callout tone-${check.severity?.alertTone ?: "ok"}") {
        icon(if (check.severity == null) "check-circle" else "trash")
        div {
            p("callout-title", check.title)
            p("", check.detail)
        }
    }
}

/** Resumen de una línea para la fila de Inicio. */
internal fun App.engineTestsSummary(): String {
    val current = report ?: return "Leyendo…"
    val tests = current.monitorTests ?: return "Tu motor no da resultados de autopruebas"
    val findings = current.healthFindings
    return when {
        findings.isNotEmpty() -> "${plural(findings.size, "aviso", "avisos")} antes de que se encienda el testigo"
        tests.all { it.notRunYet } -> "Aún sin hacer desde el último borrado"
        else -> "${plural(tests.size, "prueba", "pruebas")} · todo dentro de límites"
    }
}

internal fun App.renderEngineTests(screen: HTMLElement) {
    val current = report
    if (current == null) {
        renderNeedsConnection(
            screen, "gauge", "Autopruebas del motor",
            "Conecta el adaptador para ver las pruebas que el motor hace solo: fallos de encendido por cilindro, catalizador y sondas lambda.",
        )
        return
    }

    screen.div("section-head") {
        div {
            p("eyebrow", "Motor · OBD-II modo 06")
            h1("section-title", "Autopruebas del motor")
        }
    }

    val tests = current.monitorTests
    if (tests == null) {
        screen.div("empty") {
            div("empty-icon") { icon("gauge") }
            h2("empty-title", "Sin autopruebas")
            p("empty-text", "La centralita del motor no ha dado resultados de sus autopruebas (modo 06), o el adaptador no ha podido leerlos.")
        }
        return
    }

    screen.p(
        "muted",
        "El motor se prueba solo mientras conduces y guarda cada resultado con sus límites. " +
            "Aquí se ve lo que va mal antes de que se encienda el testigo.",
    )

    val findings = current.healthFindings
    screen.div("alert-list") {
        if (findings.isEmpty()) {
            div("callout tone-ok") {
                icon("check-circle")
                div {
                    p("callout-title", if (tests.all { it.notRunYet }) "Pruebas sin hacer todavía" else "Todo dentro de límites")
                    p(
                        "",
                        if (tests.all { it.notRunYet }) "Se borraron las averías hace poco. Conduce unos días y vuelve a mirarlo."
                        else "Ninguna prueba ha fallado ni está cerca del límite.",
                    )
                }
            }
        } else {
            findings.forEach { findingCard(it) }
        }
    }

    val cylinders = EngineHealth.misfires(tests)
    if (cylinders.isNotEmpty()) {
        screen.div("card list-card") {
            h2("card-title", "Fallos de encendido por cilindro")
            cylinders.forEach { c ->
                val last = c.lastTrip ?: 0
                val average = c.average ?: 0
                val tone = when {
                    last >= 50 || average >= 20 -> "danger"
                    last >= 5 || average >= 3 -> "warn"
                    else -> "ok"
                }
                div("monitor-row") {
                    div("monitor-text") {
                        p("monitor-name", "Cilindro ${c.cylinder}")
                        p("monitor-detail", "Último trayecto: ${c.lastTrip ?: "—"} · media de 10 trayectos: ${c.average ?: "—"}")
                    }
                    span("status-pill tone-$tone", if (tone == "ok") "Bien" else "$last fallos")
                }
            }
        }
    }

    val others = tests.filterNot { it.isMisfire }
    others.groupBy { it.systemName }.forEach { (system, results) ->
        screen.div("card list-card") {
            h2("card-title", system)
            results.forEach { t ->
                val usage = t.limitUsage
                val (tone, label) = when {
                    t.notRunYet -> "accent" to "Sin hacer"
                    !t.hasLimits -> "accent" to EngineHealth.format(t)
                    !t.passed -> "danger" to "No pasa"
                    usage != null && usage >= EngineHealth.NEAR_LIMIT -> "warn" to "Por poco"
                    else -> "ok" to "Bien"
                }
                div("monitor-row") {
                    div("monitor-text") {
                        p("monitor-name", t.testName)
                        p(
                            "monitor-detail mono",
                            if (t.notRunYet) "—" else "${EngineHealth.format(t)} · límites ${EngineHealth.limits(t)}",
                        )
                    }
                    span("status-pill tone-$tone", label)
                }
            }
        }
    }
}
