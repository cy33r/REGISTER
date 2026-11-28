package app.tether.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Small HTTPS reads (release metadata, SHA256SUMS) from GitHub only; redirects are followed by hand to known hosts. */
internal object Net {
    private val HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com")

    fun text(address: String, accept: String, maxBytes: Int = 1024 * 1024): String {
        var url = URL(address)
        repeat(5) {
            if (url.protocol != "https" || url.host !in HOSTS || url.port !in setOf(-1, 443) || url.userInfo != null) {
                throw IOException("unexpected host ${url.host}")
            }
            val c = url.openConnection() as HttpsURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Tether-Android")
                c.setRequestProperty("Accept", accept)
                when (val status = c.responseCode) {
                    200 -> return c.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) throw IOException("answer too large")
                            out.write(buf, 0, n)
                        }
                        out.toString("UTF-8")
                    }
                    301, 302, 303, 307, 308 -> url = URL(url, c.getHeaderField("Location") ?: throw IOException("redirect without Location"))
                    else -> throw IOException("HTTP $status")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
