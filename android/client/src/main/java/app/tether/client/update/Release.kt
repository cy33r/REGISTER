package app.tether.client.update

import org.json.JSONObject

/**
 * A newer stable release of the project and the one APK in it for this app. Pure parsing, no Android calls, so the
 * rules are unit tested: only the official repository, only stable tags, only the exact asset name (never a
 * `-debug-signed` build or the other app's APK), and only GitHub's own download address.
 */
class Release(val version: String, val versionCode: Long, val apkName: String, val apkUrl: String, val apkBytes: Long,
              val sumsUrl: String) {

    companion object {
        const val REPO = "cy33r/REGISTER"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val DOWNLOADS = "https://github.com/$REPO/releases/download"
        const val SUMS = "SHA256SUMS"
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private val STABLE_TAG = Regex("""v(\d{1,4})\.(\d{1,2})\.(\d{1,2})""")

        /**
         * The same versionCode as android/app/build.gradle.kts gives a stable VERSION: major*1000000 + minor*10000 +
         * patch*100 + 99 (a pre-release takes its number in place of 99, so a stable release updates its RCs).
         */
        fun stableCode(tag: String): Long? = STABLE_TAG.matchEntire(tag)?.destructured?.let { (ma, mi, pa) ->
            ma.toLong() * 1_000_000 + mi.toLong() * 10_000 + pa.toLong() * 100 + 99
        }

        /**
         * The release in GitHub's `releases/latest` answer when it is newer than [installedCode], null when this app is
         * up to date. Throws [UpdateError] (NO_APK) when the release is not a stable one with this app's APK and
         * SHA256SUMS: never guess another file.
         */
        fun fromLatest(latest: JSONObject, apkPrefix: String, installedCode: Long): Release? {
            val tag = latest.optString("tag_name")
            val code = stableCode(tag) ?: throw UpdateError(UpdateError.Kind.NO_APK)
            if (latest.optBoolean("draft") || latest.optBoolean("prerelease")) throw UpdateError(UpdateError.Kind.NO_APK)
            if (code <= installedCode) return null
            val version = tag.removePrefix("v")
            val apkName = "$apkPrefix-$version.apk"
            val assets = latest.optJSONArray("assets") ?: throw UpdateError(UpdateError.Kind.NO_APK)
            var apk: JSONObject? = null
            var sums: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                when (a.optString("name")) { apkName -> apk = a; SUMS -> sums = a }
            }
            val apkUrl = "$DOWNLOADS/$tag/$apkName"
            val sumsUrl = "$DOWNLOADS/$tag/$SUMS"
            val bytes = apk?.optLong("size", -1) ?: -1
            if (apk?.optString("browser_download_url") != apkUrl || sums?.optString("browser_download_url") != sumsUrl ||
                bytes !in 1..MAX_APK_BYTES) throw UpdateError(UpdateError.Kind.NO_APK)
            return Release(version, code, apkName, apkUrl, bytes, sumsUrl)
        }

        /** The lowercase hex SHA-256 that `sha256sum` wrote for [name] in a SHA256SUMS file, or null. */
        fun sumFor(sums: String, name: String): String? = sums.lineSequence().mapNotNull { line ->
            // "<64 hex><space><space or *><name>", as GNU sha256sum writes it in text or binary mode.
            Regex("""([0-9a-fA-F]{64}) [ *](.+)""").matchEntire(line.trimEnd('\r'))?.destructured
        }.firstOrNull { (_, file) -> file == name }?.component1()?.lowercase()
    }
}

/** Why a step failed, in the words the Help card shows (UpdateUi). */
class UpdateError(val kind: Kind, cause: Throwable? = null) : Exception(kind.name, cause) {
    enum class Kind { NETWORK, NO_APK, DOWNLOAD, CHECKSUM, NOT_THIS_APP, OTHER_KEY, NOT_NEWER, INSTALL }
}
