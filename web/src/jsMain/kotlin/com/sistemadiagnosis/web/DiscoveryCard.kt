package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.DiscoveryHit
import com.sistemadiagnosis.core.util.toHex
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/** Búsqueda de centralitas con CAN de 29 bits (las que no contestan en el escaneo normal). */
class DiscoveryState {
    var running = false
    var probed = 0
    var total = 256
    var hits: List<DiscoveryHit> = emptyList()
    var finished = false
    var job: Job? = null

    fun stop() {
        job?.cancel()
        job = null
        running = false
    }
}

internal fun App.renderDiscoveryCard(container: HTMLElement) {
    val state = discovery
    container.div("card discovery-card") {
        h2("card-title", "Centralitas que no contestan")
        p(
            "muted",
            "En tu A3 solo el cambio responde con las direcciones habituales. Los VAG más nuevos pueden usar otras " +
                "(CAN de 29 bits). Esta prueba pregunta en 256 direcciones con una orden inofensiva y apunta las que contestan. Tarda alrededor de 1 minuto.",
        )
        if (state.running || state.finished) {
            div("progress") { div("progress-fill") { setAttribute("style", "width:${state.probed * 100 / state.total}%") } }
            p("progress-label", "${state.probed}/${state.total} probadas · ${plural(state.hits.size, "contesta", "contestan")}")
        }
        if (state.hits.isNotEmpty()) {
            div("module-list") {
                state.hits.forEach { hit ->
                    div("did-row") {
                        span("module-address did-id", (hit.requestId and 0xFF).toHex(2))
                        div("action-text") {
                            p("did-hex mono", "${hit.requestId.toHex(8)} → ${hit.responseId.toHex(8)}")
                            p("action-detail mono", hit.response)
                        }
                    }
                }
            }
        }
        if (state.finished && !state.running && state.hits.isEmpty()) {
            p("muted", "Ninguna contestó con ese esquema. Copia el registro (Más › Registro de conexión) y pásamelo.")
        }
        div("button-row") {
            when {
                state.running -> button("btn btn-ghost", "Detener", { state.stop(); render() }) { icon("pause") }
                else -> button("btn btn-primary", if (state.finished) "Repetir la búsqueda" else "Buscar centralitas", { startDiscovery() }) {
                    icon("search")
                    if (session == null || moduleScanning) setAttribute("disabled", "")
                }
            }
            if (state.hits.isNotEmpty()) button("btn btn-ghost", "Copiar", { copyDiscovery() }) { icon("copy") }
        }
    }
}

private fun App.startDiscovery() {
    val current = session ?: return
    val state = discovery
    if (state.running) return
    state.running = true
    state.finished = false
    state.probed = 0
    state.hits = emptyList()
    render()
    DebugLog.add("Búsqueda 29 bits: empezando (17FC0000-17FC00FF, petición 10 01)")
    state.job = scope.launch {
        try {
            current.discoverExtendedModules().collect { progress ->
                state.probed = progress.probed
                state.total = progress.total
                progress.hit?.let { hit ->
                    state.hits = state.hits + hit
                    DebugLog.add("Búsqueda 29 bits: ${hit.requestId.toHex(8)} contesta desde ${hit.responseId.toHex(8)}: ${hit.response}")
                }
                if (progress.hit != null || progress.probed % 8 == 0) render()
            }
            state.finished = true
            DebugLog.add("Búsqueda 29 bits: terminada · ${state.hits.size} contestan")
        } catch (e: DiagnosisException) {
            handleFailure(e, "Error en la búsqueda")
        } finally {
            state.running = false
            state.job = null
            render()
        }
    }
}

private fun App.copyDiscovery() {
    val text = "Búsqueda 29 bits · ${vehicle.displayName} ${vehicle.generation}\n" +
        discovery.hits.joinToString("\n") { "${it.requestId.toHex(8)} -> ${it.responseId.toHex(8)}: ${it.response}" }
    window.navigator.asDynamic().clipboard?.writeText(text)?.then({ _: dynamic -> toast("Copiado: pégalo en el chat", "ok") })
}
