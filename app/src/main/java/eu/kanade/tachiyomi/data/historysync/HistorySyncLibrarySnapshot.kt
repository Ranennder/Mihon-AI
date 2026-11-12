package eu.kanade.tachiyomi.data.historysync

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

/** A complete list authored by one Android installation, independent of reading history. */
@Serializable
data class HistorySyncLibrarySnapshot(
    val format: String,
    val version: Int,
    val deviceId: String,
    val modifiedAt: Long,
    val entries: List<HistorySyncLibraryEntry>,
)

@Serializable
data class HistorySyncLibraryEntry(
    val id: String,
    val source: String,
    val manga: String,
    @Required val metadata: HistorySyncMetadata = HistorySyncMetadata(),
    val dateAdded: Long? = null,
)

object HistorySyncLibraryProtocol {
    const val FORMAT = "aidoku-mihon-library"
    const val VERSION = 1
    const val MAX_ENTRIES = 20_000

    fun titleId(source: String, manga: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$source\n$manga".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun validate(snapshot: HistorySyncLibrarySnapshot, writer: String) {
        require(snapshot.format == FORMAT && snapshot.version == VERSION) { "Unsupported library snapshot format" }
        require(snapshot.deviceId == writer && HistorySyncProtocol.isDeviceId(writer)) {
            "Library snapshot writer mismatch"
        }
        require(snapshot.modifiedAt in 0..HistorySyncProtocol.MAX_TIME) { "Invalid library snapshot clock" }
        require(snapshot.entries.size <= MAX_ENTRIES) { "Too many library entries" }
        val ids = HashSet<String>()
        snapshot.entries.forEach { entry ->
            require(ids.add(entry.id)) { "Duplicate library identity" }
            validateEntry(entry)
        }
    }

    internal fun validateEntry(entry: HistorySyncLibraryEntry) {
        HistorySyncProtocol.validateIdentityComponent(entry.source)
        HistorySyncProtocol.validateIdentityComponent(entry.manga)
        if (!entry.source.startsWith("local:")) {
            require(entry.manga.startsWith('/') && !entry.manga.startsWith("//")) {
                "Invalid portable library route"
            }
        }
        require(entry.id == titleId(entry.source, entry.manga)) {
            "Invalid or duplicate library identity"
        }
        require(entry.dateAdded == null || entry.dateAdded in 0..HistorySyncProtocol.MAX_TIME) {
            "Invalid library added date"
        }
        with(entry.metadata) {
            listOfNotNull(
                mangaTitle,
                chapterTitle,
                mangaUrl,
                chapterUrl,
            ).forEach(HistorySyncProtocol::validateUnicode)
            require(chapterTitle == null && chapterUrl == null) {
                "A library entry cannot contain chapter metadata"
            }
            require(
                mangaTitle == null ||
                    (mangaTitle.isNotEmpty() && mangaTitle.toByteArray(Charsets.UTF_8).size <= 16_384),
            ) {
                "Library title exceeds its text limit"
            }
            require(mangaUrl == null || safeWebsiteURL(mangaUrl)) { "Unsafe library manga URL" }
            if (mangaUrl != null && !entry.source.startsWith("local:")) {
                val website = HistorySyncIdentityResolver.website(mangaUrl, null, manga = true)
                require(website != null && website.source == entry.source && website.path == entry.manga) {
                    "Library website URL conflicts with its identity"
                }
            }
        }
    }

    /** An equal-stamp conflict must not be hidden by a later version. */
    fun latestOwn(writer: String, snapshots: List<HistorySyncLibrarySnapshot>): HistorySyncLibrarySnapshot? {
        val evidence = HashMap<Long, HistorySyncLibrarySnapshot>()
        snapshots.forEach { original ->
            validate(original, writer)
            val snapshot = original.copy(entries = original.entries.sortedBy { it.id })
            require(evidence.putIfAbsent(snapshot.modifiedAt, snapshot)?.let { it == snapshot } != false) {
                "Conflicting library snapshots at the same stamp"
            }
        }
        return evidence.values.maxByOrNull { it.modifiedAt }
    }

    /** Native duplicate rows can represent the same portable title. Membership is still true. */
    fun canonicalEntries(entries: List<HistorySyncLibraryEntry>): List<HistorySyncLibraryEntry> {
        val unique = sortedMapOf<String, HistorySyncLibraryEntry>()
        entries.forEach { entry ->
            val previous = unique[entry.id]
            if (previous == null) {
                unique[entry.id] = entry
            } else {
                require(previous.source == entry.source && previous.manga == entry.manga) {
                    "Conflicting library identity"
                }
                unique[entry.id] = previous.copy(
                    metadata = HistorySyncMetadata(
                        HistorySyncProtocol.minimum(previous.metadata.mangaTitle, entry.metadata.mangaTitle),
                        HistorySyncProtocol.minimum(previous.metadata.chapterTitle, entry.metadata.chapterTitle),
                        HistorySyncProtocol.minimum(previous.metadata.mangaUrl, entry.metadata.mangaUrl),
                        HistorySyncProtocol.minimum(previous.metadata.chapterUrl, entry.metadata.chapterUrl),
                    ),
                    dateAdded = listOfNotNull(previous.dateAdded, entry.dateAdded).filter { it > 0 }.minOrNull(),
                )
            }
        }
        require(unique.size <= MAX_ENTRIES) { "Too many library entries" }
        return unique.values.toList()
    }

    internal fun safeWebsiteURL(value: String): Boolean = runCatching {
        require(
            value.toByteArray(Charsets.UTF_8).size <= 16_384 && value.none {
                it.isWhitespace() || it.isISOControl()
            },
        )
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.rawUserInfo == null && uri.rawFragment == null)
        require(!uri.host.isNullOrEmpty() && uri.host.all { it.code < 128 })
        require(uri.port == -1 || uri.port in 1..65535)
        true
    }.getOrDefault(false)
}
