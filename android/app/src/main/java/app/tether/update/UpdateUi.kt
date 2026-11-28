package app.tether.update

import app.tether.R
import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/**
 * The words of the Help card's update row, the same in both apps: each app draws them with its own label and button
 * views (they differ), and hands the button's tap to [onTap].
 */
object UpdateUi {
    /** The line under the buttons: where things stand, or null when there is nothing to say. */
    fun status(a: Activity, u: Updater): String? = when (val s = u.state) {
        Updater.State.Idle -> null
        Updater.State.Checking -> a.getString(R.string.update_checking)
        Updater.State.UpToDate -> a.getString(R.string.update_up_to_date, installedVersion(a))
        is Updater.State.Available -> a.getString(R.string.update_available, s.release.version, installedVersion(a))
        is Updater.State.Downloading -> a.getString(R.string.update_downloading, s.release.version, s.percent)
        is Updater.State.Verifying -> a.getString(R.string.update_verifying)
        is Updater.State.Ready -> a.getString(R.string.update_ready, s.release.version)
        is Updater.State.Installing -> a.getString(R.string.update_installing)
        is Updater.State.Failed -> a.getString(when (s.kind) {
            UpdateError.Kind.NETWORK -> R.string.update_err_network
            UpdateError.Kind.NO_APK -> R.string.update_err_no_apk
            UpdateError.Kind.DOWNLOAD -> R.string.update_err_download
            UpdateError.Kind.CHECKSUM -> R.string.update_err_checksum
            UpdateError.Kind.NOT_THIS_APP, UpdateError.Kind.NOT_NEWER -> R.string.update_err_not_this_app
            UpdateError.Kind.OTHER_KEY -> R.string.update_err_other_key
            UpdateError.Kind.INSTALL -> R.string.update_err_install
        })
    }

    /** The button's text, or null while something runs (the status line says what). */
    fun button(a: Activity, u: Updater): String? = when (val s = u.state) {
        Updater.State.Idle, Updater.State.UpToDate, is Updater.State.Failed -> a.getString(R.string.update_check)
        is Updater.State.Available -> a.getString(R.string.update_to, s.release.version)
        is Updater.State.Ready -> a.getString(R.string.update_install)
        else -> null
    }

    /** The second, smaller button: turn the daily check on or off. */
    fun dailyButton(a: Activity, u: Updater): String =
        a.getString(if (u.daily) R.string.update_daily_off else R.string.update_daily_on)

    /** [inCall]: a call is up on this phone; installing would end it. */
    fun onTap(a: Activity, u: Updater, inCall: Boolean) {
        when (u.tap(a, inCall)) {
            Updater.Tap.DONE -> Unit
            Updater.Tap.IN_CALL -> Toast.makeText(a, R.string.update_wait_for_call, Toast.LENGTH_LONG).show()
            Updater.Tap.NEED_INSTALL_PERMISSION -> AlertDialog.Builder(a)
                .setTitle(R.string.update_install)
                .setMessage(R.string.update_permission)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    runCatching { a.startActivity(u.installPermissionIntent()) }
                }.show()
        }
    }

    private fun installedVersion(a: Activity): String =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
}
