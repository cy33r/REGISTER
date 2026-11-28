package app.tether.client.calls

import app.tether.core.AudioFrame
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tone this phone plays when the Android phone reports a call ended because the far end was busy or the network
 * failed it (`call.state` `tone`, protocol §5): a port of TetherCore's CallEndTone (same cadences, same numbers).
 * 425 Hz like the ringback; busy 0.5 s on / 0.5 s off, congestion 0.2 s on / 0.2 s off (CEPT, as Android's
 * TONE_SUP_BUSY / TONE_SUP_CONGESTION). It plays for [SECONDS] through the call's audio, the call screen says "Busy" /
 * "Call failed", then the local call ends; ending it here, or a new call, stops it at once.
 */
enum class CallEndTone(val wire: String, val onMs: Int, val offMs: Int) {
    BUSY("busy", 500, 500),
    CONGESTION("congestion", 200, 200);

    /** The cadence's level (0…1) at sample [i] (5 ms ramps at each edge). */
    fun envelope(i: Int, rate: Int): Double {
        val on = rate * onMs / 1000; val period = on + rate * offMs / 1000
        val pos = i % period; val edge = maxOf(1, (rate * RAMP).toInt())
        return if (pos >= on) 0.0 else minOf(pos, on - pos, edge).toDouble() / edge
    }

    companion object {
        const val SECONDS = 3.0
        const val FREQUENCY = 425.0
        const val RAMP = 0.005

        /** "busy" and "congestion"; null for "ended" (not played), unknown values and no tone. */
        fun of(wire: String?): CallEndTone? = entries.firstOrNull { it.wire == wire }

        /** Whether an ended call plays its tone here: a call shown here, not an incoming call still ringing (missed). */
        fun play(tone: String?, shownHere: Boolean, ringingIncoming: Boolean): CallEndTone? =
            if (!shownHere || ringingIncoming) null else of(tone)
    }
}

/** Fills audio buffers with a [CallEndTone] from the start of its cadence (the playback thread). */
class CallEndToneGenerator(val tone: CallEndTone, private val rate: Int) {
    var position = 0; private set
    private var phase = 0.0

    fun fill(out: FloatArray, n: Int) {
        val step = 2 * PI * CallEndTone.FREQUENCY / rate
        for (i in 0 until n) {
            out[i] = (LEVEL * tone.envelope(position, rate) * sin(phase)).toFloat()
            phase += step; if (phase > 2 * PI) phase -= 2 * PI
            position++
        }
    }

    companion object { const val LEVEL = 0.12 }
}

/**
 * The sample rate a downlink frame really carries (TetherCore's DownlinkRate). Every frame is 20 ms and G.711 has one
 * byte per sample, so a µ-law frame's length says its rate: the Android phone app 1.2.6 sent µ-law at its helper's
 * 16 kHz, announced as 8 kHz, after moving a running call to a client that had just reconnected (played at 8 kHz:
 * half speed, half of it drained; drift 996668 ppm, drained 8826 ms of 434 frames on the iPhone, 2026-11-28).
 */
object DownlinkRate {
    fun of(codec: Int, announced: Int, frameBytes: Int): Int {
        if (codec != AudioFrame.PCMU) return announced
        val carried = frameBytes * 50
        return if (carried == 8000 || carried == 16000) carried else announced
    }
}

/** Counts the far end's speech in played frames for the call statistics (the helper's own threshold, 300 RMS). */
object SpeechLevel {
    const val RMS = 300.0
    fun isSpeech(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false
        var e = 0.0
        for (s in pcm) e += s.toDouble() * s
        return sqrt(e / pcm.size) >= RMS
    }
}

/** What the call screen says about the call's audio, from the Android phone's `status.helper`. */
enum class CallAudioNotice {
    /** The helper is stopped and cannot start: the Android phone is not on Wi-Fi. */
    NEEDS_WIFI;

    companion object {
        /**
         * [helperState] / [helperWhy] / [noCallAudio]: `status.helper.state` / `.why` / `.noCallAudio` (builds after 1.2.6
         * send `noCallAudio` while a call has no audio; any value counts). None for an ended call.
         */
        fun forCall(callState: String, helperState: String?, helperWhy: String?, noCallAudio: String? = null): CallAudioNotice? = when {
            callState == "ended" -> null
            !noCallAudio.isNullOrEmpty() -> NEEDS_WIFI
            helperState != null && helperState != "running" && helperWhy == "wifi" -> NEEDS_WIFI
            else -> null
        }
    }
}
