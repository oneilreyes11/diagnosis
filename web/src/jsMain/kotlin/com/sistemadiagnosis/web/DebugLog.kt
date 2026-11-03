package com.sistemadiagnosis.web

import kotlinx.browser.window

/**
 * Registro de diagnóstico de la conexión: pasos del Bluetooth, servicios y
 * características encontrados, cada byte enviado y recibido, desconexiones y
 * errores, con hora y milisegundos desde el inicio del intento.
 *
 * Se guarda en el navegador (sobrevive a recargas y a cerrar la app) para poder
 * probar en el coche y compartirlo después de una vez.
 */
object DebugLog {
    private const val STORAGE_KEY = "diagnosis.debuglog"
    private const val MAX_LINES = 3000

    private val lines = ArrayDeque<String>()
    private var attemptStart = now()
    private var pendingSave = false

    val size: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()

    init {
        try {
            window.localStorage.getItem(STORAGE_KEY)?.split('\n')?.filter { it.isNotEmpty() }?.let { lines.addAll(it) }
        } catch (e: Throwable) {
            // Sin almacenamiento (modo privado): el registro vive solo en memoria.
        }
    }

    /** Marca el inicio de un intento de conexión: los tiempos relativos se cuentan desde aquí. */
    fun startAttempt(title: String) {
        attemptStart = now()
        add("")
        add("==================== $title ====================")
        add("Fecha: ${js("new Date()").toLocaleString("es-ES")}")
        add("Navegador: ${window.navigator.userAgent}")
        add("Página: ${window.location.href}")
    }

    fun add(message: String) {
        val line = if (message.isEmpty() || message.startsWith("====")) message else {
            val elapsed = (now() - attemptStart).toLong()
            "${clock()} +${elapsed}ms  $message"
        }
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        scheduleSave()
    }

    fun error(context: String, error: Throwable) {
        add("ERROR $context: ${error::class.simpleName}: ${error.message}")
        error.cause?.let { add("   causa: ${it::class.simpleName}: ${it.message}") }
    }

    fun text(): String = lines.joinToString("\n")

    fun clear() {
        lines.clear()
        save()
    }

    /** Comparte el registro (WhatsApp, correo, la app de Claude…) o lo copia si no se puede. */
    fun share() {
        val text = text()
        val navigator = window.navigator.asDynamic()
        if (navigator.share != null) {
            val data: dynamic = js("({})")
            data.title = "Registro de conexión Diagnosis"
            data.text = text
            navigator.share(data).catch { _: dynamic -> copy() }
        } else {
            copy()
        }
    }

    fun copy() {
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null) {
            clipboard.writeText(text()).then(
                { _: dynamic -> toast("Registro copiado: pégalo en el chat", "ok") },
                { _: dynamic -> toast("No se pudo copiar el registro", "warn") },
            )
        } else {
            toast("Este navegador no deja copiar", "warn")
        }
    }

    /** Guarda como mucho una vez por ciclo del navegador: hay muchas líneas seguidas. */
    private fun scheduleSave() {
        if (pendingSave) return
        pendingSave = true
        window.setTimeout({
            pendingSave = false
            save()
        }, 0)
    }

    private fun save() {
        try {
            window.localStorage.setItem(STORAGE_KEY, text())
        } catch (e: Throwable) {
            // Almacenamiento lleno o bloqueado: se sigue registrando en memoria.
        }
    }

    private fun now(): Double = window.performance.now()

    private fun clock(): String {
        val date: dynamic = js("new Date()")
        fun two(value: dynamic) = value.toString().padStart(2, '0')
        return "${two(date.getHours())}:${two(date.getMinutes())}:${two(date.getSeconds())}." +
            date.getMilliseconds().toString().padStart(3, '0')
    }
}

/** Bytes en hexadecimal, para ver también los no imprimibles. */
fun ByteArray.toHexDump(): String = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Texto legible con los saltos de línea visibles. */
fun ByteArray.toPrintable(): String =
    decodeToString().replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0")
