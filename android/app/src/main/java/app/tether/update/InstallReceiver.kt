package app.tether.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** The package installer's answer to an update session. Not exported: only our own PendingIntent reaches it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's own "Do you want to update this app?" screen; the user decides there.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) Updater.current()?.installFinished(PackageInstaller.STATUS_FAILURE)
                else runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.current()?.installFinished(PackageInstaller.STATUS_FAILURE) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.current()?.installFinished(status)
        }
    }
}
