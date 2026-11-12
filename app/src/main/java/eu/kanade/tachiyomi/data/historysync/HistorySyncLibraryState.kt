package eu.kanade.tachiyomi.data.historysync

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** Membership is independent of reading progress and descriptive title metadata. */
@Serializable
data class HistorySyncLibraryState(val format: String, val version: Int, val records: List<HistorySyncLibraryRecord>)

@Serializable
data class HistorySyncLibraryMembership(val included: Boolean, val modifiedAt: Long, val deviceId: String)

@Serializable
data class HistorySyncLibraryRecord(
    val id: String,
    val source: String,
    val manga: String,
    @Required val metadata: HistorySyncMetadata = HistorySyncMetadata(),
    val dateAdded: Long? = null,
    val membership: HistorySyncLibraryMembership,
) {
    fun entry() = HistorySyncLibraryEntry(id, source, manga, metadata, dateAdded)
}

object HistorySyncLibraryStateProtocol {
    const val FORMAT = "aidoku-mihon-library-state"
    const val VERSION = 1
    const val MAX_RECORDS = 50_000

    fun state(records: List<HistorySyncLibraryRecord>) = HistorySyncLibraryState(FORMAT, VERSION, records)

    fun validate(state: HistorySyncLibraryState) {
        require(state.format == FORMAT && state.version == VERSION) { "Unsupported shared library format" }
        require(state.records.size <= MAX_RECORDS) { "Too many shared library records" }
        val ids = HashSet<String>()
        state.records.forEach { record ->
            require(ids.add(record.id)) { "Duplicate shared library identity" }
            HistorySyncLibraryProtocol.validateEntry(record.entry())
            with(record.membership) {
                require(modifiedAt in 0..HistorySyncProtocol.MAX_TIME && HistorySyncProtocol.isDeviceId(deviceId)) {
                    "Invalid library membership stamp"
                }
            }
        }
    }

    /** Check all raw evidence first: a newer register must not conceal corruption. */
    fun merge(
        states: List<HistorySyncLibraryState>,
        localEvidence: List<HistorySyncLibraryRecord> = emptyList(),
    ): HistorySyncLibraryState? {
        if (states.isEmpty()) return null
        states.forEach(::validate)
        localEvidence.forEach { validate(state(listOf(it))) }
        data class Stamp(val id: String, val time: Long, val device: String)
        val evidence = HashMap<Stamp, Boolean>()
        (states.map { it.records } + listOf(localEvidence)).forEach { records ->
            records.forEach { record ->
                with(record.membership) {
                    val previous = evidence.putIfAbsent(Stamp(record.id, modifiedAt, deviceId), included)
                    require(previous == null || previous == included) { "Conflicting library membership at one stamp" }
                }
            }
        }
        val records = sortedMapOf<String, HistorySyncLibraryRecord>()
        (states.map { it.records } + listOf(localEvidence)).forEach { incoming ->
            incoming.forEach { record ->
                records[record.id] = records[record.id]?.let { mergeRecord(it, record) } ?: record
            }
        }
        return state(records.values.toList()).also(::validate)
    }

    fun mergeRecord(left: HistorySyncLibraryRecord, right: HistorySyncLibraryRecord): HistorySyncLibraryRecord {
        require(left.id == right.id && left.source == right.source && left.manga == right.manga) {
            "Conflicting shared library identity"
        }
        val a = left.membership
        val b = right.membership
        val order = a.modifiedAt.compareTo(b.modifiedAt).takeIf { it != 0 } ?: a.deviceId.compareTo(b.deviceId)
        require(order != 0 || a.included == b.included) { "Conflicting library membership at one stamp" }
        return left.copy(
            metadata = HistorySyncMetadata(
                mangaTitle = HistorySyncProtocol.minimum(left.metadata.mangaTitle, right.metadata.mangaTitle),
                mangaUrl = HistorySyncProtocol.minimum(left.metadata.mangaUrl, right.metadata.mangaUrl),
            ),
            dateAdded = listOfNotNull(left.dateAdded, right.dateAdded).filter { it > 0 }.minOrNull(),
            membership = if (order >= 0) a else b,
        )
    }
}
