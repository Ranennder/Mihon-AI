package eu.kanade.tachiyomi.ui.reader.upscale

import okhttp3.Cookie
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DirectDownloadHeadersTest {
    private val jarCookies = listOf(Cookie.Builder().name("session").value("global").domain("example.com").build())

    @Test
    fun `explicit source cookies and user agent survive direct transfer`() {
        val request = Request.Builder().url("https://example.com/page.png")
            .header("Cookie", "session=source")
            .header("User-Agent", "Source Agent")
            .header("Referer", "https://example.com/chapter")
            .build()
        val headers = directDownloadHeaders(request, jarCookies, "Default Agent")
        assertEquals("session=source", headers["Cookie"])
        assertEquals("Source Agent", headers["User-Agent"])
        assertEquals("https://example.com/chapter", headers["Referer"])
    }

    @Test
    fun `missing session headers use matching cookies and default agent`() {
        val request = Request.Builder().url("https://example.com/page.png").build()
        val headers = directDownloadHeaders(request, jarCookies, "Default Agent")
        assertEquals("session=global", headers["Cookie"])
        assertEquals("Default Agent", headers["User-Agent"])
    }

    @Test
    fun `transport compression and host headers are not forwarded`() {
        val request = Request.Builder().url("https://example.com/page.png")
            .header("Accept-Encoding", "gzip, br")
            .header("Host", "obsolete.example")
            .header("Connection", "keep-alive")
            .build()
        val headers = directDownloadHeaders(request, emptyList(), "Agent")
        assertEquals("identity", headers["Accept-Encoding"])
        assertFalse(headers.containsKey("Host"))
        assertFalse(headers.containsKey("Connection"))
        assertFalse(headers.containsKey("Cookie"))
    }
}
