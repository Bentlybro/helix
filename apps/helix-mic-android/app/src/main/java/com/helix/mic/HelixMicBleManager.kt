package com.helix.mic

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

    private val _deviceAddress = MutableStateFlow<String?>(null)
    val deviceAddress: StateFlow<String?> = _deviceAddress

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _isReceivingAudio = MutableStateFlow(false)
    val isReceivingAudio: StateFlow<Boolean> = _isReceivingAudio

    // Rolling audio buffer — store chunks, concatenate only when needed
    private val audioChunks = ArrayDeque<ByteArray>()
    private var totalAudioBytes = 0
    private val audioLock = Any()
    private val maxBufferBytes = 320_000 // ~10s at 16kHz 16-bit mono
    private var packetCounter = 0

    private val _audioBuffer = MutableStateFlow<ByteArray>(ByteArray(0))
    val audioBuffer: StateFlow<ByteArray> = _audioBuffer

    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount

    private val _totalAudioBytes = MutableStateFlow(0)
    val totalAudioBytesFlow: StateFlow<Int> = _totalAudioBytes

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
                this@HelixMicBleManager.gatt = gatt
                // Request larger MTU first — need >23 for audio streaming
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _deviceAddress.value = null
                _batteryPercent.value = null
                _isReceivingAudio.value = false
                this@HelixMicBleManager.gatt = null
                gattQueue.clear()
                gattBusy = false
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.d("HelixMic", "MTU changed to $mtu (status=$status)")
            // Now discover services after MTU is negotiated
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
            android.util.Log.d("HelixMic", "Service found! Characteristics: ${service.characteristics.map { it.uuid }}")

            // Subscribe to audio first — this is the critical one that starts the mic
            val audioChar = service.getCharacteristic(AUDIO_UUID)
            if (audioChar == null) {
                android.util.Log.e("HelixMic", "Audio characteristic $AUDIO_UUID NOT FOUND!")
            } else {
                android.util.Log.d("HelixMic", "Writing audio CCCD...")
                gatt.setCharacteristicNotification(audioChar, true)
                val desc = audioChar.getDescriptor(CCCD_UUID)
                if (desc == null) {
                    android.util.Log.e("HelixMic", "Audio CCCD descriptor NOT FOUND!")
                } else {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val result = gatt.writeDescriptor(desc)
                    android.util.Log.d("HelixMic", "writeDescriptor(audio) result=$result")
                }
            }

            // Queue battery after a short delay to avoid collisions
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
                    BATTERY_UUID -> _batteryPercent.value =
                        value.firstOrNull()?.toInt()?.and(0xFF)
                }
            }
            nextGattOp()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Extract UUID and value immediately — Android 16 invalidates the object
            // after this callback returns, so we must copy everything up front
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: return

            when (uuid) {
                AUDIO_UUID -> {
                    val packet = AudioPacket.parse(value) ?: return
                    if (packet.data.isNotEmpty()) {
                        synchronized(audioLock) {
                            audioChunks.add(packet.data)
                            totalAudioBytes += packet.data.size
                            // Trim from front if over limit
                            while (totalAudioBytes > maxBufferBytes && audioChunks.isNotEmpty()) {
                                totalAudioBytes -= audioChunks.removeFirst().size
                            }
                        }
                        packetCounter++
                        // Only update UI every 50 packets to avoid flooding Compose
                        if (packetCounter % 50 == 0) {
                            _packetCount.value = packetCounter
                            _totalAudioBytes.value = totalAudioBytes
                        }
                        _isReceivingAudio.value = true
                    }
                }
                BATTERY_UUID -> _batteryPercent.value =
                    value.firstOrNull()?.toInt()?.and(0xFF)
            }
        }
    }

    fun startScan() {
        _connectionState.value = ConnectionState.SCANNING
        val filter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
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
        _isReceivingAudio.value = false
    }

    fun clearAudioBuffer() {
        synchronized(audioLock) {
            audioChunks.clear()
            totalAudioBytes = 0
        }
        _audioBuffer.value = ByteArray(0)
        _packetCount.value = 0
        _totalAudioBytes.value = 0
        _isReceivingAudio.value = false
    }

    /** Concatenate all chunks into a single ByteArray — only call when transcribing */
    fun getAudioBufferForTranscription(): ByteArray {
        synchronized(audioLock) {
            val result = ByteArray(totalAudioBytes)
            var offset = 0
            for (chunk in audioChunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        }
    }
}
