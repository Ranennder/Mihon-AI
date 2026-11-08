package eu.kanade.tachiyomi.data.historysync

import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class GoogleDriveHistorySyncTransportTest {
    private val device = "00000000-0000-4000-8000-000000000001"
    private val other = "00000000-0000-4000-8000-000000000002"
    private val document = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, device, emptyList())
    private val bytes = HistorySyncProtocol.encode(document)
    private val clients = mutableListOf<OkHttpClient>()

    @AfterEach
    fun close() = clients.forEach {
        it.dispatcher.executorService.shutdown()
        it.connectionPool.evictAll()
    }

    @Test
    fun `all pages validate before media downloads and bearer stays per request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            when {
                request.url.queryParameter("alt") == "media" -> response(request, 200, bytes.toString(Charsets.UTF_8))
                request.url.queryParameter("pageToken") == null ->
                    response(request, 200, listing(file(), next = "page-two"))
                else -> response(request, 200, listing())
            }
        }
        assertEquals(listOf(document), GoogleDriveHistorySyncTransport(Tokens(), client).pull())
        assertEquals(3, requests.size)
        assertEquals("page-two", requests[1].url.queryParameter("pageToken"))
        assertEquals("media", requests[2].url.queryParameter("alt"))
        assertTrue(requests.all { it.header("Authorization") == "Bearer initial" && it.header("Cookie") == null })
        assertEquals("appDataFolder", requests[0].url.queryParameter("spaces"))
        assertTrue(requests[0].url.queryParameter("q")!!.contains("trashed = false"))
    }

    @Test
    fun `malformed second page never downloads or writes a partial result`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            response(
                request,
                200,
                if (request.url.queryParameter("pageToken") == null) listing(file(), next = "two") else "{}",
            )
        }
        assertThrows(Exception::class.java) {
            runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.url.queryParameter("alt") == null })
    }

    @Test
    fun `duplicate devices or file IDs across pages abort the entire listing`() = runBlocking {
        for (second in listOf(file(id = "second"), file(device = other))) {
            var count = 0
            val client = client { request ->
                count++
                response(request, 200, if (count == 1) listing(file(), next = "two") else listing(second))
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
            }
            assertEquals(2, count)
        }
    }

    @Test
    fun `malformed metadata incomplete listing and page loops are rejected`() = runBlocking {
        val invalid = listOf(
            listing(file(name = "foreign.json")),
            listing(file(device = device.replace("00000000", "AAAAAAAA"))),
            listing(file(size = "-1")),
            listing(file(size = "8388609")),
            listing(file(format = "other")),
            listing(file(id = "../foreign")),
            "{\"files\":[],\"incompleteSearch\":true}",
            "{\"files\":[],\"nextPageToken\":\"\"}",
            listing(next = "loop"),
        )
        for (json in invalid) {
            var count = 0
            val client = client { request ->
                count++
                response(request, 200, json)
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
            }
            assertTrue(count <= 2)
        }
    }

    @Test
    fun `replica count and aggregate size are bounded before media requests`() = runBlocking {
        for (count in listOf(5, 65)) {
            val files = (1..count).map {
                val writer = "00000000-0000-4000-8000-%012d".format(it)
                file(id = "id$it", device = writer, size = if (count == 5) "8388608" else "1")
            }
            var requests = 0
            val client = client { request ->
                requests++
                response(request, 200, listing(*files.toTypedArray()))
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
            }
            assertEquals(1, requests)
        }
    }

    @Test
    fun `document writer and downloaded size must match the listed metadata`() = runBlocking {
        val payloads = listOf(
            HistorySyncProtocol.encode(document.copy(deviceId = other)),
            bytes + byteArrayOf(32),
        )
        for (payload in payloads) {
            val client = client { request ->
                response(
                    request,
                    200,
                    if (request.url.queryParameter("alt") == null) {
                        listing(file())
                    } else {
                        payload.toString(Charsets.UTF_8)
                    },
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
            }
        }
    }

    @Test
    fun `own file is relisted before patch and another device is never patched`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            response(request, 200, if (request.method == "GET") listing(file(), file("other", other)) else "{}")
        }
        GoogleDriveHistorySyncTransport(Tokens(), client).push(document)
        assertEquals(listOf("GET", "PATCH"), requests.map { it.method })
        assertEquals("file", requests.last().url.pathSegments.last())
        assertEquals("media", requests.last().url.queryParameter("uploadType"))
        assertEquals(bytes.toString(Charsets.UTF_8), Buffer().also { requests.last().body!!.writeTo(it) }.readUtf8())
    }

    @Test
    fun `missing own file creates multipart appData metadata without modifying other replica`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            response(request, 200, if (request.method == "GET") listing(file("other", other)) else "{}")
        }
        GoogleDriveHistorySyncTransport(Tokens(), client).push(document)
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
        val upload = requests.last()
        assertEquals("multipart", upload.url.queryParameter("uploadType"))
        val multipart = Buffer().also { upload.body!!.writeTo(it) }.readUtf8()
        assertTrue(multipart.contains("\"parents\":[\"appDataFolder\"]"))
        assertTrue(multipart.contains(GoogleDriveHistorySyncTransport.filename(device)))
        assertTrue(multipart.contains("\"historySyncDeviceId\":\"$device\""))
        assertTrue(multipart.contains(bytes.toString(Charsets.UTF_8)))
    }

    @Test
    fun `duplicates during push relisting prevent every upload`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client { request ->
            requests += request
            response(request, 200, listing(file(), file("duplicate")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).push(document) }
        }
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test
    fun `create permits exactly thirty two MiB and rejects one byte above before upload`() = runBlocking {
        for (extra in listOf(0, 1)) {
            val requests = mutableListOf<Request>()
            val files = aggregateFiles(bytes.size, extra)
            val client = client { request ->
                requests += request
                response(request, 200, if (request.method == "GET") listing(*files.toTypedArray()) else "{}")
            }
            val transport = GoogleDriveHistorySyncTransport(Tokens(), client)
            if (extra == 0) {
                transport.push(document)
                assertEquals(listOf("GET", "POST"), requests.map { it.method })
            } else {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { transport.push(document) } }
                assertEquals(listOf("GET"), requests.map { it.method })
            }
        }
    }

    @Test
    fun `patch subtracts prior own size and enforces the resulting aggregate boundary`() = runBlocking {
        val identity = HistorySyncIdentity("https://example.com", "/manga", "/chapter")
        val updated = document.copy(
            records = listOf(
                HistorySyncRecord(
                    id = identity.recordId(),
                    identity = identity,
                    progress = HistorySyncProgress(1, 10, 1, device),
                    completion = HistorySyncCompletion(false, 1, device),
                    history = HistorySyncHistory(1, 1, device),
                ),
            ),
        )
        val growth = HistorySyncProtocol.encode(updated).size - bytes.size
        assertTrue(growth > 0)
        for (extra in listOf(0, 1)) {
            val requests = mutableListOf<Request>()
            val files = aggregateFiles(growth + bytes.size, extra) + file()
            val client = client { request ->
                requests += request
                response(request, 200, if (request.method == "GET") listing(*files.toTypedArray()) else "{}")
            }
            val transport = GoogleDriveHistorySyncTransport(Tokens(), client)
            if (extra == 0) {
                transport.push(updated)
                assertEquals(listOf("GET", "PATCH"), requests.map { it.method })
            } else {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { transport.push(updated) } }
                assertEquals(listOf("GET"), requests.map { it.method })
            }
        }
    }

    @Test
    fun `unauthorized token is invalidated and request retries exactly once`() = runBlocking {
        val headers = mutableListOf<String?>()
        val tokens = Tokens()
        val client = client { request ->
            headers += request.header("Authorization")
            response(request, if (headers.size == 1) 401 else 200, listing())
        }
        assertEquals(emptyList<HistorySyncDocument>(), GoogleDriveHistorySyncTransport(tokens, client).pull())
        assertEquals(listOf("Bearer initial", "Bearer renewed"), headers)
        assertEquals(listOf("initial"), tokens.invalidated)
        var requests = 0
        val denied = client { request ->
            requests++
            response(request, 401, "private error body")
        }
        assertThrows(GoogleDriveReconnectRequired::class.java) {
            runBlocking { GoogleDriveHistorySyncTransport(Tokens(), denied).pull() }
        }
        assertEquals(2, requests)
    }

    @Test
    fun `redirect and remote error body cannot leak credentials or cause another request`() = runBlocking {
        for (code in listOf(302, 403, 500)) {
            var requests = 0
            val client = client { request ->
                requests++
                response(request, code, "secret-token private@example.com").newBuilder()
                    .header("Location", "https://foreign.example/private").build()
            }
            val error = assertThrows(IOException::class.java) {
                runBlocking { GoogleDriveHistorySyncTransport(Tokens(), client).pull() }
            }
            assertEquals(1, requests)
            assertFalse(error.message.orEmpty().contains("secret-token"))
            assertFalse(error.message.orEmpty().contains("private@example.com"))
        }
    }

    @Test
    fun `Drive identity reads immutable permission ID using only bearer authorization`() = runBlocking {
        var request: Request? = null
        val client = client {
            request = it
            response(it, 200, "{\"user\":{\"permissionId\":\"123456\",\"emailAddress\":\"reader@example.com\"}}")
        }
        assertEquals("123456", GoogleDriveHistorySyncTransport.account(Tokens(), client).permissionId)
        assertEquals("/drive/v3/about", request!!.url.encodedPath)
        assertEquals("user(permissionId,emailAddress,displayName)", request!!.url.queryParameter("fields"))
    }

    private fun client(handler: (Request) -> Response): OkHttpClient =
        WebDavHistorySyncTransport.defaultClient().newBuilder()
            .dispatcher(Dispatcher()).connectionPool(ConnectionPool())
            .addInterceptor { handler(it.request()) }.build().also { clients += it }

    private fun response(request: Request, code: Int, text: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(code)
        .body(text.toResponseBody("application/json".toMediaType())).build()

    private fun file(
        id: String = "file",
        device: String = this.device,
        size: String = bytes.size.toString(),
        name: String = GoogleDriveHistorySyncTransport.filename(device),
        format: String = GoogleDriveHistorySyncTransport.FORMAT,
    ): String = "{\"id\":\"$id\",\"name\":\"$name\",\"size\":\"$size\"," +
        "\"appProperties\":{\"historySyncFormat\":\"$format\",\"historySyncDeviceId\":\"$device\"}}"

    private fun listing(vararg files: String, next: String? = null): String =
        "{\"files\":[${files.joinToString(",")}]${next?.let { ",\"nextPageToken\":\"$it\"" }.orEmpty()}}"

    // Metadata fills the cloud budget without allocating or downloading large replicas.
    private fun aggregateFiles(reservedBytes: Int, extraBytes: Int): List<String> = (2..5).map { index ->
        file(
            id = "foreign$index",
            device = "00000000-0000-4000-8000-%012d".format(index),
            size = (HistorySyncProtocol.MAX_BYTES - if (index == 5) reservedBytes - extraBytes else 0).toString(),
        )
    }

    private class Tokens : GoogleDriveTokenSource {
        val invalidated = mutableListOf<String>()

        override suspend fun accessToken(): String = if (invalidated.isEmpty()) "initial" else "renewed"

        override suspend fun invalidate(token: String) {
            invalidated += token
        }
    }
}
