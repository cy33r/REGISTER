package app.tether.telecom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.util.Log
import app.tether.TetherApp

/**
 * Turns the Android phone's screen off when it is held to the ear during a call taken on the phone itself,
 * as the stock dialer does. Tether is the default phone app, so without this the screen stayed on and the
 * cheek pressed buttons.
 *
 * Held only while: a call is connected, dialing or on hold, its audio stays on this phone (no client owns it),
 * and the route is the earpiece (not the speaker, a headset or Bluetooth). Released with
 * RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY, so the screen comes back once the phone leaves the ear.
 */
class ProximityGuard(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val lock: PowerManager.WakeLock? = ctx.getSystemService(PowerManager::class.java)
        ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "register:proximity")
        ?.apply { setReferenceCounted(false) }

    /** Calls change ownership (Move here on the iPhone) without a Telecom event: check again every second. */
    private val tick = object : Runnable {
        override fun run() { update(); if (CallRegistry.active().isNotEmpty()) main.postDelayed(this, 1000) }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }

    fun stop() { main.removeCallbacks(tick); release() }

    fun update(route: Int? = null) {
        val l = lock ?: return
        val r = route ?: CallRegistry.service?.callAudioState?.route
        if (wanted(r)) { if (!l.isHeld) { l.acquire(); Log.i(TAG, "proximity on") } }
        else release()
    }

    private fun wanted(route: Int?): Boolean {
        val calls = CallRegistry.active()
        val live = calls.any { it.stateCompat() in LIVE }
        if (!live) return false
        if (route != CallAudioState.ROUTE_EARPIECE) return false
        val owner = runCatching { (ctx.applicationContext as TetherApp).hub.audio.owner }.getOrNull()
        return owner == null
    }

    private fun release() {
        val l = lock ?: return
        if (l.isHeld) { l.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY); Log.i(TAG, "proximity off") }
    }

    companion object {
        private const val TAG = "Register.Proximity"
        private val LIVE = setOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_HOLDING)
    }
}
