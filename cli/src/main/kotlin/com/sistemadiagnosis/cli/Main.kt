package com.sistemadiagnosis.cli

import com.sistemadiagnosis.core.DiagnosisException
import com.sistemadiagnosis.core.DiagnosisSession
import com.sistemadiagnosis.core.QuickScanResult
import com.sistemadiagnosis.core.elm.ObdProtocol
import com.sistemadiagnosis.core.transport.Elm327EmulatorServer
import com.sistemadiagnosis.core.transport.LoggingTransport
import com.sistemadiagnosis.core.transport.MockTransport
import com.sistemadiagnosis.core.transport.SimulatedVehicle
import com.sistemadiagnosis.core.transport.Transport
import com.sistemadiagnosis.core.transport.WifiTransport
import com.sistemadiagnosis.transport.serial.SerialTransport
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.system.exitProcess

private const val USAGE = """
Uso: diag <conexión> [--log]

Conexión (una):
  --mock [escenario]      Simulador. Escenarios: can (defecto), kwp, sin-vin, apagado
  --wifi [host[:puerto]]  ELM327 WiFi (defecto 192.168.0.10:35000)
  --serial PUERTO         ELM327 USB o Bluetooth emparejado (p.ej. COM5)
  --baud N                Baudios para --serial (defecto 38400)
  --ports                 Lista los puertos serie disponibles

  --emulate [escenario]   Hace de ELM327 WiFi en este PC (puerto 35000) para probar
                          la app del móvil sin coche. Ctrl+C para salir.

  --log                   Muestra todo el tráfico con el adaptador
"""

fun main(args: Array<String>) {
    useConsoleEncoding()
    exitProcess(runBlocking { run(args.toList()) })
}

/**
 * Java 17 en Windows escribe en cp1252 cuando la salida no es una consola
 * (Git Bash, tuberías) y las tildes se rompen. Usamos la codificación de la
 * consola si la hay y UTF-8 en el resto de casos.
 */
private fun useConsoleEncoding() {
    val charset = System.console()?.charset() ?: Charsets.UTF_8
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, charset))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, charset))
}

private suspend fun run(args: List<String>): Int {
    if (args.isEmpty() || "--help" in args || "-h" in args) {
        println(USAGE.trimIndent())
        return 0
    }
    if ("--ports" in args) {
        SerialTransport.availablePorts().ifEmpty { listOf("(ninguno)") }.forEach(::println)
        return 0
    }

    val base = try {
        if ("--emulate" in args) return emulate(scenario(args.valueAfter("--emulate") ?: "can"))
        transportFrom(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        println(USAGE.trimIndent())
        return 2
    }
    val transport = if ("--log" in args) LoggingTransport(base) { System.err.println("   $it") } else base

    println("Conectando por ${transport.displayName}…")
    return try {
        val result = DiagnosisSession(transport).quickScan()
        printResult(result)
        if (result.vehicleError == null) 0 else 1
    } catch (e: DiagnosisException) {
        System.err.println("Error: ${e.message}")
        1
    }
}

private fun printResult(result: QuickScanResult) {
    println("Adaptador      ${result.adapter.version}")
    result.adapter.warnings.forEach { println("  aviso: $it") }
    result.batteryVoltage?.let { println("Batería        %.1f V".format(it)) }
    result.vehicleError?.let {
        println("Vehículo       sin comunicación: $it")
        return
    }
    result.obdProtocol?.let { println("Protocolo      ${it.displayName}") }
    val vin = result.vin
    if (vin == null) {
        println("VIN            no disponible (el vehículo no responde a 09 02)")
    } else {
        println("VIN            ${vin.value}")
        println("  WMI ${vin.wmi} · VDS ${vin.vds} · VIS ${vin.vis}")
        println("  Dígito de control " + if (vin.checkDigitValid) "correcto" else "no aplica/incorrecto (normal fuera de Norteamérica)")
    }
}

private fun emulate(vehicle: SimulatedVehicle): Int {
    val server = try {
        Elm327EmulatorServer(vehicle) { println("  $it") }.start()
    } catch (e: IOException) {
        System.err.println("No se pudo escuchar en el puerto ${WifiTransport.DEFAULT_PORT}: ${e.message}")
        return 1
    }
    println("Emulando un ELM327 WiFi (${vehicle.protocol.displayName}, VIN ${vehicle.vin ?: "ninguno"}).")
    println("En el móvil, conecta a la misma red WiFi que este PC y usa:")
    localIpv4Addresses().ifEmpty { listOf("127.0.0.1") }.forEach { println("  IP $it  puerto ${server.port}") }
    println("Si Windows pregunta por el firewall, permite el acceso en redes privadas. Ctrl+C para salir.")
    while (true) Thread.sleep(Long.MAX_VALUE)
}

private fun localIpv4Addresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }

private fun List<String>.valueAfter(flag: String): String? =
    getOrNull(indexOf(flag) + 1)?.takeUnless { it.startsWith("--") }

private fun transportFrom(args: List<String>): Transport = when {
    "--mock" in args -> MockTransport(scenario(args.valueAfter("--mock") ?: "can"), latencyMs = 30)
    "--wifi" in args -> {
        val target = args.valueAfter("--wifi")
        val host = target?.substringBefore(':') ?: WifiTransport.DEFAULT_HOST
        val port = target?.substringAfter(':', "")?.ifEmpty { null }?.toIntOrNull() ?: WifiTransport.DEFAULT_PORT
        WifiTransport(host, port)
    }
    "--serial" in args -> {
        val port = args.valueAfter("--serial") ?: throw IllegalArgumentException("Falta el puerto tras --serial")
        val baud = args.valueAfter("--baud")?.toIntOrNull() ?: SerialTransport.DEFAULT_BAUD_RATE
        SerialTransport(port, baud)
    }
    else -> throw IllegalArgumentException("Indica una conexión: --mock, --wifi, --serial o --emulate")
}

private fun scenario(name: String): SimulatedVehicle = when (name) {
    "can" -> SimulatedVehicle()
    "kwp" -> SimulatedVehicle(protocol = ObdProtocol.ISO_14230_4_KWP_FAST, vin = "VF1BB05CF26010203", batteryVoltage = 12.3)
    "sin-vin" -> SimulatedVehicle(protocol = ObdProtocol.ISO_9141_2, vin = null)
    "apagado" -> SimulatedVehicle(ignitionOn = false, batteryVoltage = 11.9)
    else -> throw IllegalArgumentException("Escenario desconocido: $name")
}
