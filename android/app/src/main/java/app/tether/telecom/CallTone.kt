package app.tether.telecom

import android.media.ToneGenerator
import android.telecom.DisconnectCause

/**
 * The `tone` of an ended call (protocol §5 `Call`): what Android's own phone app would play for how the call ended
 * (Telecom's InCallTonePlayer plays `DisconnectCause.getTone()`). Clients play "busy" and "congestion" themselves;
 * until 1.2.6 a busy or failed call just vanished from the iPhone without a sound.
 */
object CallTone {
    /** ToneGenerator.TONE_UNKNOWN (hidden in the SDK): no tone, what DisconnectCause(code) carries. */
    const val NONE = -1

    /** The wire value of a ToneGenerator tone, null for none or one clients need not play. */
    fun of(tone: Int): String? = when (tone) {
        ToneGenerator.TONE_SUP_BUSY, ToneGenerator.TONE_CDMA_NETWORK_BUSY, ToneGenerator.TONE_CDMA_NETWORK_BUSY_ONE_SHOT -> "busy"
        ToneGenerator.TONE_SUP_CONGESTION, ToneGenerator.TONE_SUP_CONGESTION_ABBREV, ToneGenerator.TONE_SUP_ERROR,
        ToneGenerator.TONE_SUP_INTERCEPT, ToneGenerator.TONE_SUP_INTERCEPT_ABBREV, ToneGenerator.TONE_SUP_RADIO_NOTAVAIL,
        ToneGenerator.TONE_CDMA_REORDER, ToneGenerator.TONE_CDMA_ABBR_REORDER,
        ToneGenerator.TONE_CDMA_INTERCEPT, ToneGenerator.TONE_CDMA_ABBR_INTERCEPT -> "congestion"
        ToneGenerator.TONE_PROP_PROMPT, ToneGenerator.TONE_CDMA_CALLDROP_LITE -> "ended"
        else -> null
    }

    /**
     * For the test line's debug hooks only: the disconnect code and tone an end with [wire] gets ("busy", "congestion",
     * "ended"); null for an ordinary remote end without a tone.
     */
    fun fakeEnd(wire: String?): Pair<Int, Int>? = when (wire) {
        "busy" -> DisconnectCause.BUSY to ToneGenerator.TONE_SUP_BUSY
        "congestion" -> DisconnectCause.ERROR to ToneGenerator.TONE_SUP_CONGESTION
        "ended" -> DisconnectCause.REMOTE to ToneGenerator.TONE_PROP_PROMPT
        else -> null
    }
}
