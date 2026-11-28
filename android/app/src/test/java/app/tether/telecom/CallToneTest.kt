package app.tether.telecom

import android.media.ToneGenerator
import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `tone` of an ended call: a busy or failed call used to vanish from the iPhone without a sound (2026-11-28). */
class CallToneTest {
    @Test fun busyFailedAndEndedTonesMapToTheWire() {
        assertEquals("busy", CallTone.of(17))                 // TONE_SUP_BUSY, as Telephony gives for DisconnectCause.BUSY
        assertEquals("busy", CallTone.of(ToneGenerator.TONE_CDMA_NETWORK_BUSY))
        assertEquals("congestion", CallTone.of(18))           // TONE_SUP_CONGESTION
        assertEquals("congestion", CallTone.of(21))           // TONE_SUP_ERROR (unobtainable / invalid number)
        assertEquals("congestion", CallTone.of(ToneGenerator.TONE_CDMA_REORDER))
        assertEquals("ended", CallTone.of(27))                // TONE_PROP_PROMPT: the owner's ordinary call ends of 2026-11-28
        assertEquals("ended", CallTone.of(ToneGenerator.TONE_CDMA_CALLDROP_LITE))
        assertNull("no tone", CallTone.of(CallTone.NONE))
        assertNull("a DTMF digit is no end tone", CallTone.of(ToneGenerator.TONE_DTMF_5))
    }

    @Test fun theTestLineEndsLikeTheNetworkWould() {
        assertEquals(DisconnectCause.BUSY to ToneGenerator.TONE_SUP_BUSY, CallTone.fakeEnd("busy"))
        for (w in listOf("busy", "congestion", "ended")) assertEquals(w, CallTone.of(CallTone.fakeEnd(w)!!.second))
        assertNull(CallTone.fakeEnd(null))
        assertNull(CallTone.fakeEnd("ring"))
    }
}
