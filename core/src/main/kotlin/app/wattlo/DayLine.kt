package app.wattlo

import java.time.LocalDate
import java.time.ZonedDateTime

/** Pure logic of the line views: the colour roles along a bar, and the coming phases of the state. */
object DayLine {
    /** The window of the 12-hour line: slots shown, and how many of them are before the current one. */
    const val WINDOW_SLOTS = 24
    const val WINDOW_BEFORE = 6

    /** The 48 roles of one day (all [DialModel.Role.NO_DATA] when the day is not known). */
    fun dayRoles(flags: BooleanArray?, maybe: BooleanArray?, yellowAsOutage: Boolean): List<DialModel.Role> =
        List(Schedule.SLOTS) { DialModel.roleOf(flags, maybe, it, yellowAsOutage) }

    /**
     * [count] half-hour slots from [before] slots before the current one, across midnight; a slot of a day that is
     * not known is [DialModel.Role.NO_DATA].
     */
    fun windowRoles(
        days: Map<LocalDate, BooleanArray>, maybe: Map<LocalDate, BooleanArray>, yellowAsOutage: Boolean,
        now: ZonedDateTime, before: Int = WINDOW_BEFORE, count: Int = WINDOW_SLOTS,
    ): List<DialModel.Role> {
        val first = slotStart(now).minusMinutes(30L * before)
        return List(count) { i ->
            val t = first.plusMinutes(30L * i)
            val d = t.toLocalDate()
            DialModel.roleOf(days[d], maybe[d], Schedule.slotOf(t), yellowAsOutage)
        }
    }

    /** One stretch of the same state; [open] means the data ends there, so the state may well go on. */
    data class Phase(val off: Boolean, val from: ZonedDateTime, val to: ZonedDateTime, val open: Boolean)

    /** The current phase and those after it, at most [max]; empty without data for now. */
    fun phases(days: Map<LocalDate, BooleanArray>, now: ZonedDateTime, max: Int): List<Phase> {
        var cur = Schedule.at(days, now) ?: return emptyList()
        val out = ArrayList<Phase>()
        val first = slotStart(now)
        var from = now
        for (i in 1..LIMIT) {
            val t = first.plusMinutes(30L * i)
            val s = Schedule.at(days, t)
            if (s == null) {
                out += Phase(cur, from, t, open = true)
                return out
            }
            if (s != cur) {
                out += Phase(cur, from, t, open = false)
                if (out.size >= max) return out
                from = t
                cur = s
            }
        }
        out += Phase(cur, from, first.plusMinutes(30L * LIMIT), open = true)
        return out
    }

    private const val LIMIT = 48 * 3

    private fun slotStart(t: ZonedDateTime): ZonedDateTime =
        t.withMinute(if (t.minute >= 30) 30 else 0).withSecond(0).withNano(0)
}
