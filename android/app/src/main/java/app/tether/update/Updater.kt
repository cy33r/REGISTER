package app.tether.update

import app.tether.R
import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Updates the app from the project's GitHub Releases (#19): Google Play's in-app updates only serve apps installed
 * from Play, and Tether is not there. Nothing runs unless the user asks (Help › Check for updates) or turned on the
 * daily check, which only runs when the app's screen opens. There is no server and no account: the app reads
 * `releases/latest`, DownloadManager fetches the APK, the APK must match the release's SHA256SUMS and the installed
 * app's signing key, and Android's package installer asks the user before it installs.
 *
 * One per process ([get]); its state outlives the screen, so leaving Help during a download is fine.
 */
class Updater private constructor(private val ctx: Context, private val apkPrefix: String) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Verifying(val release: Release) : State
        data class Ready(val release: Release, val file: File) : State
        data class Installing(val release: Release, val file: File) : State
        data class Failed(val kind: UpdateError.Kind) : State
    }

    /** What a tap on the Help card's update button led to, for the screen to act on. */
    enum class Tap { DONE, NEED_INSTALL_PERMISSION, IN_CALL }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "app-update").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val prefs = ctx.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    @Volatile var state: State = State.Idle; private set

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** The daily check: off unless the user turns it on. */
    var daily: Boolean
        get() = prefs.getBoolean(KEY_DAILY, false)
        set(on) { prefs.edit().putBoolean(KEY_DAILY, on).apply(); notifyChanged() }

    /** Called when the app's main screen opens: checks if the daily check is on and the last check is a day old. */
    fun maybeCheckDaily() {
        if (!daily || state != State.Idle) return
        val last = prefs.getLong(KEY_LAST, 0)
        if (System.currentTimeMillis() - last < DAY_MS) return
        check(quiet = true)
    }

    /** The Help card's one update button: check, download, or install, depending on where things stand. */
    fun tap(activity: Activity, inCall: Boolean): Tap = when (val s = state) {
        is State.Available -> { download(s.release); Tap.DONE }
        is State.Ready -> when {
            !s.file.isFile -> { check(); Tap.DONE }
            inCall -> Tap.IN_CALL   // installing replaces the running app and would end the call
            !activity.packageManager.canRequestPackageInstalls() -> Tap.NEED_INSTALL_PERMISSION
            else -> { install(s); Tap.DONE }
        }
        State.Idle, State.UpToDate, is State.Failed -> { check(); Tap.DONE }
        else -> Tap.DONE   // busy: checking, downloading, verifying, installing
    }

    /** Android's "Install unknown apps" page for this app, where the user allows Tether to install its updates. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun check(quiet: Boolean = false) {
        set(State.Checking)
        work.execute {
            val next = try {
                val json = JSONObject(Net.text(Release.LATEST_URL, "application/vnd.github+json"))
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                Release.fromLatest(json, apkPrefix, Verify.installedCode(ctx))?.let { State.Available(it) } ?: State.UpToDate
            } catch (e: UpdateError) {
                State.Failed(e.kind)
            } catch (_: Exception) {
                State.Failed(UpdateError.Kind.NETWORK)
            }
            // A daily check that could not reach GitHub says nothing; the next one tries again.
            set(if (quiet && next is State.Failed) State.Idle else if (quiet && next == State.UpToDate) State.Idle else next)
        }
    }

    private fun download(release: Release) {
        set(State.Downloading(release, 0))
        work.execute {
            set(try {
                val file = fetch(release)
                set(State.Verifying(release))
                if (file.length() != release.apkBytes) throw UpdateError(UpdateError.Kind.CHECKSUM)
                val sums = try { Net.text(release.sumsUrl, "application/octet-stream", 64 * 1024) }
                    catch (e: IOException) { throw UpdateError(UpdateError.Kind.NETWORK, e) }
                val expected = Release.sumFor(sums, release.apkName) ?: throw UpdateError(UpdateError.Kind.NO_APK)
                if (Verify.sha256(file) != expected) throw UpdateError(UpdateError.Kind.CHECKSUM)
                Verify.apk(ctx, file, release)
                State.Ready(release, file)
            } catch (e: UpdateError) {
                folder()?.listFiles()?.forEach { it.delete() }   // never keep a file that failed a check
                State.Failed(e.kind)
            } catch (_: Exception) {
                folder()?.listFiles()?.forEach { it.delete() }
                State.Failed(UpdateError.Kind.DOWNLOAD)
            })
        }
    }

    /** DownloadManager into the app's own external files (no storage permission); waits here, on the work thread. */
    private fun fetch(release: Release): File {
        val dir = folder() ?: throw UpdateError(UpdateError.Kind.DOWNLOAD)
        dir.listFiles()?.forEach { it.delete() }   // older updates, and so DownloadManager does not pick a "-1" name
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(ctx.getString(R.string.update_download_title, release.version))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, DIR, release.apkName))
        while (true) {
            Thread.sleep(1000)
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) throw UpdateError(UpdateError.Kind.DOWNLOAD)   // cancelled from the notification
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return File(dir, release.apkName).takeIf { it.isFile }
                        ?: throw UpdateError(UpdateError.Kind.DOWNLOAD)
                    DownloadManager.STATUS_FAILED -> { dm.remove(id); throw UpdateError(UpdateError.Kind.DOWNLOAD) }
                    else -> set(State.Downloading(release, (done * 100 / release.apkBytes).toInt().coerceIn(0, 99)))
                }
            }
        }
    }

    /** A package-installer session; InstallReceiver brings up Android's confirmation and reports the result. */
    private fun install(ready: State.Ready) {
        set(State.Installing(ready.release, ready.file))
        work.execute {
            try {
                val installer = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(ctx.packageName)
                    setSize(ready.file.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    // Mutable: the installer adds its status (and the confirmation screen) to this intent.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                    session.commit(result.intentSender)
                }
            } catch (_: Exception) {
                set(State.Failed(UpdateError.Kind.INSTALL))
            }
        }
    }

    /** From InstallReceiver. Success needs nothing: Android replaces this process with the new version. */
    internal fun installFinished(status: Int) {
        val s = state as? State.Installing ?: return
        set(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) State.Ready(s.release, s.file)   // the user said no
            else State.Failed(UpdateError.Kind.INSTALL))
    }

    private fun folder(): File? = ctx.getExternalFilesDir(DIR)?.takeIf {
        Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
    }

    private fun set(s: State) {
        state = s
        notifyChanged()
    }

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    companion object {
        private const val DIR = "updates"
        private const val KEY_DAILY = "daily"
        private const val KEY_LAST = "last_check"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile private var instance: Updater? = null

        /** [apkPrefix]: the release asset name before "-<version>.apk", "Tether-A54" or "Tether-Client". */
        fun get(ctx: Context, apkPrefix: String): Updater = instance ?: synchronized(this) {
            instance ?: Updater(ctx.applicationContext, apkPrefix).also { instance = it }
        }

        internal fun current(): Updater? = instance
    }
}
