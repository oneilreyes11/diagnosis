package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.obd.PidCatalog
import org.w3c.dom.HTMLElement
import kotlin.math.PI

internal fun App.renderReadiness(screen: HTMLElement) {
    val current = report
    if (current == null) {
        renderNeedsConnection(
            screen, "shield", "Preparación ITV",
            "Conecta el adaptador para ver el testigo de motor y los monitores de emisiones que se revisan en la inspección.",
        )
        return
    }
    val status = current.monitorStatus
    if (status == null) {
        screen.div("empty") {
            div("empty-icon") { icon("shield") }
            h2("empty-title", "Sin datos de monitores")
            p("empty-text", "La centralita del motor no ha informado del estado de sus monitores.")
        }
        return
    }

    val total = status.monitors.size
    val complete = status.completeCount
    val (tone, verdict, detail) = when {
        status.milOn -> Triple(
            "danger", "No superaría la inspección",
            "El testigo de motor está encendido. Hay que reparar la avería que lo enciende.",
        )
        status.allComplete -> Triple(
            "ok", "Listo para la inspección",
            "Testigo apagado y todos los monitores de emisiones completos.",
        )
        else -> Triple(
            "warn", "Faltan monitores por completar",
            "Algunas estaciones de inspección rechazan el coche si hay monitores sin completar. Conduce unos días antes de ir.",
        )
    }

    screen.div("section-head") {
        div {
            p("eyebrow", "Emisiones · OBD-II")
            h1("section-title", "Preparación ITV")
        }
    }

    screen.div("readiness-card tone-$tone") {
        val radius = 42.0
        val circumference = 2 * PI * radius
        val filled = if (total == 0) 0.0 else circumference * complete / total
        insertAdjacentHTML(
            "beforeend",
            """<svg class="ring" viewBox="0 0 100 100" aria-hidden="true">
                <circle class="ring-track" cx="50" cy="50" r="$radius"/>
                <circle class="ring-fill" cx="50" cy="50" r="$radius" stroke-dasharray="$filled $circumference" transform="rotate(-90 50 50)"/>
               </svg>""",
        )
        div("ring-label") {
            span("ring-value", "$complete/$total")
            span("ring-caption", "completos")
        }
        div("readiness-text") {
            h2("readiness-verdict", verdict)
            p("readiness-detail", detail)
        }
    }

    screen.div("card list-card") {
        div("monitor-row") {
            div("monitor-text") {
                p("monitor-name", "Testigo de motor")
                p("monitor-detail", "Se enciende cuando hay una avería que afecta a las emisiones.")
            }
            span("status-pill tone-${if (status.milOn) "danger" else "ok"}", if (status.milOn) "Encendido" else "Apagado")
        }
        status.monitors.sortedBy { it.complete }.forEach { state ->
            div("monitor-row") {
                div("monitor-text") {
                    p("monitor-name", state.monitor.label)
                    p("monitor-detail", state.monitor.description)
                }
                span("status-pill tone-${if (state.complete) "ok" else "warn"}", if (state.complete) "Completo" else "Pendiente")
            }
        }
    }

    if (current.tripData.isNotEmpty()) {
        screen.div("card") {
            h2("card-title", "Desde el último borrado")
            el("dl", "facts") {
                current.tripData.forEach { (pid, value) ->
                    PidCatalog[pid]?.let { fact(it.name, it.formatWithUnit(value)) }
                }
            }
        }
    }

    screen.div("card") {
        h2("card-title", "Cómo completar los monitores")
        el("ul", "check-list") {
            el("li", "", "Conduce varios días con trayectos variados: arranque en frío, 15-20 minutos de carretera a velocidad constante y algo de ciudad.")
            el("li", "", "El monitor EVAP suele necesitar el depósito entre 1/4 y 3/4 y que el coche pase la noche parado.")
            el("li", "", "No borres averías ni desconectes la batería justo antes de la inspección: los monitores vuelven a empezar.")
        }
    }
}
