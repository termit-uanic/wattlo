package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSyncTest {
    private val min = 60_000L

    // ---------------------------------------------------------------- the phone

    @Test fun push_aChangeGoesAtOnce() = assertTrue(ScheduleSync.shouldPush(sameAsLast = false, lastPushAt = 1_000, now = 2_000))

    @Test fun push_nothingNewIsKeptQuiet() =
        assertFalse(ScheduleSync.shouldPush(sameAsLast = true, lastPushAt = 0, now = 29 * min))

    @Test fun push_theHeartbeatTellsTheWatchTheDataIsCurrent() =
        assertTrue(ScheduleSync.shouldPush(sameAsLast = true, lastPushAt = 0, now = 30 * min))

    @Test fun heartbeatIsShorterThanWhatTheWatchTrusts() =
        assertTrue("one missed message must not make the watch go to the internet",
            ScheduleSync.HEARTBEAT_MS * 3 / 2 <= ScheduleSync.PHONE_FRESH_MS)

    // ---------------------------------------------------------------- the watch

    private fun accept(region: String? = "KYIV", queue: String? = "3.1", mine: Long = 100, sent: Long = 200, ok: Boolean = true) =
        ScheduleSync.shouldAccept("KYIV", "3.1", region, queue, mine, sent, ok)

    @Test fun accept_itsOwnRegionAndQueue() = assertTrue(accept())
    @Test fun accept_notAnotherRegion() = assertFalse(accept(region = "LVIV"))
    @Test fun accept_notAnotherQueue() = assertFalse(accept(queue = "3.2"))
    @Test fun accept_notAnItemWithoutThem() = assertFalse(accept(region = null, queue = null))
    @Test fun accept_notAnOlderOneThanItHas() = assertFalse(accept(mine = 300, sent = 200))
    @Test fun accept_theSameMomentIsFine() = assertTrue(accept(mine = 200, sent = 200))
    @Test fun accept_notEmptyOrBrokenDays() = assertFalse(accept(ok = false))

    @Test fun parseDaysOfAnEmptyOrBrokenStringIsEmpty() {
        assertEquals(0, Store.parseDays("").size)
        assertEquals(0, Store.parseDays("2026-11-22=012").size)
        assertEquals(1, Store.parseDays("2026-11-22=" + "0".repeat(48)).size)
    }
}
