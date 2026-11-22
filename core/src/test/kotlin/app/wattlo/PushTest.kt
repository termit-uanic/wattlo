package app.wattlo

import org.junit.Assert.assertEquals
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
}
