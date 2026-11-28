package app.tether.audio

import org.json.JSONObject

/**
 * Whether a bridged call's audio is restarted because the helper came up on an older method (issue #15: it was
 * restarted every 1.5 s, three times in a row, with "auto" over the self-test's choice, cutting working audio; five
 * starts in one second on the reference phone, 2026-11-24).
 *
 * The helper now waits for the call's audio mode before it opens anything (helper OpenGate), so falling back means the
 * interception API refused in a call's own mode. One restart is still worth it then, and only then: on Android 13+,
 * for the call's current start (never an INFO of an earlier one), with the method choice that start used (never
 * overriding the self-test's choice of an older method), when the interception API's refusal was the "not available in
 * mode" kind, the call active, and [waitMs] after that INFO.
 */
object AudioRestart {
    const val MAX = 1

    fun wanted(sdk: Int, info: JSONObject?, choice: String, restarts: Int, msSinceInfo: Long, callActive: Boolean, waitMs: Long = 3000): Boolean {
        if (info == null || sdk < 33 || restarts >= MAX || !callActive || msSinceInfo < waitMs) return false
        if (choice != "auto" && choice != "intercept") return false
        val capture = info.optString("capture"); val inject = info.optString("inject")
        if (capture == "intercept" && inject == "intercept") return false
        val errors = info.optJSONArray("errors") ?: return false
        return (0 until errors.length()).any { val e = errors.optString(it); e.contains(":intercept ") && e.contains("not available in mode") }
    }

    /**
     * The call's downlink cannot be captured at all: every capture method failed in the call's own audio mode (the start
     * INFO says capture none), the call is active, [waitMs] passed since that INFO (the same wait [wanted] gives the
     * interception API) and no restart is coming ([restartWanted]: [wanted] now). Then the client would hear nothing
     * while the phone's own earpiece stays muted, so the call goes back to the phone like one whose capture reads only
     * zeros ("blocked"). Realme X (Android 11, issue #26, 2026-11-28): voice_downlink, voice_call and telephony_rx all
     * refused with "startRecording failed" in every call, the earpiece muted, and the user heard nothing anywhere.
     * Not on the test line (its paths say nothing about real calls), nor before Android 11 (no call audio there anyway).
     */
    fun captureNone(sdk: Int, info: JSONObject?, testLine: Boolean, callActive: Boolean, msSinceInfo: Long, restartWanted: Boolean,
                    waitMs: Long = 3000): Boolean {
        if (info == null || testLine || sdk < 30 || !callActive || msSinceInfo < waitMs || restartWanted) return false
        val capture = info.optString("capture")
        return capture.isEmpty() || capture == "null"
    }
}
