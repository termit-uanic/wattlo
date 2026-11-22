package app.wattlo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpSharingTest {
    private val calls = mutableListOf<String>()
    private val real = Http.transport

    @Before fun fake() { Http.transport = { url, form -> calls += "$url|$form"; "body of $url" } }
    @After fun restore() { Http.transport = real }

    @Test fun outsideSharingEveryRequestGoesOut() {
        Http.request("https://a/x"); Http.request("https://a/x")
        assertEquals(2, calls.size)
    }

    @Test fun insideSharingTheSameRequestGoesOutOnce() {
        val bodies = Http.sharing { listOf(Http.request("https://a/x"), Http.request("https://a/x"), Http.request("https://a/x")) }
        assertEquals(1, calls.size)
        assertEquals(setOf("body of https://a/x"), bodies.toSet())
    }

    @Test fun differentAddressesAndFormsAreDifferentRequests() {
        Http.sharing {
            Http.request("https://a/x"); Http.request("https://a/y")
            Http.request("https://a/x", "d=1"); Http.request("https://a/x", "d=2"); Http.request("https://a/x", "d=1")
        }
        assertEquals(4, calls.size)
    }

    @Test fun theNextRefreshAsksAgain() {
        Http.sharing { Http.request("https://a/x") }
        Http.sharing { Http.request("https://a/x") }
        assertEquals(2, calls.size)
    }
}
