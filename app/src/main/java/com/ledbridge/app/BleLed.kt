package com.ledbridge.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Bluetooth LE link to the LED strip controller (ELK-BLEDOM family, advertised as
 * "BLEDDM"; the one driven by the Lotus Lantern app).
 *
 * - Finds the strip by name (or reuses the saved address) and reconnects on its own.
 * - Writes commands to characteristic FFF3 (service FFF0) or FFE1 (service FFE0).
 * - Everything runs on the main thread so the BLE stack never gets flooded.
 */
@SuppressLint("MissingPermission")
class BleLed(
    context: Context,
    private val target: String,
    savedMac: String?,
    private val onMac: (String?) -> Unit,
) {
    companion object {
        private fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

        private val SVC_FFF0 = uuid16(0xFFF0)
        private val CHR_FFF3 = uuid16(0xFFF3)
        private val SVC_FFE0 = uuid16(0xFFE0)
        private val CHR_FFE1 = uuid16(0xFFE1)
        private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

        private fun frame(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

        // ELK-BLEDOM protocol frames.
        val ON: ByteArray = frame(0x7E, 0x04, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
        val OFF: ByteArray = frame(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)

        fun color(r: Int, g: Int, b: Int): ByteArray =
            frame(0x7E, 0x07, 0x05, 0x03, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), 0x10, 0xEF)

        // Native brightness validated on ELK-BLEDOB03 at 50% and 100%.
        fun brightness(percent: Int): ByteArray =
            frame(0x7E, 0x04, 0x01, percent.coerceIn(1, 100), 0x01, 0xFF, 0x02, 0x01, 0xEF)
    }

    private val ctx: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile
    var connected = false
        private set

    private var running = false
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private var mac: String? = savedMac
    private var scanning = false
    private var scanToken = 0
    private var attemptToken = 0
    private var failCount = 0
    private val seen = HashSet<String>()
    private val queue = ArrayDeque<ByteArray>()
    private var draining = false

    // ---------------------------------------------------------------- ciclo de vida

    fun start() {
        running = true
        main.post { connectOrScan() }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        main.post {
            stopScan()
            closeGatt()
            queue.clear()
            draining = false
            connected = false
            BridgeState.bleConnected = false
        }
    }

    // ---------------------------------------------------------------- enviar

    fun send(bytes: ByteArray) {
        main.post {
            if (!connected) return@post
            queue.addLast(bytes)
            if (!draining) {
                draining = true
                drain()
            }
        }
    }

    private fun drain() {
        val data = queue.removeFirstOrNull()
        if (data == null) {
            draining = false
            return
        }
        if (write(data)) {
            main.postDelayed({ drain() }, 60)
        } else {
            // One retry, in case the Bluetooth stack was busy.
            main.postDelayed({
                if (!write(data)) BridgeState.log("Falló el envío de un comando por Bluetooth")
                main.postDelayed({ drain() }, 60)
            }, 100)
        }
    }

    @Suppress("DEPRECATION")
    private fun write(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = writeType
                c.value = data
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- conectar / buscar

    private fun retryLater(ms: Long) {
        if (running) main.postDelayed({ connectOrScan() }, ms)
    }

    private fun connectOrScan() {
        if (!running || connected) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            BridgeState.log("El Bluetooth del celular está apagado. Reintento en 5 s.")
            retryLater(5000)
            return
        }
        val m = mac
        if (m != null) connect(ad.getRemoteDevice(m)) else startScan()
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) {
            retryLater(5000)
            return
        }
        if (scanning) return
        scanning = true
        val token = ++scanToken
        seen.clear()
        BridgeState.log("Buscando \"$target\"…")
        try {
            sc.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCb,
            )
        } catch (e: Exception) {
            scanning = false
            BridgeState.log("No pude escanear: ${e.message}")
            retryLater(5000)
            return
        }
        main.postDelayed({
            if (running && scanning && token == scanToken) {
                stopScan()
                BridgeState.log(
                    "No encontré la tira. Cierra Lotus Lantern del todo (Forzar detención) " +
                        "y revisa que la tira esté enchufada. La primera vez deja la pantalla encendida.",
                )
                retryLater(2000)
            }
        }, 15000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) {
            // nothing to do
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name: String? = result.scanRecord?.deviceName ?: result.device.name
            val addr = result.device.address
            main.post {
                if (!scanning) return@post
                if (name != null && seen.size < 25 && seen.add(addr)) {
                    BridgeState.log("Visto: $name ($addr)")
                }
                val match = if (MAC_REGEX.matches(target)) {
                    addr.equals(target, ignoreCase = true)
                } else {
                    name != null && name.contains(target, ignoreCase = true)
                }
                if (match) {
                    stopScan()
                    mac = addr
                    connect(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                BridgeState.log("Falló el escaneo Bluetooth (código $errorCode)")
                retryLater(5000)
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        closeGatt()
        BridgeState.log("Conectando con ${device.address}…")
        val token = ++attemptToken
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        // Watchdog: if the link is not ready within 20 s, try again.
        main.postDelayed({
            if (running && token == attemptToken && !connected) {
                BridgeState.log("La tira no respondió, reintentando…")
                closeGatt()
                failCount++
                connectOrScan()
            }
        }, 20000)
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        writeChar = null
        try {
            g?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        try {
            g?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    try {
                        g.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices()
                } else {
                    val was = connected
                    connected = false
                    BridgeState.bleConnected = false
                    queue.clear()
                    draining = false
                    closeGatt()
                    BridgeState.log(
                        if (was) "Bluetooth desconectado (estado $status)" else "No se pudo conectar (estado $status)",
                    )
                    failCount++
                    if (failCount == 2) BridgeState.log("Tip: cierra Lotus Lantern (Forzar detención); la tira solo acepta una conexión.")
                    if (!was && mac != null && failCount >= 3) {
                        BridgeState.log("No logro conectar con la dirección guardada; vuelvo a buscar la tira.")
                        mac = null
                        onMac(null)
                        failCount = 0
                    }
                    retryLater(3000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val c = if (status == BluetoothGatt.GATT_SUCCESS) findWriteChar(g) else null
                if (c == null) {
                    BridgeState.log("No encontré la característica de escritura (FFF3/FFE1). Servicios que ve el celular:")
                    for (s in g.services) BridgeState.log("  servicio ${s.uuid}")
                    closeGatt()
                    retryLater(5000)
                    return@post
                }
                writeChar = c
                writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                failCount = 0
                connected = true
                BridgeState.bleConnected = true
                val addr = g.device.address
                mac = addr
                onMac(addr)
                BridgeState.log("Bluetooth conectado ✔ ($addr)")
            }
        }
    }

    private fun findWriteChar(g: BluetoothGatt): BluetoothGattCharacteristic? {
        g.getService(SVC_FFF0)?.getCharacteristic(CHR_FFF3)?.let { return it }
        g.getService(SVC_FFE0)?.getCharacteristic(CHR_FFE1)?.let { return it }
        for (s in g.services) {
            for (c in s.characteristics) {
                if (c.uuid == CHR_FFF3 || c.uuid == CHR_FFE1) return c
            }
        }
        return null
    }
}
