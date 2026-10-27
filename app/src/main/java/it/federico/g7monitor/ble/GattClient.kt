package it.federico.g7monitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import it.federico.g7monitor.protocol.G7Gatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Coroutine wrapper around [BluetoothGatt]. Android allows a single outstanding GATT
 * operation, so every operation is serialised through [opLock] and awaits its callback.
 * Notifications/indications and disconnection arrive on [events].
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val log: (String) -> Unit,
) {
    sealed interface Event {
        class Notification(val uuid: UUID, val value: ByteArray) : Event
        data object Disconnected : Event
    }

    val events = Channel<Event>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var discovered: CompletableDeferred<Boolean>? = null
    @Volatile var isConnected = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("gatt state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                connected?.complete(false)
                discovered?.complete(false)
                pendingOp?.complete(-1)
                events.trySend(Event.Disconnected)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            events.trySend(Event.Notification(c.uuid, value.copyOf()))
        }

        @Deprecated("Used only on Android 12")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                events.trySend(Event.Notification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
            }
        }
    }

    /**
     * @param autoConnect true lets the Bluetooth controller wait for the (bonded) sensor in
     * the background, which is the most battery friendly way to catch the next reading.
     */
    suspend fun connect(autoConnect: Boolean, timeoutMs: Long): Boolean {
        val d = CompletableDeferred<Boolean>()
        connected = d
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        return withTimeoutOrNull(timeoutMs) { d.await() } ?: false
    }

    suspend fun discover(): Boolean {
        val g = gatt ?: return false
        val d = CompletableDeferred<Boolean>()
        discovered = d
        if (!g.discoverServices()) return false
        return withTimeoutOrNull(15_000) { d.await() } ?: false
    }

    fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(G7Gatt.CGM_SERVICE)?.getCharacteristic(uuid)

    fun requestHighPriority() {
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    private suspend fun op(timeoutMs: Long = 5_000, start: () -> Boolean): Boolean = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pendingOp = d
        if (!start()) {
            pendingOp = null
            return false
        }
        val status = withTimeoutOrNull(timeoutMs) { d.await() }
        pendingOp = null
        status == BluetoothGatt.GATT_SUCCESS
    }

    /** Enable notifications (or indications) on a characteristic of the CGM service. */
    suspend fun enable(uuid: UUID, indication: Boolean): Boolean {
        val g = gatt ?: return false
        val c = characteristic(uuid) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val desc = c.getDescriptor(G7Gatt.CCCD) ?: return false
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        }
    }

    suspend fun write(uuid: UUID, value: ByteArray, withResponse: Boolean = true): Boolean {
        val g = gatt ?: return false
        val c = characteristic(uuid) ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        isConnected = false
        connected?.complete(false)
        events.close()
    }
}
