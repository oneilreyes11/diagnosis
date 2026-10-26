package com.sistemadiagnosis.web

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal fun App.renderMore(screen: HTMLElement) {
    screen.div("section-head") {
        div {
            p("eyebrow", "Ajustes e información")
            h1("section-title", "Más")
        }
    }

    screen.div("card vehicle-card") {
        div("vehicle-head") {
            div("brand-mark brand-mark-lg") { icon("car") }
            div {
                p("vehicle-name", "${vehicle.displayName} ${vehicle.generation}")
                p("vehicle-sub", "${vehicle.platform} · ${vehicle.year}")
            }
        }
        el("dl", "facts") {
            fact("Motor", "${vehicle.engine.name} · ${vehicle.engine.code}")
            fact("Familia", vehicle.engine.family)
            fact("Potencia", vehicle.engine.power)
            fact("Combustible", vehicle.engine.fuel)
        }
        div("chip-row") { vehicle.engine.features.forEach { span("chip", it) } }
    }

    val info = connection
    screen.div("card") {
        h2("card-title", "Conexión")
        if (info == null) {
            p("muted", "Sin adaptador conectado.")
            div("button-row") {
                button("btn btn-primary", "Conectar adaptador", { connectAdapter() }) { icon("bluetooth") }
            }
        } else {
            el("dl", "facts") {
                fact("Modo", if (isDemo) "Demostración (datos simulados)" else "Adaptador real")
                fact("Adaptador", info.adapter.version)
                fact("Protocolo", info.obdProtocol.displayName)
                info.batteryVoltage?.let { fact("Tensión en el conector", "${formatNumber(it, 1)} V") }
                fact("Centralita", "${vehicle.engineEcu.address} · ${vehicle.engineEcu.name}")
                info.adapter.warnings.forEach { fact("Aviso", it) }
            }
            div("button-row") {
                button("btn btn-ghost", "Desconectar", { disconnect() }) { icon("logout") }
            }
        }
    }

    screen.el("details", "card log-card") {
        el("summary", "log-summary") {
            icon("terminal")
            span("", "Tráfico con el adaptador")
            span("muted log-count", "${traffic.size} líneas")
        }
        el("pre", "log", if (traffic.isEmpty()) "Aún no hay tráfico." else traffic.joinToString("\n"))
        if (traffic.isNotEmpty()) {
            div("button-row") {
                button("btn btn-ghost btn-sm", "Copiar", {
                    window.navigator.asDynamic().clipboard?.writeText(traffic.joinToString("\n"))
                    toast("Tráfico copiado", "ok")
                }) { icon("copy") }
            }
        }
    }

    screen.div("card") {
        h2("card-title", "Acerca de")
        el("ul", "check-list") {
            el("li", "", "Diagnóstico de la centralita del motor por OBD-II (SAE J1979): averías, datos en vivo, datos congelados y monitores de emisiones.")
            el("li", "", "Codificación y adaptaciones no disponibles: tu A3 lleva protección de diagnosis (SFD) que solo Volkswagen puede desbloquear.")
            el("li", "", "Orientativo: no sustituye la revisión de un profesional.")
        }
        p("muted version", "Diagnosis · fase 2")
    }
}
