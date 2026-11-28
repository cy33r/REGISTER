package app.tether.adb

/**
 * When the shell helper can run without Wi-Fi (issues #26, #29: call audio only while the phone was on Wi-Fi).
 *
 * The helper is started through adbd, and init keeps every process adbd starts in adbd's own process group (the
 * cgroup `uid_0/pid_<adbd>`); when adbd stops or restarts, init kills that whole group. `setsid` does not leave it,
 * and the shell user may not move itself out (`cgroup.procs` belongs to the system user; checked on an Android 13
 * emulator, where a detached helper died with adbd and kept serving once adbd stayed up). Android stops adbd when
 * neither USB debugging nor Wireless debugging is on, and turns Wireless debugging off as soon as Wi-Fi goes. So:
 * with USB debugging on, adbd and the helper outlive Wi-Fi; without it, the helper stops with Wi-Fi and cannot be
 * started again until Wi-Fi is back (or TCP debugging is set up).
 *
 * Samsung also restarts adbd when the USB cable is unplugged, with USB debugging on (Galaxy A54, Android 17,
 * 2026-11-28: a new adbd at the unplug, the helper gone with it; plugging the cable back in did not restart adbd). A
 * helper that runs while the cable is in therefore stops at the next unplug, and without Wi-Fi nothing can start it
 * again: three calls that day had no audio at all. Wi-Fi for a few seconds is enough to start it; it then keeps
 * running without Wi-Fi (USB debugging on) until the next unplug.
 */
object HelperLife {
    /**
     * Status `helper.why` while the helper is not running: "wifi" when it cannot start because the phone has no Wi-Fi
     * and so Android has turned Wireless debugging off ([adbWifi]; Android 11+, TCP debugging not set up); null otherwise.
     */
    fun why(running: Boolean, sdk: Int, wifi: Boolean, adbWifi: Boolean, legacy: Boolean): String? =
        // Not [adbWifi]: Samsung keeps the Wireless debugging setting on while Wi-Fi is off (Galaxy A54, 2026-11-28), and
        // without Wi-Fi it cannot be reached either way.
        if (!running && ConnectOrder.tlsSupported(sdk) && !wifi && !legacy) "wifi" else null

    /**
     * Status `helper.needsWifi`: the running helper stops when the phone leaves Wi-Fi, because only Wireless debugging
     * keeps adbd up ([usbDebugging] off).
     */
    fun needsWifi(running: Boolean, sdk: Int, usbDebugging: Boolean): Boolean =
        running && ConnectOrder.tlsSupported(sdk) && !usbDebugging

    /**
     * Status `helper.stopsOnUnplug`: the running helper lives in an adbd that unplugging the USB cable restarts (a
     * [samsung] phone, the cable in), and the phone is off Wi-Fi, so the helper could not start again afterwards.
     * TCP debugging ([legacy]) does not help: adbd restarts all the same, but then it is reachable again.
     */
    fun stopsOnUnplug(running: Boolean, sdk: Int, usbDebugging: Boolean, usbConnected: Boolean, wifi: Boolean, samsung: Boolean, legacy: Boolean): Boolean =
        running && ConnectOrder.tlsSupported(sdk) && usbDebugging && usbConnected && !wifi && samsung && !legacy

    /**
     * Status `helper.noCallAudio`: "wifi" while a call is ringing, dialing or going on ([callGoing]) whose audio would be
     * bridged ([audio] "full" or "fallback", call audio set up: [adbPaired]) but cannot be, because the helper is not
     * running and cannot start without Wi-Fi ([why] "wifi"). Clients show it on their call screen and the Android phone
     * posts a notification: turn on Wi-Fi for a moment. Null otherwise.
     */
    fun noCallAudio(callGoing: Boolean, running: Boolean, why: String?, audio: String, adbPaired: Boolean): String? =
        if (callGoing && !running && why == "wifi" && adbPaired && (audio == "full" || audio == "fallback")) "wifi" else null
}
