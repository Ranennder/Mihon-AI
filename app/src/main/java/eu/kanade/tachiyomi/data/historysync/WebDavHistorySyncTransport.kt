package eu.kanade.tachiyomi.data.historysync

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.ensureActive
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class WebDavHistorySyncTransport(
    private val credentials: HistorySyncCredentials,
    client: OkHttpClient = defaultClient(),
) : HistorySyncTransport {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()
    private val root = validateRoot(credentials.root)
    private val folder = root.newBuilder().addPathSegment(FOLDER).addPathSegment("").build()
    private val authorization = credentials.run {
        require(
            username.isNotBlank() && ':' !in username && username.none(Char::isISOControl) &&
                password.isNotEmpty() && password.none(Char::isISOControl),
        ) { "Enter a valid WebDAV login and app password" }
        Credentials.basic(username, password, Charsets.UTF_8)
    }

    override suspend fun pull(): List<HistorySyncDocument> {
        request(folder, "MKCOL", ByteArray(0), "application/octet-stream").use {
            checkStatus(it.code, setOf(201, 405))
        }
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent()
        val bytes = request(folder, "PROPFIND", xml.toByteArray(), "application/xml", depth = "1").use {
            checkStatus(it.code, setOf(207))
            readBounded(it.body.byteStream(), MAX_XML_BYTES)
        }
        val names = replicaNames(bytes, folder)
        val documents = ArrayList<HistorySyncDocument>()
        var totalBytes = 0L
        for (name in names) {
            coroutineContext.ensureActive()
            val document = request(folder.newBuilder().addPathSegment(name).build(), "GET").use {
                checkStatus(it.code, setOf(200))
                val replica = readBounded(it.body.byteStream(), HistorySyncProtocol.MAX_BYTES)
                totalBytes += replica.size
                require(totalBytes <= MAX_PULL_BYTES) { "History replicas exceed the aggregate download limit" }
                HistorySyncProtocol.decode(replica)
            }
            require(name == "device-${document.deviceId}.json") { "Replica filename does not match its device ID" }
            documents += document
        }
        return documents
    }

    override suspend fun push(document: HistorySyncDocument) {
        val name = "device-${document.deviceId}.json"
        request(
            folder.newBuilder().addPathSegment(name).build(),
            "PUT",
            HistorySyncProtocol.encode(document),
            "application/json",
        ).use {
            checkStatus(it.code, setOf(200, 201, 204))
        }
    }

    private suspend fun request(
        url: HttpUrl,
        method: String,
        data: ByteArray? = null,
        type: String = "application/json",
        depth: String? = null,
    ): okhttp3.Response {
        require(url.scheme == "https" && sameOrigin(root, url))
        val builder = Request.Builder().url(url).header("Authorization", authorization)
            .header("Accept", "application/json, application/xml").header("Cache-Control", "no-cache")
        depth?.let { builder.header("Depth", it) }
        return client.newCall(builder.method(method, data?.toRequestBody(type.toMediaType())).build()).await()
    }

    private fun checkStatus(code: Int, accepted: Set<Int>) {
        if (code !in accepted) {
            throw IOException(
                when (code) {
                    401, 403 -> "WebDAV authentication failed; check your login and app password"
                    507 -> "Cloud storage is full"
                    in 300..399 -> "WebDAV redirected the request; enter the final HTTPS root URL"
                    else -> "WebDAV request failed (HTTP $code)"
                },
            )
        }
    }

    companion object {
        const val FOLDER = "aidoku-mihon-history-v1"
        const val MAX_REPLICAS = 64
        const val MAX_XML_BYTES = 1024 * 1024
        const val MAX_PULL_BYTES = 32L * 1024 * 1024
        private val filename = Regex("device-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")

        fun validateRoot(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull() ?: error("Enter a valid HTTPS WebDAV root")
            require(
                url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
                    url.fragment == null,
            ) {
                "WebDAV requires HTTPS without credentials in the URL, query, or fragment"
            }
            return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .build()

        fun replicaNames(xml: ByteArray, folder: HttpUrl): List<String> {
            require(xml.size <= MAX_XML_BYTES)
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            // Some compatible parsers do not expose this optional feature;
            // unsupported features already report false by the XML Pull API.
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            require(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) { "Unsafe WebDAV XML parser" }
            parser.setInput(ByteArrayInputStream(xml), "UTF-8")
            val names = linkedSetOf<String>()
            var hasRoot = false
            var responseDepth: Int? = null
            var responseHrefs = mutableListOf<String>()
            var responseStatuses = mutableListOf<String>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                require(event != XmlPullParser.DOCDECL) { "WebDAV XML must not contain a DTD" }
                if (event == XmlPullParser.START_TAG && parser.depth == 1) {
                    require(!hasRoot && parser.namespace == "DAV:" && parser.name == "multistatus") {
                        "Invalid WebDAV multistatus response"
                    }
                    hasRoot = true
                }
                if (event == XmlPullParser.START_TAG && parser.depth == 2 && parser.namespace == "DAV:" &&
                    parser.name == "response"
                ) {
                    responseDepth = parser.depth
                    responseHrefs = mutableListOf()
                    responseStatuses = mutableListOf()
                }
                if (event == XmlPullParser.START_TAG && parser.depth == 3 && responseDepth == 2 &&
                    parser.namespace == "DAV:" && parser.name == "href"
                ) {
                    responseHrefs += parser.nextText().trim()
                }
                if (event == XmlPullParser.START_TAG && responseDepth == 2 &&
                    parser.depth in 3..4 && parser.namespace == "DAV:" && parser.name == "status"
                ) {
                    responseStatuses += parser.nextText().trim()
                }
                if (event == XmlPullParser.END_TAG && parser.depth == responseDepth) {
                    require(responseHrefs.size == 1 && responseHrefs.single().isNotEmpty()) {
                        "Incomplete WebDAV response"
                    }
                    require(responseStatuses.all { status ->
                        val parts = status.split(Regex("[ \\t]+"))
                        parts.size >= 2 && Regex("HTTP/[0-9]+(?:\\.[0-9]+)?").matches(parts[0]) &&
                            parts[1].length == 3 && parts[1].toIntOrNull()?.let { it in 200..299 } == true
                    }) { "WebDAV returned a partial listing" }
                    val url = folder.resolve(responseHrefs.single())
                    if (url != null && sameOrigin(folder, url) && url.username.isEmpty() && url.password.isEmpty() &&
                        url.query == null && url.fragment == null &&
                        url.pathSegments.size == folder.pathSegments.size &&
                        url.encodedPath.substringBeforeLast('/') == folder.encodedPath.trimEnd('/')
                    ) {
                        val name = url.pathSegments.last()
                        if (filename.matches(name) && url.encodedPath.endsWith("/$name")) names += name
                    }
                    responseDepth = null
                }
                event = parser.nextToken()
            }
            require(hasRoot) { "Invalid WebDAV multistatus response" }
            require(names.size <= MAX_REPLICAS) { "Too many history sync devices" }
            return names.sorted()
        }

        fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maximum, 16_384))
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size().toLong() + count <= maximum) { "Cloud response exceeds its allowed size" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
            left.scheme == right.scheme && left.host == right.host && left.port == right.port
    }
}
