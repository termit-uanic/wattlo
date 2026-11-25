package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {
    @Test fun topicIsRegionAndQueue() {
        assertEquals("poltavska-oblast_1.1", Push.topic(Region.POLTAVA, "1.1"))
    }

    /** FCM topics may only have these characters: the data service and the app must agree on all of them. */
    @Test fun everyTopicIsAllowedByFcm() {
        val ok = Regex("^[a-zA-Z0-9\\-_.~%]+$")
        for (r in Region.entries) for (q in r.queues) assertTrue(Push.topic(r, q), ok.matches(Push.topic(r, q)))
    }

    @Test fun pushNewerThanTheDataIsNews() = assertTrue(Refresh.pushIsNews(haveUpdatedAt = 100, pushedUpdatedAt = 200))

    @Test fun pushNotNewerThanTheDataHasNothingToFetch() {
        assertFalse(Refresh.pushIsNews(haveUpdatedAt = 200, pushedUpdatedAt = 200))
        assertFalse(Refresh.pushIsNews(haveUpdatedAt = 300, pushedUpdatedAt = 200))
    }

    @Test fun unknownTimesCountAsNews() {
        assertTrue(Refresh.pushIsNews(haveUpdatedAt = 0, pushedUpdatedAt = 200)) // data of another source, or an older phone
        assertTrue(Refresh.pushIsNews(haveUpdatedAt = 200, pushedUpdatedAt = 0)) // a push without the time
    }

    @Test fun updatedAtOfTheFileIsReadAndMissingIsZero() {
        assertEquals(1795427213000L, JsonSources.wattloUpdatedAt("""{"updatedAt":"2026-11-23T09:46:53Z"}"""))
        assertEquals(0L, JsonSources.wattloUpdatedAt("""{"checkedAt":"2026-11-23T09:46:53Z"}"""))
        assertEquals(0L, JsonSources.wattloUpdatedAt("not json"))
    }
}
