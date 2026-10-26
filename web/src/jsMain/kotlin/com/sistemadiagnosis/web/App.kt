package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.ConnectionInfo
import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.DiagnosticReport
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.obd.PidCategory
import com.sistemadiagnosis.core.transport.CallbackTransport
import com.sistemadiagnosis.core.transport.LoggingTransport
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.transport.TransportException
import com.sistemadiagnosis.core.vehicle.VehicleProfile
import com.sistemadiagnosis.core.vehicle.Vehicles
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data class Connected(val info: ConnectionInfo, val demo: Boolean) : LinkState
}

enum class Tab(val label: String, val icon: String, val slug: String) {
    HOME("Inicio", "home", ""),
    FAULTS("Averías", "alert", "averias"),
    LIVE("En vivo", "activity", "en-vivo"),
    READINESS("ITV", "shield", "itv"),
    MORE("Más", "more", "mas"),
}

/** Estado de la app y acciones; las pantallas lo leen y lo pintan. */
class App(private val root: HTMLElement) {
    val scope = MainScope()
    val vehicle: VehicleProfile = Vehicles.AUDI_A3_8Y_DFYA

    var link: LinkState = LinkState.Disconnected
        private set
    var session: DiagnosisSession? = null
        private set
    var report: DiagnosticReport? = null
        private set
    var scanning = false
        private set
    var tab = Tab.HOME
        private set

    /** Categoría de "En vivo"; null = destacados. */
    var liveCategory: PidCategory? = null
    var livePaused = false

    /** Tarea de la pantalla actual (datos en vivo); se cancela al cambiar de pantalla. */
    var screenJob: Job? = null

    val traffic = ArrayDeque<String>()

    private lateinit var header: HTMLElement
    private lateinit var content: HTMLElement
    private lateinit var tabBar: HTMLElement

    val connection: ConnectionInfo? get() = (link as? LinkState.Connected)?.info
    val isDemo: Boolean get() = (link as? LinkState.Connected)?.demo == true

    /** Hay una entrada de historial de un apartado encima de la de Inicio. */
    private var onSectionEntry = false

    fun start() {
        root.clear()
        header = root.el("header", "topbar")
        content = root.el("main", "screen")
        tabBar = root.el("nav", "tabbar") { setAttribute("aria-label", "Secciones") }
        window.history.replaceState(tabState(Tab.HOME), "", window.location.pathname)
        window.addEventListener("popstate", { event -> onHistoryBack(event.asDynamic().state) })
        installSwipeBack(canGoBack = { isSheetOpen || tab != Tab.HOME }, goBack = ::goBack)
        render()
    }

    /**
     * Inicio es la raíz: entrar en un apartado desde Inicio crea una entrada de
     * historial y cambiar entre apartados la sustituye, así "atrás" siempre
     * lleva a Inicio.
     */
    fun navigate(target: Tab) = whenHistoryIdle {
        if (target != tab) {
            when {
                target == Tab.HOME -> if (onSectionEntry) window.history.back() else show(Tab.HOME)
                tab == Tab.HOME -> {
                    window.history.pushState(tabState(target), "", "#${target.slug}")
                    onSectionEntry = true
                    show(target)
                }
                else -> {
                    window.history.replaceState(tabState(target), "", "#${target.slug}")
                    show(target)
                }
            }
        }
    }

    /** Atrás: cierra la hoja abierta o vuelve a Inicio. */
    fun goBack() {
        when {
            isSheetOpen -> closeTopSheet()
            tab != Tab.HOME -> navigate(Tab.HOME)
        }
    }

    private fun onHistoryBack(state: dynamic) {
        if (handleSheetPop()) return
        val target = Tab.entries.firstOrNull { it.name == state?.tab } ?: return
        onSectionEntry = target != Tab.HOME
        if (target != tab) show(target)
    }

    private fun show(target: Tab) {
        tab = target
        render()
        window.scrollTo(0.0, 0.0)
    }

    private fun tabState(target: Tab): dynamic {
        val state: dynamic = js("({})")
        state.tab = target.name
        return state
    }

    fun render() {
        screenJob?.cancel()
        screenJob = null
        renderHeader()
        renderTabs()
        content.clear()
        val screen = content.div("screen-inner")
        if (tab != Tab.HOME) {
            screen.button("back-link", "Inicio", ::goBack) {
                setAttribute("aria-label", "Volver a Inicio")
                icon("back")
            }
        }
        when (tab) {
            Tab.HOME -> renderHome(screen)
            Tab.FAULTS -> renderFaults(screen)
            Tab.LIVE -> renderLive(screen)
            Tab.READINESS -> renderReadiness(screen)
            Tab.MORE -> renderMore(screen)
        }
    }

    private fun renderHeader() {
        header.clear()
        header.div("topbar-inner") {
            div("brand") {
                div("brand-mark") { icon("car") }
                div("brand-text") {
                    p("brand-title", "${vehicle.displayName} ${vehicle.generation}")
                    p("brand-sub", "${vehicle.engine.name} ${vehicle.engine.code} · ${vehicle.year}")
                }
            }
            val (tone, label) = when (val state = link) {
                LinkState.Disconnected -> "idle" to "Sin conexión"
                LinkState.Connecting -> "busy" to "Conectando"
                is LinkState.Connected -> if (state.demo) "demo" to "Demostración" else "ok" to "Conectado"
            }
            span("pill pill-$tone") {
                span("pill-dot")
                span("", label)
            }
        }
    }

    private fun renderTabs() {
        tabBar.clear()
        tabBar.div("tabbar-inner") {
            Tab.entries.forEach { target ->
                button("tab${if (target == tab) " active" else ""}", null, { navigate(target) }) {
                    setAttribute("aria-label", target.label)
                    if (target == tab) setAttribute("aria-current", "page")
                    icon(target.icon)
                    span("tab-label", target.label)
                    val count = if (target == Tab.FAULTS) report?.faults?.size ?: 0 else 0
                    if (count > 0) span("tab-badge", "$count")
                }
            }
        }
    }

    // --- Conexión ---------------------------------------------------------------

    /** Debe llamarse desde un clic: el navegador solo abre el selector Bluetooth así. */
    fun connectAdapter() {
        if (!WebBluetooth.isAvailable()) {
            showBluetoothUnavailable()
            return
        }
        WebBluetooth.requestDevice().then({ device: dynamic ->
            scope.launch { connect(CallbackTransport(WebBluetoothLink(device), openTimeoutMs = 15_000), demo = false) }
            Unit
        }, { error: Throwable ->
            // NotFoundError: el usuario cerró el selector sin elegir.
            if (error.asDynamic().name != "NotFoundError") {
                showError("No se pudo abrir el Bluetooth", error.message ?: "Error desconocido", CONNECTION_HINTS)
            }
            Unit
        })
    }

    fun connectDemo() {
        scope.launch { connect(MockTransport(SimulatedVehicle.audiA3(), latencyMs = 20), demo = true) }
    }

    private suspend fun connect(transport: Transport, demo: Boolean) {
        traffic.clear()
        report = null
        link = LinkState.Connecting
        render()
        val newSession = DiagnosisSession(LoggingTransport(transport, ::logTraffic), vehicle)
        try {
            val info = newSession.connect()
            session = newSession
            link = LinkState.Connected(info, demo)
            render()
            if (info.vehicleMatch == false) toast("El VIN leído no corresponde a tu ${vehicle.displayName}", "warn")
            diagnose()
        } catch (e: DiagnosisException) {
            link = LinkState.Disconnected
            render()
            showError("No se pudo conectar", e.message ?: "Error desconocido", CONNECTION_HINTS, "Reintentar" to ::connectAdapter)
        }
    }

    fun diagnose() {
        val current = session ?: return
        if (scanning) return
        scope.launch {
            scanning = true
            render()
            try {
                report = current.runDiagnostics()
            } catch (e: DiagnosisException) {
                handleFailure(e)
            } finally {
                scanning = false
                render()
            }
        }
    }

    fun clearFaults() {
        val current = session ?: return
        scope.launch {
            try {
                current.clearFaults()
                toast("Averías borradas", "ok")
                diagnose()
            } catch (e: DiagnosisException) {
                handleFailure(e, "No se pudieron borrar las averías")
            }
        }
    }

    fun disconnect() {
        screenJob?.cancel()
        val current = session
        session = null
        report = null
        link = LinkState.Disconnected
        scope.launch { current?.disconnect() }
        render()
        toast("Adaptador desconectado")
    }

    fun handleFailure(error: DiagnosisException, title: String = "Error en la lectura") {
        if (error is TransportException) {
            session = null
            link = LinkState.Disconnected
            render()
            showError("Se perdió la conexión", error.message ?: "", CONNECTION_HINTS, "Reconectar" to ::connectAdapter)
        } else {
            showError(title, error.message ?: "Error desconocido")
        }
    }

    private fun logTraffic(line: String) {
        traffic.addLast(line)
        while (traffic.size > MAX_TRAFFIC_LINES) traffic.removeFirst()
    }

    private fun showBluetoothUnavailable() {
        showSheet("Bluetooth no disponible") { close ->
            div("sheet-icon tone-accent") { icon("bluetooth") }
            h2("sheet-title", "Este navegador no tiene Bluetooth")
            p("sheet-text", "Para hablar con el adaptador hace falta un navegador con Web Bluetooth:")
            div("option-list") {
                option("iPhone o iPad", "Abre esta página con la app gratuita Bluefy.", "https://apps.apple.com/app/id1492822055")
                option("Android", "Usa Google Chrome.", null)
                option("Ordenador", "Usa Google Chrome o Microsoft Edge.", null)
            }
            div("sheet-actions") {
                button("btn btn-primary", "Ver demostración", { close(); connectDemo() }) { icon("play") }
                button("btn btn-ghost", "Cerrar", { close() })
            }
        }
    }

    private fun HTMLElement.option(name: String, detail: String, url: String?) {
        val row = if (url != null) el("a", "option-row") {
            setAttribute("href", url)
            setAttribute("target", "_blank")
            setAttribute("rel", "noopener")
        } else div("option-row")
        row.div("option-text") {
            p("option-name", name)
            p("option-detail", detail)
        }
        if (url != null) row.icon("external", "icon icon-muted")
    }

    // --- Informe ----------------------------------------------------------------

    fun shareReport() {
        val text = buildReportText() ?: return
        val navigator = window.navigator.asDynamic()
        if (navigator.share != null) {
            val data: dynamic = js("({})")
            data.title = "Diagnosis ${vehicle.displayName}"
            data.text = text
            navigator.share(data).catch { _: dynamic -> }
        } else if (navigator.clipboard != null) {
            navigator.clipboard.writeText(text).then({ _: dynamic -> toast("Informe copiado al portapapeles", "ok") })
        }
    }

    private fun buildReportText(): String? {
        val current = report ?: return null
        val info = connection
        val date = js("new Date()").toLocaleString("es-ES").unsafeCast<String>()
        return buildString {
            appendLine("Informe de diagnosis · ${vehicle.displayName} ${vehicle.generation} ${vehicle.engine.name} ${vehicle.engine.code}")
            appendLine("Fecha: $date")
            info?.vin?.let { appendLine("VIN: ${it.value}") }
            current.monitorStatus?.let { status ->
                appendLine("Testigo de motor: ${if (status.milOn) "encendido" else "apagado"}")
                append("Monitores de emisiones: ${status.completeCount}/${status.monitors.size} completos")
                if (status.incomplete.isNotEmpty()) append(" (pendientes: ${status.incomplete.joinToString { it.monitor.label }})")
                appendLine()
            }
            appendLine()
            if (current.faults.isEmpty()) {
                appendLine("Sin averías en el motor.")
            } else {
                appendLine("Averías (${current.faults.size}):")
                current.faults.forEach { fault ->
                    appendLine("• ${fault.dtc.code} · ${fault.description.title}")
                    appendLine("  ${fault.kinds.joinToString { it.label }} · Gravedad ${fault.severity.label.lowercase()}")
                }
            }
            current.freezeFrame?.let { frame ->
                appendLine()
                appendLine("Datos congelados (${frame.trigger.code}):")
                frame.values.forEach { (pid, value) ->
                    PidCatalog[pid]?.let { appendLine("  ${it.name}: ${it.formatWithUnit(value)}") }
                }
            }
            appendLine()
            append("Generado con Diagnosis · lectura de la centralita del motor por OBD-II")
        }
    }

    /** Cuántas averías confirmadas o pendientes se pueden borrar. */
    val clearableCount: Int
        get() = report?.faults?.count { DtcKind.STORED in it.kinds || DtcKind.PENDING in it.kinds } ?: 0

    companion object {
        private const val MAX_TRAFFIC_LINES = 600

        val CONNECTION_HINTS = listOf(
            "Pon el contacto del coche (no hace falta arrancar).",
            "Comprueba que el adaptador está bien enchufado en el conector OBD, bajo el volante.",
            "Cierra otras apps que puedan estar usando el adaptador.",
            "Acerca el móvil al adaptador y vuelve a intentarlo.",
        )
    }
}
