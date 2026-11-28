package app.tether.client

import app.tether.client.calls.CallAudioNotice
import app.tether.client.calls.CallEndTone
import app.tether.client.calls.CallEndToneGenerator
import app.tether.client.calls.CallSink
import app.tether.client.calls.CallTracker
import app.tether.client.calls.EndReason
import app.tether.client.calls.SpeechLevel
import app.tether.client.model.Call
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** The busy / failure tone of an ended call (TetherCore's CallTonesTests, ported) and how the call tracker plays it. */
class CallTonesTest {
    @Test fun wireValues() {
        assertEquals(CallEndTone.BUSY, CallEndTone.of("busy"))
        assertEquals(CallEndTone.CONGESTION, CallEndTone.of("congestion"))
        assertNull("the call-ended beep is not played", CallEndTone.of("ended"))
        assertNull(CallEndTone.of(null))
        assertNull(CallEndTone.of("reorder"))
        assertEquals("busy", Call.of(JSONObject("""{"cid":"c","num":"+10000000001","state":"ended","dir":"out","tone":"busy"}""")).tone)
        assertNull(Call.of(JSONObject("""{"cid":"c","num":"+10000000001","state":"ended","dir":"out"}""")).tone)
    }

    @Test fun whichEndedCallsPlay() {
        assertEquals(CallEndTone.BUSY, CallEndTone.play("busy", shownHere = true, ringingIncoming = false))
        assertEquals(CallEndTone.CONGESTION, CallEndTone.play("congestion", shownHere = true, ringingIncoming = false))
        assertNull(CallEndTone.play("busy", shownHere = false, ringingIncoming = false))
        assertNull(CallEndTone.play("busy", shownHere = true, ringingIncoming = true))
        assertNull(CallEndTone.play("ended", shownHere = true, ringingIncoming = false))
    }

    @Test fun cadence() {
        val rate = 16000
        fun onShare(t: CallEndTone) = (0 until rate * 3).count { t.envelope(it, rate) > 0.99 } / (rate * 3.0)
        assertEquals(1.0, CallEndTone.BUSY.envelope(rate / 4, rate), 0.0)
        assertEquals(0.0, CallEndTone.BUSY.envelope(rate * 3 / 4, rate), 0.0)
        assertEquals(1.0, CallEndTone.BUSY.envelope(rate + rate / 4, rate), 0.0)
        assertEquals(1.0, CallEndTone.CONGESTION.envelope(rate / 10, rate), 0.0)
        assertEquals(0.0, CallEndTone.CONGESTION.envelope(rate * 3 / 10, rate), 0.0)
        assertEquals(0.5, onShare(CallEndTone.BUSY), 0.02)
        assertEquals(0.5, onShare(CallEndTone.CONGESTION), 0.03)
        assertEquals(0.0, CallEndTone.BUSY.envelope(0, rate), 0.0)
    }

    @Test fun generatorLevelAndFrequency() {
        val g = CallEndToneGenerator(CallEndTone.BUSY, 16000)
        val buf = FloatArray(8000)
        g.fill(buf, 8000)
        assertEquals(8000, g.position)
        assertTrue(buf.maxOf { abs(it) } <= CallEndToneGenerator.LEVEL + 1e-6)
        val part = buf.copyOfRange(800, 7200)
        val crossings = (1 until part.size).count { (part[it - 1] < 0) != (part[it] < 0) }
        assertEquals(2 * 425 * 0.4, crossings.toDouble(), 4.0)
        val off = FloatArray(8000) { 1f }
        g.fill(off, 8000)
        assertEquals(0f, off.copyOfRange(200, 7900).maxOf { abs(it) }, 0f)
    }

    @Test fun speechCount() {
        assertFalse(SpeechLevel.isSpeech(ShortArray(320)))
        assertFalse(SpeechLevel.isSpeech(ShortArray(320) { (200 * sin(it * 0.2)).toInt().toShort() }))
        assertTrue(SpeechLevel.isSpeech(ShortArray(320) { (3000 * sin(it * 0.2)).toInt().toShort() }))
        assertFalse(SpeechLevel.isSpeech(ShortArray(0)))
    }

    @Test fun noAudioNotice() {
        assertEquals(CallAudioNotice.NEEDS_WIFI, CallAudioNotice.forCall("active", "stopped", "wifi"))
        assertEquals(CallAudioNotice.NEEDS_WIFI, CallAudioNotice.forCall("ringing", "stopped", "wifi"))
        assertNull(CallAudioNotice.forCall("ended", "stopped", "wifi"))
        assertNull(CallAudioNotice.forCall("active", "running", null))
        assertNull(CallAudioNotice.forCall("active", "stopped", null))
        assertNull(CallAudioNotice.forCall("active", null, null))
        // Builds after 1.2.6: status.helper.noCallAudio (the fixture variant "nocallaudio"); any value counts.
        val h = JSONObject("""{"state":"stopped","up":false,"down":false,"why":"wifi","noCallAudio":"wifi"}""")
        assertEquals(CallAudioNotice.NEEDS_WIFI, CallAudioNotice.forCall("active", h.optString("state"), h.optString("why"), h.optString("noCallAudio")))
        assertEquals(CallAudioNotice.NEEDS_WIFI, CallAudioNotice.forCall("active", "starting", null, "other"))
        assertNull(CallAudioNotice.forCall("active", "running", "", ""))
    }

    // ---------------------------------------------------------------- the call tracker

    private val link = ClientStateTest.FakeLink()
    private val sink = ToneSink()
    private var clock = 1_000L
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val t = CallTracker(link, sink, now = { clock }, schedule = { ms, b -> timers += ms to b })

    class ToneSink : CallSink by RecordingSink() {
        val events = ArrayList<String>()
        var tone: CallEndTone? = null
        override fun reportIncoming(local: String, c: Call) { events += "incoming ${c.cid}" }
        override fun reportStarted(local: String, c: Call) { events += "started ${c.cid}" }
        override fun ended(local: String, reason: EndReason) { events += "ended $reason" }
        override fun endTone(tone: CallEndTone?) { this.tone = tone; events += "tone ${tone?.wire}" }
        override fun idle() { events += "idle" }
    }

    private fun call(cid: String, state: String, dir: String = "out", tone: String? = null) =
        Call(cid, "+10000000002", null, state, dir, null, null, if (tone == "busy") "BUSY" else null, true, false, null, tone)

    private fun fire() { val due = timers.toList(); timers.clear(); due.forEach { it.second() } }

    @Test fun busyPlaysThreeSecondsThenEnds() {
        t.performStart("L1", "+10000000002", null)
        t.handle(call("o", "dialing"))
        t.handle(call("o", "ended", tone = "busy"))
        assertEquals(listOf("tone busy"), sink.events)
        assertEquals(CallEndTone.BUSY, sink.tone)
        assertTrue("the local call stays while the tone plays", t.hasCalls)
        val shown = t.calls.single()
        assertEquals("ended", shown.state); assertEquals(CallEndTone.BUSY, shown.endTone)
        assertEquals(3000L, timers.single().first)
        fire()
        assertEquals(listOf("tone busy", "tone null", "ended BUSY", "idle"), sink.events)
        assertFalse(t.hasCalls)
    }

    @Test fun congestionEndsAsFailed() {
        t.handle(call("o", "active"))
        t.handle(call("o", "ended", tone = "congestion"))
        fire()
        assertEquals(listOf("started o", "tone congestion", "tone null", "ended FAILED", "idle"), sink.events)
    }

    @Test fun endingHereStopsTheToneAtOnce() {
        t.performStart("L1", "+10000000002", null)
        t.handle(call("o", "dialing"))
        t.handle(call("o", "ended", tone = "busy"))
        t.performEnd("L1")
        assertEquals(listOf("tone busy", "tone null", "idle"), sink.events)
        assertTrue("the Android phone's call is over: nothing is sent for it", link.sent.none { it.t == app.tether.core.T.CALL_END })
        fire()   // the timer of the stopped tone does nothing
        assertEquals(listOf("tone busy", "tone null", "idle"), sink.events)
    }

    @Test fun aNewCallStopsTheTone() {
        t.handle(call("o", "active"))
        t.handle(call("o", "ended", tone = "busy"))
        t.handle(call("i", "ringing", dir = "in"))
        assertEquals(listOf("started o", "tone busy", "tone null", "ended BUSY", "idle", "incoming i"), sink.events)
        assertNull(sink.tone)
    }

    @Test fun noToneEndsAtOnce() {
        t.handle(call("o", "active"))
        t.handle(call("o", "ended", tone = "ended"))
        assertEquals(listOf("started o", "ended REMOTE", "idle"), sink.events)
        assertTrue(timers.isEmpty())
    }

    @Test fun aMissedCallPlaysNoTone() {
        t.handle(call("i", "ringing", dir = "in"))
        t.handle(call("i", "ended", dir = "in", tone = "busy"))
        assertEquals(listOf("incoming i", "ended UNANSWERED", "idle"), sink.events)
    }

    @Test fun linkLostStopsTheTone() {
        t.handle(call("o", "active"))
        t.handle(call("o", "ended", tone = "busy"))
        t.linkLost()
        assertNull(sink.tone)
        assertTrue(sink.events.containsAll(listOf("tone null", "ended BUSY")))
        assertFalse(t.hasCalls)
    }
}
