package com.jpcottin.tairoto.share

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.jpcottin.tairoto.net.Pipe
import com.jpcottin.tairoto.net.RelayServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Lagoons over Bluetooth Low Energy, for devices on a table with no Wi-Fi in
 * common. The host opens an L2CAP channel and hands every device that comes
 * in to its relay, the same relay that serves Wi-Fi, and advertises the
 * channel: the service data under [SERVICE] is the channel's number (PSM),
 * the scan response the lagoon's name. Others scan for it and connect.
 *
 * L2CAP channels need Android 10, the permissions without location Android
 * 12: below that Bluetooth is simply not offered. The channel is not paired
 * (nothing to confirm on either device): a lagoon is no secret.
 */
class BluetoothLagoons(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    /** True if this device can share over Bluetooth right now: new enough, allowed, switched on. */
    fun usable(): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK && adapter != null && PERMISSIONS.all {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } && runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    // ---------------------------------------------------------------- hosting

    /** Takes devices over Bluetooth into [relay] and says so around, until closed; null if Bluetooth cannot. */
    @SuppressLint("MissingPermission")
    fun host(relay: RelayServer, name: String): AutoCloseable? {
        if (!usable()) return null
        val a = adapter ?: return null
        val server: BluetoothServerSocket = try {
            a.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            Log.w(TAG, "no Bluetooth channel: ${e.message}")
            return null
        }
        val psm = server.psm
        val accepting = Thread({
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break // closed
                }
                Log.i(TAG, "a device came in over Bluetooth")
                relay.serve(BluetoothPipe(socket))
            }
        }, "bt-accept").apply { isDaemon = true; start() }

        val advertiser = a.bluetoothLeAdvertiser
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settings: AdvertiseSettings) {
                Log.i(TAG, "advertising the lagoon on channel $psm")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "could not advertise the lagoon: $errorCode")
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE), Advert.encodePsm(psm))
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Advert.NAME_ID, Advert.encodeName(name))
            .build()
        try {
            advertiser?.startAdvertising(settings, data, response, callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not advertise the lagoon: ${e.message}")
        }
        return AutoCloseable {
            runCatching { advertiser?.stopAdvertising(callback) }
            runCatching { server.close() }
            accepting.join(500)
        }
    }

    // ---------------------------------------------------------------- finding

    private val seen = HashMap<String, Pair<NearbyLagoon, Long>>()
    private val devices = HashMap<String, BluetoothDevice>()
    private var scanning: ScanCallback? = null

    /** Looks for lagoons until [stopScan]; [onChange] gets the ones heard of lately, every time that changes. */
    @SuppressLint("MissingPermission")
    fun scan(onChange: (List<NearbyLagoon>) -> Unit) {
        if (!usable() || scanning != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val psm = Advert.decodePsm(record.getServiceData(ParcelUuid(SERVICE))) ?: return
                val name = Advert.decodeName(record.getManufacturerSpecificData(Advert.NAME_ID))
                    ?: record.deviceName ?: "Nearby lagoon"
                val address = result.device.address
                val lagoon = NearbyLagoon(name, address, psm, NearbyLagoon.Via.BLUETOOTH)
                val changed = synchronized(seen) {
                    devices[address] = result.device
                    val before = seen[address]?.first
                    seen[address] = lagoon to SystemClock.elapsedRealtime()
                    before != lagoon || prune()
                }
                if (changed) onChange(current())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "could not look for lagoons over Bluetooth: $errorCode")
            }
        }
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE), ByteArray(0), ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not look for lagoons over Bluetooth: ${e.message}")
        }
    }

    /** Forgets lagoons not heard of for a while (the host stopped). True if one went. */
    private fun prune(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return seen.entries.removeAll { now - it.value.second > FORGET_MS }
    }

    private fun current(): List<NearbyLagoon> = synchronized(seen) { prune(); seen.values.map { it.first } }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val c = scanning ?: return
        scanning = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(c) }
        synchronized(seen) { seen.clear() }
    }

    // ---------------------------------------------------------------- joining

    /** Opens a channel to a lagoon found by [scan]. Blocks; throws if it cannot. */
    @SuppressLint("MissingPermission")
    fun connect(lagoon: NearbyLagoon): Pipe {
        if (!usable()) throw IOException("Bluetooth is off or not allowed")
        // The device as the scan saw it: its address may be a random one only the scan knows how to reach.
        val device = synchronized(seen) { devices[lagoon.host] } ?: adapter!!.getRemoteDevice(lagoon.host)
        val socket = device.createInsecureL2capChannel(lagoon.port)
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("could not reach ${lagoon.name} over Bluetooth: ${e.message}", e)
        }
        return BluetoothPipe(socket)
    }

    private class BluetoothPipe(private val socket: BluetoothSocket) : Pipe {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override val label: String = "bluetooth"
        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Tairoto's lagoons, in Bluetooth advertisements. */
        val SERVICE: UUID = UUID.fromString("7a1e0f3c-5b2d-4c8e-9a61-2f4b8d0c7e15")
        const val MIN_SDK = 31
        val PERMISSIONS = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
        )
        private const val FORGET_MS = 8_000L
        private const val TAG = "TairotoShare"
    }
}

/**
 * What goes in a lagoon's Bluetooth advertisement, which holds 31 bytes: the
 * channel number as two bytes, and in the scan response the name, cut to fit
 * on a character boundary.
 */
object Advert {
    /** Manufacturer id reserved for tests and prototypes; the name rides in it. */
    const val NAME_ID = 0xFFFF
    const val MAX_NAME_BYTES = 26

    fun encodePsm(psm: Int): ByteArray = byteArrayOf((psm ushr 8).toByte(), psm.toByte())

    fun decodePsm(data: ByteArray?): Int? {
        if (data == null || data.size < 2) return null
        val psm = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        return psm.takeIf { it > 0 }
    }

    fun encodeName(name: String): ByteArray {
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > MAX_NAME_BYTES) break
            out.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeName(data: ByteArray?): String? =
        data?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
}
