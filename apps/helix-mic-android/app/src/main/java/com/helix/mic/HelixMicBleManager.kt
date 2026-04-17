package com.helix.mic

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

val SERVICE_UUID: UUID = UUID.fromString("917649A0-D98E-11E5-9EEC-0002A5D5C51B")
val AUDIO_UUID: UUID = UUID.fromString("917649B0-D98E-11E5-9EEC-0002A5D5C51B")
val BATTERY_UUID: UUID = UUID.fromString("917649A2-D98E-11E5-9EEC-0002A5D5C51B")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

const val DEVICE_NAME = "Helix-Mic"

// Audio packet format: [seq_num: uint16][flags: uint8][audio data bytes]
// flags bit 0: 0 = Opus, 1 = raw PCM 16kHz 16-bit mono
data class AudioPacket(
    val seqNum: Int,
    val isRawPcm: Boolean,
    val data: ByteArray
) {
    companion object {
        fun parse(bytes: ByteArray): AudioPacket? {
            if (bytes.size < 3) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val seqNum = buf.short.toInt() and 0xFFFF
            val flags = buf.get().toInt() and 0xFF
            val isRawPcm = (flags and 0x01) != 0
            val audioData = bytes.copyOfRange(3, bytes.size)
            return AudioPacket(seqNum, isRawPcm, audioData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioPacket
        if (seqNum != other.seqNum) return false
        if (isRawPcm != other.isRawPcm) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = seqNum
        result = 31 * result + isRawPcm.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class HelixMicBleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null

    // GATT operation queue — one at a time
    private val gattQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var gattBusy = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Auto-reconnect state
    @Volatile private var autoReconnect = false
    private var reconnectAttempt = 0
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress

    // null = unknown / not received yet; same sentinel used for firmware's 0xFF
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _batteryMillivolts = MutableStateFlow<Int?>(null)
    val batteryMillivolts: StateFlow<Int?> = _batteryMillivolts

    // Last disconnect reason — human-readable for UI display
    private val _lastDisconnectReason = MutableStateFlow<String?>(null)
    val lastDisconnectReason: StateFlow<String?> = _lastDisconnectReason

    private val _isReceivingAudio = MutableStateFlow(false)
    val isReceivingAudio: StateFlow<Boolean> = _isReceivingAudio

    private var packetCounter = 0

    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount

    // Raw audio frame flow — TranscriptionService consumes this
    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val audioFrames: SharedFlow<ByteArray> = _audioFrames

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
            val deviceName = result.device.name
            if (deviceName == DEVICE_NAME) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                _connectionState.value = ConnectionState.CONNECTING
                _deviceAddress.value = result.device.address
                result.device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0
                this@HelixMicBleManager.gatt = gatt
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _lastDisconnectReason.value = gattDisconnectReason(status)
                gatt?.close()
                this@HelixMicBleManager.gatt = null
                gattQueue.clear()
                gattBusy = false
                _batteryPercent.value = null
                _isReceivingAudio.value = false

                if (autoReconnect) {
                    val delayMs = minOf(1000L * (1 shl minOf(reconnectAttempt, 5)), 30_000L)
                    reconnectAttempt++
                    Log.d("HelixMic", "Auto-reconnect attempt $reconnectAttempt in ${delayMs}ms (reason: ${_lastDisconnectReason.value})")
                    _connectionState.value = ConnectionState.SCANNING
                    reconnectHandler.postDelayed({ startScan() }, delayMs)
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _deviceAddress.value = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("HelixMic", "MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            android.util.Log.d("HelixMic", "onServicesDiscovered status=$status services=${gatt.services.map { it.uuid }}")
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                android.util.Log.e("HelixMic", "Service $SERVICE_UUID NOT FOUND!")
                return
            }

            // Subscribe to audio notifications (queued so it serializes with battery)
            val audioChar = service.getCharacteristic(AUDIO_UUID)
            if (audioChar == null) {
                android.util.Log.e("HelixMic", "Audio characteristic $AUDIO_UUID NOT FOUND!")
            } else {
                enqueue {
                    gatt.setCharacteristicNotification(audioChar, true)
                    val desc = audioChar.getDescriptor(CCCD_UUID)
                    if (desc == null) {
                        android.util.Log.e("HelixMic", "Audio CCCD descriptor NOT FOUND!")
                        nextGattOp()
                    } else {
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(desc)
                    }
                }
            }

            // Queue battery subscription (NOTIFY + initial READ)
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

            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            android.util.Log.d("HelixMic", "onDescriptorWrite uuid=${descriptor.characteristic.uuid} status=$status")
            nextGattOp()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf()
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                when (uuid) {
                    BATTERY_UUID -> {
                        parseBattery(value, "READ")
                    }
                }
            }
            nextGattOp()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: return

            when (uuid) {
                AUDIO_UUID -> {
                    val packet = AudioPacket.parse(value) ?: return
                    if (packet.data.isNotEmpty()) {
                        // Emit to SharedFlow for TranscriptionService
                        _audioFrames.tryEmit(packet.data)

                        packetCounter++
                        if (packetCounter % 50 == 0) {
                            _packetCount.value = packetCounter
                        }
                        _isReceivingAudio.value = true
                    }
                }
                BATTERY_UUID -> parseBattery(value, "NOTIFY")
            }
        }
    }

    private fun parseBattery(value: ByteArray, kind: String) {
        val hex = value.joinToString(" ") { "%02x".format(it) }
        val rawPct = value.firstOrNull()?.toInt()?.and(0xFF)
        val pct = if (rawPct == null || rawPct == 0xFF) null else rawPct
        val mv: Int? = if (value.size >= 3) {
            ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8))
        } else null
        Log.d("HelixMic", "battery $kind raw=[$hex] pct=$pct mV=$mv")
        _batteryPercent.value = pct
        _batteryMillivolts.value = mv
    }

    fun startScan() {
        autoReconnect = true
        reconnectAttempt = 0

        // Fast path: if the device is already bonded (paired in system settings),
        // connect directly instead of scanning. Scans often can't find bonded
        // devices that are already connected at the OS level.
        val bonded = bluetoothAdapter?.bondedDevices?.firstOrNull { it.name == DEVICE_NAME }
        if (bonded != null) {
            Log.d("HelixMic", "Found bonded device ${bonded.address}, connecting directly")
            _connectionState.value = ConnectionState.CONNECTING
            _deviceAddress.value = bonded.address
            bonded.connectGatt(context, false, gattCallback)
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        // Service UUID filter works when the screen is off, unlike name-only filters.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .setDeviceName(DEVICE_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        autoReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        gattQueue.clear()
        gattBusy = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceAddress.value = null
        _batteryPercent.value = null
        _isReceivingAudio.value = false
    }

    /** Translate Android GATT disconnect status codes into human-readable reasons. */
    private fun gattDisconnectReason(status: Int): String = when (status) {
        0 -> "Disconnected cleanly"
        8 -> "Connection timeout — device out of range?"
        19 -> "Device disconnected"
        22 -> "Link supervision timeout"
        34 -> "Connection rejected"
        62 -> "Connection failed"
        133 -> "GATT error — try again"
        147 -> "Link lost — device out of range?"
        else -> "Disconnected (status $status)"
    }

    fun resetPacketCount() {
        packetCounter = 0
        _packetCount.value = 0
        _isReceivingAudio.value = false
    }
}
