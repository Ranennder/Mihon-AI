package eu.kanade.tachiyomi.data.historysync

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

interface GoogleDriveTokenSource {
    suspend fun accessToken(): String
    suspend fun invalidate(token: String)
}

class GoogleDriveReconnectRequired : IOException("Reconnect your Google account")

/** Each installation writes only its own private appDataFolder replica. A full,
 * bounded listing is validated before downloading or applying any documents. */
class GoogleDriveHistorySyncTransport(
    private val tokens: GoogleDriveTokenSource,
    client: OkHttpClient = WebDavHistorySyncTransport.defaultClient(),
) : HistorySyncTransport {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun pull(): List<HistorySyncDocument> {
        val files = listReplicas()
        val documents = ArrayList<HistorySyncDocument>()
        var total = 0L
        for (file in files) {
            coroutineContext.ensureActive()
            val bytes = request(
                API.newBuilder().addPathSegment(file.id).addQueryParameter("alt", "media").build(),
            ).use { response ->
                checkStatus(response.code)
                readBounded(response.body.byteStream(), HistorySyncProtocol.MAX_BYTES)
            }
            require(bytes.size.toLong() == file.size.toLong()) { "History replica size changed during download" }
            total += bytes.size
            require(total <= MAX_PULL_BYTES) { "History replicas exceed the aggregate download limit" }
            val document = HistorySyncProtocol.decode(bytes)
            require(document.deviceId == file.appProperties.getValue(DEVICE_PROPERTY)) {
                "History replica writer does not match its metadata"
            }
            documents += document
        }
        return documents
    }

    override suspend fun push(document: HistorySyncDocument) {
        val bytes = HistorySyncProtocol.encode(document)
        // Relist even after a pull: a deleted or duplicate own file must never
        // turn into an overwrite of another device or an unbounded set of files.
        val files = listReplicas()
        val own = files.singleOrNull { it.appProperties[DEVICE_PROPERTY] == document.deviceId }
        val resultingBytes = files.sumOf { it.size.toLong() } - (own?.size?.toLong() ?: 0) + bytes.size
        require(resultingBytes <= MAX_PULL_BYTES) { "History replicas exceed the aggregate download limit" }
        val url: HttpUrl
        val body: RequestBody
        val method: String
        if (own != null) {
            url = UPLOAD.newBuilder().addPathSegment(own.id).addQueryParameter("uploadType", "media").build()
            body = bytes.toRequestBody(JSON)
            method = "PATCH"
        } else {
            require(files.size < MAX_REPLICAS) { "Too many history replicas" }
            val metadata = CreateFile(
                name = filename(document.deviceId),
                appProperties = mapOf(FORMAT_PROPERTY to FORMAT, DEVICE_PROPERTY to document.deviceId),
            )
            body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(HistorySyncProtocol.json.encodeToString(metadata).toRequestBody(JSON))
                .addPart(bytes.toRequestBody(JSON)).build()
            url = UPLOAD.newBuilder().addQueryParameter("uploadType", "multipart").build()
            method = "POST"
        }
        request(url, method, body).use { checkStatus(it.code, setOf(200, 201)) }
    }

    private suspend fun listReplicas(): List<DriveFile> {
        val files = ArrayList<DriveFile>()
        val ids = HashSet<String>()
        val devices = HashSet<String>()
        val pages = HashSet<String>()
        var page: String? = null
        var total = 0L
        do {
            coroutineContext.ensureActive()
            val url = API.newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter(
                    "q",
                    "trashed = false and appProperties has { key='$FORMAT_PROPERTY' and value='$FORMAT' }",
                )
                .addQueryParameter("fields", "files(id,name,size,appProperties),nextPageToken,incompleteSearch")
                .addQueryParameter("pageSize", "100")
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build()
            val listing = request(url).use { response ->
                checkStatus(response.code)
                HistorySyncProtocol.json.decodeFromString<FileList>(
                    readBounded(response.body.byteStream(), MAX_LIST_BYTES).toString(Charsets.UTF_8),
                )
            }
            require(!listing.incompleteSearch) { "Incomplete Google Drive listing" }
            for (file in listing.files) {
                val device = file.appProperties[DEVICE_PROPERTY].orEmpty()
                val size = file.size.toLongOrNull()
                require(
                    FILE_ID.matches(file.id) && ids.add(file.id) && HistorySyncProtocol.isDeviceId(device) &&
                        devices.add(device) && file.name == filename(device) &&
                        file.appProperties[FORMAT_PROPERTY] == FORMAT &&
                        file.size.matches(Regex("[0-9]{1,20}")) &&
                        size != null && size in 0..HistorySyncProtocol.MAX_BYTES,
                ) { "Invalid or duplicate Google Drive history replica" }
                total += size
                require(total <= MAX_PULL_BYTES) { "History replicas exceed the aggregate download limit" }
                files += file
                require(files.size <= MAX_REPLICAS) { "Too many history replicas" }
            }
            page = listing.nextPageToken
            require(
                page == null ||
                    (page.isNotEmpty() && page.length <= 4096 && pages.add(page) && pages.size < MAX_REPLICAS),
            ) {
                "Invalid Google Drive page token"
            }
        } while (page != null)
        return files
    }

    private suspend fun request(url: HttpUrl, method: String = "GET", body: RequestBody? = null): Response {
        require(url.scheme == "https" && url.host == "www.googleapis.com")
        repeat(2) { attempt ->
            val token = tokens.accessToken()
            require(token.isNotBlank() && token.none(Char::isISOControl)) { "Invalid Google authorization" }
            val response = client.newCall(
                Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .header("Accept", "application/json").header("Cache-Control", "no-cache")
                    .method(method, body).build(),
            ).await()
            if (response.code != 401) return response
            response.close()
            tokens.invalidate(token)
            if (attempt == 1) throw GoogleDriveReconnectRequired()
        }
        throw GoogleDriveReconnectRequired()
    }

    companion object {
        const val FORMAT = "aidoku-mihon-history-v1"
        const val FORMAT_PROPERTY = "historySyncFormat"
        const val DEVICE_PROPERTY = "historySyncDeviceId"
        const val MAX_REPLICAS = 64
        const val MAX_PULL_BYTES = 32L * 1024 * 1024
        private const val MAX_LIST_BYTES = 1024 * 1024
        private val API = "https://www.googleapis.com/drive/v3/files".toHttpUrl()
        private val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val FILE_ID = Regex("[A-Za-z0-9_-]{1,256}")

        fun filename(device: String): String = "$FORMAT-device-$device.json"

        suspend fun account(
            tokens: GoogleDriveTokenSource,
            client: OkHttpClient = WebDavHistorySyncTransport.defaultClient(),
        ): GoogleDriveAccount {
            val transport = GoogleDriveHistorySyncTransport(tokens, client)
            val url = "https://www.googleapis.com/drive/v3/about".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "user(permissionId,emailAddress,displayName)").build()
            val account = transport.request(url).use { response ->
                checkStatus(response.code)
                HistorySyncProtocol.json.decodeFromString<About>(
                    readBounded(response.body.byteStream(), MAX_LIST_BYTES).toString(Charsets.UTF_8),
                ).user
            }
            require(
                account.permissionId.matches(Regex("[0-9]{1,128}")) && account.emailAddress.isNotBlank() &&
                    account.emailAddress.length <= 320 && account.emailAddress.none(Char::isISOControl) &&
                    account.displayName.length <= 1024 && account.displayName.none(Char::isISOControl),
            ) { "Invalid Google account identity" }
            return account
        }

        private fun checkStatus(code: Int, accepted: Set<Int> = setOf(200)) {
            if (code !in accepted) throw IOException("Google Drive request failed (HTTP $code)")
        }

        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size().toLong() + count <= limit) { "Google Drive response exceeds its size limit" }
                output.write(buffer, 0, count)
            }
        }
    }
}

@Serializable
data class GoogleDriveAccount(val permissionId: String, val emailAddress: String, val displayName: String = "")

@Serializable
private data class About(val user: GoogleDriveAccount)

@Serializable
private data class DriveFile(val id: String, val name: String, val size: String, val appProperties: Map<String, String>)

@Serializable
private data class FileList(
    val files: List<DriveFile>,
    val nextPageToken: String? = null,
    val incompleteSearch: Boolean = false,
)

@Serializable
private data class CreateFile(
    val name: String,
    val appProperties: Map<String, String>,
    val parents: List<String> = listOf("appDataFolder"),
    val mimeType: String = "application/json",
)
