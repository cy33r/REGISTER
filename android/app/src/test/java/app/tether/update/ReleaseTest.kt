package app.tether.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The update rules: only the official repository, stable tags, this app's exact APK and SHA256SUMS (#19). */
class ReleaseTest {
    private val base = "https://github.com/cy33r/REGISTER/releases/download/v1.3.0"

    private fun asset(name: String, url: String = "$base/$name", size: Long = 12_000_000) =
        JSONObject().put("name", name).put("browser_download_url", url).put("size", size)

    private fun latest(tag: String = "v1.3.0", vararg assets: JSONObject = arrayOf(
        asset("REGISTER-Android-1.3.0.apk"), asset("REGISTER-Client-1.3.0.apk"), asset("SHA256SUMS", size = 400))) =
        JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false).put("assets", JSONArray(assets.toList()))

    @Test fun versionCodeMatchesTheBuildScript() {
        assertEquals(1_020_999L, Release.stableCode("v1.2.9"))
        assertEquals(1_021_099L, Release.stableCode("v1.2.10"))
        assertEquals(12_345_699L, Release.stableCode("v12.34.56"))
        assertNull(Release.stableCode("v1.3.0-rc.1"))
        assertNull(Release.stableCode("1.3.0"))
    }

    @Test fun picksThisAppsApkOnly() {
        val gateway = Release.fromLatest(latest(), "REGISTER-Android", 1_020_999)!!
        assertEquals("1.3.0", gateway.version)
        assertEquals(1_030_099L, gateway.versionCode)
        assertEquals("$base/REGISTER-Android-1.3.0.apk", gateway.apkUrl)
        assertEquals("$base/SHA256SUMS", gateway.sumsUrl)
        assertEquals("REGISTER-Client-1.3.0.apk", Release.fromLatest(latest(), "REGISTER-Client", 0)!!.apkName)
    }

    @Test fun upToDateOrNewerIsNoUpdate() {
        assertNull(Release.fromLatest(latest(), "REGISTER-Android", 1_030_099))
        assertNull(Release.fromLatest(latest(), "REGISTER-Android", 1_030_101))
    }

    @Test fun aStableReleaseUpdatesItsReleaseCandidate() {
        // 1.3.0-rc.2 has versionCode 1030002 (build.gradle.kts), below the release's 1030099.
        assertEquals("1.3.0", Release.fromLatest(latest(), "REGISTER-Android", 1_030_002)!!.version)
    }

    @Test fun refusesPrereleasesDraftsAndOtherTags() {
        for (json in listOf(latest().put("prerelease", true), latest().put("draft", true), latest(tag = "v1.3.0-rc.1"),
            latest(tag = "nightly"))) {
            assertThrows(UpdateError::class.java) { Release.fromLatest(json, "REGISTER-Android", 0) }
        }
    }

    @Test fun neverOffersADebugSignedBuildOrAFileFromElsewhere() {
        val sums = asset("SHA256SUMS", size = 400)
        for (apk in listOf(
            asset("REGISTER-Android-1.3.0-debug-signed.apk"),                                         // CI build without the release key
            asset("REGISTER-Android-1.3.0.apk", url = "https://example.com/REGISTER-Android-1.3.0.apk"),     // another host
            asset("REGISTER-Android-1.3.0.apk", url = base.replace("cy33r", "someone") + "/REGISTER-Android-1.3.0.apk"),   // a fork
            asset("REGISTER-Android-1.3.0.apk", size = 0),
        )) {
            assertThrows(UpdateError::class.java) { Release.fromLatest(latest("v1.3.0", apk, sums), "REGISTER-Android", 0) }
        }
        // No SHA256SUMS: nothing to check against, so no update.
        assertThrows(UpdateError::class.java) { Release.fromLatest(latest("v1.3.0", asset("REGISTER-Android-1.3.0.apk")), "REGISTER-Android", 0) }
    }

    @Test fun readsTheSumThatSha256sumWrote() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = "$a  REGISTER-iPhone-1.3.0-unsigned.ipa\r\n$b *REGISTER-Android-1.3.0.apk\n${"c".repeat(64)}  REGISTER-Client-1.3.0.apk\n"
        assertEquals("b".repeat(64), Release.sumFor(sums, "REGISTER-Android-1.3.0.apk"))
        assertEquals("c".repeat(64), Release.sumFor(sums, "REGISTER-Client-1.3.0.apk"))
        assertNull(Release.sumFor(sums, "REGISTER-Android-1.3.1.apk"))
        assertNull(Release.sumFor("${"d".repeat(63)}  REGISTER-Android-1.3.0.apk", "REGISTER-Android-1.3.0.apk"))
    }
}
