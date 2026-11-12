package eu.kanade.tachiyomi.data.historysync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncLibraryStateProtocolTest {
    private val android = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val ios = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun record(included: Boolean = true, time: Long = 0, device: String = android): HistorySyncLibraryRecord {
        val source = "mangabuff"
        val manga = "/manga/title"
        return HistorySyncLibraryRecord(
            HistorySyncLibraryProtocol.titleId(source, manga),
            source,
            manga,
            HistorySyncMetadata(mangaTitle = "Unread favorite", mangaUrl = "https://mangabuff.ru$manga"),
            123_000,
            HistorySyncLibraryMembership(included, time, device),
        )
    }

    private fun document(vararg records: HistorySyncLibraryRecord) = HistorySyncDocument(
        HistorySyncProtocol.FORMAT,
        1,
        android,
        emptyList(),
        libraryState = HistorySyncLibraryStateProtocol.state(records.toList()),
    )

    @Test
    fun `legacy absence remains unknown and empty state advertises new capability`() {
        val legacy = document().copy(libraryState = null)
        assertNull(HistorySyncProtocol.decode(HistorySyncProtocol.encode(legacy)).libraryState)
        val empty = document()
        assertEquals(empty, HistorySyncProtocol.decode(HistorySyncProtocol.encode(empty)))
        assertTrue(HistorySyncProtocol.encode(empty).toString(Charsets.UTF_8).contains("libraryState"))
    }

    @Test
    fun `explicit null state is rejected before application`() {
        val legacy = HistorySyncProtocol.encode(document().copy(libraryState = null)).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncProtocol.decode(legacy.dropLast(1).plus(",\"libraryState\":null}").toByteArray())
        }
    }

    @Test
    fun `shared fixture carries unread add removal and forwarded peer writers without chapters`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/history-sync/library-state-v1.json")).readBytes()
        val value = HistorySyncProtocol.decode(bytes)
        assertTrue(value.records.isEmpty())
        val records = requireNotNull(value.libraryState).records
        assertEquals(3, records.size)
        assertEquals(2, records.count { it.membership.included })
        assertTrue(records.any { it.membership.deviceId != value.deviceId })
        assertEquals(value, HistorySyncProtocol.decode(HistorySyncProtocol.encode(value)))
    }

    @Test
    fun `new removals beat legacy baseline and a later readd beats the tombstone`() {
        val initial = document(record())
        val removed = document(record(false, 100, ios))
        val merge = HistorySyncProtocol.merge(android, initial, removed)
        assertFalse(requireNotNull(merge.libraryState).records.single().membership.included)
        val added = HistorySyncProtocol.merge(android, merge, document(record(true, 101)))
        assertTrue(requireNotNull(added.libraryState).records.single().membership.included)
    }

    @Test
    fun `equal time uses UUID order and merge is commutative associative and idempotent`() {
        val a = document(record(true, 100, android))
        val b = document(record(false, 100, ios))
        val c = document(record(true, 101, android))
        assertEquals(HistorySyncProtocol.merge(android, a, b), HistorySyncProtocol.merge(android, b, a))
        assertEquals(
            HistorySyncProtocol.merge(android, a, b, c),
            HistorySyncProtocol.merge(android, HistorySyncProtocol.merge(android, a, b), c),
        )
        val merged = HistorySyncProtocol.merge(android, a, b)
        assertEquals(merged, HistorySyncProtocol.merge(android, merged, merged))
        assertFalse(requireNotNull(merged.libraryState).records.single().membership.included)
    }

    @Test
    fun `obsolete equal stamp conflicts cannot hide behind a newer record`() {
        val a = document(record(true, 100))
        val b = document(record(false, 100))
        val newer = document(record(false, 200, ios))
        for (documents in listOf(arrayOf(a, newer, b), arrayOf(newer, b, a))) {
            assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.merge(android, *documents) }
        }
    }

    @Test
    fun `metadata and date added cannot overwrite membership and use deterministic minima`() {
        val a = record(false, 200, ios).copy(dateAdded = 20, metadata = HistorySyncMetadata(mangaTitle = "Z"))
        val b = record(true, 100).copy(dateAdded = 10, metadata = HistorySyncMetadata(mangaTitle = "A"))
        val merged = HistorySyncLibraryStateProtocol.mergeRecord(a, b)
        assertFalse(merged.membership.included)
        assertEquals(10L, merged.dateAdded)
        assertEquals("A", merged.metadata.mangaTitle)
    }

    @Test
    fun `state merge forwards peer library state and never forwards peer full snapshot`() {
        val entry = record().entry()
        val peer = document(record(false, 100, ios)).copy(
            deviceId = ios,
            librarySnapshot = HistorySyncLibrarySnapshot(HistorySyncLibraryProtocol.FORMAT, 1, ios, 10, listOf(entry)),
        )
        val merged = HistorySyncProtocol.merge(android, peer)
        assertNull(merged.librarySnapshot)
        assertEquals(peer.libraryState, merged.libraryState)
    }

    @Test
    fun `missing title metadata and chapter fields are invalid in the new state`() {
        val encoded = HistorySyncProtocol.encode(document(record())).toString(Charsets.UTF_8)
        for (changed in listOf(
            encoded.replace("\"metadata\":", "\"missingMetadata\":"),
            encoded.replace("\"chapterTitle\":null", "\"chapterTitle\":\"\""),
            encoded.replace("\"mangaTitle\":\"Unread favorite\"", "\"mangaTitle\":\"\""),
        )) {
            assertThrows(Exception::class.java) { HistorySyncProtocol.decode(changed.toByteArray()) }
        }
    }

    @Test
    fun `malformed stamps duplicate identities unsafe URLs and identity mismatches fail validation`() {
        val valid = record()
        val invalid = listOf(
            document(valid, valid),
            document(valid.copy(id = "0".repeat(64))),
            document(valid.copy(membership = valid.membership.copy(modifiedAt = -1))),
            document(valid.copy(membership = valid.membership.copy(deviceId = "not-a-device"))),
            document(
                valid.copy(metadata = valid.metadata.copy(mangaUrl = "https://user:pass@mangabuff.ru/manga/title")),
            ),
            document(valid.copy(metadata = valid.metadata.copy(mangaUrl = "https://mangabuff.ru/manga/another"))),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.encode(value) }
        }
    }

    @Test
    fun `library clock does not enter the maximum chapter clock`() {
        val value = document(record(true, HistorySyncProtocol.MAX_TIME, ios))
        assertEquals(0L, HistorySyncProtocol.maxClock(value.records))
        assertEquals(value.libraryState, HistorySyncProtocol.merge(android, value).libraryState)
    }
}
