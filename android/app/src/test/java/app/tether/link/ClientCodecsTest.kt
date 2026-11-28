package app.tether.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call's audio moved to a client's new link before its sync (Galaxy A54 with an iPhone, 2026-11-28). */
class ClientCodecsTest {
    @Test fun aClientBackOnANewLinkKeepsItsCodecs() {
        val m = ClientCodecs()
        assertNull("a client not seen yet: G.711 until its sync", m.known("aa"))
        m.remember("aa", listOf("opus", "pcmu"))
        // Before 1.2.7 the new link had only ["pcmu"] here, so the running 16 kHz call was announced as 8 kHz G.711.
        assertEquals(listOf("opus", "pcmu"), m.known("aa"))
        assertNull("another client", m.known("bb"))
        m.remember(null, listOf("opus")); m.remember("bb", emptyList())
        assertNull(m.known("bb"))
    }

    @Test fun aSyncThatBringsOpusAnnouncesTheCallAgain() {
        assertTrue("G.711 sent at the helper's 16 kHz: switch to Opus", ClientCodecs.reannounce("pcmu", "opus", 16000, 16000))
        assertFalse("already the right codec", ClientCodecs.reannounce("opus", "opus", 16000, 16000))
        assertFalse("no call audio running", ClientCodecs.reannounce(null, "opus", 16000, 16000))
        assertFalse("a call started at 8 kHz would need a helper restart", ClientCodecs.reannounce("pcmu", "opus", 16000, 8000))
    }
}
