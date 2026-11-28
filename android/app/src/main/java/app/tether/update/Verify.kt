package app.tether.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** The checks a downloaded APK passes before it goes to the package installer. Each failure names its reason. */
internal object Verify {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The APK is this app, newer than the installed one, the version the release says, and signed with the same key
     * as the installed app (the project's release key). Android would refuse another key anyway, but only after the
     * user confirmed; this says why up front, and a copy built with someone's own key is never offered an update.
     */
    fun apk(ctx: Context, file: File, release: Release) {
        val pm = ctx.packageManager
        val apk = archiveInfo(pm, file) ?: throw UpdateError(UpdateError.Kind.NOT_THIS_APP)
        if (apk.packageName != ctx.packageName) throw UpdateError(UpdateError.Kind.NOT_THIS_APP)
        if (apk.versionName != release.version || code(apk) != release.versionCode) throw UpdateError(UpdateError.Kind.NOT_THIS_APP)
        if (code(apk) <= installedCode(ctx)) throw UpdateError(UpdateError.Kind.NOT_NEWER)
        val mine = signers(installedInfo(pm, ctx.packageName))
        val theirs = signers(apk)
        if (mine.isEmpty() || mine != theirs) throw UpdateError(UpdateError.Kind.OTHER_KEY)
    }

    fun installedCode(ctx: Context): Long = code(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    /** Both forms: some Android 9 to 12 builds leave signingInfo empty for an APK file, but fill in the old one. */
    @Suppress("DEPRECATION")
    private val SIGS = PackageManager.GET_SIGNATURES or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? = pm.getPackageArchiveInfo(file.path, SIGS)

    private fun installedInfo(pm: PackageManager, name: String): PackageInfo = pm.getPackageInfo(name, SIGS)

    /** SHA-256 of each current signing certificate (the newest one after a key rotation), as a set; empty = unknown. */
    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val certs: Array<Signature>? = (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else null)
            ?.takeIf { it.isNotEmpty() } ?: p.signatures
        return certs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
