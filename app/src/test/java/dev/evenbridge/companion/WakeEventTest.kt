package dev.evenbridge.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WakeEventTest {
    private val now = 1_800_000_000_000L
    private fun event(id: String = Protocol.hash("wake")) = JSONObject().put("id", id).put("kind", "t3").put("issuedAt", now).put("expiresAt", now + 30_000)
    @Test fun strictGenericBoundedProtocol() {
        assertEquals("t3", WakeEvent.parse(event()).kind)
        listOf(event().put("kind", "reply"), event().put("id", "arbitrary intent"), event().put("issuedAt", now.toString()), event().put("expiresAt", now + 30_001), event().put("issuedAt", now + 0.5)).forEach {
            assertThrows(Exception::class.java) { WakeEvent.parse(it) }
        }
        assertThrows(Exception::class.java) { WakeEvent.batch(JSONArray().put(event()).put(event())) }
        val large = JSONArray(); repeat(9) { large.put(event(Protocol.hash(it.toString()))) }
        assertThrows(Exception::class.java) { WakeEvent.batch(large) }
    }
    @Test fun encryptedReceiptSurvivesRestartAndExpiresWithoutReplay() {
        val event = WakeEvent.parse(event())
        val journal = WakeJournal(JSONObject())
        assertEquals(1, journal.consume(listOf(event), now).size)
        val restarted = WakeJournal(JSONObject(journal.data.toString()))
        assertTrue(restarted.consume(listOf(event), now).isEmpty())
        assertTrue(restarted.consume(listOf(event), now + 35_001).isEmpty())
        assertEquals(0, restarted.data.length())
        assertTrue(WakeJournal(JSONObject()).consume(listOf(event), now - 5001).isEmpty())
    }
    @Test fun receiptCapacityFailsClosed() {
        val data = JSONObject(); repeat(256) { data.put(Protocol.hash("seen-$it"), now + 30_000) }
        assertTrue(WakeJournal(data).consume(listOf(WakeEvent.parse(event())), now).isEmpty())
    }
    @Test fun companionCannotCaptureItsOwnRelayEvenIfEnabled() {
        val own = "dev.evenbridge.companion"
        assertFalse(Protocol.captureAllowed(own, own, setOf(own)))
        assertFalse(Protocol.captureAllowed(own, own, setOf(own), false, "simulation"))
        assertFalse(Protocol.captureAllowed(own, own, setOf(own), true, "even_display_wake_v1"))
        assertTrue(Protocol.captureAllowed(own, own, setOf(own), true, "simulation"))
        assertFalse(Protocol.captureAllowed("app.test", own, emptySet()))
        assertTrue(Protocol.captureAllowed("app.test", own, setOf("app.test")))
    }
}
