package com.sistemadiagnosis.core.transport

import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Se hace pasar por un ELM327 WiFi escuchando por TCP. Permite probar las apps
 * (iPhone, Android) sin coche ni adaptador: el móvil se conecta a la IP del PC.
 */
class Elm327EmulatorServer(
    private val vehicle: SimulatedVehicle = SimulatedVehicle(),
    port: Int = WifiTransport.DEFAULT_PORT,
    private val onEvent: (String) -> Unit = {},
) : Closeable {
    private val server = ServerSocket(port)

    val port: Int get() = server.localPort

    fun start(): Elm327EmulatorServer {
        thread(isDaemon = true, name = "elm327-emulator") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true, name = "elm327-emulator-client") { serve(client) }
            }
        }
        return this
    }

    private fun serve(client: Socket) = client.use {
        val address = client.remoteSocketAddress
        onEvent("Cliente conectado: $address")
        val simulator = Elm327Simulator(vehicle)
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (e: IOException) {
                -1
            }
            if (count < 0) break
            output.write(simulator.feed(buffer.decodeToString(0, count)).encodeToByteArray())
            output.flush()
        }
        onEvent("Cliente desconectado: $address")
    }

    override fun close() = server.close()
}
