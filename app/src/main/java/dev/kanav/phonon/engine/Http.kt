package dev.kanav.phonon.engine

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS downloads from Hugging Face. Blocking: call off the main thread. */
object Http {
    class HttpException(val code: Int, message: String) : IOException(message)

    /**
     * Download [url] into [dest], through a `.part` file so a failed download leaves nothing
     * behind. [onProgress] gets bytes done and the total (or -1). [cancelled] is polled.
     */
    fun download(
        url: String,
        dest: File,
        token: String? = null,
        cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val part = File(dest.path + ".part")
        dest.parentFile?.mkdirs()
        val conn = open(url, token)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                    if (total >= 0 && done != total) throw IOException("download of ${dest.name} ended early")
                }
            }
            if (!part.renameTo(dest)) throw IOException("could not save ${dest.name}")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    fun text(url: String, token: String? = null): String {
        val conn = open(url, token)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, token: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "phonon-android")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw HttpException(
                code,
                when (code) {
                    401, 403 -> if (token.isNullOrBlank()) {
                        "this model is gated: accept its terms on Hugging Face and add a token"
                    } else {
                        "Hugging Face refused the token: check it, and that you accepted the model's terms"
                    }
                    404 -> "not found on Hugging Face ($url)"
                    else -> "HTTP $code from $url"
                },
            )
        }
        return conn
    }
}
