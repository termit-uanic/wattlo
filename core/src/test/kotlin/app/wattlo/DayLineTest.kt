package app.wattlo

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLineTest {
    private val day = LocalDate.of(2026, 11, 24)

    private fun flags(vararg ranges: IntRange) = BooleanArray(48).also { f -> ranges.forEach { r -> r.forEach { f[it] = true } } }

    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 24, h, m, 0, 0, Schedule.ZONE)

    // outages 10:30-13:00 and 16:30-20:00
    private val today = flags(21..25, 33..39)

    @Test fun phases_followTheStateAndStopAtTheEndOfTheData() {
        val p = DayLine.phases(mapOf(day to today), at(11, 0), 10)
        assertEquals(listOf(true, false, true, false), p.map { it.off })
        assertEquals(at(11, 0), p[0].from)
        assertEquals(at(13, 0), p[0].to)
        assertEquals(at(16, 30), p[1].to)
        // tomorrow is not known: the last phase ends at midnight and says the data ends there
        assertTrue(p.last().open)
        assertEquals(at(0, 0).plusDays(1), p.last().to)
        assertFalse(p.dropLast(1).any { it.open })
    }

    @Test fun phases_areLimitedAndEmptyWithoutData() {
        assertEquals(2, DayLine.phases(mapOf(day to today), at(11, 0), 2).size)
        assertTrue(DayLine.phases(emptyMap(), at(11, 0), 4).isEmpty())
    }

    @Test fun phases_goAcrossMidnightWhenTomorrowIsKnown() {
        val tomorrow = flags(0..3)
        val p = DayLine.phases(mapOf(day to flags(40..47), day.plusDays(1) to tomorrow), at(22, 0), 10)
        // 22:00 power off until 24:00, which goes on into tomorrow's 00:00-02:00, then power
        assertEquals(listOf(true, false), p.take(2).map { it.off })
        assertEquals(at(0, 0).plusDays(1).plusHours(2), p[0].to)
    }

    @Test fun windowRoles_startThreeHoursBackAndMarkMissingDays() {
        val r = DayLine.windowRoles(mapOf(day to today), emptyMap(), true, at(11, 0))
        assertEquals(DayLine.WINDOW_SLOTS, r.size)
        // slot 6 is the current half hour (11:00): inside the 10:30-13:00 outage
        assertEquals(DialModel.Role.RED, r[DayLine.WINDOW_BEFORE])
        assertEquals(DialModel.Role.GREEN, r[0]) // 08:00
        // the window ends at 20:00, so it stays inside today
        assertFalse(r.contains(DialModel.Role.NO_DATA))
        val late = DayLine.windowRoles(mapOf(day to today), emptyMap(), true, at(21, 0))
        assertEquals(DialModel.Role.NO_DATA, late.last()) // 05:30 of the day that is not known
    }

    @Test fun dayRoles_haveNoDataForAnUnknownDay() {
        assertTrue(DayLine.dayRoles(null, null, true).all { it == DialModel.Role.NO_DATA })
        assertEquals(48, DayLine.dayRoles(today, null, true).size)
    }

    @Test fun appView_unknownOrNotOfferedFallsBackToTheRing() {
        assertEquals(AppView.LINE, AppView.of("LINE", AppView.WATCH))
        assertEquals(AppView.RING, AppView.of("TIMELINE", AppView.WATCH)) // the timeline is for the phone
        assertEquals(AppView.RING, AppView.of("STAGES", AppView.PHONE))
        assertEquals(AppView.RING, AppView.of("nope", AppView.PHONE))
        assertEquals(AppView.RING, AppView.of(null, AppView.PHONE))
    }
}
