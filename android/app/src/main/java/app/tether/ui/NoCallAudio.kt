package app.tether.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tether.R
import app.tether.TetherApp

/**
 * The Android phone's own notification while a bridged call has no call audio because the helper cannot start
 * without Wi-Fi (Hub.AudioSession.checkNoCallAudio; status `helper.noCallAudio`). Galaxy A54, 2026-11-28: after a USB
 * unplug restarted adbd, three calls had no audio and nothing said why.
 */
object NoCallAudio {
    const val NOTIF_ID = 32

    fun show(ctx: Context, on: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!on) { nm.cancel(NOTIF_ID); return }
        val text = ctx.getString(R.string.notif_no_call_audio_text)
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, TetherApp.CH_SETUP)
            .setSmallIcon(R.drawable.ic_tether)
            .setColor(ctx.getColor(R.color.warn))
            .setContentTitle(ctx.getString(R.string.notif_no_call_audio_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}
