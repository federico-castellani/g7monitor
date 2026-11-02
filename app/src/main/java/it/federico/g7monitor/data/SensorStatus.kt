package it.federico.g7monitor.data

import it.federico.g7monitor.protocol.SensorState
import androidx.annotation.StringRes
import it.federico.g7monitor.R
import it.federico.g7monitor.str

enum class Link(@param:StringRes private val labelRes: Int) {
    Off(R.string.link_off),
    Scanning(R.string.link_scanning),
    Connecting(R.string.link_connecting),
    Authenticating(R.string.link_authenticating),
    Bonding(R.string.link_bonding),
    Receiving(R.string.link_receiving),
    Waiting(R.string.link_waiting),
    Error(R.string.link_error);

    val label: String get() = str(labelRes)
}

data class SensorStatus(
    val link: Link = Link.Off,
    val sensorState: SensorState? = null,
    val lastContact: Long? = null,
    val message: String? = null,
    val deviceName: String? = null,
)
