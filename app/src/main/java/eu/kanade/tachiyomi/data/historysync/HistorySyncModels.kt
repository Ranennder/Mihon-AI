package eu.kanade.tachiyomi.data.historysync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class HistorySyncDocument(
    val format: String,
    val version: Int,
    val deviceId: String,
    val records: List<HistorySyncRecord>,
)

@Serializable
data class HistorySyncIdentity(val source: String, val manga: String, val chapter: String) {
    fun recordId(): String = MessageDigest.getInstance("SHA-256")
        .digest("$source\n$manga\n$chapter".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

@Serializable
data class HistorySyncMetadata(
    val mangaTitle: String? = null,
    val chapterTitle: String? = null,
    val mangaUrl: String? = null,
    val chapterUrl: String? = null,
)

@Serializable
data class HistorySyncProgress(val page: Long, val totalPages: Long? = null, val modifiedAt: Long, val deviceId: String)

@Serializable
data class HistorySyncCompletion(val read: Boolean, val modifiedAt: Long, val deviceId: String)

@Serializable
data class HistorySyncHistory(val lastReadAt: Long?, val modifiedAt: Long, val deviceId: String)

@Serializable
data class HistorySyncRecord(
    val id: String,
    val identity: HistorySyncIdentity,
    val metadata: HistorySyncMetadata = HistorySyncMetadata(),
    val progress: HistorySyncProgress,
    val completion: HistorySyncCompletion,
    val history: HistorySyncHistory,
)

/** The same transport-independent v1 registers are implemented on iOS. */
object HistorySyncProtocol {
    const val FORMAT = "aidoku-mihon-history"
    const val VERSION = 1
    const val MAX_TIME = 9_007_199_254_740_991L
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_RECORDS = 50_000
    private val devicePattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val idPattern = Regex("[0-9a-f]{64}")
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isDeviceId(value: String): Boolean = devicePattern.matches(value)

    fun decode(bytes: ByteArray): HistorySyncDocument {
        require(bytes.size <= MAX_BYTES) { "History replica exceeds 8 MiB" }
        // Java's default decoder replaces malformed UTF-8; a protocol identity
        // must not silently change before its SHA-256 is verified.
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<HistorySyncDocument>(text).also(::validate)
    }

    fun encode(document: HistorySyncDocument): ByteArray {
        validate(document)
        return json.encodeToString(document).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "History replica exceeds 8 MiB" }
        }
    }

    fun validate(document: HistorySyncDocument) {
        require(document.format == FORMAT && document.version == VERSION) { "Unsupported history sync format" }
        require(isDeviceId(document.deviceId)) { "Invalid sync device ID" }
        require(document.records.size <= MAX_RECORDS) { "Too many history records" }
        val seen = HashSet<String>()
        document.records.forEach { record ->
            with(record.identity) { listOf(source, manga, chapter).forEach(::validateIdentityComponent) }
            require(idPattern.matches(record.id) && record.id == record.identity.recordId() && seen.add(record.id)) {
                "Invalid or duplicate history identity"
            }
            with(record.progress) {
                validateStamp(modifiedAt, deviceId)
                require(
                    page in 0..MAX_TIME && (totalPages == null || (totalPages in 1..MAX_TIME && totalPages >= page)),
                ) {
                    "Invalid chapter position"
                }
            }
            with(record.completion) { validateStamp(modifiedAt, deviceId) }
            with(record.history) {
                validateStamp(modifiedAt, deviceId)
                require(lastReadAt == null || lastReadAt in 0..MAX_TIME) { "Invalid history date" }
            }
            with(record.metadata) {
                listOfNotNull(mangaTitle, chapterTitle, mangaUrl, chapterUrl).forEach(::validateUnicode)
            }
        }
    }

    fun maxClock(records: Iterable<HistorySyncRecord>): Long = records.maxOfOrNull {
        maxOf(it.progress.modifiedAt, it.completion.modifiedAt, it.history.modifiedAt)
    } ?: 0

    fun nextClock(systemMs: Long, observed: Long): Long {
        require(observed < MAX_TIME) { "History sync clock is exhausted" }
        return maxOf(systemMs.coerceIn(1, MAX_TIME), observed + 1)
    }

    fun merge(deviceId: String, vararg documents: HistorySyncDocument): HistorySyncDocument {
        documents.forEach(::validate)
        data class StampKey(val id: String, val field: String, val time: Long, val device: String)
        val evidence = HashMap<StampKey, Any>()
        fun check(id: String, field: String, time: Long, device: String, value: Any) {
            val key = StampKey(id, field, time, device)
            require(evidence.putIfAbsent(key, value)?.let { it == value } != false) {
                "Conflicting values at the same history stamp"
            }
        }
        // Validate obsolete equal-stamp conflicts before a newer version can
        // hide them; otherwise batch merge would depend on replica order.
        documents.forEach { document ->
            document.records.forEach { record ->
                with(record.progress) { check(record.id, "progress", modifiedAt, this.deviceId, this) }
                with(record.completion) { check(record.id, "completion", modifiedAt, this.deviceId, this) }
                with(record.history) { check(record.id, "history", modifiedAt, this.deviceId, this) }
            }
        }
        val records = sortedMapOf<String, HistorySyncRecord>()
        documents.forEach { document ->
            document.records.forEach { incoming ->
                records[incoming.id] = records[incoming.id]?.let { mergeRecord(it, incoming) } ?: incoming
            }
        }
        return HistorySyncDocument(FORMAT, VERSION, deviceId, records.values.toList()).also(::validate)
    }

    fun mergeRecord(local: HistorySyncRecord, remote: HistorySyncRecord): HistorySyncRecord {
        require(local.id == remote.id && local.identity == remote.identity) { "Conflicting history identity" }
        val progress = pick(local.progress, remote.progress, { it.modifiedAt }, { it.deviceId })
        val completion = pick(local.completion, remote.completion, { it.modifiedAt }, { it.deviceId })
        val history = pick(local.history, remote.history, { it.modifiedAt }, { it.deviceId })
        return local.copy(
            progress = progress,
            completion = completion,
            history = history,
            metadata = HistorySyncMetadata(
                minimum(local.metadata.mangaTitle, remote.metadata.mangaTitle),
                minimum(local.metadata.chapterTitle, remote.metadata.chapterTitle),
                minimum(local.metadata.mangaUrl, remote.metadata.mangaUrl),
                minimum(local.metadata.chapterUrl, remote.metadata.chapterUrl),
            ),
        )
    }

    private fun <T> pick(left: T, right: T, time: (T) -> Long, device: (T) -> String): T {
        val order = time(left).compareTo(time(right)).takeIf { it != 0 } ?: device(left).compareTo(device(right))
        require(order != 0 || left == right) { "Conflicting values at the same history stamp" }
        return if (order >= 0) left else right
    }

    private fun minimum(left: String?, right: String?): String? {
        val a = left?.takeIf(String::isNotEmpty) ?: return right?.takeIf(String::isNotEmpty)
        val b = right?.takeIf(String::isNotEmpty) ?: return a
        val aa = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(aa.size, bb.size)) {
            val comparison = (aa[index].toInt() and 0xff).compareTo(bb[index].toInt() and 0xff)
            if (comparison != 0) return if (comparison < 0) a else b
        }
        return if (aa.size <= bb.size) a else b
    }

    private fun validateStamp(time: Long, device: String) {
        require(time in 0..MAX_TIME && isDeviceId(device)) { "Invalid history stamp" }
    }

    private fun validateIdentityComponent(value: String) {
        validateUnicode(value)
        require(
            value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 4096 &&
                value.none { it.code in 0..31 || it.code in 127..159 },
        ) {
            "Invalid history identity component"
        }
    }

    private fun validateUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate()) { "Invalid Unicode in history record" }
            } else {
                require(!character.isLowSurrogate()) { "Invalid Unicode in history record" }
            }
        }
    }
}
