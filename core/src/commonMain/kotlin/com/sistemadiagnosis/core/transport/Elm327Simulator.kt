package com.sistemadiagnosis.core.transport

import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.obd.Dtc
import com.sistemadiagnosis.core.util.toHex
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.TimeSource

/** Vehículo que responde a través de [Elm327Simulator]. */
data class SimulatedVehicle(
    val protocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500,
    val vin: String? = "WVWZZZ1KZ6W000001",
    val batteryVoltage: Double = 12.6,
    val ignitionOn: Boolean = true,
    val storedDtcs: List<String> = emptyList(),
    val pendingDtcs: List<String> = emptyList(),
    val permanentDtcs: List<String> = emptyList(),
    val engineRunning: Boolean = true,
) {
    companion object {
        /** Demostración: Audi A3 8Y 1.5 TFSI con un fallo de encendido en el cilindro 2. */
        fun audiA3(): SimulatedVehicle = SimulatedVehicle(
            vin = "WAUZZZGY5MA000001",
            batteryVoltage = 14.1,
            storedDtcs = listOf("P0302"),
            pendingDtcs = listOf("P2181"),
            permanentDtcs = listOf("P0302"),
        )
    }
}

/**
 * Simula un ELM327 v1.5 conectado a [vehicle], con el formato de salida real
 * (eco, espacios, saltos de línea, "SEARCHING...", multitrama CAN) y un motor
 * que funciona: ralentí con acelerones periódicos y temperaturas que suben.
 *
 * Las cabeceras (ATH1) se aceptan pero no se imprimen.
 */
class Elm327Simulator(val vehicle: SimulatedVehicle = SimulatedVehicle()) {
    private val pendingInput = StringBuilder()
    private var echo = true
    private var linefeeds = true
    private var spaces = true
    private var connectedProtocol: ObdProtocol? = null

    private val stored = vehicle.storedDtcs.toMutableList()
    private val pending = vehicle.pendingDtcs.toMutableList()
    private val permanent = vehicle.permanentDtcs.toMutableList()
    private var freezeFrameDtc: String? = vehicle.storedDtcs.firstOrNull()
    private var monitorsReset = false
    private val start = TimeSource.Monotonic.markNow()

    /** Comandos recibidos, en orden. */
    val receivedCommands = mutableListOf<String>()

    /** Recibe texto del cliente y devuelve lo que el adaptador respondería. */
    fun feed(input: String): String {
        pendingInput.append(input)
        val output = StringBuilder()
        while (true) {
            val end = pendingInput.indexOf('\r')
            if (end < 0) break
            val command = pendingInput.substring(0, end).trim()
            pendingInput.deleteRange(0, end + 1)
            output.append(respond(command))
        }
        return output.toString()
    }

    private fun respond(command: String): String {
        receivedCommands += command
        val echoed = if (echo) command + eol() else ""
        val reply = handle(command.uppercase().replace(" ", ""))
        return echoed + reply.joinToString(eol()) + eol() + eol() + ">"
    }

    private fun handle(command: String): List<String> = when {
        command == "ATZ" -> {
            echo = true; linefeeds = true; spaces = true; connectedProtocol = null
            listOf("", "ELM327 v1.5")
        }
        command == "ATI" -> listOf("ELM327 v1.5")
        command == "ATE0" || command == "ATE1" -> ok { echo = command.last() == '1' }
        command == "ATL0" || command == "ATL1" -> ok { linefeeds = command.last() == '1' }
        command == "ATS0" || command == "ATS1" -> ok { spaces = command.last() == '1' }
        command == "ATH0" || command == "ATH1" -> ok {}
        command.startsWith("ATSH") || command.startsWith("ATCRA") || command == "ATAR" -> ok {}
        command.startsWith("ATSP") -> ok { connectedProtocol = null }
        command == "ATRV" -> listOf(formatVoltage(vehicle.batteryVoltage))
        command == "ATDPN" -> listOf(connectedProtocol?.let { "A${it.code}" } ?: "A0")
        command == "ATDP" -> listOf(connectedProtocol?.let { "AUTO, ${it.displayName}" } ?: "AUTO")
        command.startsWith("AT") -> listOf("?")
        else -> handleObd(command)
    }

    private fun handleObd(command: String): List<String> {
        if (command.length % 2 != 0 || command.any { it !in "0123456789ABCDEF" }) return listOf("?")
        val searching = if (connectedProtocol == null) listOf("SEARCHING...") else emptyList()
        if (!vehicle.ignitionOn) return searching + "UNABLE TO CONNECT"
        connectedProtocol = vehicle.protocol
        val bytes = command.chunked(2).map { it.toInt(16) }
        val payload = when (bytes[0]) {
            0x01 -> currentData(bytes.drop(1))
            0x02 -> freezeFrameData(bytes.drop(1))
            0x03 -> dtcPayload(0x43, stored)
            0x07 -> dtcPayload(0x47, pending)
            0x0A -> dtcPayload(0x4A, permanent)
            0x04 -> clear()
            0x09 -> if (bytes.getOrNull(1) == 0x02) return searching + (vinResponse() ?: listOf("NO DATA")) else null
            else -> null
        }
        return searching + (payload?.let(::message) ?: listOf("NO DATA"))
    }

    // Servicio 01: varios PIDs por petición, como una centralita CAN real.
    private fun currentData(pids: List<Int>): List<Int>? {
        if (pids.isEmpty() || (pids.size > 1 && !vehicle.protocol.isCan)) return null
        val payload = mutableListOf(0x41)
        for (pid in pids.take(6)) {
            val data = if (pid % 0x20 == 0) supportBitmap(CURRENT_PIDS, pid) else pidBytes(pid, engineState())
            if (data != null) payload += listOf(pid) + data.toList()
        }
        return payload.takeIf { it.size > 1 }
    }

    // Servicio 02: PID y número de trama (solo la 0).
    private fun freezeFrameData(request: List<Int>): List<Int>? {
        val pid = request.getOrNull(0) ?: return null
        if (request.getOrNull(1) != 0x00) return null
        val data = when {
            pid % 0x20 == 0 -> supportBitmap(FREEZE_PIDS, pid)
            pid == 0x02 -> (freezeFrameDtc?.let { Dtc(it).toBytes() } ?: (0 to 0)).toList().toIntArray()
            freezeFrameDtc != null && pid in FREEZE_PIDS -> pidBytes(pid, FREEZE_STATE)
            else -> null
        } ?: return null
        return listOf(0x42, pid, 0x00) + data.toList()
    }

    private fun dtcPayload(responseService: Int, dtcs: List<String>): List<Int> {
        val dtcBytes = dtcs.flatMap { Dtc(it).toBytes().toList() }
        return if (vehicle.protocol.isCan) listOf(responseService, dtcs.size) + dtcBytes
        else listOf(responseService) + dtcBytes + List(if (dtcBytes.isEmpty()) 6 else (6 - dtcBytes.size % 6) % 6) { 0 }
    }

    private fun clear(): List<Int> {
        stored.clear()
        pending.clear()
        freezeFrameDtc = null
        monitorsReset = true
        return listOf(0x44)
    }

    private fun message(payload: List<Int>): List<String> {
        if (!vehicle.protocol.isCan) {
            // Fuera de CAN no hay multitrama: las averías llegan en líneas de 3.
            if (payload.size <= 7) return listOf(hex(*payload.toIntArray()))
            return payload.drop(1).chunked(6).map { hex(payload[0], *it.toIntArray()) }
        }
        if (payload.size <= 7) return listOf(hex(*payload.toIntArray()))
        // Primer segmento 6 bytes, siguientes 7; el último se rellena como en el bus.
        val segments = listOf(payload.take(6)) + payload.drop(6).chunked(7) { it + List(7 - it.size) { 0 } }
        return listOf(payload.size.toHex(3)) +
            segments.mapIndexed { i, bytes -> (i % 16).toHex(1) + ":" + (if (spaces) " " else "") + hex(*bytes.toIntArray()) }
    }

    private fun vinResponse(): List<String>? {
        val vinBytes = vehicle.vin?.map { it.code } ?: return null
        return if (vehicle.protocol.isCan) message(listOf(0x49, 0x02, 0x01) + vinBytes)
        else (List(3) { 0 } + vinBytes).chunked(4).mapIndexed { i, bytes -> hex(0x49, 0x02, i + 1, *bytes.toIntArray()) }
    }

    private fun supportBitmap(supported: Set<Int>, base: Int): IntArray? {
        // Una centralita solo responde a 20, 40… si tiene PIDs por encima.
        if (base > 0 && supported.none { it > base }) return null
        val bitmap = IntArray(4)
        for (bit in 0 until 32) {
            val pid = base + bit + 1
            val present = pid in supported || (bit == 31 && supported.any { it > pid })
            if (present) bitmap[bit / 8] = bitmap[bit / 8] or (0x80 shr (bit % 8))
        }
        return bitmap
    }

    /** Estado del motor: ralentí con un acelerón cada 12 s y calentamiento progresivo. */
    private fun engineState(): EngineState {
        if (!vehicle.engineRunning) return EngineState.stopped(vehicle.batteryVoltage)
        val seconds = start.elapsedNow().inWholeMilliseconds / 1000.0
        val phase = (seconds % 12.0) / 12.0
        val rev = if (phase in 0.55..0.8) sin((phase - 0.55) / 0.25 * PI) else 0.0
        val wobble = sin(seconds * 2.3)
        val coolant = minOf(91.0, 46.0 + seconds * 0.9)
        return EngineState(
            seconds = seconds,
            rpm = 760 + 18 * wobble + 2900 * rev,
            load = 21 + 3 * wobble + 52 * rev,
            coolant = coolant,
            oil = minOf(96.0, coolant - 9 + seconds * 0.05),
            intakeAir = 27 + 4 * rev,
            manifold = 31 + 2 * wobble + 118 * rev,
            maf = 3.1 + 0.2 * wobble + 42 * rev,
            throttle = 3.5 + 38 * rev,
            timing = 9 + 2 * wobble - 6 * rev,
            shortTrim = 1.6 * sin(seconds * 3.7),
            longTrim = 2.3,
            railPressure = 48 + 4 * wobble + 210 * rev,
            lambda = 1.0 + 0.012 * sin(seconds * 5.1) - 0.1 * rev,
            lambdaTarget = 1.0 - 0.1 * rev,
            catalyst = 420 + 160 * rev,
            voltage = 14.15 + 0.06 * wobble,
            fuelRate = 0.75 + 0.05 * wobble + 11 * rev,
            speed = 0.0,
            postCatalyst = 0.68 + 0.02 * wobble,
        )
    }

    private fun pidBytes(pid: Int, s: EngineState): IntArray? = when (pid) {
        0x01 -> monitorStatusBytes()
        0x04 -> u8(s.load * 255 / 100)
        0x05 -> u8(s.coolant + 40)
        0x06 -> u8(s.shortTrim * 128 / 100 + 128)
        0x07 -> u8(s.longTrim * 128 / 100 + 128)
        0x0B -> u8(s.manifold)
        0x0C -> u16(s.rpm * 4)
        0x0D -> u8(s.speed)
        0x0E -> u8((s.timing + 64) * 2)
        0x0F -> u8(s.intakeAir + 40)
        0x10 -> u16(s.maf * 100)
        0x11 -> u8(s.throttle * 255 / 100)
        0x15 -> intArrayOf(clampByte(s.postCatalyst * 200), 0xFF)
        0x1F -> u16(s.seconds)
        0x21 -> u16(if (stored.isNotEmpty()) 37.0 else 0.0)
        0x23 -> u16(s.railPressure * 10)
        0x2F -> u8(64.0 * 255 / 100)
        0x30 -> u8(if (monitorsReset) 0.0 else 12.0)
        0x31 -> u16(if (monitorsReset) 0.0 else 1240.0)
        0x33 -> u8(101.0)
        0x34 -> u16(s.lambda * 32768) + u16(128.0 * 256)
        0x3C -> u16((s.catalyst + 40) * 10)
        0x42 -> u16(s.voltage * 1000)
        0x43 -> u16(s.load * 1.1 * 255 / 100)
        0x44 -> u16(s.lambdaTarget * 32768)
        0x46 -> u8(19.0 + 40)
        0x49 -> u8(s.throttle * 0.9 * 255 / 100)
        0x4C -> u8(s.throttle * 255 / 100)
        0x4D -> u16(if (stored.isNotEmpty()) 46.0 else 0.0)
        0x4E -> u16(if (monitorsReset) 0.0 else 1980.0)
        0x5C -> u8(s.oil + 40)
        0x5E -> u16(s.fuelRate * 20)
        else -> null
    }

    private fun monitorStatusBytes(): IntArray {
        val mil = if (stored.isNotEmpty()) 0x80 else 0x00
        // Continuos (fallos, combustible, componentes) + catalizador, EVAP, sondas y su calefacción.
        val incomplete = if (monitorsReset) 0x65 else 0x04
        return intArrayOf(mil or stored.size, 0x07, 0x65, incomplete)
    }

    private fun ok(apply: () -> Unit): List<String> {
        apply()
        return listOf("OK")
    }

    private fun hex(vararg bytes: Int) = bytes.joinToString(if (spaces) " " else "") { it.toHex(2) }

    private fun formatVoltage(volts: Double): String {
        val tenths = (volts * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}V"
    }

    private fun eol() = if (linefeeds) "\r\n" else "\r"

    private class EngineState(
        val seconds: Double, val rpm: Double, val load: Double, val coolant: Double, val oil: Double,
        val intakeAir: Double, val manifold: Double, val maf: Double, val throttle: Double, val timing: Double,
        val shortTrim: Double, val longTrim: Double, val railPressure: Double, val lambda: Double,
        val lambdaTarget: Double, val catalyst: Double, val voltage: Double, val fuelRate: Double,
        val speed: Double, val postCatalyst: Double,
    ) {
        companion object {
            fun stopped(voltage: Double) = EngineState(
                0.0, 0.0, 0.0, 20.0, 20.0, 20.0, 101.0, 0.0, 0.0, 0.0, 0.0, 0.0, 6.0, 1.0, 1.0, 20.0, voltage, 0.0, 0.0, 0.45,
            )
        }
    }

    private companion object {
        val CURRENT_PIDS = setOf(
            0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x15, 0x1F, 0x21, 0x23, 0x2F,
            0x30, 0x31, 0x33, 0x34, 0x3C, 0x42, 0x43, 0x44, 0x46, 0x49, 0x4C, 0x4D, 0x4E, 0x5C, 0x5E,
        )
        val FREEZE_PIDS = setOf(0x02, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x33, 0x42, 0x5C)

        /** Momento del fallo: motor aún frío, acelerando suave a 52 km/h. */
        val FREEZE_STATE = EngineState(
            seconds = 214.0, rpm = 1840.0, load = 46.0, coolant = 41.0, oil = 33.0, intakeAir = 14.0, manifold = 78.0,
            maf = 14.6, throttle = 18.0, timing = 4.0, shortTrim = 9.4, longTrim = 3.1, railPressure = 120.0,
            lambda = 1.0, lambdaTarget = 1.0, catalyst = 310.0, voltage = 14.3, fuelRate = 4.2, speed = 52.0, postCatalyst = 0.6,
        )

        fun clampByte(value: Double) = value.roundToInt().coerceIn(0, 255)
        fun u8(value: Double) = intArrayOf(clampByte(value))
        fun u16(value: Double): IntArray {
            val raw = value.roundToInt().coerceIn(0, 0xFFFF)
            return intArrayOf(raw shr 8, raw and 0xFF)
        }
    }
}
