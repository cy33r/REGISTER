package app.tether.core

/** Small decisions of the link, kept pure so they are unit-tested (see LinkPolicyTest). */
object LinkPolicy {
    /**
     * Whether a GATT session must end when an ACL of the same central changes. iOS runs GATT over whichever
     * transport it has (classic Bluetooth while the phones are paired for tethering, LE otherwise) and moves it
     * when the other one appears or goes: the session on the old bearer is then dead even if no GATT disconnect
     * arrives for it.
     */
    fun gattPipeEnds(pipeOnLe: Boolean, aclIsLe: Boolean, aclUp: Boolean): Boolean =
        if (aclIsLe) (aclUp && !pipeOnLe) || (!aclUp && pipeOnLe) else (!aclUp && !pipeOnLe)

    /**
     * Our own LE link to the paired iPhone. Held only while a session is up (keeps iOS on LE under Bluetooth
     * tethering); never otherwise, because iOS does not complete an app's new connect over a link we hold. While
     * paired without a session, pulsed (up briefly, then dropped): a link coming up completes a waiting connect.
     */
    fun holdLeLink(sessionUp: Boolean) = sessionUp
    /**
     * [linkOpen]: any LE link to the iPhone exists, ours or a lingering one. Only a link coming *up* completes the
     * app's waiting connect; a pulse into an existing link just keeps it alive, and back-to-back pulses held one
     * link open forever so the reopened app never connected (seen 2026-11-22). So: pulse only when there is none.
     */
    fun pulseLeLink(paired: Boolean, sessionUp: Boolean, linkOpen: Boolean) = paired && !sessionUp && !linkOpen

    /** After a session ended or the service started: try a pulse every [PULSE_SOON_EVERY_S] s for [PULSE_SOON_FOR_S] s. */
    const val PULSE_SOON_EVERY_S = 5L
    const val PULSE_SOON_FOR_S = 60L
    const val PULSE_EVERY_S = 30L
    /** Short, so the link (plus Android's ~4 s linger after release) is gone before the next pulse. */
    const val PULSE_UP_MS = 3_000L

    /** What to do with a session that has been silent for [silentMs]; [sinceWakeMs]: since we woke the client without
     *  an answer yet (null: not woken since it last sent anything). */
    enum class Idle { KEEP, WAKE, CLOSE }

    /**
     * A suspended iPhone app sends nothing and cannot answer pings, so silence alone means nothing. After
     * [IDLE_WAKE_MS] of it the client is woken (GATT wake notification) and pinged; only a client that still says
     * nothing [WAKE_ANSWER_MS] later is gone (a half-open session). Closing at once instead made the iPhone relink,
     * handshake and sync every 15 minutes (seen 2026-11-23: 13 relinks in 3 hours).
     */
    fun idle(silentMs: Long, sinceWakeMs: Long?): Idle = when {
        silentMs < IDLE_WAKE_MS -> Idle.KEEP
        sinceWakeMs == null -> Idle.WAKE
        sinceWakeMs >= WAKE_ANSWER_MS -> Idle.CLOSE
        else -> Idle.KEEP
    }
    const val IDLE_WAKE_MS = 15 * 60_000L
    const val WAKE_ANSWER_MS = 45_000L

    /** With no session, a paired iPhone is woken this often (see Hub.wakeWithoutSession). */
    fun wakeUnlinked(sinceLastMs: Long) = sinceLastMs >= WAKE_UNLINKED_MS
    const val WAKE_UNLINKED_MS = 3 * 60_000L
}

/**
 * The token a pair-mode HELLO must prove. The open pairing code's; or, for [windowMs] after a pairing completed,
 * that pairing's token, for the same central only (it may not have received our FIN and says hello in pair mode
 * again; refusing it made it forget a pairing that had succeeded).
 */
class PairingTokens(private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last: Triple<ByteArray, ByteArray, Long>? = null

    @Synchronized fun completed(token: ByteArray, centralKey: ByteArray) { last = Triple(token, centralKey, now() + windowMs) }

    @Synchronized fun tokenFor(centralKey: ByteArray, open: ByteArray?): ByteArray? =
        open ?: last?.takeIf { it.second.contentEquals(centralKey) && now() < it.third }?.first
}

/**
 * When the Android phone asks the central for a faster or slower LE connection (BlePeripheral.tune), per LE connection.
 * Issue #13 (Galaxy A73 with an iPhone, 2026-11-25): a "balanced" request sent 9–15 s after the call's "high" ones got
 * no answer, and 40 s later (the link layer's procedure timeout) Android dropped the link with status 34 (LL response
 * timeout), every time: six drops in one evening, one in the middle of the next call ("audio stops"). The balanced
 * request that came 40 s after the previous one was answered in 130 ms; the "high" ones at a call's start always were.
 * Galaxy A54 with an iPhone 17 (2026-11-28 13:10): a "balanced" request repeated 0.6 s after the same request had been
 * answered (a new session on the same connection) was never answered either; the call's "high" 10 s later never
 * applied (the controller runs one parameter procedure at a time) and the link dropped 40.0 s after the repeat.
 *
 * So, per connection: a request is never sent twice ([want] the same priority does nothing), "high" goes out at once
 * (a ring must not wait; a high sent 7 ms after a balanced one on a fresh connection was answered in 90 ms), "balanced"
 * only once [minGapMs] passed since the last request ([want] returns the time to ask again; the caller re-asks then and
 * nothing is sent if "high" was wanted meanwhile), and never as the first request on a connection: nothing asked yet
 * means the connection runs at the central's own parameters, and "balanced" exists only to undo a "high". One pacer
 * lives as long as its LE connection (see [LinkPriorities]), not a session.
 */
class PriorityPacer(private val minGapMs: Long = 35_000, private val settleMs: Long = 1_500) {
    private var lastSent: Boolean? = null   // true: high, false: balanced, null: nothing sent on this link yet
    private var lastAt = Long.MIN_VALUE / 2
    private var wanted: Boolean? = null
    /** When the last request went out with no connection update since (-1: answered, or nothing sent). */
    private var unansweredSince = -1L
    /** A request waits for the previous one's answer ([settleMs] at most); [answered] says it may go now. */
    private var heldForAnswer = false

    /**
     * What to do now for [high] at [nowMs]: send it, nothing, or ask again in [Step.retryInMs]. [unansweredMs]: a request
     * goes out while the previous one has had no answer for that long (-1: it had one), for the link log.
     */
    data class Step(val send: Boolean, val retryInMs: Long = -1, val unansweredMs: Long = -1)

    @Synchronized fun want(high: Boolean, nowMs: Long): Step {
        wanted = high
        if (lastSent == high) return Step(send = false)
        // Never two requests in flight: a Galaxy Note 20 Ultra (1.2.7, issue #26) sent "high" 0.1 s after an unanswered
        // request and dropped the link 39 s later. Wait for the answer (usually 90-130 ms), [settleMs] at most.
        if (unansweredSince >= 0 && nowMs - unansweredSince < settleMs) {
            heldForAnswer = true
            return Step(send = false, retryInMs = unansweredSince + settleMs - nowMs)
        }
        if (high) return sent(true, nowMs)
        if (lastSent == null) return Step(send = false)   // nothing to undo on this connection
        val wait = lastAt + minGapMs - nowMs
        return if (wait <= 0) sent(false, nowMs) else Step(send = false, retryInMs = wait)
    }

    /** A delayed request is due: send what the link wants now, if it still differs from what was asked last. */
    @Synchronized fun due(nowMs: Long): Step = wanted?.let { want(it, nowMs) } ?: Step(send = false)

    /**
     * The connection's parameters changed (Android's connection-updated callback): the last request was answered.
     * Returns true when a request was held back for this answer (the caller asks [due] now instead of after [settleMs]).
     */
    @Synchronized fun answered(): Boolean { unansweredSince = -1; return heldForAnswer.also { heldForAnswer = false } }

    /** Unanswered for this long at [nowMs] (-1: answered, or nothing sent). */
    @Synchronized fun unansweredMs(nowMs: Long): Long = if (unansweredSince < 0) -1 else nowMs - unansweredSince

    private fun sent(high: Boolean, nowMs: Long): Step {
        val open = if (unansweredSince < 0) -1 else nowMs - unansweredSince
        lastSent = high; lastAt = nowMs; unansweredSince = nowMs; heldForAnswer = false
        return Step(send = true, unansweredMs = open)
    }
}

/**
 * One [PriorityPacer] per LE connection (ACL) to a central, by address. A session ending says nothing about the
 * connection: the iPhone restarts its session on a connection that stays up (services changed, a new L2CAP channel),
 * and until 1.2.6 the pacer went with the session, so the next session repeated "balanced" on the same connection and
 * the link dropped 40 s later (Galaxy A54, 2026-11-28). Only the connection going down ([connectionDown]: the LE ACL
 * broadcast) or Bluetooth stopping ([forget]) starts a fresh pacer.
 */
class LinkPriorities(private val minGapMs: Long = 35_000) {
    private val pacers = HashMap<String, PriorityPacer>()

    @Synchronized private fun pacer(address: String) = pacers.getOrPut(address) { PriorityPacer(minGapMs) }
    @Synchronized fun want(address: String, high: Boolean, nowMs: Long) = pacer(address).want(high, nowMs)
    @Synchronized fun due(address: String, nowMs: Long) = pacer(address).due(nowMs)
    @Synchronized fun answered(address: String): Boolean = pacers[address]?.answered() ?: false
    @Synchronized fun unansweredMs(address: String, nowMs: Long): Long = pacers[address]?.unansweredMs(nowMs) ?: -1
    /** The LE connection to [address] is gone: the next one starts with nothing asked. */
    @Synchronized fun connectionDown(address: String) { pacers.remove(address) }
    @Synchronized fun forget() { pacers.clear() }
}
