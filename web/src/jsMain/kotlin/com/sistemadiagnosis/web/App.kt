package com.sistemadiagnosis.web

import com.sistemadiagnosis.core.ConnectionInfo
import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.DiagnosticReport
import com.sistemadiagnosis.core.ModuleScanResult
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.obd.PidCategory
import com.sistemadiagnosis.core.transport.CallbackTransport
import com.sistemadiagnosis.core.transport.LoggingTransport
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.transport.TransportException
import com.sistemadiagnosis.core.history.HistoryAlert
import com.sistemadiagnosis.core.history.HistoryAnalysis
import com.sistemadiagnosis.core.history.HistoryEntry
import com.sistemadiagnosis.core.history.HistoryFault
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.pdf.ReportInput
import com.sistemadiagnosis.core.pdf.ReportPdf
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

enum class Tab(val label: String, val icon: String, val slug: String, val inBar: Boolean = true) {
    HOME("Inicio", "home", ""),
    FAULTS("Averías", "alert", "averias"),
    LIVE("En vivo", "activity", "en-vivo"),
    READINESS("ITV", "shield", "itv"),
    MORE("Más", "more", "mas"),
    HISTORY("Historial", "clock", "historial", inBar = false),
    EXPLORER("Valores de medición", "gauge", "medidas", inBar = false),
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

    /** Escaneo completo: resultados por centralita según van llegando. */
    var moduleResults: List<ModuleScanResult> = emptyList()
        private set
    var moduleScanning = false
        private set

    /** Categoría de "En vivo"; null = destacados. */
    var liveCategory: PidCategory? = null
    var livePaused = false

    /** Tarea de la pantalla actual (datos en vivo); se cancela al cambiar de pantalla. */
    var screenJob: Job? = null

    val traffic = ArrayDeque<String>()

    /** Lectura de esta conexión en el historial; se completa con la diagnosis y el escaneo. */
    var currentEntry: HistoryEntry? = null
        private set
    private var demoHistory: List<HistoryEntry> = emptyList()

    /** Explorador de valores de medición. */
    val explorer = ExplorerState()

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
            Tab.HISTORY -> renderHistory(screen)
            Tab.EXPLORER -> renderExplorer(screen)
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
            Tab.entries.filter { it.inBar }.forEach { target ->
                button("tab${if (target == tab) " active" else ""}", null, { navigate(target) }) {
                    setAttribute("aria-label", target.label)
                    if (target == tab) setAttribute("aria-current", "page")
                    icon(target.icon)
                    span("tab-label", target.label)
                    // Con escaneo completo cuenta todas las centralitas (el motor incluido); si no, el OBD-II.
                    val count = when {
                        target != Tab.FAULTS -> 0
                        moduleResults.isNotEmpty() -> moduleResults.sumOf { it.faults.size }
                        else -> report?.faults?.size ?: 0
                    }
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
        DebugLog.startAttempt("Intento de conexión")
        DebugLog.add("Abriendo el selector Bluetooth del navegador…")
        WebBluetooth.requestDevice().then({ device: dynamic ->
            DebugLog.add("Selector: elegido '${device.name}'")
            scope.launch { connect(CallbackTransport(WebBluetoothLink(device), openTimeoutMs = 15_000), demo = false) }
            Unit
        }, { error: Throwable ->
            DebugLog.add("Selector cerrado o fallido: ${error.asDynamic().name}: ${error.message}")
            // NotFoundError: el usuario cerró el selector sin elegir, a menudo porque su adaptador no aparecía.
            if (error.asDynamic().name == "NotFoundError") {
                toast("¿No aparece tu adaptador? Puede ser Bluetooth clásico, que no funciona en iPhone", "warn")
            } else {
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
        moduleResults = emptyList()
        link = LinkState.Connecting
        render()
        val newSession = DiagnosisSession(LoggingTransport(transport, ::logTraffic), vehicle)
        if (transport is CallbackTransport) {
            transport.onDisconnected = { reason ->
                // Solo para la sesión activa: durante connect() el propio fallo ya lo explica.
                if (session === newSession) handleFailure(TransportException(reason))
            }
        }
        if (demo) DebugLog.startAttempt("Demostración")
        DebugLog.add("Sesión: inicializando adaptador y conectando con el coche…")
        try {
            val info = newSession.connect()
            DebugLog.add(
                "Sesión: CONECTADO · adaptador='${info.adapter.version}' protocolo=${info.obdProtocol.displayName} " +
                    "batería=${info.batteryVoltage} VIN=${info.vin?.value} coincide=${info.vehicleMatch} " +
                    "PIDs=${info.supportedPids.size} avisos=${info.adapter.warnings}",
            )
            session = newSession
            link = LinkState.Connected(info, demo)
            val now = nowMillis()
            currentEntry = HistoryEntry.start(now.toLong().toString(), now)
            if (demo) demoHistory = demoPastEntries(now)
            render()
            if (info.vehicleMatch == false) toast("El VIN leído no corresponde a tu ${vehicle.displayName}", "warn")
            diagnose()
        } catch (e: DiagnosisException) {
            DebugLog.error("conectando (pantalla: No se pudo conectar)", e)
            link = LinkState.Disconnected
            render()
            showError(
                "No se pudo conectar", e.message ?: "Error desconocido", CONNECTION_HINTS,
                "Reintentar" to ::connectAdapter, shareLog = !demo,
            )
        }
    }

    fun diagnose() {
        val current = session ?: return
        if (scanning) return
        scope.launch {
            scanning = true
            render()
            DebugLog.add("Diagnosis: leyendo averías, monitores y datos congelados…")
            try {
                val result = current.runDiagnostics()
                report = result
                DebugLog.add("Diagnosis: terminada · ${result.faults.size} averías · monitores=${result.monitorStatus != null}")
                connection?.let { info -> updateEntry { it.withDiagnosis(info, result) } }
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

    /** Recorre todas las centralitas del perfil; la lista se va llenando en pantalla. */
    fun scanAllModules() {
        val current = session ?: return
        if (moduleScanning) return
        moduleResults = emptyList()
        moduleScanning = true
        render()
        DebugLog.add("Escaneo completo: ${vehicle.modules.size} direcciones…")
        scope.launch {
            var complete = false
            try {
                current.scanModules().collect { result ->
                    moduleResults = moduleResults + result
                    DebugLog.add(
                        "Escaneo: ${result.module.address} ${if (result.present) "responde" else "no responde"}" +
                            (result.identity?.systemName?.let { " · $it" } ?: "") +
                            " · ${result.faults.size} averías" + (result.error?.let { " · ERROR $it" } ?: ""),
                    )
                    render()
                }
                complete = true
            } catch (e: DiagnosisException) {
                handleFailure(e, "Error en el escaneo")
            } finally {
                moduleScanning = false
                if (complete) updateEntry { it.withModuleScan(moduleResults) }
                render()
            }
        }
    }

    /** Borra las averías de una centralita y la vuelve a leer. */
    fun clearModule(result: ModuleScanResult) {
        val current = session ?: return
        scope.launch {
            try {
                current.clearModuleFaults(result.module)
                replaceModuleResult(current.rescanModule(result.module))
                toast("Averías borradas en ${result.module.address}", "ok")
                if (result.module == vehicle.engineEcu) diagnose()
            } catch (e: DiagnosisException) {
                handleFailure(e, "No se pudieron borrar las averías")
            }
        }
    }

    /** Borra las averías de todas las centralitas que tenían y repite el escaneo. */
    fun clearAllModules() {
        val current = session ?: return
        val withFaults = moduleResults.filter { it.faults.isNotEmpty() }
        scope.launch {
            try {
                withFaults.forEach { current.clearModuleFaults(it.module) }
                toast("Averías borradas en ${plural(withFaults.size, "centralita", "centralitas")}", "ok")
                scanAllModules()
                diagnose()
            } catch (e: DiagnosisException) {
                handleFailure(e, "No se pudieron borrar las averías")
            }
        }
    }

    private fun replaceModuleResult(updated: ModuleScanResult) {
        moduleResults = moduleResults.map { if (it.module == updated.module) updated else it }
        render()
    }

    fun disconnect() {
        DebugLog.add("Usuario: pulsa Desconectar")
        screenJob?.cancel()
        val current = session
        session = null
        report = null
        moduleResults = emptyList()
        currentEntry = null
        explorer.stop()
        link = LinkState.Disconnected
        scope.launch { current?.disconnect() }
        render()
        toast("Adaptador desconectado")
    }

    fun handleFailure(error: DiagnosisException, title: String = "Error en la lectura") {
        DebugLog.error(if (error is TransportException) "conexión perdida (pantalla: Se perdió la conexión)" else title, error)
        // El aviso de corte y la lectura en curso pueden fallar a la vez: basta con un mensaje.
        if (error is TransportException && link == LinkState.Disconnected) return
        if (error is TransportException) {
            explorer.stop()
            session = null
            link = LinkState.Disconnected
            render()
            showError(
                "Se perdió la conexión", error.message ?: "", CONNECTION_HINTS,
                "Reconectar" to ::connectAdapter, shareLog = !isDemo,
            )
        } else {
            showError(title, error.message ?: "Error desconocido", shareLog = !isDemo)
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

    // --- Historial ---------------------------------------------------------------

    /** Lecturas de la más antigua a la más reciente (la de esta conexión incluida). */
    val history: List<HistoryEntry>
        get() {
            val stored = if (isDemo) demoHistory else HistoryStore.all
            val vin = connection?.vin?.value
            // Solo este coche: si se conecta a otro, sus lecturas no se mezclan.
            val own = stored.filter { vin == null || it.vin == null || it.vin == vin }
            val current = currentEntry?.takeIf { it.milOn != null || it.batteryVoltage != null || it.moduleFaults != null }
            return if (current == null) own else own.filter { it.id != current.id } + current
        }

    val historyAlerts: List<HistoryAlert>
        get() = HistoryAnalysis.alerts(history, ::formatDate)

    private fun updateEntry(change: (HistoryEntry) -> HistoryEntry) {
        val updated = change(currentEntry ?: return)
        currentEntry = updated
        if (!isDemo) HistoryStore.save(updated)
    }

    fun deleteHistoryEntry(id: String) {
        if (isDemo) demoHistory = demoHistory.filter { it.id != id } else HistoryStore.delete(id)
        if (currentEntry?.id == id) currentEntry = null
        render()
    }

    fun clearHistory() {
        if (isDemo) demoHistory = emptyList() else HistoryStore.clear()
        currentEntry = null
        toast("Historial borrado")
        render()
    }

    /** Lecturas inventadas para la demostración: la avería del cilindro 2 vuelve y la batería baja poco a poco. */
    private fun demoPastEntries(now: Double): List<HistoryEntry> {
        val day = 86_400_000.0
        fun past(days: Int, volts: Double, km: Double, engine: List<String>, modules: List<HistoryFault>?) =
            HistoryEntry.start("demo-$days", now - days * day).copy(
                vin = SimulatedVehicle.audiA3().vin, batteryVoltage = volts, odometerKm = km, milOn = engine.isNotEmpty(),
                monitorsComplete = 7, monitorsTotal = 7,
                engineFaults = engine.map { HistoryFault("01", it, "Fallo de encendido en el cilindro 2", Severity.HIGH) },
                moduleFaults = modules, modulesFound = modules?.let { 15 },
            )
        return listOf(
            past(92, 12.70, 38_910.0, emptyList(), emptyList()),
            past(61, 12.58, 39_820.0, listOf("P0302"), null),
            past(33, 12.36, 40_560.0, emptyList(), emptyList()),
            past(9, 12.28, 41_040.0, emptyList(), null),
        )
    }

    // --- Informe ----------------------------------------------------------------

    /** Informe en PDF: se comparte (WhatsApp, correo, Archivos…) o se descarga. */
    fun exportPdf() {
        val current = report
        if (current == null && moduleResults.isEmpty()) {
            toast("Primero lee el coche", "warn")
            return
        }
        val now = nowMillis()
        val bytes = try {
            ReportPdf.build(
                ReportInput(
                    vehicle = vehicle,
                    connection = connection,
                    report = current,
                    modules = moduleResults,
                    history = history.filter { it.id != currentEntry?.id },
                    dateText = formatDateTime(now),
                    demo = isDemo,
                    formatDate = ::formatDate,
                ),
            )
        } catch (e: Throwable) {
            DebugLog.error("generando el PDF", e)
            showError("No se pudo crear el informe", e.message ?: "Error desconocido")
            return
        }
        val fileName = "Diagnosis-${vehicle.model}-${formatDate(now).replace('/', '-')}.pdf".replace(' ', '-')
        DebugLog.add("Informe PDF: ${bytes.size} bytes")
        sharePdf(bytes, fileName)
    }

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
