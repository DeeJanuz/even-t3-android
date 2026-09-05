package dev.evenbridge.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun httpsAndEmulatorTransport() {
        assertEquals("https://mac.example:8443", Protocol.endpoint("https://mac.example:8443/", false))
        assertEquals("http://10.0.2.2:18789", Protocol.endpoint("http://10.0.2.2:18789", true))
        listOf("http://mac.example", "http://192.168.1.5", "https://user:secret@mac.example", "https://mac.example/path", "https://mac.example?token=secret", "https://mac.example#x", "file:///tmp/x").forEach { raw ->
            assertThrows(Exception::class.java) { Protocol.endpoint(raw, true) }
        }
        assertThrows(Exception::class.java) { Protocol.endpoint("http://10.0.2.2:18789", false) }
    }
    private val issuedAt = 1_800_000_000_000L
    private fun valid() = JSONObject().put("id", "$issuedAt-82dd9b6e-c470-42f9-81c9-a40ea16411da").put("issuedAt", issuedAt).put("expiresAt", issuedAt + 30_000).put("operation", "reply")
        .put("key", Protocol.hash("key")).put("revision", Protocol.hash("revision")).put("text", "Hello")
    @Test fun commandAcceptsOnlyBoundedKnownActions() {
        assertEquals("Hello", Protocol.command(valid()).text)
        assertThrows(Exception::class.java) { Protocol.command(valid().put("operation", "launch")) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("key", "arbitrary intent")) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("text", " ")) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("text", "a".repeat(4001))) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("text", "a\u0000b")) }
        assertNull(Protocol.command(valid().put("operation", "dismiss")).text)
    }
    @Test fun displayContentBoundedAndControlCharactersRemoved() {
        assertEquals("abc\ndef", Protocol.clean("abc\u0000\ndef", 100))
        assertEquals("abc", Protocol.clean("abcdef", 3))
        assertEquals("", Protocol.clean(null, 3))
        assertEquals("app.example", Protocol.appLabel("app.example", "\u0000 \t"))
        assertEquals("app.example", Protocol.appLabel("app.example", null))
        assertEquals("Messages", Protocol.appLabel("app.example", "Messages"))
    }
    @Test fun expiredCommandCannotBeRenewedOrReplayedAfterPruning() {
        val command = Protocol.command(valid())
        assertTrue(command.timely(issuedAt))
        assertFalse(command.timely(issuedAt - 5001))
        assertFalse(command.timely(issuedAt + 30_000))
        assertThrows(Exception::class.java) { Protocol.command(valid().put("expiresAt", issuedAt + 60_000)) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("issuedAt", issuedAt + 1000)) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("issuedAt", issuedAt.toString())) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("expiresAt", issuedAt + 30_000.5)) }
        assertThrows(Exception::class.java) { Protocol.command(valid().put("id", "82dd9b6e-c470-42f9-81c9-a40ea16411da")) }
        val journal = CommandJournal(JSONObject())
        journal.record(command, "unknown")
        val restarted = CommandJournal(JSONObject(journal.data.toString()))
        assertEquals("unknown", restarted.status(command.id))
        restarted.prune(issuedAt + 34_999)
        assertEquals("unknown", restarted.status(command.id))
        restarted.prune(issuedAt + 35_001)
        assertEquals("", restarted.status(command.id))
        assertFalse(command.timely(issuedAt + 35_001))
    }
    @Test fun completedCommandsRetainAcknowledgementWithoutReinvokingAction() {
        val command = Protocol.command(valid())
        val journal = CommandJournal(JSONObject())
        journal.record(command, "unknown")
        journal.record(command, "handed_off")
        val restarted = CommandJournal(JSONObject(journal.data.toString()))
        assertEquals("handed_off", restarted.status(command.id))
    }
}
