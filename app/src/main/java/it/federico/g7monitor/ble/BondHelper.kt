package it.federico.g7monitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
object BondHelper {

    fun isBonded(device: BluetoothDevice) = device.bondState == BluetoothDevice.BOND_BONDED

    /** Creates the Android BLE bond and waits for the result. */
    suspend fun createBond(context: Context, device: BluetoothDevice, timeoutMs: Long = 60_000): Boolean {
        if (isBonded(device)) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != device.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> result.complete(true)
                    BluetoothDevice.BOND_NONE -> result.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            if (!device.createBond() && !isBonded(device)) false
            else withTimeoutOrNull(timeoutMs) { result.await() } ?: isBonded(device)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * Removes a bond via the hidden removeBond() API, as xDrip does for expired G7 sensors
     * (each G7 has a new address). Not guaranteed on every Android build.
     */
    fun removeBond(device: BluetoothDevice): Boolean = try {
        device.javaClass.getMethod("removeBond").invoke(device) as Boolean
    } catch (_: Exception) {
        false
    }
}
