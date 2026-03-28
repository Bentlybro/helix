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

val SERVICE_UUID: UUID = UUID.fromString("917649A0-D98E-11E5-9EEC-0002A5D5C51B")
val ORIENTATION_UUID: UUID = UUID.fromString("917649A1-D98E-11E5-9EEC-0002A5D5C51B")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class Orientation(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f
)

enum class ConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, CONNECTED
}

@SuppressLint("MissingPermission")
class HelixBleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _orientation = MutableStateFlow(Orientation())
    val orientation: StateFlow<Orientation> = _orientation

    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress

    private val _log = MutableStateFlow<List<Orientation>>(emptyList())
    val log: StateFlow<List<Orientation>> = _log

    // Calibration offset — subtracted from every reading
    private var calibrationOffset = Orientation()
    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated

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
                this@HelixBleManager.gatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(ORIENTATION_UUID) ?: return
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == ORIENTATION_UUID) {
                parseOrientation(characteristic.value)
            }
        }
    }

    private fun parseOrientation(data: ByteArray) {
        if (data.size < 12) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val raw = Orientation(roll = buf.float, pitch = buf.float, yaw = buf.float)

        // Apply calibration offset
        val calibrated = Orientation(
            roll = raw.roll - calibrationOffset.roll,
            pitch = raw.pitch - calibrationOffset.pitch,
            yaw = wrapAngle(raw.yaw - calibrationOffset.yaw)
        )

        _orientation.value = calibrated
        _log.value = (_log.value + calibrated).takeLast(100)
    }

    /** Set current orientation as zero reference */
    fun calibrate() {
        calibrationOffset = _orientation.value.let {
            // Need to capture the raw value before offset — store raw separately
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
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceAddress.value = null
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}
