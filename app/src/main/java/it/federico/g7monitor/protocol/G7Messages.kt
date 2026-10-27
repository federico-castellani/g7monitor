package it.federico.g7monitor.protocol

import java.util.UUID

/** GATT layout shared by G6/G7 (xDrip g5model.BluetoothServices). */
object G7Gatt {
    val ADVERTISEMENT: UUID = UUID.fromString("0000FEBC-0000-1000-8000-00805F9B34FB")
    val CGM_SERVICE: UUID = UUID.fromString("F8083532-849E-531C-C594-30F1F86A4EA5")
    val CONTROL: UUID = UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5")
    val AUTHENTICATION: UUID = UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5")
    val BACKFILL: UUID = UUID.fromString("F8083536-849E-531C-C594-30F1F86A4EA5")
    val EXTRA_DATA: UUID = UUID.fromString("F8083538-849E-531C-C594-30F1F86A4EA5")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** G7 advertises as DXCMxx; Dexcom ONE+ / Stelo use DX01 / DX02. */
    fun isG7Name(name: String?): Boolean =
        name != null && (name.startsWith("DXCM") || name.startsWith("DX01") || name.startsWith("DX02"))

    const val CHUNK = 20
}

/** Commands written to the Control characteristic. */
object G7Commands {
    /** EGlucoseTxMessage (short form used for G7). */
    val GLUCOSE = byteArrayOf(0x4E)
    val DISCONNECT = byteArrayOf(0x09)

    /** BackFillTxMessage2: 0x59, start and end in sensor seconds (LE). */
    fun backfill(startDexTime: Int, endDexTime: Int): ByteArray =
        le(9).put(0x59).putInt(startDexTime).putInt(endDexTime).array()
}

enum class SensorState(val code: Int, val label: String) {
    Unknown(0x00, "Sconosciuto"),
    Stopped(0x01, "Fermo"),
    WarmingUp(0x02, "Riscaldamento"),
    ExcessNoise(0x03, "Rumore eccessivo"),
    NeedsFirstCalibration(0x04, "Calibrazione iniziale richiesta"),
    NeedsSecondCalibration(0x05, "Seconda calibrazione richiesta"),
    Ok(0x06, "OK"),
    NeedsCalibration(0x07, "Calibrazione richiesta"),
    CalibrationConfused1(0x08, "Calibrazione incoerente"),
    CalibrationConfused2(0x09, "Calibrazione incoerente"),
    NeedsDifferentCalibration(0x0a, "Altra calibrazione richiesta"),
    SensorFailed(0x0b, "Sensore guasto"),
    SensorFailed2(0x0c, "Sensore guasto"),
    UnusualCalibration(0x0d, "Calibrazione insolita"),
    InsufficientCalibration(0x0e, "Calibrazione insufficiente"),
    Ended(0x0f, "Terminato"),
    SensorFailed3(0x10, "Sensore guasto"),
    TransmitterProblem(0x11, "Problema trasmettitore"),
    Errors(0x12, "Errori sensore"),
    SensorFailed4(0x13, "Sensore guasto"),
    SensorFailed5(0x14, "Sensore guasto"),
    SensorFailed6(0x15, "Sensore guasto"),
    SensorFailedStart(0x16, "Avvio sensore fallito"),
    SensorFailedStart2(0x17, "Avvio sensore fallito"),
    SensorExpired(0x18, "Sensore scaduto"),
    SensorFailed7(0x19, "Sensore guasto"),
    SensorStopped2(0x1A, "Sensore fermo"),
    SensorFailed8(0x1B, "Sensore guasto"),
    SensorFailed9(0x1C, "Sensore guasto"),
    SensorFailed10(0x1D, "Sensore guasto"),
    SensorFailed11(0x1E, "Sensore guasto");

    val usable get() = this == Ok || this == NeedsCalibration
    val warmingUp get() = this == WarmingUp

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun parse(code: Int): SensorState = byCode[code and 0xFF] ?: Unknown
    }
}

/**
 * G7 glucose packet, opcode 0x4E (xDrip EGlucoseRxMessage2):
 * [0]=0x4E [1]=status [2..5]=sensor clock (s since session start) [6..7]=sequence
 * [8..9]=? [10..11]=age of reading (s) [12..13]=glucose (12 bits) [14]=algorithm state
 * [15]=trend (signed, mg/dL/min * 10, 127 = none) [16..17]=predicted [18]=info
 */
data class G7Glucose(
    val status: Int,
    val sensorClockSeconds: Long,
    val sequence: Int,
    val ageSeconds: Int,
    val glucoseMgdl: Int,
    val displayOnly: Boolean,
    val state: SensorState,
    val trendMgdlPerMin: Double?,
    val predictedMgdl: Int?,
) {
    /** Wall clock of this reading, given the time the packet was received. */
    fun readingTime(receivedAt: Long) = receivedAt - ageSeconds * 1000L

    /** Wall clock of the sensor session start (sensor clock 0). */
    fun sessionStart(receivedAt: Long) = receivedAt - sensorClockSeconds * 1000L

    /** xDrip reports "warming up" for a just started sensor that still says stopped. */
    val adjustedState: SensorState
        get() = if (state == SensorState.Stopped && sensorClockSeconds < 30 * 60) SensorState.WarmingUp else state

    companion object {
        const val OPCODE = 0x4E

        fun parse(packet: ByteArray): G7Glucose? {
            if (packet.size < 19 || packet[0].toInt() != OPCODE) return null
            val b = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.get()
            val status = b.u8()
            val clock = b.u32()
            val seq = b.u16()
            b.u16()
            val age = b.u16()
            val raw = b.u16()
            val state = SensorState.parse(b.u8())
            val trend = b.get().toInt()
            val predicted = b.u16() and 0x03FF
            return G7Glucose(
                status = status,
                sensorClockSeconds = clock,
                sequence = seq,
                ageSeconds = age,
                glucoseMgdl = raw and 0x0FFF,
                displayOnly = (raw and 0xF000) != 0,
                state = state,
                trendMgdlPerMin = if (trend == 127) null else trend / 10.0,
                predictedMgdl = if (predicted == 0x3FF) null else predicted,
            )
        }
    }
}

/** One historical reading from the backfill characteristic (9 bytes, G7 format). */
data class G7BackfillRecord(val sensorSeconds: Long, val glucoseMgdl: Int, val state: SensorState, val trendMgdlPerMin: Double?) {
    companion object {
        const val SIZE = 9

        /** Decodes the concatenated backfill notifications (xDrip BackFillStream, stream type 1). */
        fun parseStream(stream: ByteArray): List<G7BackfillRecord> {
            val out = mutableListOf<G7BackfillRecord>()
            val b = java.nio.ByteBuffer.wrap(stream).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= SIZE) {
                val t = b.u32()
                val g = b.u16() and 0x0FFF
                val st = SensorState.parse(b.u8())
                b.get() // extra
                val tr = b.get().toInt()
                if (t != 0L) out += G7BackfillRecord(t, g, st, if (tr == 127) null else tr / 10.0)
            }
            return out
        }
    }
}
