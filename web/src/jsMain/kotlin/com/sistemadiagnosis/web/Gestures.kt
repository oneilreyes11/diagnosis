package com.sistemadiagnosis.web

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import kotlin.js.Date
import kotlin.math.abs
import kotlin.math.min

private const val TRIGGER_DISTANCE = 90.0
private const val MAX_VERTICAL = 60.0
private const val MAX_DURATION_MS = 900.0

/**
 * Deslizar hacia la derecha para volver, como en iOS. Se dispara en `touchend`:
 * si el navegador hace su propio gesto de atrás, la página recibe `touchcancel`
 * y este no actúa, así nunca se retrocede dos veces.
 */
fun installSwipeBack(canGoBack: () -> Boolean, goBack: () -> Unit) {
    val body = document.body ?: return
    val indicator = body.div("swipe-back") {
        setAttribute("aria-hidden", "true")
        icon("back")
    }
    var tracking = false
    var startX = 0.0
    var startY = 0.0
    var startTime = 0.0
    var dx = 0.0
    var dy = 0.0

    fun hideIndicator() {
        indicator.classList.remove("dragging", "ready")
        indicator.style.removeProperty("transform")
        indicator.style.removeProperty("opacity")
    }

    fun listen(type: String, handler: (Event) -> Unit) {
        document.asDynamic().addEventListener(type, handler, js("({ passive: true })"))
    }

    listen("touchstart") { event ->
        val touches = event.asDynamic().touches
        val target = event.target?.unsafeCast<Element>()
        // Las zonas con desplazamiento horizontal propio no cuentan.
        val horizontalScroller = target?.closest(".chips-scroll, .log, .technical") != null
        tracking = touches.length == 1 && canGoBack() && !horizontalScroller
        if (tracking) {
            startX = touches[0].clientX.unsafeCast<Double>()
            startY = touches[0].clientY.unsafeCast<Double>()
            startTime = Date.now()
            dx = 0.0
            dy = 0.0
        }
    }

    listen("touchmove") { event ->
        if (!tracking) return@listen
        val touch = event.asDynamic().touches[0]
        dx = touch.clientX.unsafeCast<Double>() - startX
        dy = touch.clientY.unsafeCast<Double>() - startY
        if (abs(dy) > MAX_VERTICAL && abs(dy) > abs(dx)) {
            tracking = false
            hideIndicator()
            return@listen
        }
        if (dx > 12) {
            val progress = min(dx / TRIGGER_DISTANCE, 1.0)
            indicator.classList.add("dragging")
            indicator.classList.toggle("ready", progress >= 1.0)
            indicator.style.transform = "translateX(${-56 + 64 * progress}px)"
            indicator.style.opacity = "$progress"
        }
    }

    listen("touchend") {
        val trigger = tracking && dx >= TRIGGER_DISTANCE && abs(dy) < MAX_VERTICAL && Date.now() - startTime < MAX_DURATION_MS
        tracking = false
        hideIndicator()
        if (trigger) goBack()
    }

    listen("touchcancel") {
        tracking = false
        hideIndicator()
    }
}
