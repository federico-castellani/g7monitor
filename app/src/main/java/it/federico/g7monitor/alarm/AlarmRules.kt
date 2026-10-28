package it.federico.g7monitor.alarm

import it.federico.g7monitor.data.GlucoseReading

/** Pure decision logic (no Android): which alarms are true right now. Tested on the JVM. */
object AlarmRules {
    /** A glucose reading older than this does not trigger glucose or rate alarms (signal loss takes over). */
    const val FRESH_MS = 11 * 60_000L

    /**
     * @param recent readings of the last ~30 minutes, oldest first
     * @param newest time of the newest reading ever stored (for signal loss), null if none
     * @param receiving the phone is supposed to receive data (collection / background download on)
     * @param warmingUp sensor warm-up: no readings expected yet
     */
    fun triggered(
        configs: Map<AlarmType, AlarmConfig>,
        recent: List<GlucoseReading>,
        newest: Long?,
        now: Long,
        receiving: Boolean,
        warmingUp: Boolean,
    ): Set<AlarmType> {
        val out = mutableSetOf<AlarmType>()
        fun on(t: AlarmType) = configs[t]?.enabled == true
        fun v(t: AlarmType) = configs.getValue(t).value

        val latest = recent.lastOrNull()?.takeIf { now - it.timestamp <= FRESH_MS }
        if (latest != null) {
            val g = latest.mgdl
            val urgentLow = on(AlarmType.UrgentLow) && g <= v(AlarmType.UrgentLow)
            val urgentHigh = on(AlarmType.UrgentHigh) && g >= v(AlarmType.UrgentHigh)
            if (urgentLow) out += AlarmType.UrgentLow
            if (urgentHigh) out += AlarmType.UrgentHigh
            // The milder alarm stays quiet while the urgent one is true (even if that one was snoozed).
            if (!urgentLow && on(AlarmType.Low) && g < v(AlarmType.Low)) out += AlarmType.Low
            if (!urgentHigh && on(AlarmType.High) && g > v(AlarmType.High)) out += AlarmType.High

            val rate = rate(recent)
            if (rate != null) {
                if (on(AlarmType.FallingFast) && rate <= -v(AlarmType.FallingFast) / 10.0) out += AlarmType.FallingFast
                if (on(AlarmType.RisingFast) && rate >= v(AlarmType.RisingFast) / 10.0) out += AlarmType.RisingFast
            }
        }

        if (on(AlarmType.SignalLoss) && receiving && !warmingUp && newest != null &&
            now - newest >= v(AlarmType.SignalLoss) * 60_000L
        ) out += AlarmType.SignalLoss
        return out
    }

    /** mg/dL per minute: the sensor's own trend, or computed from the previous reading (4–16 min earlier). */
    fun rate(recent: List<GlucoseReading>): Double? {
        val last = recent.lastOrNull() ?: return null
        last.trend?.takeIf { it.isFinite() }?.let { return it }
        val prev = recent.dropLast(1).lastOrNull { last.timestamp - it.timestamp in 4 * 60_000L..16 * 60_000L } ?: return null
        return (last.mgdl - prev.mgdl) / ((last.timestamp - prev.timestamp) / 60_000.0)
    }
}
