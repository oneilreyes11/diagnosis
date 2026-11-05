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
    var total = 512
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
                "o necesitar otro tipo de dirección. Esta prueba pregunta en unas 500 direcciones con una orden inofensiva y apunta las que contestan. Tarda 1-2 minutos.",
        )
        if (state.running || state.finished) {
            div("progress") { div("progress-fill") { setAttribute("style", "width:${state.probed * 100 / state.total}%") } }
            p("progress-label", "${state.probed}/${state.total} probadas · ${plural(state.hits.size, "contesta", "contestan")}")
        }
        if (state.hits.isNotEmpty()) {
            div("module-list") {
                state.hits.forEach { hit ->
                    div("did-row") {
                        val wide = hit.requestId > 0xFFF
                        span("module-address did-id", if (wide) (hit.requestId and 0xFF).toHex(2) else hit.requestId.toHex(3))
                        div("action-text") {
                            p("did-hex mono", "${hit.requestId.toHex(if (wide) 8 else 3)} → ${hit.responseId.toHex(if (hit.responseId > 0xFFF) 8 else 3)}")
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
    DebugLog.add("Búsqueda: empezando (11 bits 700-7FF y 29 bits 17FC00xx, petición 10 01)")
    state.job = scope.launch {
        try {
            current.discoverModules().collect { progress ->
                state.probed = progress.probed
                state.total = progress.total
                progress.note?.let { DebugLog.add("Búsqueda: $it") }
                progress.hit?.let { hit ->
                    state.hits = state.hits + hit
                    DebugLog.add("Búsqueda: ${hit.requestId.toHex(if (hit.requestId > 0xFFF) 8 else 3)} contesta desde ${hit.responseId.toHex(if (hit.responseId > 0xFFF) 8 else 3)}: ${hit.response}")
                }
                if (progress.hit != null || progress.probed % 8 == 0) render()
            }
            state.finished = true
            DebugLog.add("Búsqueda: terminada · ${state.hits.size} contestan")
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
