package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.history.HistoryEntry
import com.sistemadiagnosis.core.history.HistoryFault
import com.sistemadiagnosis.core.knowledge.Severity
import kotlinx.browser.window

/**
 * Lecturas guardadas en el navegador (localStorage), de la más antigua a la
 * más reciente. Solo se guardan las de un adaptador real: la demostración usa
 * un historial inventado en memoria.
 */
object HistoryStore {
    private const val STORAGE_KEY = "diagnosis.history"
    private const val MAX_ENTRIES = 300

    private val entries = mutableListOf<HistoryEntry>()

    init {
        try {
            window.localStorage.getItem(STORAGE_KEY)?.let { raw ->
                val array = JSON.parse<Array<dynamic>>(raw)
                array.mapNotNullTo(entries) { decode(it) }
            }
        } catch (e: Throwable) {
            // Sin almacenamiento o datos corruptos: se empieza de cero.
        }
    }

    val all: List<HistoryEntry> get() = entries.toList()

    /** Añade la lectura o la sustituye si ya existe (misma conexión). */
    fun save(entry: HistoryEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry else entries += entry
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    fun delete(id: String) {
        entries.removeAll { it.id == id }
        persist()
    }

    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(entries.map(::encode).toTypedArray()))
        } catch (e: Throwable) {
            toast("No se pudo guardar el historial en este navegador", "warn")
        }
    }

    private fun encode(entry: HistoryEntry): dynamic {
        val o: dynamic = js("({})")
        o.id = entry.id
        o.t = entry.timestamp
        o.vin = entry.vin
        o.km = entry.odometerKm
        o.v = entry.batteryVoltage
        o.mil = entry.milOn
        o.mc = entry.monitorsComplete
        o.mt = entry.monitorsTotal
        o.ef = entry.engineFaults.map(::encodeFault).toTypedArray()
        o.mf = entry.moduleFaults?.map(::encodeFault)?.toTypedArray()
        o.mn = entry.modulesFound
        return o
    }

    private fun encodeFault(fault: HistoryFault): dynamic {
        val o: dynamic = js("({})")
        o.s = fault.source
        o.c = fault.code
        o.t = fault.title
        o.g = fault.severity.name
        return o
    }

    private fun decode(o: dynamic): HistoryEntry? {
        val id = o.id as? String ?: return null
        val timestamp = (o.t as? Number)?.toDouble() ?: return null
        return HistoryEntry(
            id = id,
            timestamp = timestamp,
            vin = o.vin as? String,
            odometerKm = (o.km as? Number)?.toDouble(),
            batteryVoltage = (o.v as? Number)?.toDouble(),
            milOn = o.mil as? Boolean,
            monitorsComplete = (o.mc as? Number)?.toInt(),
            monitorsTotal = (o.mt as? Number)?.toInt(),
            engineFaults = decodeFaults(o.ef) ?: emptyList(),
            moduleFaults = decodeFaults(o.mf),
            modulesFound = (o.mn as? Number)?.toInt(),
        )
    }

    private fun decodeFaults(value: dynamic): List<HistoryFault>? {
        if (value == null || value == undefined) return null
        return value.unsafeCast<Array<dynamic>>().mapNotNull { f ->
            val code = f.c as? String ?: return@mapNotNull null
            HistoryFault(
                source = f.s as? String ?: "01",
                code = code,
                title = f.t as? String ?: code,
                severity = Severity.entries.firstOrNull { it.name == f.g } ?: Severity.MEDIUM,
            )
        }
    }
}

/** Fecha corta en español: 04/11/2026. */
fun formatDate(timestamp: Double): String =
    js("new Date(timestamp)").toLocaleDateString("es-ES", js("({day: '2-digit', month: '2-digit', year: 'numeric'})")).unsafeCast<String>()

/** Fecha y hora: 04/11/2026, 18:30. */
fun formatDateTime(timestamp: Double): String =
    js("new Date(timestamp)").toLocaleString(
        "es-ES", js("({day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'})"),
    ).unsafeCast<String>()

fun nowMillis(): Double = js("Date.now()").unsafeCast<Double>()
