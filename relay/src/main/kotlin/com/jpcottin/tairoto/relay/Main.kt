package com.jpcottin.tairoto.relay

import com.jpcottin.tairoto.net.RelayLink
import com.jpcottin.tairoto.net.RelayServer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The relay for emulators: they cannot reach each other, but every one of
 * them reaches the computer it runs on at 10.0.2.2.
 *
 *     relay [--port 7171]
 */
fun main(args: Array<String>) {
    val port = args.indexOf("--port").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: RelayLink.DEFAULT_PORT
    val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    val server = RelayServer(port) { println("${LocalTime.now().format(time)}  $it") }
    println("Tairoto relay on port ${server.port} (emulators: ${RelayLink.EMULATOR_HOST}:${server.port}). Ctrl-C to stop.")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
