package it.federico.g7monitor.meter

import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bluetooth SIG "Glucose Profile" (GLP/GLS 1.0), used by most Bluetooth blood glucose meters
 * (Contour Next One, Accu-Chek Guide/Instant, OneTouch Verio Flex, CareSens, …), as xDrip+ does.
 * Pure Kotlin: parsing and command building, tested on the JVM.
 */
object GlucoseProfile {
    private fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

    val GLUCOSE_SERVICE: UUID = uuid16(0x1808)
    val MEASUREMENT: UUID = uuid16(0x2A18)
    val MEASUREMENT_CONTEXT: UUID = uuid16(0x2A34)
    /** Record Access Control Point: asks the meter for stored records. */
    val RACP: UUID = uuid16(0x2A52)
    val CURRENT_TIME_SERVICE: UUID = uuid16(0x1805)
    val CURRENT_TIME: UUID = uuid16(0x2A2B)

    const val MGDL_PER_MMOL = 18.0182

    // ------------------------------------------------------------------ RACP

    private const val OP_REPORT_RECORDS = 0x01
    private const val OP_RESPONSE = 0x06
    private const val OPERATOR_ALL = 0x01
    private const val OPERATOR_GREATER_OR_EQUAL = 0x03
    private const val FILTER_SEQUENCE = 0x01

    /** Every record in the meter's memory. */
    fun requestAll() = byteArrayOf(OP_REPORT_RECORDS.toByte(), OPERATOR_ALL.toByte())

    /** Records with sequence number ≥ [sequence] (16 bit, wraps around). */
    fun requestFrom(sequence: Int) = byteArrayOf(
        OP_REPORT_RECORDS.toByte(), OPERATOR_GREATER_OR_EQUAL.toByte(), FILTER_SEQUENCE.toByte(),
        (sequence and 0xFF).toByte(), (sequence shr 8 and 0xFF).toByte(),
    )

    enum class RacpResult { Success, NoRecords, Unsupported, Error }

    /** @return null when [value] is not a RACP response to "report stored records". */
    fun parseRacpResponse(value: ByteArray): RacpResult? {
        if (value.size < 4 || value[0].toInt() and 0xFF != OP_RESPONSE || value[2].toInt() and 0xFF != OP_REPORT_RECORDS) return null
        return when (value[3].toInt() and 0xFF) {
            0x01 -> RacpResult.Success
            0x06 -> RacpResult.NoRecords
            0x02, 0x03, 0x04, 0x05, 0x09 -> RacpResult.Unsupported
            else -> RacpResult.Error
        }
    }

    // ------------------------------------------------------------------ measurement

    /**
     * One stored result.
     * @param time meter clock (base time + time offset), as Unix ms in the phone's time zone
     * @param controlSolution test with control solution: never a real glucose value
     */
    data class Measurement(val sequence: Int, val time: Long, val mgdl: Int?, val controlSolution: Boolean)

    /** Glucose Measurement characteristic (0x2A18). */
    fun parseMeasurement(v: ByteArray, zone: TimeZone = TimeZone.getDefault()): Measurement? {
        if (v.size < 10) return null
        val flags = u8(v, 0)
        val timeOffsetPresent = flags and 0x01 != 0
        val concentrationPresent = flags and 0x02 != 0
        val molPerLitre = flags and 0x04 != 0
        var i = 1
        val sequence = u16(v, i); i += 2
        val year = u16(v, i)
        val cal = Calendar.getInstance(zone).apply {
            clear()
            set(year, u8(v, i + 2) - 1, u8(v, i + 3), u8(v, i + 4), u8(v, i + 5), u8(v, i + 6))
        }
        i += 7
        if (timeOffsetPresent) {
            if (v.size < i + 2) return null
            cal.add(Calendar.MINUTE, s16(v, i)); i += 2
        }
        if (year == 0) return null // meter clock never set: no usable time
        var mgdl: Int? = null
        var control = false
        if (concentrationPresent) {
            if (v.size < i + 3) return null
            val value = sfloat(u16(v, i))
            val type = u8(v, i + 2) and 0x0F
            control = type == 0x0A
            mgdl = value?.let {
                // kg/L → mg/dL is ×100 000; mol/L → mmol/L is ×1000
                if (molPerLitre) (it * 1000 * MGDL_PER_MMOL).roundToInt() else (it * 100_000).roundToInt()
            }
        }
        return Measurement(sequence, cal.timeInMillis, mgdl, control)
    }

    /** IEEE-11073 16-bit SFLOAT: 4-bit signed exponent, 12-bit signed mantissa; null for special values. */
    fun sfloat(raw: Int): Double? {
        val mantissaRaw = raw and 0x0FFF
        if (mantissaRaw in 0x07FE..0x0802) return null // NaN, NRes, ±Infinity, reserved
        val mantissa = if (mantissaRaw >= 0x0800) mantissaRaw - 0x1000 else mantissaRaw
        val expRaw = raw shr 12 and 0x0F
        val exponent = if (expRaw >= 8) expRaw - 16 else expRaw
        return mantissa * 10.0.pow(exponent)
    }

    // ------------------------------------------------------------------ current time

    /** Current Time (0x2A2B) → Unix ms, or null when unset. */
    fun parseCurrentTime(v: ByteArray, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (v.size < 7 || u16(v, 0) == 0) return null
        return Calendar.getInstance(zone).apply {
            clear()
            set(u16(v, 0), u8(v, 2) - 1, u8(v, 3), u8(v, 4), u8(v, 5), u8(v, 6))
        }.timeInMillis
    }

    private fun u8(v: ByteArray, i: Int) = v[i].toInt() and 0xFF
    private fun u16(v: ByteArray, i: Int) = u8(v, i) or (u8(v, i + 1) shl 8)
    private fun s16(v: ByteArray, i: Int) = u16(v, i).let { if (it >= 0x8000) it - 0x10000 else it }
}
