package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.obd.PidCategory
import com.sistemadiagnosis.core.obd.PidDefinition
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.math.round

private const val HISTORY = 60
private const val MAX_TILES = 12

/** Referencias de un mosaico para actualizarlo sin repintar la pantalla. */
private class LiveTile(val definition: PidDefinition, val root: HTMLElement) {
    val value: HTMLElement = root.querySelector(".tile-value")!!.unsafeCast<HTMLElement>()
    val bar: HTMLElement = root.querySelector(".tile-bar-fill")!!.unsafeCast<HTMLElement>()
    val line: Element = root.querySelector("polyline")!!
    val history = ArrayDeque<Double>()
    var min: Double? = null
    var max: Double? = null
    var latest: Double? = null

    fun update(reading: Double) {
        latest = reading
        min = minOf(min ?: reading, reading)
        max = maxOf(max ?: reading, reading)
        value.textContent = definition.format(reading)
        val fraction = ((reading - definition.min) / (definition.max - definition.min)).coerceIn(0.0, 1.0)
        bar.style.width = "${fraction * 100}%"
        history.addLast(reading)
        while (history.size > HISTORY) history.removeFirst()
        line.setAttribute("points", sparkline(history))
    }

    private fun sparkline(values: ArrayDeque<Double>): String {
        if (values.size < 2) return ""
        val low = values.min()
        val high = values.max()
        val span = (high - low).takeIf { it > 1e-9 } ?: 1.0
        return values.mapIndexed { i, v ->
            val x = i * 100.0 / (HISTORY - 1)
            val y = if (high - low < 1e-9) 16.0 else 29.0 - (v - low) / span * 26.0
            "${round(x * 100) / 100},${round(y * 100) / 100}"
        }.joinToString(" ")
    }
}

internal fun App.renderLive(screen: HTMLElement) {
    val info = connection
    val current = session
    if (info == null || current == null) {
        renderNeedsConnection(screen, "activity", "Datos en vivo", "Conecta el adaptador para ver los sensores del motor en tiempo real.")
        return
    }

    val available = PidCatalog.all.filter { definition ->
        val inputs = PidCatalog.derivedInputs[definition.pid]
        if (inputs != null) inputs.all { it in info.supportedPids } else definition.pid in info.supportedPids
    }
    val categories = PidCategory.entries.filter { category -> available.any { it.category == category } }
    val shown = (
        liveCategory?.let { category -> available.filter { it.category == category } }
            ?: PidCatalog.featured.mapNotNull { pid -> available.firstOrNull { it.pid == pid } }
        ).take(MAX_TILES)

    lateinit var rate: HTMLElement
    screen.div("section-head") {
        div {
            p("eyebrow", "Motor · ${available.size} sensores")
            h1("section-title", "En vivo")
        }
        div("live-status") {
            span("live-dot${if (livePaused) " paused" else ""}")
            rate = span("live-rate", if (livePaused) "En pausa" else "Conectando…")
            button("icon-button", null, {
                livePaused = !livePaused
                render()
            }) {
                setAttribute("aria-label", if (livePaused) "Reanudar" else "Pausar")
                icon(if (livePaused) "play" else "pause")
            }
        }
    }

    screen.div("chips-scroll") {
        setAttribute("role", "tablist")
        chip("Destacados", liveCategory == null) { liveCategory = null; render() }
        categories.forEach { category ->
            chip(category.label, liveCategory == category) { liveCategory = category; render() }
        }
    }

    val tiles = mutableMapOf<Int, LiveTile>()
    screen.div("tile-grid") {
        shown.forEach { definition ->
            val tile = div("tile") {
                pressable(definition.name) { showPidDetail(definition, tiles[definition.pid]) }
                p("tile-label", definition.name)
                div("tile-reading") {
                    span("tile-value", "—")
                    if (definition.unit.isNotEmpty()) span("tile-unit", definition.unit)
                }
                div("tile-bar") { div("tile-bar-fill") }
                insertAdjacentHTML(
                    "beforeend",
                    """<svg class="spark" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true"><polyline points=""/></svg>""",
                )
            }
            tiles[definition.pid] = LiveTile(definition, tile)
        }
    }

    screen.p("footnote", "Lectura directa de la centralita del motor por OBD-II. Pulsa un sensor para ver qué mide y su mínimo y máximo de esta sesión.")

    if (livePaused || shown.isEmpty()) return
    screenJob = scope.launch {
        try {
            current.liveData(shown.map { it.pid }).collect { sample ->
                rate.textContent = "${formatNumber(sample.updatesPerSecond, 1)} act./s"
                sample.values.forEach { (pid, value) -> tiles[pid]?.update(value) }
            }
        } catch (e: DiagnosisException) {
            handleFailure(e)
        }
    }
}

private fun HTMLElement.chip(label: String, selected: Boolean, action: () -> Unit) {
    button("chip chip-filter${if (selected) " selected" else ""}", label, action) {
        setAttribute("role", "tab")
        setAttribute("aria-selected", selected.toString())
    }
}

private fun App.showPidDetail(definition: PidDefinition, tile: LiveTile?) {
    showSheet(definition.name) { close ->
        p("eyebrow", definition.category.label)
        h2("sheet-title", definition.name)
        div("big-reading") {
            span("big-value", tile?.latest?.let { definition.format(it) } ?: "—")
            if (definition.unit.isNotEmpty()) span("big-unit", definition.unit)
        }
        p("prose", definition.description)
        el("dl", "facts") {
            fact("Mínimo de la sesión", tile?.min?.let { definition.formatWithUnit(it) } ?: "—")
            fact("Máximo de la sesión", tile?.max?.let { definition.formatWithUnit(it) } ?: "—")
            if (definition.isDerived) fact("Origen", "Calculado a partir de otros sensores")
            else fact("PID OBD", "01 ${definition.pid.toString(16).uppercase().padStart(2, '0')}", mono = true)
        }
        div("sheet-actions") { button("btn btn-primary", "Cerrar", { close() }) }
    }
}
