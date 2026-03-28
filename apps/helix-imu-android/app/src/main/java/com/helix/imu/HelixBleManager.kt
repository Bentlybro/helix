package com.helix.imu

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

val SERVICE_UUID: UUID = UUID.fromString("917649A0-D98E-11E5-9EEC-0002A5D5C51B")
val ORIENTATION_UUID: UUID = UUID.fromString("917649A1-D98E-11E5-9EEC-0002A5D5C51B")
val BATTERY_UUID: UUID = UUID.fromString("917649A2-D98E-11E5-9EEC-0002A5D5C51B")
val TEMPERATURE_UUID: UUID = UUID.fromString("917649A3-D98E-11E5-9EEC-0002A5D5C51B")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class Orientation(val roll: Float = 0f, val pitch: Float = 0f, val yaw: Float = 0f)

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class HelixBleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null

    // GATT operation queue — one at a time
    private val gattQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var gattBusy = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _orientation = MutableStateFlow(Orientation())
    val orientation: StateFlow<Orientation> = _orientation

    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress

    private val _log = MutableStateFlow<List<Orientation>>(emptyList())
    val log: StateFlow<List<Orientation>> = _log

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _temperature = MutableStateFlow<Float?>(null)
    val temperature: StateFlow<Float?> = _temperature

    private var calibrationOffset = Orientation()
    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated

    // Queue a GATT operation and execute if not busy
    private fun enqueue(op: () -> Unit) {
        gattQueue.add(op)
        if (!gattBusy) nextGattOp()
    }

    private fun nextGattOp() {
        val op = gattQueue.poll()
        if (op != null) {
            gattBusy = true
            op()
        } else {
            gattBusy = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            _connectionState.value = ConnectionState.CONNECTING
            _deviceAddress.value = result.device.address
            result.device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@HelixBleManager.gatt = gatt
                gatt.discoverServices()
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _deviceAddress.value = null
                _batteryPercent.value = null
                _temperature.value = null
                this@HelixBleManager.gatt = null
                gattQueue.clear()
                gattBusy = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return

            // Enable orientation notifications
            service.getCharacteristic(ORIENTATION_UUID)?.let { char ->
                enqueue {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    } ?: nextGattOp()
                }
            }

            // Enable battery notifications + initial read
            service.getCharacteristic(BATTERY_UUID)?.let { char ->
                enqueue {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    } ?: nextGattOp()
                }
                enqueue { gatt.readCharacteristic(char) }
            }

            // Enable temperature notifications + initial read
            service.getCharacteristic(TEMPERATURE_UUID)?.let { char ->
                enqueue {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(CCCD_UUID)?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    } ?: nextGattOp()
                }
                enqueue { gatt.readCharacteristic(char) }
            }

            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            nextGattOp()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    BATTERY_UUID -> _batteryPercent.value =
                        characteristic.value.firstOrNull()?.toInt()?.and(0xFF)
                    TEMPERATURE_UUID -> _temperature.value = parseFloat(characteristic.value)
                }
            }
            nextGattOp()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                ORIENTATION_UUID -> parseOrientation(characteristic.value)
                BATTERY_UUID -> _batteryPercent.value =
                    characteristic.value.firstOrNull()?.toInt()?.and(0xFF)
                TEMPERATURE_UUID -> _temperature.value = parseFloat(characteristic.value)
            }
        }
    }

    private fun parseFloat(data: ByteArray): Float? {
        if (data.size < 4) return null
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun parseOrientation(data: ByteArray) {
        if (data.size < 12) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val raw = Orientation(roll = buf.float, pitch = buf.float, yaw = buf.float)
        val calibrated = Orientation(
            roll = raw.roll - calibrationOffset.roll,
            pitch = raw.pitch - calibrationOffset.pitch,
            yaw = wrapAngle(raw.yaw - calibrationOffset.yaw)
        )
        _orientation.value = calibrated
        _log.value = (_log.value + calibrated).takeLast(100)
    }

    fun calibrate() {
        calibrationOffset = _orientation.value.let {
            Orientation(
                roll = it.roll + calibrationOffset.roll,
                pitch = it.pitch + calibrationOffset.pitch,
                yaw = it.yaw + calibrationOffset.yaw
            )
        }
        _isCalibrated.value = true
        _log.value = emptyList()
    }

    fun resetCalibration() {
        calibrationOffset = Orientation()
        _isCalibrated.value = false
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }

    fun startScan() {
        _connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        gattQueue.clear()
        gattBusy = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceAddress.value = null
        _batteryPercent.value = null
        _temperature.value = null
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}
