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

    screen.div("card list-card") {
        actionRow("clock", "Historial", "${plural(history.size, "lectura guardada", "lecturas guardadas")}") { navigate(Tab.HISTORY) }
        actionRow("gauge", "Autopruebas del motor", "Fallos de encendido por cilindro, catalizador y sondas") { navigate(Tab.TESTS) }
        actionRow("gauge", "Valores de medición", "Explorador de datos de cada centralita (solo lectura)") { navigate(Tab.EXPLORER) }
        actionRow("share", "Informe PDF", "Para el taller o para vender el coche") { exportPdf() }
    }

    screen.div("card") {
        h2("card-title", "Funciones de servicio")
        div("service-item") {
            p("service-title", "Poner a cero el aviso de servicio")
            p(
                "muted",
                "En el A3 8Y se hace desde el propio coche, sin herramientas: en el MMI, menú Vehículo › Servicio y controles › " +
                    "Intervalos de servicio (los nombres cambian un poco según la versión del software). Si la opción no aparece, lo hace el taller.",
            )
        }
        div("service-item") {
            p("service-title", "Borrar averías")
            p("muted", "Disponible en Averías: del motor por OBD-II y de cada centralita tras el escaneo completo.")
        }
        div("service-item") {
            p("service-title", "Lo que no hace esta app")
            p(
                "muted",
                "Reset de servicio por diagnosis, ajustes básicos, adaptaciones y codificaciones: en tu A3 son escrituras en centralitas " +
                    "protegidas por la protección de diagnosis (SFD) de Volkswagen. Sin su desbloqueo oficial la centralita las rechaza, " +
                    "y escribir a ciegas puede dejar el coche mal configurado. Aquí todo es de lectura, salvo borrar averías.",
            )
        }
    }

    screen.div("card") {
        h2("card-title", "Registro de conexión")
        p(
            "muted",
            "Guarda cada paso del Bluetooth y todo lo que se envía y recibe, también entre intentos y aunque cierres la app. " +
                "Prueba en el coche y luego compártelo de una vez.",
        )
        p("muted", if (DebugLog.isEmpty) "Vacío." else "${DebugLog.size} líneas guardadas.")
        div("button-row") {
            button("btn btn-primary btn-sm", "Compartir", { DebugLog.share() }) { icon("share") }
            button("btn btn-ghost btn-sm", "Copiar", { DebugLog.copy() }) { icon("copy") }
            button("btn btn-ghost btn-sm", "Borrar", {
                DebugLog.clear()
                toast("Registro borrado")
                render()
            })
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
            el("li", "", "Escaneo de todas las centralitas por UDS, historial de lecturas e informe en PDF.")
            el("li", "", "Codificación y adaptaciones no disponibles: tu A3 lleva protección de diagnosis (SFD) que solo Volkswagen puede desbloquear.")
            el("li", "", "Orientativo: no sustituye la revisión de un profesional.")
        }
        p("muted version", "Diagnosis · fase 5")
    }
}
