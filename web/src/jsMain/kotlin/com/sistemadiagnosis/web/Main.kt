package com.sistemadiagnosis.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

fun main() {
    registerServiceWorker()
    val root = document.getElementById("app")?.unsafeCast<HTMLElement>() ?: return
    App(root).start()
}

/** Guarda la app en caché para abrirla sin conexión (en el coche). Solo en HTTPS. */
private fun registerServiceWorker() {
    val serviceWorker = window.navigator.asDynamic().serviceWorker ?: return
    if (window.location.protocol == "https:") serviceWorker.register("sw.js")
}
