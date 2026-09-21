package eu.kanade.tachiyomi.ui.reader.upscale

import okhttp3.Cookie
import okhttp3.Request

internal fun directDownloadHeaders(
    request: Request,
    cookies: List<Cookie>,
    defaultUserAgent: String,
): Map<String, String> = buildMap {
    request.headers.forEach { (name, value) ->
        if (name.lowercase() !in
            setOf("host", "accept-encoding", "content-length", "connection", "transfer-encoding")
        ) {
            put(name, value)
        }
    }
    if (request.header("Cookie") == null && cookies.isNotEmpty()) {
        put("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
    }
    if (request.header("User-Agent") == null && defaultUserAgent.isNotBlank()) {
        put("User-Agent", defaultUserAgent)
    }
    // urllib does not decode compressed HTTP responses automatically.
    put("Accept-Encoding", "identity")
}
