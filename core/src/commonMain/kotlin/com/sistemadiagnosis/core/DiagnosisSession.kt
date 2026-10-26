package com.sistemadiagnosis.core

import com.sistemadiagnosis.core.elm.AdapterInfo
import com.sistemadiagnosis.core.elm.Elm327Driver
import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.knowledge.DtcDescription
import com.sistemadiagnosis.core.knowledge.DtcKnowledge
import com.sistemadiagnosis.core.knowledge.Severity
import com.sistemadiagnosis.core.obd.Dtc
import com.sistemadiagnosis.core.obd.DtcKind
import com.sistemadiagnosis.core.obd.FreezeFrame
import com.sistemadiagnosis.core.obd.MonitorStatus
import com.sistemadiagnosis.core.obd.ObdClient
import com.sistemadiagnosis.core.obd.PidCatalog
import com.sistemadiagnosis.core.obd.Vin
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.vehicle.VehicleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

data class QuickScanResult(
    val adapter: AdapterInfo,
    val batteryVoltage: Double?,
    val obdProtocol: ObdProtocol?,
    val vin: Vin?,
    /** Por qué no se pudo hablar con el vehículo (contacto quitado, sin OBD…); null si todo fue bien. */
    val vehicleError: String?,
)

data class ConnectionInfo(
    val adapter: AdapterInfo,
    val batteryVoltage: Double?,
    val obdProtocol: ObdProtocol,
    val vin: Vin?,
    /** true si el VIN encaja con el perfil del vehículo; null si no hay perfil o VIN. */
    val vehicleMatch: Boolean?,
    /** PIDs de datos en vivo que ofrece la centralita del motor. */
    val supportedPids: Set<Int>,
)

data class FaultEntry(val dtc: Dtc, val kinds: Set<DtcKind>, val description: DtcDescription) {
    val severity: Severity get() = description.severity
}

data class DiagnosticReport(
    val monitorStatus: MonitorStatus?,
    /** Ordenadas de más a menos grave. */
    val faults: List<FaultEntry>,
    val freezeFrame: FreezeFrame?,
    /** Contadores de uso: km desde el borrado, calentamientos, km con testigo… */
    val tripData: Map<Int, Double>,
) {
    val highestSeverity: Severity? get() = faults.maxOfOrNull { it.severity }
    val confirmedCount: Int get() = faults.count { DtcKind.STORED in it.kinds }
}

data class LiveSample(val values: Map<Int, Double>, val updatesPerSecond: Double)

/** Punto de entrada para las apps (web, iOS, Android, CLI). */
class DiagnosisSession(
    private val transport: Transport,
    val vehicle: VehicleProfile? = null,
) {
    private val elm = Elm327Driver(transport)
    private val obd = ObdClient(elm)

    val transportName: String get() = transport.displayName

    var connection: ConnectionInfo? = null
        private set

    /**
     * Abre el adaptador y establece comunicación con el motor. Con un perfil de
     * vehículo, dirige las peticiones solo a la centralita del motor.
     */
    @Throws(DiagnosisException::class, CancellationException::class)
    suspend fun connect(): ConnectionInfo = withExpectedErrors {
        try {
            val adapter = elm.initialize()
            val voltage = orNull { obd.readBatteryVoltage() }
            val protocol = obd.connect()
            val vin = orNull { obd.readVin() }
            val engine = vehicle?.engineEcu
            if (engine != null && protocol.isCan) orNull { elm.addressEcu(engine.requestId, engine.responseId) }
            val supported = obd.supportedPids()
            ConnectionInfo(adapter, voltage, protocol, vin, vehicle?.matches(vin), supported).also { connection = it }
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }

    /** Testigo, monitores, averías (confirmadas, pendientes y permanentes), datos congelados y contadores. */
    @Throws(DiagnosisException::class, CancellationException::class)
    suspend fun runDiagnostics(): DiagnosticReport = withExpectedErrors {
        val status = orNull { obd.readMonitorStatus() }
        val found = DtcKind.entries.flatMap { kind -> (orNull { obd.readDtcs(kind) } ?: emptyList()).map { it to kind } }
        val faults = found.groupBy({ it.first }, { it.second })
            .map { (dtc, kinds) -> FaultEntry(dtc, kinds.toSet(), DtcKnowledge.describe(dtc, vehicle)) }
            .sortedWith(
                compareByDescending<FaultEntry> { it.severity }
                    .thenBy { if (DtcKind.STORED in it.kinds) 0 else 1 }
                    .thenBy { it.dtc.code },
            )
        val freezeFrame = if (faults.isEmpty()) null else orNull { obd.readFreezeFrame() }
        val supported = connection?.supportedPids.orEmpty()
        val trip = orNull { obd.readPids(TRIP_PIDS.filter { it in supported }) } ?: emptyMap()
        DiagnosticReport(status, faults, freezeFrame, trip)
    }

    /**
     * Borra averías y datos congelados del motor. Reinicia también los monitores
     * de emisiones: tras borrar hay que conducir para que vuelvan a completarse.
     */
    @Throws(DiagnosisException::class, CancellationException::class)
    suspend fun clearFaults() = withExpectedErrors { obd.clearDtcs() }

    /**
     * Lee los datos pedidos en bucle mientras se recoja el flujo. Incluye los
     * valores derivados (p.ej. presión de turbo) y la frecuencia de refresco.
     */
    fun liveData(pids: List<Int>): Flow<LiveSample> = flow {
        val supported = connection?.supportedPids.orEmpty()
        val direct = pids.filter { PidCatalog[it]?.isDerived == false && it in supported }
        val inputs = pids.flatMap { PidCatalog.derivedInputs[it].orEmpty() }.filter { it in supported }
        val baroNeeded = BARO in inputs
        val fast = (direct + inputs.filter { it != BARO }).distinct()
        if (fast.isEmpty() && !baroNeeded) return@flow
        var baro: Double? = null
        var cycle = 0
        while (true) {
            val started = TimeSource.Monotonic.markNow()
            // La presión atmosférica apenas cambia: basta con leerla de vez en cuando.
            val request = if (baroNeeded && (baro == null || cycle % 30 == 0)) fast + BARO else fast
            val values = withExpectedErrors { obd.readPids(request) }.toMutableMap()
            val readBaro = values[BARO]
            if (readBaro != null) baro = readBaro else baro?.let { values[BARO] = it }
            values += PidCatalog.derive(values)
            val elapsedMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
            emit(LiveSample(values.filterKeys { it in pids }, 1000.0 / elapsedMs))
            cycle++
        }
    }

    suspend fun disconnect() {
        connection = null
        transport.close()
    }

    /**
     * Abre el adaptador, lee su versión y la batería, conecta con el vehículo,
     * lee el VIN y cierra. Solo lanza si el adaptador no se puede usar; los
     * fallos con el vehículo van en [QuickScanResult.vehicleError].
     */
    @Throws(DiagnosisException::class, CancellationException::class)
    suspend fun quickScan(): QuickScanResult = withExpectedErrors {
        try {
            val adapter = elm.initialize()
            val voltage = orNull { obd.readBatteryVoltage() }
            var protocol: ObdProtocol? = null
            var vin: Vin? = null
            val vehicleError = try {
                protocol = obd.connect()
                vin = obd.readVin()
                null
            } catch (e: DiagnosisException) {
                e.message
            }
            QuickScanResult(adapter, voltage, protocol, vin, vehicleError)
        } finally {
            transport.close()
        }
    }

    /** Un dato opcional que falle no debe tumbar toda la operación. */
    private inline fun <T> orNull(block: () -> T): T? =
        try {
            block()
        } catch (e: DiagnosisException) {
            null
        }

    /** Las apps nativas solo reciben [DiagnosisException]: otra excepción cerraría la app iOS. */
    private inline fun <T> withExpectedErrors(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DiagnosisException) {
            throw e
        } catch (e: Exception) {
            throw DiagnosisException("Error inesperado: ${e.message ?: e::class.simpleName}", e)
        }

    companion object {
        private const val BARO = 0x33
        private val TRIP_PIDS = listOf(0x21, 0x30, 0x31, 0x4D, 0x4E)

        /** Sesión contra un vehículo simulado, para probar la app sin adaptador. */
        fun simulated(): DiagnosisSession = DiagnosisSession(MockTransport(SimulatedVehicle(), latencyMs = 30))
    }
}
