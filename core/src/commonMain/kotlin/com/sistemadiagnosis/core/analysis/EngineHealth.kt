package com.sistemadiagnosis.core.analysis

import com.sistemadiagnosis.core.DiagnosticReport
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.MonitorTestResult
import kotlin.math.roundToInt

/** Conclusión en lenguaje claro sacada de los datos del motor. */
data class HealthFinding(val severity: Severity, val title: String, val detail: String)

/** Fallos de encendido de un cilindro según las autopruebas. */
data class CylinderMisfires(val cylinder: Int, val lastTrip: Int?, val average: Int?)

/**
 * Lee las autopruebas del motor (modo 06) y avisa de lo que va mal antes de que
 * se encienda el testigo: fallos de encendido en un cilindro, pruebas fuera de
 * límites y pruebas que pasan por poco.
 */
object EngineHealth {
    /** Por encima de esto, la prueba pasa "por los pelos". */
    const val NEAR_LIMIT = 0.9

    fun misfires(tests: List<MonitorTestResult>): List<CylinderMisfires> =
        tests.filter { it.cylinder != null }
            .groupBy { it.cylinder!! }
            .map { (cylinder, results) ->
                CylinderMisfires(
                    cylinder,
                    lastTrip = results.firstOrNull { it.tid == 0x0C }?.rawValue,
                    average = results.firstOrNull { it.tid == 0x0B }?.rawValue,
                )
            }
            .sortedBy { it.cylinder }

    fun findings(tests: List<MonitorTestResult>): List<HealthFinding> {
        val findings = mutableListOf<HealthFinding>()

        val cylinders = misfires(tests)
        for (c in cylinders) {
            val last = c.lastTrip ?: 0
            val average = c.average ?: 0
            if (last < 5 && average < 3) continue
            val others = cylinders.filter { it.cylinder != c.cylinder }
            val othersQuiet = others.isNotEmpty() && others.all { (it.lastTrip ?: 0) < 2 && (it.average ?: 0) < 2 }
            val severity = if (last >= 50 || average >= 20) Severity.HIGH else Severity.MEDIUM
            findings += HealthFinding(
                severity,
                "Cilindro ${c.cylinder}: fallos de encendido",
                "$last en el último trayecto y una media de $average en los últimos 10. " +
                    (if (othersQuiet) "El resto de cilindros va bien, así que el problema está en ese cilindro: " else "") +
                    "lo más habitual es la bobina o la bujía. Prueba a cambiar la bobina con la de otro cilindro: si el fallo se mueve, es la bobina.",
            )
        }

        val limited = tests.filter { it.hasLimits }
        for (test in limited.filterNot { it.passed }) {
            findings += HealthFinding(
                Severity.MEDIUM,
                "${test.systemName}: prueba no superada",
                "${test.testName} dio ${format(test)} y debe estar entre ${limits(test)}. Si se repite, la centralita guardará una avería y encenderá el testigo.",
            )
        }
        for (test in limited.filter { it.passed && (it.limitUsage ?: 0.0) >= NEAR_LIMIT }) {
            val catalyst = test.mid in 0x21..0x24
            findings += HealthFinding(
                if (catalyst) Severity.MEDIUM else Severity.LOW,
                "${test.systemName}: pasa por poco",
                "${test.testName} está al ${((test.limitUsage ?: 0.0) * 100).roundToInt()} % del límite (${format(test)}; límites ${limits(test)}). " +
                    if (catalyst) "Un catalizador que pierde eficiencia suele venir de fallos de encendido o aceite quemado: arréglalo antes de que haya que cambiarlo."
                    else "Aún pasa, pero vigílalo en las próximas lecturas.",
            )
        }
        return findings.sortedByDescending { it.severity }
    }

    fun format(test: MonitorTestResult): String = number(test.value) + if (test.unit.isEmpty()) "" else " ${test.unit}"

    fun limits(test: MonitorTestResult): String = "${number(test.min)} y ${number(test.max)}" + if (test.unit.isEmpty()) "" else " ${test.unit}"

    private fun number(value: Double): String {
        val abs = kotlin.math.abs(value)
        val decimals = when {
            abs >= 100 || value == value.roundToInt().toDouble() -> 0
            abs >= 10 -> 1
            abs >= 1 -> 2
            else -> 3
        }
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val rounded = kotlin.math.round(abs * factor).toLong()
        val sign = if (value < 0 && rounded != 0L) "-" else ""
        if (decimals == 0) return sign + rounded
        return sign + (rounded / factor.toLong()) + "," + (rounded % factor.toLong()).toString().padStart(decimals, '0')
    }
}

/** ¿Se han borrado averías hace poco? Útil al comprar o vender un coche y antes de la ITV. */
data class ClearCheck(
    /** null: sin indicios de borrado reciente. */
    val severity: Severity?,
    val title: String,
    val detail: String,
)

object ClearDetection {
    /** PIDs: km y arranques en frío desde el borrado. */
    private const val KM_SINCE_CLEAR = 0x31
    private const val WARMUPS_SINCE_CLEAR = 0x30

    /** null si el coche no da los contadores (no se puede saber). */
    fun check(report: DiagnosticReport): ClearCheck? {
        val km = report.tripData[KM_SINCE_CLEAR]
        val warmups = report.tripData[WARMUPS_SINCE_CLEAR]
        val pendingMonitors = report.monitorStatus?.incomplete?.size ?: 0
        val hidden = report.faults.filter { DtcKind.PERMANENT in it.kinds && DtcKind.STORED !in it.kinds }

        if (hidden.isNotEmpty()) {
            val codes = hidden.joinToString(", ") { it.dtc.code }
            return ClearCheck(
                Severity.HIGH,
                "Avería borrada que sigue guardada",
                "La centralita conserva $codes como avería permanente: se borró, pero el motor aún no ha comprobado que esté reparada. " +
                    "Si se borró sin arreglarla, volverá a aparecer y encenderá el testigo.",
            )
        }
        if (km == null && warmups == null) return null
        val since = listOfNotNull(
            km?.let { if (it >= 65_535) "más de 65.535 km" else "${thousands(it)} km" },
            warmups?.let { "${it.roundToInt()} arranques en frío" },
        ).joinToString(" y ")
        val monitors = if (pendingMonitors > 0) " Hay $pendingMonitors ${if (pendingMonitors == 1) "monitor" else "monitores"} de emisiones sin terminar." else ""
        val recent = (km != null && km < 200) || (warmups != null && warmups < 5)
        return when {
            recent -> ClearCheck(
                Severity.MEDIUM,
                "Averías borradas hace muy poco",
                "Se borraron hace $since.$monitors Si vas a comprar este coche, pregunta por qué: borrar justo antes de venderlo puede ocultar una avería. " +
                    "También pasa tras una reparación en el taller o al cambiar la batería.",
            )
            km != null && km < 1_000 -> ClearCheck(
                Severity.LOW,
                "Borrado reciente",
                "Las averías se borraron hace $since.$monitors Vuelve a leerlo dentro de unos días: si había algo, ya habrá reaparecido.",
            )
            else -> ClearCheck(null, "Sin borrado reciente", "Las averías se borraron por última vez hace $since.")
        }
    }

    private fun thousands(value: Double): String =
        kotlin.math.round(value).toLong().toString().reversed().chunked(3).joinToString(".").reversed()
}
