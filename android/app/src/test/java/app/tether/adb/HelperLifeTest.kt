package app.tether.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issues #26 / #29: call audio stopped when the phone left Wi-Fi, and nothing said why. */
class HelperLifeTest {
    @Test fun stoppedWithoutWifiSaysSo() {
        assertEquals("wifi", HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = false, legacy = false))
    }

    @Test fun noReasonWhileRunningOrWhenWifiOrTcpDebuggingCanStartIt() {
        assertNull(HelperLife.why(running = true, sdk = 33, wifi = false, adbWifi = false, legacy = false))
        assertNull(HelperLife.why(running = false, sdk = 33, wifi = true, adbWifi = true, legacy = false))
        // No Wi-Fi, even if the Wireless debugging setting still says on (Samsung keeps it on): it cannot be reached.
        assertEquals("Samsung keeps the setting on without Wi-Fi (A54)", "wifi", HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = true, legacy = false))
        // TCP debugging reaches adbd on 127.0.0.1 without Wi-Fi.
        assertNull(HelperLife.why(running = false, sdk = 33, wifi = false, adbWifi = false, legacy = true))
        // Android 8-10 have no Wireless debugging: Wi-Fi is not what is missing there.
        assertNull(HelperLife.why(running = false, sdk = 29, wifi = false, adbWifi = false, legacy = false))
    }

    @Test fun onlyWirelessDebuggingKeepsTheHelperUp() {
        assertTrue(HelperLife.needsWifi(running = true, sdk = 33, usbDebugging = false))
        assertFalse(HelperLife.needsWifi(running = true, sdk = 33, usbDebugging = true))
        assertFalse(HelperLife.needsWifi(running = false, sdk = 33, usbDebugging = false))
        assertFalse(HelperLife.needsWifi(running = true, sdk = 29, usbDebugging = false))
    }

    @Test fun aSamsungHelperWithTheCableInStopsAtTheNextUnplug() {
        // Galaxy A54, 2026-11-28: USB debugging on, Wi-Fi off, cable in; unplugging it restarted adbd, the helper went with
        // it and could not start again: three calls without any audio, nothing said why. needsWifi was false all along.
        assertFalse(HelperLife.needsWifi(running = true, sdk = 37, usbDebugging = true))
        assertTrue(HelperLife.stopsOnUnplug(running = true, sdk = 37, usbDebugging = true, usbConnected = true, wifi = false, samsung = true, legacy = false))
        assertFalse("cable out: plugging it in does not restart adbd", HelperLife.stopsOnUnplug(true, 37, true, usbConnected = false, wifi = false, samsung = true, legacy = false))
        assertFalse("on Wi-Fi it starts again at once", HelperLife.stopsOnUnplug(true, 37, true, true, wifi = true, samsung = true, legacy = false))
        assertFalse("only seen on Samsung", HelperLife.stopsOnUnplug(true, 37, true, true, false, samsung = false, legacy = false))
        assertFalse("TCP debugging reaches the new adbd", HelperLife.stopsOnUnplug(true, 37, true, true, false, true, legacy = true))
        assertFalse("not running", HelperLife.stopsOnUnplug(false, 37, true, true, false, true, false))
    }

    @Test fun aCallWithoutTheHelperSaysWhy() {
        // The same calls: ringing / dialing with the helper down and no Wi-Fi.
        assertEquals("wifi", HelperLife.noCallAudio(callGoing = true, running = false, why = "wifi", audio = "full", adbPaired = true))
        assertEquals("wifi", HelperLife.noCallAudio(true, false, "wifi", "fallback", true))
        assertNull("no call", HelperLife.noCallAudio(false, false, "wifi", "full", true))
        assertNull("it runs", HelperLife.noCallAudio(true, true, null, "full", true))
        assertNull("it can start (Wi-Fi is on)", HelperLife.noCallAudio(true, false, null, "full", true))
        assertNull("no call audio on this Android version anyway", HelperLife.noCallAudio(true, false, "wifi", "unavailable", true))
        assertNull("this phone's calls are not bridged", HelperLife.noCallAudio(true, false, "wifi", "blocked", true))
        assertNull("call audio never set up", HelperLife.noCallAudio(true, false, "wifi", "full", false))
    }
}
