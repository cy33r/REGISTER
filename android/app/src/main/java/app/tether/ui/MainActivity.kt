package app.tether.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tether.R
import app.tether.TetherApp
import app.tether.adb.Adb
import app.tether.adb.ConnectOrder
import app.tether.core.PairingCode
import app.tether.helper.CallAudio
import app.tether.helper.LocalMutes
import app.tether.helper.MicPath
import app.tether.link.Hub
import app.tether.link.LinkLog
import app.tether.link.LinkService
import app.tether.report.PhoneReport
import app.tether.report.ReportUi
import app.tether.telecom.CallRegistry
import app.tether.update.UpdateUi
import app.tether.update.Updater
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private const val RELEASES_URL = "https://github.com/cy33r/REGISTER/releases"
private val FA_DIGITS = Regex("(?<![A-Za-z0-9.:/\\-])[0-9]+(?![0-9A-Za-z:/\\-]|\\.[0-9A-Za-z])")

class MainActivity : Activity() {
    private lateinit var app: TetherApp
    private lateinit var root: LinearLayout
    private lateinit var scroller: ScrollView
    private val advanced get() = page == Page.ADVANCED
    /** REGISTER: Persian (right to left, Persian digits) or English (capitals, a touch smaller and spaced out). */
    private val isFa by lazy { UiPrefs.isFa(this) }
    private val dir get() = if (isFa) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
    private fun sized(sp: Float) = if (isFa) sp else sp * 0.92f
    private val main = Handler(Looper.getMainLooper())
    private var qrUntil = 0L
    private var dialerRequestMessage: Int? = null
    private var qrBitmap: Bitmap? = null
    private var qrLegacy = false
    private val listener: () -> Unit = { main.post { render() } }
    private val updater by lazy { Updater.get(this, "REGISTER-Android") }

    /** Runtime permissions this Android version knows: Bluetooth ones from 12, notifications from 13. */
    private val perms = (
        (if (android.os.Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()) +
        (if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()) + listOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )).toTypedArray()

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(UiPrefs.wrap(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = if (UiPrefs.isFa(this)) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR   // REGISTER: Persian, right to left
        app = application as TetherApp
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(28)) }
        scroller = ScrollView(this).apply { addView(root); setBackgroundColor(color(R.color.background)); isFillViewport = true }
        setContentView(scroller)
        if (missingPerms().isEmpty()) LinkService.start(this) else requestPermissions(missingPerms(), 1)
    }

    override fun onResume() {
        super.onResume()
        app.hub.addListener(listener)
        updater.addListener(listener)
        updater.maybeCheckDaily()
        render()
        tick()
    }

    override fun onPause() {
        app.hub.removeListener(listener)
        updater.removeListener(listener)
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        LinkService.start(this)
        render()
    }

    private fun tick() {
        main.postDelayed({ if (qrUntil > 0) render(); tick() }, 1000)
    }

    private fun missingPerms() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    @Suppress("DEPRECATION") // The platform role and legacy Telecom APIs both return an Activity result.
    private fun requestDialerRole() {
        try {
            val intent = if (android.os.Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles == null || !roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    dialerRequestMessage = R.string.dialer_role_unavailable
                    Log.w("Tether", "Default dialer role unavailable on SDK ${android.os.Build.VERSION.SDK_INT}")
                    render()
                    return
                }
                roles.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else {
                Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            dialerRequestMessage = null
            startActivityForResult(intent, REQUEST_DIALER_ROLE)
        } catch (e: ActivityNotFoundException) {
            dialerRequestUnavailable(e)
        } catch (e: SecurityException) {
            dialerRequestUnavailable(e)
        }
    }

    private fun dialerRequestUnavailable(e: RuntimeException) {
        Log.w("Tether", "Could not open default dialer request", e)
        dialerRequestMessage = R.string.dialer_role_request_failed
        render()
    }

    @Deprecated("The platform role request uses the Activity result API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DIALER_ROLE) return
        val held = app.sims.isDialer()
        Log.i("Tether", "Default dialer request: result=$resultCode held=$held sdk=${android.os.Build.VERSION.SDK_INT}")
        dialerRequestMessage = dialerRoleMessage(held, resultCode)
        render()
        if (held) app.hub.refresh()
    }

    private companion object {
        const val REQUEST_DIALER_ROLE = 2
    }


    /** REGISTER: the home screen and the pages its rows open. */
    private enum class Page { HOME, SETUP, AUDIO, LOOK, ADVANCED, ABOUT }
    private var page = Page.HOME

    private fun open(p: Page) { page = p; updateBack(); render(); scroller.scrollTo(0, 0) }

    private fun render() {
        root.removeAllViews()
        when (page) {
            Page.HOME -> renderHome()
            Page.SETUP -> sub(R.string.section_setup) { setupSection(card(null)) }
            Page.AUDIO -> sub(R.string.section_audio) { audioSection(card(null)) }
            Page.LOOK -> sub(R.string.section_language) { lookSection(card(null)) }
            Page.ADVANCED -> sub(R.string.section_advanced) { advancedSection(card(null)) }
            Page.ABOUT -> sub(R.string.section_about) { aboutSection(card(null)) }
        }
    }

    private fun renderHome() {
        // Header: the app's icon, name and version.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(18))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) }
        })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(oneLine(getString(R.string.app_name), R.color.text, 22f, bold = true))
        names.addView(oneLine(getString(R.string.version_label, ltr(appVersion())), R.color.muted, 13f))
        head.addView(names)
        root.addView(head)

        // The link: state, paired iPhones, pairing.
        val link = card(null)
        linkSection(link)

        // Setup: in full until every step is done, then one line.
        val steps = setupSteps()
        val done = steps.count { it }
        if (done < steps.size) {
            setupSection(card(getString(R.string.section_setup)))
        } else {
            val g = group(null)
            g.addView(row(R.drawable.ic_check, R.color.ok, getString(R.string.setup_complete),
                fa(getString(R.string.n_of_m, done, steps.size)), last = true) { open(Page.SETUP) })
        }

        // Settings.
        val h = app.hub.audio.helperStatus()
        val running = h.optString("state") == "running"
        val g = group(getString(R.string.section_settings))
        g.addView(row(R.drawable.ic_call, R.color.tile_orange, getString(R.string.section_audio),
            getString(if (running) R.string.helper_on else R.string.helper_stopped), valueColor = if (running) R.color.muted else R.color.warn) { open(Page.AUDIO) })
        g.addView(row(R.drawable.ic_text_fields, R.color.tile_blue, getString(R.string.section_language),
            getString(if (isFa) R.string.lang_fa else R.string.lang_en) + " · " + getString(when (UiPrefs.theme(this)) {
                "light" -> R.string.theme_light; "dark" -> R.string.theme_dark; else -> R.string.theme_system })) { open(Page.LOOK) })
        g.addView(row(R.drawable.ic_link_overflow, R.color.tile_gray, getString(R.string.section_advanced), null) { open(Page.ADVANCED) })
        g.addView(row(R.drawable.ic_link_diagnostics, R.color.tile_purple, getString(R.string.report_title), null) {
            ReportUi.send(this) { PhoneReport.build(app) } })
        val news = updater.state.let { it is Updater.State.Available || it is Updater.State.Ready }
        g.addView(row(R.drawable.ic_history, R.color.tile_green, getString(R.string.section_about_update), null,
            badge = if (news) getString(R.string.badge_new) else null, last = true) { open(Page.ABOUT) })
    }

    /** A page: a back row with its title, then its content. */
    private fun sub(title: Int, content: () -> Unit) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back); setColorFilter(color(R.color.accent))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            contentDescription = getString(R.string.action_back)
            setOnClickListener { open(Page.HOME) }
        })
        bar.addView(oneLine(getString(title), R.color.text, 21f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) } })
        root.addView(bar)
        content()
    }

    private fun setupSteps(): List<Boolean> {
        val pm = getSystemService(PowerManager::class.java)
        val list = mutableListOf(missingPerms().isEmpty(), app.sims.isDialer(), pm.isIgnoringBatteryOptimizations(packageName))
        if (android.os.Build.VERSION.SDK_INT >= 30) list += Adb.hasSecureSettings(this) && app.prefs.getBoolean("adb_paired", false)
        return list
    }

    private fun linkSection(link: LinearLayout) {
        val svc = LinkService.running
        val conn = app.hub.conn
        val (state, stateColor) = when {
            svc?.peripheral?.enabled == false -> getString(R.string.state_bt_off) to R.color.bad
            conn?.isUp == true -> getString(R.string.state_connected) + " · " + conn.transport.label.uppercase() to R.color.ok
            !app.keys.isPaired -> getString(R.string.state_unpaired) to R.color.warn
            else -> getString(R.string.state_waiting) to R.color.muted
        }
        link.addView(statusLine(state, stateColor))
        // Paired devices: connected ones are marked; each can be removed on its own.
        val live = app.hub.targets()
        for (cl in app.keys.centrals()) {
            val on = live.any { it.peerKey.contentEquals(cl.key) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
            r.addView(label((if (on) "● " else "○ ") + cl.name.uppercase() + (if (on) " · " + getString(R.string.state_connected) else ""),
                if (on) R.color.ok else R.color.muted, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            r.addView(smallButton(getString(R.string.unpair)) {
                AlertDialog.Builder(this).setMessage(getString(R.string.unpair_confirm_named, cl.name.uppercase()))
                    .setPositiveButton(R.string.unpair) { _, _ ->
                        app.keys.removeCentral(cl.key)
                        app.hub.conns.filter { it.peerKey.contentEquals(cl.key) }.forEach { it.close("unpaired") }
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            })
            link.addView(r)
        }
        val remaining = (qrUntil - System.currentTimeMillis()) / 1000
        if (qrUntil > 0 && remaining > 0 && app.keys.currentToken() != null) {
            link.addView(label(getString(if (qrLegacy) R.string.pair_hint_old else R.string.pair_hint), R.color.text))
            link.addView(ImageView(this).apply {
                setImageBitmap(qrBitmap); adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
            })
            link.addView(label(getString(R.string.pair_expires, remaining.toInt()), R.color.muted, 13f).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER })
            // An iPhone app 1.1 scans only the tether1: form; the same code (same token) in that form. Advanced only.
            if (advanced) link.addView(button(getString(if (qrLegacy) R.string.pair_new_code else R.string.pair_old_app), secondary = true, full = true) {
                qrLegacy = !qrLegacy; drawQr(app.keys.openPairingCurrent() ?: return@button); render()
            })
        } else if (qrUntil > 0) {
            qrUntil = 0
            qrBitmap = null
            qrLegacy = false
        }
        if (qrUntil == 0L) link.addView(button(getString(R.string.pair_iphone), full = true) { showQr() })

    }

    private fun setupSection(setup: LinearLayout) {
        setup.addView(label(getString(R.string.setup_intro), R.color.muted, 13f))
        setup.addView(step(getString(R.string.step_n, 1, getString(R.string.step_permissions)), missingPerms().isEmpty(), getString(R.string.action_grant),
            help = getString(R.string.help_permissions)) {
            requestPermissions(missingPerms(), 1)
        })
        setup.addView(step(getString(R.string.step_n, 2, getString(R.string.step_dialer)), app.sims.isDialer(), getString(R.string.action_set),
            help = getString(R.string.help_dialer)) { requestDialerRole() })
        if (!app.sims.isDialer()) dialerRequestMessage?.let { message ->
            setup.addView(label(getString(message), R.color.warn, 13f))
        }
        val pm = getSystemService(PowerManager::class.java)
        setup.addView(step(getString(R.string.step_n, 3, getString(R.string.step_battery)), pm.isIgnoringBatteryOptimizations(packageName), getString(R.string.action_set),
            help = getString(R.string.help_battery)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        val adbDone = Adb.hasSecureSettings(this) && app.prefs.getBoolean("adb_paired", false)
        // Wireless debugging exists from Android 11; before that only TCP adb (below) can start the helper.
        if (android.os.Build.VERSION.SDK_INT >= 30) setup.addView(step(getString(R.string.step_n, 4, getString(R.string.step_adb)), adbDone, getString(R.string.action_pair),
            help = getString(R.string.help_adb)) {
            Adb.showPairingNotification(this)
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })

        if (!app.keys.isPaired && qrUntil == 0L) setup.addView(label(getString(R.string.help_pair_next), R.color.accent, 14f))

    }

    private fun audioSection(audio: LinearLayout) {
        // Call audio: interception API on Android 13+, voice-call capture on 11-12, nothing on 10.
        when (app.hub.helper.audioLevel()) {
            "unavailable" -> audio.addView(label(getString(R.string.audio_needs_android11), R.color.warn, 13f))
            "fallback" -> audio.addView(label(getString(R.string.audio_android11_12), R.color.muted, 13f))
            "blocked" -> audio.addView(label(getString(R.string.audio_blocked), R.color.warn, 13f))
        }
        val h = app.hub.audio.helperStatus()
        val running = h.optString("state") == "running"
        audio.addView(step(getString(R.string.step_helper), running, getString(R.string.action_start),
            if (running) getString(R.string.helper_on) else getString(R.string.helper_stopped)) {
            Thread { app.hub.helper.ensureRunning(); app.hub.refresh() }.start()
        })
        // Without Wi-Fi Android turns Wireless debugging off, and adbd stops with the helper in it (issues #26, #29).
        if (h.optString("why") == "wifi") audio.addView(label(getString(R.string.helper_why_wifi), R.color.warn, 13f))
        if (h.optBoolean("needsWifi")) {
            audio.addView(label(getString(R.string.helper_needs_wifi), R.color.warn, 13f))
            audio.addView(button(getString(R.string.action_usb_debugging), secondary = true) {
                if (!Adb.enableUsbDebugging(this)) runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                Thread { app.hub.pushStatus() }.start()
                render()
            })
        }
        // Samsung restarts adbd when the USB cable is pulled out, and the helper with it (Galaxy A54, 2026-11-28).
        if (h.optBoolean("stopsOnUnplug")) audio.addView(label(getString(R.string.helper_stops_on_unplug), R.color.warn, 13f))
        // The phone's own microphone during bridged calls (issue #2): by default a redirect audio mode takes it out of the
        // call where the phone allows it (MicPath); muting it is opt-in, since on some phones that also mutes the iPhone.
        if (app.hub.helper.audioLevel() != "unavailable") {
            val mic = app.hub.audio.mic
            audio.addView(oneLine(getString(R.string.mic_title), R.color.text, 15f, bold = true).apply { setPadding(0, dp(12), 0, dp(2)) })
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
            for ((choice, text) in listOf(MicPath.Setting.AUTO to R.string.mic_choice_auto, MicPath.Setting.MUTE to R.string.mic_choice_mute,
                    MicPath.Setting.OPEN to R.string.mic_choice_open)) {
                group.addView(RadioButton(this).apply {
                    id = View.generateViewId(); this.text = fa(getString(text)); textSize = sized(14f); setTextColor(color(R.color.text))
                    textDirection = dir; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    isChecked = choice == mic
                    // A click sets it; on the one already chosen it also forgets what the redirect modes did here, so they are tried anew.
                    setOnClickListener { Thread { app.hub.audio.setMic(choice); app.hub.pushStatus() }.start() }
                })
            }
            audio.addView(group)
            val autoFailed = mic == MicPath.Setting.AUTO && (android.os.Build.VERSION.SDK_INT < 33 ||
                app.hub.audio.redirectMemory().let { m -> m[MicPath.Redirect.CALL_REDIRECT]?.usable == false &&
                    (m[MicPath.Redirect.CALL_SCREENING]?.usable ?: false) == false })
            val blocked = mic == MicPath.Setting.MUTE && app.hub.audio.memory() == LocalMutes.Memory.BREAKS_UPLINK
            // No mic mute on this phone keeps the iPhone's voice in the call (Qualcomm: Android's mute is the whole Tx path).
            val unsupported = mic == MicPath.Setting.MUTE && !LocalMutes.canMute(CallAudio.silence)
            // Qualcomm: Android's mute keeps the iPhone's voice with the in-call music injection only (LocalMutes).
            val inCallMusic = unsupported && LocalMutes.canMute(CallAudio.silence, "telephony_tx")
            // Without a redirect mode the default setting's next step: the maker's own mic switch, where this phone has one that holds.
            val vendor = autoFailed && LocalMutes.autoPlan(CallAudio.silence, app.hub.audio.memory()).vendorMic
            audio.addView(label(getString(when {
                vendor -> R.string.mic_auto_vendor
                autoFailed -> R.string.mic_auto_unavailable
                mic == MicPath.Setting.AUTO -> R.string.mic_auto_note
                inCallMusic -> R.string.mic_mute_incall_music
                unsupported -> R.string.mic_mute_unsupported
                blocked -> R.string.mic_mute_blocked
                mic == MicPath.Setting.MUTE -> R.string.mic_mute_on
                else -> R.string.mic_open_note
            }), if (blocked || (unsupported && !inCallMusic) || (autoFailed && !vendor)) R.color.warn else R.color.muted, 13f))
            if (advanced) h.optString("micPath").takeIf { it.isNotEmpty() }?.let { p ->
                audio.addView(label(when (p) {
                    "off" -> getString(R.string.mic_now_off, app.hub.helper.lastMic?.optString("mode") ?: "")
                    "muted" -> getString(R.string.mic_now_muted)
                    else -> getString(R.string.mic_now_open)
                }, R.color.muted, 13f))
            }
            if (advanced) h.optString("uplink").takeIf { it.isNotEmpty() }?.let { u ->
                audio.addView(label(getString(R.string.uplink_title) + ": " + getString(when (u) {
                    "ok" -> R.string.uplink_ok
                    "checking" -> R.string.uplink_checking
                    "muted-locally-fixed" -> if (mic == MicPath.Setting.MUTE) R.string.uplink_fixed else R.string.uplink_fixed_redirect
                    "off" -> R.string.uplink_off
                    else -> R.string.uplink_unverified
                }), if (u == "muted-locally-fixed") R.color.warn else R.color.muted, 13f))
            }
        }
        // The phone's own call screen while a client has the call (issue #13): shown unless the owner turns it off.
        audio.addView(android.widget.CheckBox(this).apply {
            text = getString(R.string.call_screen_bridged); textSize = 15f; setTextColor(color(R.color.text))
            isChecked = app.prefs.getBoolean(Hub.PREF_CALL_SCREEN, true)
            setOnCheckedChangeListener { _, on -> app.prefs.edit().putBoolean(Hub.PREF_CALL_SCREEN, on).apply() }
        })
        audio.addView(label(getString(R.string.call_screen_bridged_note), R.color.muted, 13f))

    }

    private fun advancedSection(adv: LinearLayout) {
        val h = app.hub.audio.helperStatus()
        val running = h.optString("state") == "running"
        // Legacy TCP adb: opt-in, for Android 10 (no Wireless debugging) or a phone without a trusted Wi-Fi network.
        val tcpOn = app.prefs.getBoolean(Adb.PREF_LEGACY, false)
        val tcpPort = app.prefs.getInt(Adb.PREF_LEGACY_PORT, ConnectOrder.DEFAULT_LEGACY_PORT)
        adv.addView(step(getString(R.string.step_adb_tcp), tcpOn && Adb.hasSecureSettings(this), getString(R.string.action_enable),
            if (tcpOn) getString(R.string.adb_tcp_on, tcpPort) else getString(R.string.adb_tcp_off)) { enableTcpAdb(tcpPort) })
        if (tcpOn) adv.addView(button(getString(R.string.action_turn_off), secondary = true) {
            app.prefs.edit().putBoolean(Adb.PREF_LEGACY, false).apply(); Adb.reset(); render()
        })
        if (running) adv.addView(label(getString(R.string.helper_running, h.optString("method", "auto").uppercase()) +
            (h.optString("capture").takeIf { it.isNotEmpty() }?.let { " · " + getString(R.string.helper_capture, it.uppercase()) } ?: ""), R.color.muted, 13f))
        app.hub.lastSelftest?.let { st ->
            val methods = st.optJSONArray("methods")
            val txt = (0 until (methods?.length() ?: 0)).joinToString("\n") {
                val m = methods!!.getJSONObject(it); "${if (m.optBoolean("ok")) "✓" else "✗"} ${m.optString("name")}  ${m.optString("note")}"
            }
            adv.addView(label(getString(R.string.selftest_result, st.optString("chosen", "–")) + "\n" + txt, R.color.muted, 12f).apply {
                textDirection = View.TEXT_DIRECTION_LTR; typeface = Typeface.MONOSPACE })
        }
        adv.addView(button(getString(R.string.action_selftest), secondary = true, full = true) { Thread { app.hub.selftest() }.start() })
        adv.addView(button(getString(R.string.link_log), secondary = true, full = true) { showLinkLog() })
    }

    private fun lookSection(look: LinearLayout) {
        look.addView(oneLine(getString(R.string.section_language), R.color.muted, 13f))
        look.addView(segmented(listOf(UiPrefs.FA to getString(R.string.lang_fa), UiPrefs.EN to getString(R.string.lang_en)),
            UiPrefs.lang(this)) { UiPrefs.setLang(this, it) })
        look.addView(oneLine(getString(R.string.section_theme), R.color.muted, 13f).apply { setPadding(0, dp(14), 0, 0) })
        look.addView(segmented(listOf("system" to getString(R.string.theme_system), "light" to getString(R.string.theme_light),
            "dark" to getString(R.string.theme_dark)), UiPrefs.theme(this)) { UiPrefs.setTheme(this, it) })
    }

    private fun aboutSection(about: LinearLayout) {
        about.addView(label(getString(R.string.about_text), R.color.muted, 13f))
        about.addView(oneLine(getString(R.string.version_label, ltr(appVersion())), R.color.text, 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        UpdateUi.button(this, updater)?.let { t ->
            about.addView(button(t, full = true) { UpdateUi.onTap(this, updater, CallRegistry.active().isNotEmpty()) })
        }
        UpdateUi.status(this, updater)?.let { about.addView(label(it, R.color.muted, 13f)) }
        about.addView(button(UpdateUi.dailyButton(this, updater), secondary = true, full = true) { updater.daily = !updater.daily })
        about.addView(button(getString(R.string.action_releases_page), secondary = true, full = true) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
        })
    }

    /** A Latin run (a version, a name) kept left to right inside a Persian line. */
    private fun ltr(t: String) = "\u2066$t\u2069"

    private fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    /**
     * The link log (the same lines as `tools/dbg.sh linklog`: link, call-audio and mic events, no message text, no
     * numbers), with Share and Copy, so a bug report needs no computer. A header says which app, phone and Android.
     */
    private fun showLinkLog() {
        LinkLog.init(this)
        val body = LinkLog.tail(600)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val header = "REGISTER $version · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        val text = header + "\n\n" + body.ifEmpty { getString(R.string.link_log_empty) }
        val view = TextView(this).apply {
            this.text = text; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle(R.string.link_log).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(R.string.action_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "REGISTER link log").putExtra(Intent.EXTRA_TEXT, text)
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.link_log))) }
            }
            .setNeutralButton(R.string.action_copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("REGISTER link log", text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Explains the steps, then (on Enable) connects to adbd's TCP mode, grants what the app needs and starts the helper. */
    private fun enableTcpAdb(port: Int) {
        AlertDialog.Builder(this).setMessage(getString(R.string.adb_tcp_howto))
            .setPositiveButton(R.string.action_enable) { _, _ ->
                app.prefs.edit().putBoolean(Adb.PREF_LEGACY, true).apply()
                Toast.makeText(this, R.string.adb_tcp_connecting, Toast.LENGTH_SHORT).show()
                Thread {
                    Adb.reset()
                    val ok = try { Adb.get(this).ensureConnected() } catch (_: Throwable) { false }
                    if (ok) {
                        runCatching { Adb.bootstrap(this) }
                        app.prefs.edit().putBoolean("adb_paired", true).apply()
                        app.hub.helper.ensureRunning(); app.hub.refresh()
                    }
                    main.post {
                        Toast.makeText(this, if (ok) getString(R.string.adb_tcp_connected) else getString(R.string.adb_tcp_failed, port), Toast.LENGTH_LONG).show()
                        render()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showQr() {
        qrLegacy = false
        drawQr(app.keys.openPairing())
        qrUntil = app.keys.pairingExpiry
        render()
    }

    /** The QR code holds the `tether://pair?c=…` link (the iPhone's Camera opens it in Cellular), or for Cellular 1.1
     *  the `tether1:` form of the same code. */
    private fun drawQr(code: PairingCode) {
        val size = 720
        val m = QRCodeWriter().encode(if (qrLegacy) code.encode() else code.link(), BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }   // theme: ok, a QR code must be black on white
        qrBitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ tiny view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)

    /** An iPhone-style grouped section: a quiet heading (optional) above a rounded card. */
    private fun card(title: String?): LinearLayout {
        if (title != null) root.addView(oneLine(title.uppercase(), R.color.muted, 13f).apply { setPadding(dp(16), dp(2), dp(16), dp(7)) })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(color(R.color.surface)); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
            elevation = dp(1).toFloat()
        }
        root.addView(c)
        return c
    }

    /** Every text right to left (Persian), even a line that starts with a Latin word, with Persian digits. */
    private fun label(t: String, colorId: Int, size: Float = 15f) = TextView(this).apply {
        text = fa(t); textSize = sized(size); setTextColor(color(colorId)); setPadding(0, dp(3), 0, dp(3))
        textDirection = dir; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (!isFa) letterSpacing = 0.03f
        setLineSpacing(0f, 1.15f)
        // Longer notes justified, so every line of a paragraph is the same width.
        if (size <= 14f) justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** A pill button; [full]: as wide as its card. Laid out under its text, never beside it. */
    private fun button(t: String, secondary: Boolean = false, full: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = fa(t); isAllCaps = false; textSize = sized(15f)
        if (!isFa) letterSpacing = 0.05f
        setTextColor(if (secondary) color(R.color.accent) else color(R.color.on_accent))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (secondary) setStroke(dp(1), color(R.color.accent)) else setColor(color(R.color.accent))
        }
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(if (full) -1 else -2, dp(44)).apply { topMargin = dp(10) }
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
    }

    /** A row of equal buttons, the chosen one filled (like an iPhone segmented control). */
    private fun segmented(options: List<Pair<String, String>>, chosen: String, pick: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color(R.color.fill)) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        for ((key, title) in options) {
            val on = key == chosen
            row.addView(TextView(this).apply {
                text = title; textSize = sized(14f); gravity = Gravity.CENTER
                setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color(R.color.text))
                if (on) background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color(R.color.surface_raised)) }
                setPadding(dp(6), dp(9), dp(6), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { if (!on) pick(key) }
            })
        }
        return row
    }

    /** A list group: rows with hairlines between them, no inner padding. */
    private fun group(title: String?): LinearLayout {
        if (title != null) root.addView(oneLine(title.uppercase(), R.color.muted, 13f).apply { setPadding(dp(16), dp(2), dp(16), dp(7)) })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(color(R.color.surface)); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
            clipToOutline = true
        }
        root.addView(c)
        return c
    }

    /** One settings row: a coloured icon tile, the title and a value on one line each, and a chevron. */
    private fun row(icon: Int, tile: Int, title: String, value: String?, valueColor: Int = R.color.muted,
                    badge: String? = null, last: Boolean = false, onClick: () -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(12), dp(11)); minimumHeight = dp(52)
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.fill)), null,
                android.graphics.drawable.ColorDrawable(Color.WHITE))
            setOnClickListener { onClick() }
        }
        r.addView(ImageView(this).apply {
            setImageResource(icon); setColorFilter(Color.WHITE)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply { setColor(color(tile)); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) }
        })
        r.addView(oneLine(title, R.color.text, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        if (value != null) r.addView(oneLine(value, valueColor, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }; maxWidth = dp(150) })
        if (badge != null) r.addView(oneLine(badge, R.color.on_accent, 11f, bold = true).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { setColor(color(R.color.bad)); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
        })
        r.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_link_chevron); setColorFilter(color(R.color.muted)); alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
        })
        wrap.addView(r)
        if (!last) wrap.addView(View(this).apply {
            setBackgroundColor(color(R.color.hairline))
            layoutParams = LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(56) }
        })
        return wrap
    }

    /** A text that stays on one line (cut with … when it does not fit), in the app's direction. */
    private fun oneLine(t: String, colorId: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = fa(t); textSize = sized(size); setTextColor(color(colorId)); maxLines = 1; isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        textDirection = dir; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (!isFa) letterSpacing = 0.03f
    }

    /** The link's state: a coloured dot and the state in bold. */
    private fun statusLine(t: String, colorId: Int): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(colorId)) }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        })
        r.addView(oneLine(t, colorId, 16f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        return r
    }

    /** Back goes home from a page (Android 13+ through the back dispatcher, which Android 16 apps must use). */
    private var backCallback: Any? = null

    private fun updateBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val d = onBackInvokedDispatcher
        (backCallback as? android.window.OnBackInvokedCallback)?.let { d.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (page != Page.HOME) {
            val cb = android.window.OnBackInvokedCallback { open(Page.HOME) }
            d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Android 12 and older")
    override fun onBackPressed() {
        if (page != Page.HOME) open(Page.HOME) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** A small text button for a row (unpair). */
    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = fa(t); isAllCaps = false; textSize = 13f; setTextColor(color(R.color.bad))
        background = null; stateListAnimator = null; minWidth = 0; minHeight = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        setOnClickListener { onClick() }
    }

    /** A setup step: title and status on one line, what to do under it, and its button under that. */
    private fun step(title: String, done: Boolean, action: String, detail: String? = null, help: String? = null,
                     onClick: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(if (done) R.color.ok else R.color.warn)) }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
        })
        head.addView(oneLine(title, R.color.text, 15f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        head.addView(oneLine(detail ?: getString(if (done) R.string.done else R.string.missing), if (done) R.color.ok else R.color.warn, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } })
        box.addView(head)
        // What to do, in plain words, until the step is done.
        if (!done && help != null) box.addView(label(help, R.color.muted, 13f))
        if (!done) box.addView(button(action) { onClick() })
        return box
    }

    /**
     * Persian digits for what the user reads. A number touching a Latin letter or a dot, colon, slash or dash
     * (A73, 5G, 127.0.0.1, beta.2, SIM 2's carrier codes) is left as it is.
     */
    private fun fa(t: String): String = if (!isFa) t else FA_DIGITS.replace(t) { m -> m.value.map { '۰' + (it - '0') }.joinToString("") }
}

internal fun dialerRoleMessage(held: Boolean, resultCode: Int): Int? = when {
    held -> null
    resultCode == Activity.RESULT_OK -> R.string.dialer_role_unconfirmed
    else -> R.string.dialer_role_not_granted
}
