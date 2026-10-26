package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.FaultEntry
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import org.w3c.dom.HTMLElement

internal fun App.renderFaults(screen: HTMLElement) {
    val current = report
    if (current == null) {
        renderNeedsConnection(
            screen, "alert", if (scanning) "Leyendo averías…" else "Averías del motor",
            if (scanning) "Consultando las averías confirmadas, pendientes y permanentes."
            else "Conecta el adaptador para leer las averías del motor y ver qué significa cada una.",
        )
        return
    }

    screen.div("section-head") {
        div {
            p("eyebrow", "Motor · dirección ${vehicle.engineEcu.address}")
            h1("section-title", if (current.faults.isEmpty()) "Sin averías" else plural(current.faults.size, "avería", "averías"))
        }
        div("head-actions") {
            button("icon-button", null, { diagnose() }) {
                setAttribute("aria-label", "Volver a leer")
                if (scanning) setAttribute("disabled", "")
                icon("refresh")
            }
            button("icon-button", null, { shareReport() }) {
                setAttribute("aria-label", "Compartir informe")
                icon("share")
            }
        }
    }

    if (current.faults.isEmpty()) {
        screen.div("empty empty-ok") {
            div("empty-icon") { icon("check-circle") }
            h2("empty-title", "El motor no tiene averías guardadas")
            p("empty-text", "Ni confirmadas, ni pendientes, ni permanentes. Si notas algo raro al conducir, mira los datos en vivo.")
        }
    } else {
        val counts = Severity.entries.reversed().mapNotNull { severity ->
            current.faults.count { it.severity == severity }.takeIf { it > 0 }?.let { severity to it }
        }
        screen.div("severity-strip") {
            counts.forEach { (severity, count) ->
                span("chip tone-${severity.tone}") {
                    span("chip-dot")
                    span("", "$count ${severity.label.lowercase()}${if (count > 1) "s" else ""}")
                }
            }
        }
        screen.div("fault-list") {
            current.faults.forEach { fault -> faultCard(this@renderFaults, fault) }
        }
    }

    val clearable = clearableCount
    if (clearable > 0) {
        screen.div("button-row") {
            button("btn btn-danger-ghost", "Borrar averías", { confirmClear() }) { icon("trash") }
        }
    }

    screen.div("notice notice-muted") {
        icon("info")
        p("notice-text", "Esta lectura cubre la centralita del motor. El escaneo del resto de centralitas (cambio, ABS, airbag, confort…) llegará en la siguiente fase.")
    }
}

private fun HTMLElement.faultCard(app: App, fault: FaultEntry) {
    val description = fault.description
    div("fault-card tone-${fault.severity.tone}") {
        pressable("${fault.dtc.code}: ${description.title}") { app.showFaultDetail(fault) }
        div("fault-top") {
            span("code", fault.dtc.code)
            div("fault-kinds") { kindChips(fault.kinds) }
        }
        p("fault-title", description.title)
        p("fault-summary", description.explanation)
        div("fault-bottom") {
            span("severity") {
                span("severity-dot")
                span("", "Gravedad ${fault.severity.label.lowercase()}")
            }
            icon("chevron", "icon icon-muted")
        }
    }
}

private fun HTMLElement.kindChips(kinds: Set<DtcKind>) {
    DtcKind.entries.filter { it in kinds }.forEach { kind ->
        span("chip chip-${kind.name.lowercase()}", kind.label)
    }
}

internal fun App.showFaultDetail(fault: FaultEntry) {
    val description = fault.description
    val frame = report?.freezeFrame?.takeIf { it.trigger == fault.dtc }
    showSheet("${fault.dtc.code}: ${description.title}") { close ->
        div("detail-head") {
            span("code code-lg", fault.dtc.code)
            span("severity-badge tone-${fault.severity.tone}", fault.severity.label)
        }
        h2("sheet-title", description.title)
        p("meta", "${description.systemLabel} · ${description.origin}")
        div("chip-row") { kindChips(fault.kinds) }

        div("callout tone-${fault.severity.tone}") {
            icon(if (fault.severity == Severity.LOW) "info" else "alert")
            p("", description.severity.advice)
        }

        section("Qué significa") { p("prose", description.explanation) }

        description.vehicleNote?.let { note ->
            div("callout tone-accent callout-vehicle") {
                icon("car")
                div {
                    p("callout-title", "En tu ${vehicle.displayName} ${vehicle.engine.name}")
                    p("", note)
                }
            }
        }

        if (description.symptoms.isNotEmpty()) {
            section("Síntomas habituales") {
                el("ul", "bullets") { description.symptoms.forEach { el("li", "", it) } }
            }
        }
        if (description.causes.isNotEmpty()) {
            section("Causas probables", "De más a menos frecuente") {
                el("ol", "ranked") { description.causes.forEach { el("li", "", it) } }
            }
        }
        section("Qué hacer") { p("prose", description.advice) }

        if (frame != null && frame.values.isNotEmpty()) {
            section("Datos congelados", "Así estaba el motor cuando se confirmó la avería") {
                el("dl", "facts facts-grid") {
                    frame.values.entries
                        .mapNotNull { (pid, value) -> PidCatalog[pid]?.let { it to value } }
                        .sortedBy { (definition, _) -> PidCatalog.all.indexOf(definition) }
                        .forEach { (definition, value) -> fact(definition.name, definition.formatWithUnit(value)) }
                }
            }
        }

        if (!description.isDocumented) {
            div("callout tone-muted") {
                icon("info")
                p("", "Código aún sin ficha detallada. Te mostramos lo que indica su familia.")
            }
        }

        div("sheet-actions") {
            el("a", "btn btn-ghost") {
                setAttribute("href", "https://www.google.com/search?q=${fault.dtc.code}+${vehicle.brand}+${vehicle.model}+${vehicle.engine.name.replace(" ", "+")}")
                setAttribute("target", "_blank")
                setAttribute("rel", "noopener")
                icon("search")
                span("label", "Buscar más información")
            }
            button("btn btn-primary", "Entendido", { close() })
        }
    }
}

private fun HTMLElement.section(title: String, subtitle: String? = null, build: Build) {
    div("detail-section") {
        h3("detail-title", title)
        if (subtitle != null) p("detail-subtitle", subtitle)
        build()
    }
}

private fun App.confirmClear() {
    showSheet("Borrar averías") { close ->
        div("sheet-icon tone-danger") { icon("trash") }
        h2("sheet-title", "¿Borrar las averías del motor?")
        el("ul", "check-list") {
            el("li", "", "Se borran las averías confirmadas y pendientes y los datos congelados.")
            el("li", "", "Las permanentes no se borran: desaparecen solas cuando la centralita comprueba que el fallo está corregido.")
            el("li", "", "Los monitores de la ITV se reinician: hay que conducir unos días para que se completen.")
            el("li", "", "Si la causa sigue ahí, la avería volverá.")
        }
        div("callout tone-muted") {
            icon("info")
            p("", "Hazlo con el contacto puesto y el motor parado.")
        }
        div("sheet-actions") {
            button("btn btn-danger", "Borrar averías", { close(); clearFaults() }) { icon("trash") }
            button("btn btn-ghost", "Cancelar", { close() })
        }
    }
}
