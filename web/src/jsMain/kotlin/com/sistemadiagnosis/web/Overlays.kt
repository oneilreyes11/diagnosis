package com.sistemadiagnosis.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Cada hoja abierta ocupa una entrada del historial, para que "atrás" (gesto del
 * móvil, botón de Android o del navegador) la cierre en lugar de salir de la app.
 */
private object SheetHistory {
    class OpenSheet(val dismiss: (fromHistory: Boolean) -> Unit)

    val open = mutableListOf<OpenSheet>()

    /** Se ha pedido history.back() al cerrar una hoja y aún no ha llegado su popstate. */
    var pendingBack = false

    /** Acciones que deben esperar a ese popstate (si no, se lo comería la nueva entrada). */
    val queued = mutableListOf<() -> Unit>()
}

val isSheetOpen: Boolean get() = SheetHistory.open.isNotEmpty()

/** Ejecuta [action] cuando no haya un "atrás" pendiente de procesar. */
fun whenHistoryIdle(action: () -> Unit) {
    if (SheetHistory.pendingBack) SheetHistory.queued += action else action()
}

/** Para el manejador de popstate: true si el evento era de una hoja y ya está atendido. */
fun handleSheetPop(): Boolean {
    if (SheetHistory.pendingBack) {
        SheetHistory.pendingBack = false
        val actions = SheetHistory.queued.toList()
        SheetHistory.queued.clear()
        actions.forEach { it() }
        return true
    }
    val top = SheetHistory.open.lastOrNull() ?: return false
    top.dismiss(true)
    return true
}

/** Cierra la hoja de arriba como si se pulsara su botón de cerrar. */
fun closeTopSheet() {
    SheetHistory.open.lastOrNull()?.dismiss(false)
}

/**
 * Hoja modal que sube desde abajo (en escritorio, centrada). [content] recibe
 * la función para cerrarla.
 */
fun showSheet(label: String, content: HTMLElement.(close: () -> Unit) -> Unit) {
    if (SheetHistory.pendingBack) {
        SheetHistory.queued += { showSheet(label, content) }
        return
    }
    val body = document.body ?: return
    val previousFocus = document.activeElement?.unsafeCast<HTMLElement>()
    val overlay = create("div", "sheet-overlay")
    var closed = false
    lateinit var entry: SheetHistory.OpenSheet
    val onKey: (Event) -> Unit = { event -> if (event.asDynamic().key == "Escape") entry.dismiss(false) }
    entry = SheetHistory.OpenSheet { fromHistory ->
        if (!closed) {
            closed = true
            SheetHistory.open.remove(entry)
            overlay.classList.add("closing")
            document.removeEventListener("keydown", onKey)
            if (SheetHistory.open.isEmpty()) body.classList.remove("sheet-open")
            window.setTimeout({ overlay.remove() }, 220)
            previousFocus?.focus()
            if (!fromHistory) {
                SheetHistory.pendingBack = true
                window.history.back()
            }
        }
    }
    val close = { entry.dismiss(false) }
    val panel = overlay.div("sheet") {
        setAttribute("role", "dialog")
        setAttribute("aria-modal", "true")
        setAttribute("aria-label", label)
        div("sheet-handle")
        button("icon-button sheet-close", null, close) {
            setAttribute("aria-label", "Cerrar")
            icon("close")
        }
        div("sheet-body") { content(close) }
    }
    overlay.addEventListener("click", { event -> if (event.target == overlay) close() })
    document.addEventListener("keydown", onKey)
    SheetHistory.open += entry
    window.history.pushState(js("({ sheet: true })"), "")
    body.appendChild(overlay)
    body.classList.add("sheet-open")
    panel.querySelector(".sheet-close")?.unsafeCast<HTMLElement>()?.focus()
}

/** Aviso breve en la parte inferior. [tone]: ok, warn, danger o vacío. */
fun toast(message: String, tone: String = "") {
    val body = document.body ?: return
    body.querySelector(".toast")?.remove()
    val toast = body.div("toast ${if (tone.isEmpty()) "" else "toast-$tone"}") {
        setAttribute("role", "status")
        span("", message)
    }
    window.setTimeout({ toast.classList.add("closing") }, 2600)
    window.setTimeout({ toast.remove() }, 2900)
}

/** Error con título, detalle técnico y consejos. */
fun showError(title: String, detail: String, hints: List<String> = emptyList(), action: Pair<String, () -> Unit>? = null) {
    showSheet(title) { close ->
        div("sheet-icon tone-danger") { icon("alert") }
        h2("sheet-title", title)
        if (hints.isNotEmpty()) {
            el("ul", "check-list") { hints.forEach { el("li", "", it) } }
        }
        p("technical", detail)
        div("sheet-actions") {
            if (action != null) button("btn btn-primary", action.first, { close(); action.second() })
            button("btn btn-ghost", "Cerrar", { close() })
        }
    }
}
