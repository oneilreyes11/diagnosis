package com.sistemadiagnosis.web

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/** Mini DSL para construir la interfaz sin plantillas. */
typealias Build = HTMLElement.() -> Unit

fun create(tag: String, cls: String = "", text: String? = null, build: Build = {}): HTMLElement {
    val element = document.createElement(tag).unsafeCast<HTMLElement>()
    if (cls.isNotEmpty()) element.className = cls
    if (text != null) element.textContent = text
    element.build()
    return element
}

fun HTMLElement.el(tag: String, cls: String = "", text: String? = null, build: Build = {}): HTMLElement =
    create(tag, cls, text, build).also { appendChild(it) }

fun HTMLElement.div(cls: String = "", build: Build = {}) = el("div", cls, null, build)

fun HTMLElement.span(cls: String = "", text: String? = null, build: Build = {}) = el("span", cls, text, build)

fun HTMLElement.p(cls: String = "", text: String) = el("p", cls, text)

fun HTMLElement.h1(cls: String = "", text: String) = el("h1", cls, text)

fun HTMLElement.h2(cls: String = "", text: String) = el("h2", cls, text)

fun HTMLElement.h3(cls: String = "", text: String) = el("h3", cls, text)

fun HTMLElement.button(cls: String, text: String? = null, onClick: () -> Unit, build: Build = {}): HTMLButtonElement {
    val button = el("button", cls, null) {
        setAttribute("type", "button")
        build()
        if (text != null) span("label", text)
    }.unsafeCast<HTMLButtonElement>()
    button.addEventListener("click", { onClick() })
    return button
}

fun HTMLElement.icon(name: String, cls: String = "icon") {
    insertAdjacentHTML("beforeend", Icons.svg(name, cls))
}

fun HTMLElement.onClick(action: () -> Unit) {
    addEventListener("click", { action() })
}

/** Elemento pulsable accesible con teclado. */
fun HTMLElement.pressable(label: String, action: () -> Unit) {
    setAttribute("role", "button")
    setAttribute("tabindex", "0")
    setAttribute("aria-label", label)
    addEventListener("click", { action() })
    addEventListener("keydown", { event ->
        val key = event.asDynamic().key as String
        if (key == "Enter" || key == " ") {
            event.preventDefault()
            action()
        }
    })
}

fun HTMLElement.clear() {
    innerHTML = ""
}
