package com.sistemadiagnosis.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

fun main() {
    registerServiceWorker()
    // Errores no previstos también al registro, para poder verlos después de probar en el coche.
    window.addEventListener("error", { event -> DebugLog.add("ERROR JS: ${event.asDynamic().message}") })
    window.addEventListener("unhandledrejection", { event -> DebugLog.add("ERROR JS (promesa): ${event.asDynamic().reason}") })
    val root = document.getElementById("app")?.unsafeCast<HTMLElement>() ?: return
    App(root).start()
}

/** Guarda la app en caché para abrirla sin conexión (en el coche). Solo en HTTPS. */
private fun registerServiceWorker() {
    val serviceWorker = window.navigator.asDynamic().serviceWorker ?: return
    if (window.location.protocol == "https:") serviceWorker.register("sw.js")
}
