package com.sistemadiagnosis.core.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Adaptadores ELM327 WiFi: crean su propia red y escuchan por TCP,
 * casi siempre en 192.168.0.10:35000.
 */
class WifiTransport(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 5_000,
) : StreamTransport() {
    private var socket: Socket? = null

    override val displayName: String get() = "WiFi $host:$port"

    override fun connect(): Pair<InputStream, OutputStream> {
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = newSocket
        return newSocket.getInputStream() to newSocket.getOutputStream()
    }

    override fun disconnect() {
        socket?.close()
        socket = null
    }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
