package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.DidReading
import com.sistemadiagnosis.core.obd.describeNegativeResponse
import com.sistemadiagnosis.core.util.toHex
import com.sistemadiagnosis.core.vehicle.EcuDefinition
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement

/**
 * Explorador de valores de medición. Los identificadores (DID) de VAG con la
 * temperatura del aceite, el servicio, etc. no son públicos: se buscan en el
 * coche real leyendo bloques de identificadores (solo lectura) y se apuntan los
 * que contestan para identificarlos después.
 */
class ExplorerState {
    var module: EcuDefinition? = null
    var blockStart = BLOCKS.first().first
    var readings: List<DidReading> = emptyList()
    var running = false
    var job: Job? = null

    val found: List<DidReading> get() = readings.filter { it.found }

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }

    companion object {
        const val BLOCK_SIZE = 0x100

        /** Bloques donde las centralitas del grupo suelen tener datos; F1xx es identificación estándar. */
        val BLOCKS = listOf(
            0x0100 to "Estado y contadores",
            0x0200 to "Estado y contadores",
            0x0400 to "Configuración",
            0x0600 to "Codificación (lectura)",
            0x1000 to "Medidas",
            0x1100 to "Medidas",
            0x1200 to "Medidas",
            0x2000 to "Medidas",
            0x2200 to "Medidas",
            0x2900 to "Medidas",
            0x4000 to "Medidas",
            0xF100 to "Identificación",
            0xF400 to "Espejo OBD",
        )
    }
}

internal fun App.renderExplorer(screen: HTMLElement) {
    val state = explorer
    val module = state.module ?: vehicle.engineEcu
    screen.div("section-head") {
        div {
            p("eyebrow", "Avanzado · solo lectura")
            h1("section-title", "Valores de medición")
        }
    }

    screen.div("notice notice-accent") {
        icon("info")
        div {
            p("notice-title", "Cómo funciona")
            p(
                "notice-text",
                "Volkswagen no publica dónde guarda cada centralita la temperatura del aceite, los días hasta el servicio o el estado " +
                    "de la batería. El explorador pregunta uno a uno por los identificadores de un bloque (solo lectura, no cambia nada) " +
                    "y apunta los que contestan. Pásame el resultado y los identifico con tu coche para que salgan con nombre.",
            )
        }
    }

    if (session == null) {
        renderNeedsConnection(screen, "gauge", "Conecta el adaptador", "El explorador lee las centralitas del coche: necesita el adaptador conectado y el contacto puesto.")
        return
    }

    screen.div("card explorer-form") {
        h2("card-title", "Qué leer")
        el("label", "field-label", "Centralita")
        val moduleSelect = el("select", "field") {
            (listOf(vehicle.engineEcu) + vehicle.modules.filter { it != vehicle.engineEcu }).forEach { ecu ->
                el("option", "", "${ecu.address} · ${ecu.name}") {
                    setAttribute("value", ecu.address)
                    if (ecu == module) setAttribute("selected", "")
                }
            }
            if (state.running) setAttribute("disabled", "")
        }.unsafeCast<HTMLSelectElement>()
        moduleSelect.addEventListener("change", {
            state.module = vehicle.modules.firstOrNull { it.address == moduleSelect.value } ?: vehicle.engineEcu
            state.readings = emptyList()
            render()
        })

        el("label", "field-label", "Bloque de identificadores")
        div("chips-scroll explorer-blocks") {
            ExplorerState.BLOCKS.forEach { (start, label) ->
                val selected = start == state.blockStart
                button("chip chip-filter${if (selected) " selected" else ""}", null, {
                    if (!state.running) {
                        state.blockStart = start
                        state.readings = emptyList()
                        render()
                    }
                }) {
                    span("mono", "${start.toHex(4)}–${(start + 0xFF).toHex(4)}")
                    setAttribute("title", label)
                }
            }
        }
        p("muted explorer-hint", "${ExplorerState.BLOCKS.first { it.first == state.blockStart }.second} · 256 identificadores · unos 40-90 s")

        div("button-row") {
            if (state.running) {
                button("btn btn-ghost", "Detener", { state.stop(); render() }) { icon("pause") }
            } else {
                button("btn btn-primary", "Explorar este bloque", { startExplore(module) }) { icon("search") }
            }
        }
    }

    if (state.readings.isNotEmpty() || state.running) {
        val done = state.readings.size
        screen.div("card scan-card") {
            div("progress") {
                div("progress-fill") { setAttribute("style", "width:${done * 100 / ExplorerState.BLOCK_SIZE}%") }
            }
            p("progress-label", "$done/${ExplorerState.BLOCK_SIZE} leídos · ${state.found.size} con datos" + if (state.running) " · leyendo…" else "")
            val rejected = state.readings.filter { it.nrc != null && it.nrc != 0x31 }.groupBy { it.nrc }
            rejected.forEach { (nrc, list) ->
                p("muted", "${list.size} rechazados: ${describeNegativeResponse(nrc ?: 0)}")
            }
            val silent = state.readings.count { it.error != null }
            if (silent > 0) p("muted", "$silent sin respuesta")
        }
    }

    val found = state.found
    if (found.isNotEmpty()) {
        screen.div("group-head") {
            h2("group-title", "Con datos · ${module.address} ${module.name}")
            button("btn btn-ghost btn-sm", "Copiar", { copyExplorerResults(module) }) { icon("copy") }
        }
        screen.div("card list-card") {
            found.forEach { reading -> didRow(reading) }
        }
    } else if (!state.running && state.readings.size == ExplorerState.BLOCK_SIZE) {
        screen.p("footnote", "Ningún identificador de este bloque tiene datos en esta centralita. Prueba con otro bloque.")
    }
}

private fun HTMLElement.didRow(reading: DidReading) {
    val data = reading.data ?: return
    div("did-row") {
        span("module-address did-id", reading.did.toHex(4))
        div("action-text") {
            p("did-hex mono", data.toHexDump().uppercase())
            p("action-detail", interpretations(data))
        }
    }
}

/** Lecturas posibles de los bytes, para ayudar a reconocer el dato. */
private fun interpretations(data: ByteArray): String {
    val bytes = data.map { it.toInt() and 0xFF }
    val parts = mutableListOf<String>()
    val text = bytes.filter { it in 0x20..0x7E }
    if (text.size >= 3 && text.size >= bytes.size * 0.8) parts += "texto «${text.map { it.toChar() }.joinToString("").trim()}»"
    when (bytes.size) {
        1 -> parts += "${bytes[0]} · −40 = ${bytes[0] - 40}"
        2 -> parts += "${bytes[0] * 256 + bytes[1]} · bytes ${bytes[0]} y ${bytes[1]}"
        3, 4 -> parts += "${bytes.fold(0L) { acc, b -> acc * 256 + b }}"
        else -> parts += "${bytes.size} bytes"
    }
    return parts.joinToString(" · ")
}

private fun App.startExplore(module: EcuDefinition) {
    val current = session ?: return
    val state = explorer
    if (state.running) return
    val start = state.blockStart
    state.module = module
    state.readings = emptyList()
    state.running = true
    render()
    DebugLog.add("Explorador: ${module.address} ${module.name} · bloque ${start.toHex(4)}-${(start + 0xFF).toHex(4)}")
    state.job = scope.launch {
        var sinceRender = 0
        try {
            current.exploreDids(module, (start until start + ExplorerState.BLOCK_SIZE).toList()).collect { reading ->
                state.readings = state.readings + reading
                reading.data?.let { DebugLog.add("Explorador: ${module.address} 22 ${reading.did.toHex(4)} = ${it.toHexDump()}") }
                sinceRender++
                if (reading.found || sinceRender >= 8) {
                    sinceRender = 0
                    if (tab == Tab.EXPLORER) render()
                }
            }
            DebugLog.add("Explorador: terminado · ${state.found.size} con datos")
        } catch (e: DiagnosisException) {
            handleFailure(e, "Error en el explorador")
        } finally {
            state.running = false
            state.job = null
            if (tab == Tab.EXPLORER) render()
        }
    }
}

private fun App.copyExplorerResults(module: EcuDefinition) {
    val text = buildString {
        appendLine("Explorador · ${vehicle.displayName} ${vehicle.generation} · ${module.address} ${module.name} (${module.requestId.toHex(3)})")
        explorer.found.forEach { appendLine("${it.did.toHex(4)} = ${it.data!!.toHexDump().uppercase()}") }
    }
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard != null) {
        clipboard.writeText(text).then({ _: dynamic -> toast("Resultados copiados: pégalos en el chat", "ok") }, { _: dynamic -> toast("No se pudo copiar", "warn") })
    } else {
        toast("Este navegador no deja copiar", "warn")
    }
}

/** Abre el explorador con una centralita ya elegida. */
fun App.openExplorer(module: EcuDefinition?) {
    if (!explorer.running && module != null && module != explorer.module) {
        explorer.module = module
        explorer.readings = emptyList()
    }
    navigate(Tab.EXPLORER)
}
