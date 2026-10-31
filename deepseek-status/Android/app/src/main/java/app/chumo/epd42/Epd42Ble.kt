package app.chumo.epd42

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.random.Random

/**
 * EPD42 墨水屏 BLE 推送（协议与桌面版一致）。
 * 自动选择名称前缀匹配、信号最强的设备；信号相同则随机。
 */
object Epd42Ble {
    private val SERVICE_UUID: UUID = UUID.fromString("62750001-d828-918d-fb46-b6c11c675aec")
    private val CHAR_UUID: UUID = UUID.fromString("62750002-d828-918d-fb46-b6c11c675aec")

    private const val CMD_INIT = 0x01
    private const val CMD_SEND_CMD = 0x03
    private const val CMD_SEND_DATA = 0x04
    private const val CMD_DISPLAY = 0x05
    private const val CMD_SLEEP = 0x06

    private const val CHUNK = 19
    private const val DRIVER_EPD_4IN2 = 0x01

    private const val PREFS_NAME = "epd42_ble"
    private const val KEY_ADDRESS = "last_address"

    data class PushResult(val deviceName: String, val deviceAddress: String, val rssi: Int)

    @SuppressLint("MissingPermission")
    suspend fun push(
        context: Context,
        plane10: ByteArray,
        plane13: ByteArray,
        namePrefix: String = "NRF_EPD_",
        onLog: (String) -> Unit = {},
    ): PushResult = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: throw IllegalStateException("设备不支持蓝牙")
        if (!adapter.isEnabled) throw IllegalStateException("蓝牙未开启")

        // 记住上次成功连接的设备，之后直接连、不再反复扫描（降低协议栈压力）
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var device: BluetoothDevice? = null
        var rssi = 0
        val cachedAddress = prefs.getString(KEY_ADDRESS, null)
        if (cachedAddress != null) {
            try {
                val d = adapter.getRemoteDevice(cachedAddress)
                onLog("尝试直连已记住的设备 ${d.name ?: cachedAddress}（跳过扫描）…")
                device = d
            } catch (e: Exception) {
                onLog("记住的地址无效：${e.message}，重新扫描")
                prefs.edit().remove(KEY_ADDRESS).apply()
            }
        }
        if (device == null) {
            onLog("扫描蓝牙设备（前缀 $namePrefix）…")
            val candidates = scan(adapter, namePrefix, onLog)
            if (candidates.isEmpty()) throw IllegalStateException("未找到名称以 $namePrefix 开头的设备")
            val maxRssi = candidates.maxOf { it.second }
            val top = candidates.filter { it.second == maxRssi }
            val chosen = top[Random.nextInt(top.size)]
            device = chosen.first
            rssi = chosen.second
            onLog("选择设备：${device.name ?: "(无名)"} (${device.address}) RSSI=$rssi")
        }
        val target = device!!
        val deviceLabel = try { target.name } catch (e: SecurityException) { null } ?: cachedAddress ?: "(无名)"
        // 直连失败（设备不在、休眠）时自动回退扫描
        var session = GattSession(context, onLog)
        var connectedDevice: BluetoothDevice = target
        try {
            session.connectAndDiscover(connectedDevice)
        } catch (e: Exception) {
            if (cachedAddress != null) {
                onLog("直连失败（${e.javaClass.simpleName}），改为扫描查找…")
                prefs.edit().remove(KEY_ADDRESS).apply()
                session.close()
                onLog("扫描蓝牙设备（前缀 $namePrefix）…")
                val candidates = scan(adapter, namePrefix, onLog)
                if (candidates.isEmpty()) throw IllegalStateException("未找到名称以 $namePrefix 开头的设备")
                val maxRssi = candidates.maxOf { it.second }
                val top = candidates.filter { it.second == maxRssi }
                val chosen = top[Random.nextInt(top.size)]
                connectedDevice = chosen.first
                rssi = chosen.second
                session = GattSession(context, onLog)
                session.connectAndDiscover(connectedDevice)
            } else {
                throw e
            }
        }
        // 记录本次成功的设备地址
        prefs.edit().putString(KEY_ADDRESS, connectedDevice.address).apply()
        onLog("已连接：${try { connectedDevice.name } catch (e: SecurityException) { null } ?: connectedDevice.address}")

        withTimeout(300_000) {
            try {
                // 4 灰阶 = 两个平面（0x10 / 0x13），每平面 ceil(15000/19)=790 块
                val chunks10 = (plane10.size + CHUNK - 1) / CHUNK
                val chunks13 = (plane13.size + CHUNK - 1) / CHUNK
                val totalChunks = chunks10 + chunks13
                var sentChunks = 0
                val progressStep = max(1, totalChunks / 40)
                fun reportProgress() {
                    sentChunks++
                    if (sentChunks == totalChunks || sentChunks % progressStep == 0) {
                        onLog("已连接，开始发送图像… ($sentChunks/$totalChunks)")
                    }
                }
                onLog("已连接，开始发送图像… (0/$totalChunks)")
                session.writeCommand(CMD_INIT, byteArrayOf(DRIVER_EPD_4IN2.toByte()))
                session.epdWrite(0x10, plane10, ::reportProgress)
                session.epdWrite(0x13, plane13, ::reportProgress)
                session.epdWrite(0x00, byteArrayOf(0x3F))
                for ((addr, data) in StatusRenderer.LUT_4GRAY) session.epdWrite(addr, data)
                session.writeCommand(CMD_DISPLAY, null)
                session.epdWrite(0x00, byteArrayOf(0x1F))
                onLog("刷新指令已发送")
            } finally {
                session.close()
            }
        }
        onLog("推送完成")
        PushResult(deviceLabel, connectedDevice.address, rssi)
    }

    @SuppressLint("MissingPermission")
    private suspend fun scan(
        adapter: BluetoothAdapter,
        namePrefix: String,
        onLog: (String) -> Unit,
    ): List<Pair<BluetoothDevice, Int>> = suspendCancellableCoroutine { cont ->
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("蓝牙扫描不可用")
        val found = mutableListOf<Pair<BluetoothDevice, Int>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.device.name } catch (e: SecurityException) { null }
                    ?: result.scanRecord?.deviceName
                if (name != null && name.startsWith(namePrefix)) {
                    found.add(result.device to result.rssi)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("扫描失败：$errorCode")
                if (cont.isActive) cont.resume(emptyList())
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            onLog("缺少蓝牙扫描权限：${e.message}")
            if (cont.isActive) cont.resume(emptyList())
            return@suspendCancellableCoroutine
        } catch (e: Exception) {
            onLog("启动扫描失败：${e.javaClass.simpleName}: ${e.message}")
            if (cont.isActive) cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        // 协程被取消时也要停止扫描，避免扫描器注册泄漏导致后续扫不到设备
        cont.invokeOnCancellation {
            runCatching { scanner.stopScan(callback) }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(callback) } catch (_: Exception) {}
            if (cont.isActive) cont.resume(found)
        }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private class GattSession(val context: Context, val onLog: (String) -> Unit) {
        private var gatt: BluetoothGatt? = null
        private var char: BluetoothGattCharacteristic? = null

        private var connectSignal: CompletableDeferred<Unit>? = null
        private var servicesSignal: CompletableDeferred<Unit>? = null
        private var writeSignal: CompletableDeferred<Unit>? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectSignal?.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val msg = "连接断开（status=$status）"
                    connectSignal?.completeExceptionally(IllegalStateException(msg))
                    servicesSignal?.completeExceptionally(IllegalStateException(msg))
                    writeSignal?.completeExceptionally(IllegalStateException(msg))
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    servicesSignal?.completeExceptionally(IllegalStateException("服务发现失败：$status"))
                    return
                }
                val service = gatt?.getService(SERVICE_UUID)
                val c = service?.getCharacteristic(CHAR_UUID)
                if (c == null) {
                    servicesSignal?.completeExceptionally(IllegalStateException("未找到 EPD 特征值，UUID 不匹配"))
                } else {
                    char = c
                    servicesSignal?.complete(Unit)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) writeSignal?.complete(Unit)
                else writeSignal?.completeExceptionally(IllegalStateException("写入失败：$status"))
            }
        }

        suspend fun connectAndDiscover(device: BluetoothDevice): BluetoothGattCharacteristic {
            val connect = CompletableDeferred<Unit>().also { connectSignal = it }
            val services = CompletableDeferred<Unit>().also { servicesSignal = it }
            val g = device.connectGatt(context, false, callback)
                ?: throw IllegalStateException("connectGatt 失败")
            gatt = g
            withTimeout(30_000) { connect.await() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (!g.discoverServices()) throw IllegalStateException("discoverServices 失败")
            }
            withTimeout(30_000) { services.await() }
            return char ?: throw IllegalStateException("特征值不可用")
        }

        suspend fun writeRaw(data: ByteArray) {
            val g = gatt ?: throw IllegalStateException("未连接")
            val c = char ?: throw IllegalStateException("特征值不可用")
            val signal = CompletableDeferred<Unit>().also { writeSignal = it }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    c.value = data
                    g.writeCharacteristic(c)
                }
            }
            if (!ok) throw IllegalStateException("写入请求发送失败")
            withTimeout(10_000) { signal.await() }
        }

        suspend fun writeCommand(cmd: Int, payload: ByteArray?) {
            val buf = ByteArray(1 + (payload?.size ?: 0))
            buf[0] = cmd.toByte()
            if (payload != null) System.arraycopy(payload, 0, buf, 1, payload.size)
            writeRaw(buf)
        }

        suspend fun epdWrite(address: Int, data: ByteArray, onChunkSent: (() -> Unit)? = null) {
            writeCommand(CMD_SEND_CMD, byteArrayOf(address.toByte()))
            var i = 0
            while (i < data.size) {
                val end = minOf(i + CHUNK, data.size)
                writeCommand(CMD_SEND_DATA, data.copyOfRange(i, end))
                onChunkSent?.invoke()
                i = end
            }
        }

        @SuppressLint("MissingPermission")
        fun close() {
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
            char = null
        }
    }
}
