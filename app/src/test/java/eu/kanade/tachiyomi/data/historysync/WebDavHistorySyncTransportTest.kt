package eu.kanade.tachiyomi.data.historysync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections

class WebDavHistorySyncTransportTest {
    private val device = "00000000-0000-4000-8000-000000000001"
    private val account = HistorySyncCredentials("https://dav.example/account/root/", "login", "secret-app-password")
    private val folder = "https://dav.example/account/root/aidoku-mihon-history-v1/".toHttpUrl()
    private val document = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, device, emptyList())

    private fun response(request: Request, code: Int, text: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(code)
        .body(text.toResponseBody("application/json".toMediaType())).build()

    @Test
    fun `folder listing cannot send credentials to foreign or nested hrefs`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                seen += request
                when (request.method) {
                    "MKCOL" -> response(request, 405, "")
                    "PROPFIND" -> response(
                        request,
                        207,
                        """
                    <d:multistatus xmlns:d="DAV:">
                    <d:response><d:href>${folder}device-$device.json</d:href></d:response>
                    <d:response><d:href>https://evil.example/device-$device.json</d:href></d:response>
                    <d:response><d:href>${folder}nested/device-$device.json</d:href></d:response>
                    <d:response><d:href>${folder}device-$device.json?token=secret</d:href></d:response>
                    <d:response><d:href>$folder%64evice-$device.json</d:href></d:response>
                    </d:multistatus>
                        """.trimIndent(),
                    )
                    "GET" -> response(request, 200, HistorySyncProtocol.encode(document).toString(Charsets.UTF_8))
                    "PUT" -> response(request, 201, "")
                    else -> error("unexpected request")
                }
            },
        ).build()
        val transport = WebDavHistorySyncTransport(account, client)
        assertEquals(listOf(document), transport.pull())
        transport.push(document)
        assertEquals(listOf("MKCOL", "PROPFIND", "GET", "PUT"), seen.map { it.method })
        assertEquals("1", seen[1].header("Depth"))
        assertTrue(seen.all { it.url.host == "dav.example" && it.url.encodedPath.startsWith(folder.encodedPath) })
        assertEquals(
            Credentials.basic(account.username, account.password, Charsets.UTF_8),
            seen.last().header("Authorization"),
        )
        assertTrue(seen.all { it.header("Cookie") == null })
        assertEquals("device-$device.json", seen.last().url.pathSegments.last())
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `redirect is not followed and its response body cannot leak into status`() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor { chain ->
            seen += chain.request()
            response(chain.request(), 302, "secret-app-password https://evil.example/private").newBuilder()
                .header("Location", "https://evil.example/").build()
        }.build()
        val error =
            assertThrows(IOException::class.java) { runBlocking { WebDavHistorySyncTransport(account, client).pull() } }
        assertEquals(1, seen.size)
        assertFalse(error.message.orEmpty().contains("secret-app-password"))
        assertFalse(error.message.orEmpty().contains("evil.example"))
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `invalid Basic credentials are rejected before any request`() {
        val seen = mutableListOf<Request>()
        val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor { chain ->
            seen += chain.request()
            response(chain.request(), 204, "")
        }.build()
        val invalid = listOf(
            account.copy(username = ""), account.copy(username = "reader:other"),
            account.copy(username = "reader\n"), account.copy(password = ""), account.copy(password = "secret\r"),
        )
        for (credentials in invalid) {
            assertThrows(IllegalArgumentException::class.java) { WebDavHistorySyncTransport(credentials, client) }
        }
        assertTrue(seen.isEmpty())
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `invalid filename writer mismatch aborts pull without any PUT`() = runBlocking {
        val methods = mutableListOf<String>()
        val other = document.copy(deviceId = "00000000-0000-4000-8000-000000000002")
        val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            methods += request.method
            when (request.method) {
                "MKCOL" -> response(request, 201, "")
                "PROPFIND" -> response(
                    request,
                    207,
                    "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>device-$device.json</d:href></d:response></d:multistatus>",
                )
                else -> response(request, 200, HistorySyncProtocol.encode(other).toString(Charsets.UTF_8))
            }
        }.build()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { WebDavHistorySyncTransport(account, client).pull() }
        }
        assertEquals(listOf("MKCOL", "PROPFIND", "GET"), methods)
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `XML DTD and response limits reject before untrusted data is consumed`() {
        val dtd = "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///private'>]>" +
            "<d:multistatus xmlns:d=\"DAV:\"><d:href>&secret;</d:href></d:multistatus>"
        assertThrows(Exception::class.java) { WebDavHistorySyncTransport.replicaNames(dtd.toByteArray(), folder) }
        val wrongRoot = "<d:collection xmlns:d=\"DAV:\"><d:response>" +
            "<d:href>device-$device.json</d:href></d:response></d:collection>"
        assertThrows(Exception::class.java) { WebDavHistorySyncTransport.replicaNames(wrongRoot.toByteArray(), folder) }
        val nested = "<d:multistatus xmlns:d=\"DAV:\"><d:other><d:response>" +
            "<d:href>device-$device.json</d:href></d:response></d:other></d:multistatus>"
        assertTrue(WebDavHistorySyncTransport.replicaNames(nested.toByteArray(), folder).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            WebDavHistorySyncTransport.readBounded(ByteArrayInputStream(ByteArray(11)), 10)
        }
        assertEquals(10, WebDavHistorySyncTransport.readBounded(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertThrows(IllegalArgumentException::class.java) {
            WebDavHistorySyncTransport.validateRoot("http://dav.example/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavHistorySyncTransport.validateRoot("https://user:password@dav.example/")
        }
    }

    @Test
    fun `partial multistatus cannot become an empty successful pull`() = runBlocking {
        val responses = listOf(
            "<d:href>device-$device.json</d:href><d:status>HTTP/1.1 403 Forbidden</d:status>",
            "<d:href>device-$device.json</d:href><d:propstat><d:prop/>" +
                "<d:status>HTTP/1.1 500 Failed</d:status></d:propstat>",
            "<d:status>HTTP/1.1 200 OK</d:status>",
            "<d:href>device-$device.json</d:href><d:href>device-$device.json</d:href>",
        )
        for (partial in responses) {
            val methods = mutableListOf<String>()
            val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                methods += request.method
                if (request.method == "MKCOL") {
                    response(request, 405, "")
                } else {
                    response(
                        request, 207,
                        "<d:multistatus xmlns:d='DAV:'><d:response>$partial</d:response></d:multistatus>",
                    )
                }
            }.build()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { WebDavHistorySyncTransport(account, client).pull() }
            }
            assertEquals(listOf("MKCOL", "PROPFIND"), methods)
            client.dispatcher.executorService.shutdown()
        }
        val successful = "<d:multistatus xmlns:d='DAV:'><d:response>" +
            "<d:href>device-$device.json</d:href><d:propstat><d:prop/>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
        assertEquals(
            listOf("device-$device.json"),
            WebDavHistorySyncTransport.replicaNames(successful.toByteArray(), folder),
        )
    }

    @Test
    fun `canceling a sync cancels its underlying HTTP call`() = runBlocking {
        val started = CompletableDeferred<okhttp3.Call>()
        val client = WebDavHistorySyncTransport.defaultClient().newBuilder().addInterceptor { chain ->
            val call = chain.call()
            started.complete(call)
            val deadline = System.nanoTime() + 5_000_000_000
            while (!call.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            throw IOException("canceled fixture")
        }.build()
        val job = launch { WebDavHistorySyncTransport(account, client).pull() }
        val call = withTimeout(3000) { started.await() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        client.dispatcher.executorService.shutdown()
    }
}
