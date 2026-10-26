package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import org.w3c.dom.HTMLElement

internal fun App.renderHome(screen: HTMLElement) {
    when (val state = link) {
        LinkState.Disconnected -> renderWelcome(screen)
        LinkState.Connecting -> renderConnecting(screen)
        is LinkState.Connected -> renderDashboard(screen, state)
    }
}

private fun App.renderWelcome(screen: HTMLElement) {
    screen.div("hero") {
        div("hero-visual") { icon("car", "hero-icon") }
        p("eyebrow", "${vehicle.brand} · ${vehicle.platform}")
        h1("hero-title", "${vehicle.displayName} ${vehicle.generation}")
        p("hero-sub", "${vehicle.year} · ${vehicle.engine.name} ${vehicle.engine.code} · ${vehicle.engine.power}")
        div("hero-actions") {
            button("btn btn-primary btn-lg", "Conectar adaptador", { connectAdapter() }) { icon("bluetooth") }
            button("btn btn-ghost btn-lg", "Ver demostración", { connectDemo() }) { icon("play") }
        }
    }

    if (!WebBluetooth.isAvailable()) {
        screen.div("notice notice-warn") {
            icon("info")
            div {
                p("notice-title", "Este navegador no puede usar Bluetooth")
                el("p", "notice-text") {
                    innerHTML = "En iPhone, abre esta página con la app gratuita " +
                        "<a href=\"https://apps.apple.com/app/id1492822055\" target=\"_blank\" rel=\"noopener\">Bluefy</a>. " +
                        "En Android u ordenador, usa Chrome o Edge."
                }
            }
        }
    }

    screen.div("card") {
        h2("card-title", "Cómo conectar")
        el("ol", "steps") {
            step("Enchufa el adaptador", "En el conector OBD, bajo el volante.")
            step("Pon el contacto", "Para ver datos en vivo, arranca el motor.")
            step("Pulsa «Conectar adaptador»", "Y elige tu adaptador Bluetooth en la lista.")
        }
    }

    screen.div("feature-grid") {
        feature("alert", "Averías explicadas", "Qué significa cada código, causas probables y qué hacer.")
        feature("activity", "Datos en vivo", "Más de 30 sensores del motor con gráfica en tiempo real.")
        feature("snapshot", "Datos congelados", "La foto de los sensores en el instante del fallo.")
        feature("shield", "Preparación ITV", "Testigo de motor y monitores de emisiones.")
    }
}

private fun HTMLElement.step(title: String, detail: String) {
    el("li", "step") {
        span("step-dot")
        div {
            p("step-title", title)
            p("step-detail", detail)
        }
    }
}

private fun HTMLElement.feature(iconName: String, title: String, detail: String) {
    div("feature") {
        div("feature-icon") { icon(iconName) }
        p("feature-title", title)
        p("feature-detail", detail)
    }
}

private fun App.renderConnecting(screen: HTMLElement) {
    screen.div("hero hero-busy") {
        div("hero-visual") {
            div("spinner spinner-lg")
        }
        h1("hero-title", "Conectando…")
        p("hero-sub", "Inicializando el adaptador y estableciendo comunicación con el motor de tu ${vehicle.displayName}. Puede tardar unos segundos.")
    }
}

private fun App.renderDashboard(screen: HTMLElement, state: LinkState.Connected) {
    val current = report
    val info = state.info

    if (state.demo) {
        screen.div("notice notice-accent") {
            icon("play")
            div {
                p("notice-title", "Modo demostración")
                p("notice-text", "Datos simulados de un ${vehicle.displayName} ${vehicle.engine.name}. Conecta tu adaptador para leer tu coche.")
            }
        }
    }

    val tone: String
    val headline: String
    val subline: String
    val statusIcon: String
    when {
        current == null -> {
            tone = "accent"; statusIcon = "engine"
            headline = "Analizando el motor…"
            subline = "Leyendo averías, monitores y datos congelados."
        }
        current.faults.isEmpty() -> {
            tone = "ok"; statusIcon = "check-circle"
            headline = "Todo en orden"
            subline = "Sin averías en la centralita del motor."
        }
        else -> {
            val worst = current.highestSeverity ?: Severity.LOW
            tone = worst.tone; statusIcon = "alert"
            val onlyPending = current.faults.all { it.kinds == setOf(DtcKind.PENDING) }
            headline = if (onlyPending) plural(current.faults.size, "aviso pendiente", "avisos pendientes")
            else plural(current.faults.size, "avería", "averías")
            subline = when (worst) {
                Severity.HIGH -> "Revísalo cuanto antes: ignorarlo puede dañar el motor o el catalizador."
                Severity.MEDIUM -> "Revísalo pronto. Puedes circular con normalidad."
                Severity.LOW -> "Leve. Puedes circular con normalidad."
            }
        }
    }

    screen.div("status-card tone-$tone") {
        div("status-ring") {
            if (scanning && current == null) div("spinner") else icon(statusIcon, "status-icon")
        }
        div("status-text") {
            p("eyebrow", "Motor · dirección ${vehicle.engineEcu.address}")
            h1("status-title", headline)
            p("status-sub", subline)
        }
    }

    val status = current?.monitorStatus
    screen.div("metric-grid") {
        metric(
            "Testigo motor",
            when (status?.milOn) { true -> "Encendido"; false -> "Apagado"; null -> "—" },
            when (status?.milOn) { true -> "danger"; false -> "ok"; null -> "" },
        )
        metric("Averías", current?.faults?.size?.toString() ?: "—", if ((current?.faults?.size ?: 0) > 0) current?.highestSeverity?.tone ?: "" else if (current != null) "ok" else "")
        metric("Monitores ITV", status?.let { "${it.completeCount}/${it.monitors.size}" } ?: "—", status?.let { if (it.allComplete) "ok" else "warn" } ?: "")
        metric(
            "Batería",
            info.batteryVoltage?.let { "${formatNumber(it, 1)} V" } ?: "—",
            info.batteryVoltage?.let { if (it < 12.2) "warn" else "ok" } ?: "",
        )
    }

    screen.div("card list-card") {
        actionRow("alert", "Averías", current?.let {
            if (it.faults.isEmpty()) "Ninguna en el motor" else "${plural(it.faults.size, "código", "códigos")} con explicación"
        } ?: "Leyendo…") { navigate(Tab.FAULTS) }
        actionRow("activity", "Datos en vivo", "${PidCatalog.all.count { it.pid in info.supportedPids }} sensores disponibles") { navigate(Tab.LIVE) }
        actionRow("shield", "Preparación ITV", status?.let {
            if (it.allComplete) "Todos los monitores completos" else "${plural(it.incomplete.size, "monitor pendiente", "monitores pendientes")}"
        } ?: "Leyendo…") { navigate(Tab.READINESS) }
        if (current != null) actionRow("share", "Compartir informe", "Envíalo a tu taller o guárdalo") { shareReport() }
    }

    screen.div("button-row") {
        button("btn btn-ghost", if (scanning) "Analizando…" else "Volver a analizar", { diagnose() }) {
            icon("refresh")
            if (scanning) setAttribute("disabled", "")
        }
    }

    screen.div("card") {
        h2("card-title", "Vehículo")
        el("dl", "facts") {
            val vin = info.vin
            fact("VIN", vin?.value ?: "No disponible", mono = true)
            fact(
                "Identificación",
                when (info.vehicleMatch) {
                    true -> "Coincide con tu ${vehicle.displayName}" + (vin?.modelYear?.let { " · $it" } ?: "")
                    false -> "No coincide con el perfil"
                    null -> "Sin VIN"
                },
            )
            fact("Protocolo", info.obdProtocol.displayName)
            fact("Adaptador", info.adapter.version)
        }
    }
}

private fun HTMLElement.metric(label: String, value: String, tone: String) {
    div("metric${if (tone.isEmpty()) "" else " tone-$tone"}") {
        p("metric-label", label)
        p("metric-value", value)
    }
}

internal fun HTMLElement.actionRow(iconName: String, title: String, detail: String, action: () -> Unit) {
    div("action-row") {
        pressable(title, action)
        div("action-icon") { icon(iconName) }
        div("action-text") {
            p("action-title", title)
            p("action-detail", detail)
        }
        icon("chevron", "icon icon-muted chevron")
    }
}

internal fun HTMLElement.fact(label: String, value: String, mono: Boolean = false) {
    div("fact") {
        el("dt", "", label)
        el("dd", if (mono) "mono" else "", value)
    }
}

/** Estado vacío para pantallas que necesitan conexión. */
internal fun App.renderNeedsConnection(screen: HTMLElement, iconName: String, title: String, detail: String) {
    screen.div("empty") {
        div("empty-icon") { icon(iconName) }
        h2("empty-title", title)
        p("empty-text", detail)
        when (link) {
            LinkState.Connecting -> div("spinner")
            is LinkState.Connected -> div("spinner")
            LinkState.Disconnected -> div("empty-actions") {
                button("btn btn-primary", "Conectar adaptador", { connectAdapter() }) { icon("bluetooth") }
                button("btn btn-ghost", "Ver demostración", { connectDemo() }) { icon("play") }
            }
        }
    }
}
