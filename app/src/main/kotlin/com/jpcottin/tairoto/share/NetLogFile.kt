package com.jpcottin.tairoto.share

import java.io.File
import java.io.FileWriter
import java.util.concurrent.Executors

/**
 * The shared lagoon's log as a file in the app's storage, one JSON line per
 * event, written off the main thread. Logcat drops lines from an app that
 * logs a lot, and the cross-device test needs every one of them:
 *
 *     adb exec-out run-as com.jpcottin.tairoto cat files/net-log.jsonl
 */
class NetLogFile(dir: File) {
    private val file = File(dir, FILE_NAME)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "net-log").apply { isDaemon = true } }
    private var writer: FileWriter? = null

    init {
        io.execute { runCatching { writer = FileWriter(file, false) } }
    }

    fun append(line: String) {
        io.execute {
            runCatching {
                writer?.apply {
                    write(line)
                    write("\n")
                    flush()
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "net-log.jsonl"
    }
}
