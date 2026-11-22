package app.wattlo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraTest {
    @Test fun idAndBack() {
        val id = Extra.id(Region.LVIV, "3.2")
        assertEquals("LVIV:3.2", id)
        assertEquals(Region.LVIV, Extra.region(id))
        assertEquals("3.2", Extra.queue(id))
    }

    @Test fun regionNamesWithUnderscore() {
        val id = Extra.id(Region.KYIV_OBLAST, "1.1")
        assertEquals(Region.KYIV_OBLAST, Extra.region(id))
        assertEquals("1.1", Extra.queue(id))
    }

    @Test fun sameQueueInTwoRegionsIsTwoIds() = assertTrue(Extra.id(Region.LVIV, "1.1") != Extra.id(Region.KYIV, "1.1"))

    @Test fun valid_aQueueOfTheRegion() {
        assertTrue(Extra.isValid("KYIV:60.1"))
        assertTrue(Extra.isValid("TERNOPIL:12.2"))
        assertTrue(Extra.isValid("CHERNIVTSI:7"))
    }

    @Test fun valid_notAQueueOfThatRegion() {
        assertFalse(Extra.isValid("LVIV:60.1")) // Kyiv has 60 sub-queues, Lviv 12 queues
        assertFalse(Extra.isValid("KYIV:1.2"))
    }

    @Test fun valid_notAnId() {
        assertFalse(Extra.isValid("3.2")) // an older version stored plain queue names: ignored
        assertFalse(Extra.isValid("MARS:1.1"))
        assertFalse(Extra.isValid(""))
        assertNull(Extra.region("3.2"))
    }

    @Test fun theLimit() = assertEquals(8, Extra.MAX)
}
