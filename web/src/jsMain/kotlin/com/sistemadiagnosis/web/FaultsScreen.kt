package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.FaultEntry
import com.sistemadiagnosis.core.ModuleFault
import com.sistemadiagnosis.core.ModuleScanResult
import com.sistemadiagnosis.core.knowledge.DtcDescription
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.uds.UdsDtcState
import com.sistemadiagnosis.core.util.toHex
import org.w3c.dom.HTMLElement

internal fun App.renderFaults(screen: HTMLElement) {
    if (connection == null && moduleResults.isEmpty()) {
        renderNeedsConnection(
            screen, "alert", "Averías",
            "Conecta el adaptador para leer las averías de todas las centralitas y ver qué significa cada una.",
        )
        return
    }

    screen.div("section-head") {
        div {
            p("eyebrow", "${vehicle.displayName} ${vehicle.generation}")
            h1("section-title", "Averías")
        }
        div("head-actions") {
            button("icon-button", null, { shareReport() }) {
                setAttribute("aria-label", "Compartir informe")
                icon("share")
            }
        }
    }

    renderModuleScan(screen)
    renderEngineObd(screen)
}

// --- Escaneo completo -------------------------------------------------------------

private fun App.renderModuleScan(screen: HTMLElement) {
    val results = moduleResults
    val total = vehicle.modules.size
    screen.div("card scan-card") {
        div("scan-head") {
            div("action-icon") { icon("search") }
            div("action-text") {
                p("action-title", "Todas las centralitas")
                p(
                    "action-detail",
                    when {
                        moduleScanning -> "Leyendo ${results.size + 1} de $total…"
                        results.isEmpty() -> "Motor, cambio, frenos, airbag, cuadro, confort… $total direcciones."
                        else -> summary(results)
                    },
                )
            }
        }

        if (moduleScanning) {
            val current = vehicle.modules.getOrNull(results.size)
            div("progress") {
                div("progress-fill") { style.width = "${results.size * 100 / total}%" }
            }
            current?.let { p("progress-label", "${it.address} · ${it.name}") }
        } else if (results.isEmpty()) {
            button("btn btn-primary", "Escanear todas las centralitas", { scanAllModules() }) {
                if (connection == null) setAttribute("disabled", "")
                icon("search")
            }
            p("muted", "Solo lectura: identifica cada centralita y lee su memoria de averías. Tarda unos segundos.")
        }

        val present = results.filter { it.present }
        if (present.isNotEmpty()) {
            div("module-list") {
                present.sortedWith(compareByDescending<ModuleScanResult> { it.highestSeverity }.thenBy { it.module.address })
                    .forEach { moduleRow(this@renderModuleScan, it) }
            }
        }

        val absent = results.filter { !it.present }
        if (absent.isNotEmpty() && !moduleScanning) {
            el("details", "absent-modules") {
                el("summary", "", "${plural(absent.size, "dirección no responde", "direcciones no responden")} (no montadas)")
                el("ul", "bullets") { absent.forEach { el("li", "", "${it.module.address} · ${it.module.name}") } }
            }
        }

        if (results.isNotEmpty() && !moduleScanning) {
            div("button-row") {
                button("btn btn-ghost", "Volver a escanear", { scanAllModules() }) {
                    if (connection == null) setAttribute("disabled", "")
                    icon("refresh")
                }
                if (results.any { it.faults.isNotEmpty() }) {
                    button("btn btn-danger-ghost", "Borrar todas", { confirmClearAll() }) {
                        if (connection == null) setAttribute("disabled", "")
                        icon("trash")
                    }
                }
            }
        }
    }
}

private fun summary(results: List<ModuleScanResult>): String {
    val present = results.count { it.present }
    val faults = results.sumOf { it.faults.size }
    return "${plural(present, "centralita", "centralitas")} · " +
        if (faults == 0) "sin averías" else plural(faults, "avería", "averías")
}

private fun HTMLElement.moduleRow(app: App, result: ModuleScanResult) {
    val worst = result.highestSeverity
    val tone = when {
        result.error != null -> "muted"
        worst != null -> worst.tone
        else -> "ok"
    }
    div("module-row") {
        pressable("${result.module.address} ${result.module.name}") { app.showModuleDetail(result) }
        span("module-address", result.module.address)
        div("action-text") {
            p("action-title", result.module.name)
            p("action-detail", listOfNotNull(result.identity?.systemName, result.identity?.partNumber).joinToString(" · ").ifEmpty { "Sin identificación" })
        }
        span(
            "status-pill tone-$tone",
            when {
                result.error != null -> "Error"
                result.faults.isEmpty() -> "OK"
                else -> plural(result.faults.size, "avería", "averías")
            },
        )
        icon("chevron", "icon icon-muted chevron")
    }
}

private fun App.showModuleDetail(result: ModuleScanResult) {
    showSheet("${result.module.address} ${result.module.name}") { close ->
        div("detail-head") {
            span("code code-lg", result.module.address)
            val worst = result.highestSeverity
            span(
                "severity-badge tone-${worst?.tone ?: "ok"}",
                if (result.faults.isEmpty()) "Sin averías" else plural(result.faults.size, "avería", "averías"),
            )
        }
        h2("sheet-title", result.module.name)
        result.identity?.systemName?.let { p("meta", it) }

        el("dl", "facts") {
            result.identity?.partNumber?.let { fact("Referencia", it, mono = true) }
            result.identity?.softwareVersion?.let { fact("Versión de software", it, mono = true) }
            result.identity?.hardwareNumber?.let { if (it != result.identity?.partNumber) fact("Hardware", it, mono = true) }
            fact("Dirección CAN", "${result.module.requestId.toHex(3)} → ${result.module.responseId.toHex(3)}", mono = true)
        }

        result.error?.let { error ->
            div("callout tone-warn") {
                icon("alert")
                p("", "Respondió, pero no se pudo leer del todo: $error")
            }
        }

        if (result.faults.isEmpty() && result.error == null) {
            div("callout tone-ok") {
                icon("check-circle")
                p("", "La memoria de averías de esta centralita está vacía.")
            }
        }

        if (result.faults.isNotEmpty()) {
            div("detail-section") {
                h3("detail-title", "Memoria de averías")
                div("fault-list") {
                    result.faults.forEach { fault -> moduleFaultCard(this@showModuleDetail, result, fault) }
                }
            }
        }

        div("sheet-actions") {
            if (result.faults.isNotEmpty()) {
                button("btn btn-danger-ghost", "Borrar averías de esta centralita", {
                    close()
                    confirmClearModule(result)
                }) { icon("trash") }
            }
            button("btn btn-primary", "Cerrar", { close() })
        }
    }
}

private fun HTMLElement.moduleFaultCard(app: App, module: ModuleScanResult, fault: ModuleFault) {
    div("fault-card tone-${fault.severity.tone}") {
        pressable("${fault.code.displayCode}: ${fault.description.title}") { app.showModuleFaultDetail(module, fault) }
        div("fault-top") {
            span("code", fault.code.displayCode)
            div("fault-kinds") { stateChips(fault) }
        }
        p("fault-title", fault.description.title)
        fault.code.failureTypeDescription?.let { p("fault-subtype", it) }
        div("fault-bottom") {
            span("severity") {
                span("severity-dot")
                span("", "Gravedad ${fault.severity.label.lowercase()}")
            }
            icon("chevron", "icon icon-muted")
        }
    }
}

private fun HTMLElement.stateChips(fault: ModuleFault) {
    val state = fault.code.state
    span(
        "chip " + when (state) {
            UdsDtcState.ACTIVE -> "chip-stored"
            UdsDtcState.INTERMITTENT -> "chip-permanent"
            UdsDtcState.PENDING -> "chip-pending"
            UdsDtcState.STORED -> ""
        },
        state.label,
    )
    if (fault.code.warningLamp) span("chip chip-stored", "Testigo")
}

private fun App.showModuleFaultDetail(module: ModuleScanResult, fault: ModuleFault) {
    showDtcDetail(
        codeLabel = fault.code.displayCode,
        description = fault.description,
        severity = fault.severity,
        meta = "${module.module.address} · ${module.module.name}",
        chips = { stateChips(fault) },
    ) {
        div("detail-section") {
            h3("detail-title", "Estado")
            p("prose", fault.code.state.description + if (fault.code.warningLamp) " La centralita pide encender un testigo." else "")
            fault.code.failureTypeDescription?.let {
                p("prose", "Tipo de fallo (${fault.code.failureType.toHex(2)}): $it.")
            }
        }
    }
}

private fun App.confirmClearModule(result: ModuleScanResult) {
    showSheet("Borrar averías") { close ->
        div("sheet-icon tone-danger") { icon("trash") }
        h2("sheet-title", "¿Borrar las averías de ${result.module.name.lowercase()}?")
        el("ul", "check-list") {
            el("li", "", "Se vacía la memoria de averías de esta centralita.")
            el("li", "", "Si la causa sigue ahí, la avería volverá a aparecer.")
            if (result.module == vehicle.engineEcu) {
                el("li", "", "En el motor también se borran los datos congelados y se reinician los monitores de la ITV.")
            }
        }
        div("sheet-actions") {
            button("btn btn-danger", "Borrar averías", { close(); clearModule(result) }) { icon("trash") }
            button("btn btn-ghost", "Cancelar", { close() })
        }
    }
}

private fun App.confirmClearAll() {
    val withFaults = moduleResults.filter { it.faults.isNotEmpty() }
    showSheet("Borrar todas las averías") { close ->
        div("sheet-icon tone-danger") { icon("trash") }
        h2("sheet-title", "¿Borrar las averías de ${plural(withFaults.size, "centralita", "centralitas")}?")
        el("ul", "check-list") {
            el("li", "", withFaults.joinToString { "${it.module.address} ${it.module.name}" } + ".")
            el("li", "", "Si alguna causa sigue ahí, esa avería volverá a aparecer.")
            if (withFaults.any { it.module == vehicle.engineEcu }) {
                el("li", "", "En el motor se reinician también los monitores de la ITV.")
            }
        }
        div("callout tone-muted") {
            icon("info")
            p("", "Hazlo con el contacto puesto y el motor parado.")
        }
        div("sheet-actions") {
            button("btn btn-danger", "Borrar todas", { close(); clearAllModules() }) { icon("trash") }
            button("btn btn-ghost", "Cancelar", { close() })
        }
    }
}

// --- Motor por OBD-II -------------------------------------------------------------

private fun App.renderEngineObd(screen: HTMLElement) {
    val current = report
    screen.div("group-head") {
        h2("group-title", "Motor · emisiones (OBD-II)")
        button("icon-button", null, { diagnose() }) {
            setAttribute("aria-label", "Volver a leer")
            if (scanning || connection == null) setAttribute("disabled", "")
            icon("refresh")
        }
    }

    if (current == null) {
        screen.div("card") {
            p("muted", if (scanning) "Leyendo las averías del motor…" else "Sin lectura del motor.")
        }
        return
    }

    if (current.faults.isEmpty()) {
        screen.div("empty empty-ok empty-compact") {
            div("empty-icon") { icon("check-circle") }
            h2("empty-title", "Sin averías de emisiones")
            p("empty-text", "Ni confirmadas, ni pendientes, ni permanentes. El resto de averías del motor aparecen en «01» del escaneo completo.")
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
            current.faults.forEach { fault -> faultCard(this@renderEngineObd, fault) }
        }
    }

    if (clearableCount > 0) {
        screen.div("button-row") {
            button("btn btn-danger-ghost", "Borrar averías del motor", { confirmClear() }) { icon("trash") }
        }
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
    val frame = report?.freezeFrame?.takeIf { it.trigger == fault.dtc }
    showDtcDetail(
        codeLabel = fault.dtc.code,
        description = fault.description,
        severity = fault.severity,
        meta = "${fault.description.systemLabel} · ${fault.description.origin}",
        chips = { kindChips(fault.kinds) },
    ) {
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
    }
}

/** Ficha de una avería: la misma para OBD-II y para cualquier centralita. */
private fun App.showDtcDetail(
    codeLabel: String,
    description: DtcDescription,
    severity: Severity,
    meta: String,
    chips: Build,
    extra: Build = {},
) {
    showSheet("$codeLabel: ${description.title}") { close ->
        div("detail-head") {
            span("code code-lg", codeLabel)
            span("severity-badge tone-${severity.tone}", severity.label)
        }
        h2("sheet-title", description.title)
        p("meta", meta)
        div("chip-row") { chips() }

        div("callout tone-${severity.tone}") {
            icon(if (severity == Severity.LOW) "info" else "alert")
            p("", severity.advice)
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

        extra()

        if (!description.isDocumented) {
            div("callout tone-muted") {
                icon("info")
                p("", "Código aún sin ficha detallada. Te mostramos lo que indica su familia.")
            }
        }

        div("sheet-actions") {
            el("a", "btn btn-ghost") {
                setAttribute("href", "https://www.google.com/search?q=$codeLabel+${vehicle.brand}+${vehicle.model}+${vehicle.generation}")
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
