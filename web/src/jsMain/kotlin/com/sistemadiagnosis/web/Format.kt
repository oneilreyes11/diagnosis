package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.PidDefinition

/** Número con coma decimal y separador de miles en español. */
fun formatNumber(value: Double, decimals: Int): String {
    val options: dynamic = js("({})")
    options.minimumFractionDigits = decimals
    options.maximumFractionDigits = decimals
    return value.asDynamic().toLocaleString("es-ES", options).unsafeCast<String>()
}

fun PidDefinition.format(value: Double): String = formatNumber(value, decimals)

fun PidDefinition.formatWithUnit(value: Double): String =
    if (unit.isEmpty()) format(value) else "${format(value)} $unit"

/** Clase CSS de color para cada gravedad. */
val Severity.tone: String
    get() = when (this) {
        Severity.LOW -> "ok"
        Severity.MEDIUM -> "warn"
        Severity.HIGH -> "danger"
    }

fun plural(count: Int, singular: String, plural: String) = "$count ${if (count == 1) singular else plural}"
