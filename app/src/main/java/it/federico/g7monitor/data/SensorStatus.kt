package it.federico.g7monitor.data

import it.federico.g7monitor.protocol.SensorState

enum class Link(val label: String) {
    Off("Spento"),
    Scanning("Ricerca sensore…"),
    Connecting("Connessione…"),
    Authenticating("Autenticazione…"),
    Bonding("Abbinamento Bluetooth…"),
    Receiving("Ricezione dati…"),
    Waiting("In attesa della prossima lettura"),
    Error("Errore"),
}

data class SensorStatus(
    val link: Link = Link.Off,
    val sensorState: SensorState? = null,
    val lastContact: Long? = null,
    val message: String? = null,
    val deviceName: String? = null,
)
