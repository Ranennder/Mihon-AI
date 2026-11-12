package eu.kanade.tachiyomi.data.historysync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncLibraryProtocolTest {
    private val own = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun `the shared complete library fixture retains unread and unresolved favorites`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/history-sync/library-snapshot-v1.json"))
            .use { it.readBytes() }
        val document = HistorySyncProtocol.decode(bytes)
        val library = requireNotNull(document.librarySnapshot)
        assertEquals(2, library.entries.size)
        assertTrue(document.records.isEmpty())
        assertEquals(1, library.entries.count { it.source.startsWith("local:") && it.metadata.mangaUrl == null })
        assertEquals(document, HistorySyncProtocol.decode(HistorySyncProtocol.encode(document)))
    }

    @Test
    fun `legacy documents omit the new field while nullable history tombstones remain explicit`() {
        val identity = HistorySyncIdentity("mangabuff", "/manga/example", "/chapter/1")
        val record = HistorySyncRecord(
            identity.recordId(),
            identity,
            progress = HistorySyncProgress(0, null, 1, own),
            completion = HistorySyncCompletion(true, 1, own),
            history = HistorySyncHistory(null, 1, own),
        )
        val document = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, listOf(record))
        val encoded = HistorySyncProtocol.encode(document)
        val objectValue = HistorySyncProtocol.json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject
        assertFalse(objectValue.containsKey("librarySnapshot"))
        val history = objectValue.getValue("records").jsonArray.single().jsonObject.getValue("history").jsonObject
        assertTrue(history.containsKey("lastReadAt"))
        assertEquals(JsonNull, history["lastReadAt"])
        assertEquals(document, HistorySyncProtocol.decode(encoded))
    }

    @Test
    fun `absent snapshot is unknown and explicit null is invalid`() {
        val legacy = """{"format":"aidoku-mihon-history","version":1,"deviceId":"$own","records":[]}"""
        assertNull(HistorySyncProtocol.decode(legacy.toByteArray()).librarySnapshot)
        val explicitNull = legacy.dropLast(1) + ",\"librarySnapshot\":null}"
        assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.decode(explicitNull.toByteArray()) }
    }

    @Test
    fun `complete empty and unread favorite snapshots round trip without chapter records`() {
        for (entries in listOf(emptyList(), listOf(entry("unread")))) {
            val document = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, emptyList(), snapshot(entries))
            assertEquals(document, HistorySyncProtocol.decode(HistorySyncProtocol.encode(document)))
            assertTrue(document.records.isEmpty())
        }
    }

    @Test
    fun `malformed or foreign snapshot envelopes are rejected before encoding`() {
        val initial = snapshot(listOf(entry("known")))
        val invalid = listOf(
            initial.copy(format = "other"), initial.copy(version = 2), initial.copy(deviceId = peer),
            initial.copy(modifiedAt = -1), initial.copy(modifiedAt = HistorySyncProtocol.MAX_TIME + 1),
            initial.copy(entries = listOf(entry("known").copy(id = "not-a-hash"))),
            initial.copy(entries = listOf(entry("known"), entry("known"))),
            initial.copy(entries = listOf(entry("known").copy(dateAdded = -1))),
            initial.copy(entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(chapterTitle = "")))),
            initial.copy(entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(chapterUrl = "")))),
            initial.copy(entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(mangaTitle = "")))),
            initial.copy(entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(mangaUrl = "")))),
            initial.copy(entries = listOf(entry("known").copy(manga = "invalid\nidentity"))),
            initial.copy(
                entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(chapterTitle = "Not a title"))),
            ),
            initial.copy(
                entries = listOf(entry("known").copy(metadata = HistorySyncMetadata(mangaTitle = "x".repeat(16_385)))),
            ),
            initial.copy(
                entries = listOf(
                    entry("known").copy(metadata = HistorySyncMetadata(mangaUrl = "https://mangabuff.ru/other")),
                ),
            ),
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                HistorySyncProtocol.encode(HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, emptyList(), value))
            }
        }
    }

    @Test
    fun `new library entries require a metadata object while its descriptive fields may be absent`() {
        val document =
            HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, emptyList(), snapshot(listOf(entry("known"))))
        val objectValue = HistorySyncProtocol.json.parseToJsonElement(
            HistorySyncProtocol.encode(document).toString(Charsets.UTF_8),
        ).jsonObject
        val library = objectValue.getValue("librarySnapshot").jsonObject
        val item = library.getValue("entries").jsonArray.single().jsonObject
        fun bytes(entry: JsonObject): ByteArray = JsonObject(
            objectValue + ("librarySnapshot" to JsonObject(library + ("entries" to JsonArray(listOf(entry))))),
        ).toString().toByteArray(Charsets.UTF_8)
        assertThrows(Exception::class.java) { HistorySyncProtocol.decode(bytes(JsonObject(item - "metadata"))) }
        assertThrows(Exception::class.java) {
            HistorySyncProtocol.decode(bytes(JsonObject(item + ("metadata" to JsonNull))))
        }
        assertEquals(
            document,
            HistorySyncProtocol.decode(bytes(JsonObject(item + ("metadata" to JsonObject(emptyMap()))))),
        )
    }

    @Test
    fun `unsafe website URLs are rejected but an unresolved favorite remains a real entry`() {
        for (url in listOf(
            "https://user:secret@mangabuff.ru/manga/a",
            "file:///secret",
            "https://mangabuff.ru/a#reader",
        )) {
            val value = entry("known").copy(metadata = HistorySyncMetadata(mangaUrl = url))
            assertThrows(IllegalArgumentException::class.java) {
                HistorySyncLibraryProtocol.validate(snapshot(listOf(value)), own)
            }
        }
        val source = "local:android:-123"
        val unresolved = HistorySyncLibraryEntry(
            HistorySyncLibraryProtocol.titleId(source, "opaque"),
            source,
            "opaque",
            HistorySyncMetadata(mangaTitle = "Not lost"),
        )
        val value = snapshot(listOf(unresolved))
        HistorySyncLibraryProtocol.validate(value, own)
        assertEquals(1, value.entries.size)
        assertNull(value.entries.single().metadata.mangaUrl)
    }

    @Test
    fun `a stale equal stamp conflict cannot hide behind a newer snapshot`() {
        val first = snapshot(listOf(entry("first")))
        val conflict = first.copy(entries = listOf(entry("second")))
        val newest = first.copy(modifiedAt = 2)
        for (snapshots in listOf(listOf(first, conflict, newest), listOf(newest, conflict, first))) {
            assertThrows(IllegalArgumentException::class.java) { HistorySyncLibraryProtocol.latestOwn(own, snapshots) }
        }
        assertEquals(newest, HistorySyncLibraryProtocol.latestOwn(own, listOf(newest, first)))
    }

    @Test
    fun `canonical duplicates preserve true membership and deterministic metadata`() {
        val first = entry("same").copy(metadata = HistorySyncMetadata(mangaTitle = "Z"), dateAdded = 200)
        val second = first.copy(metadata = HistorySyncMetadata(mangaTitle = "A"), dateAdded = 100)
        val forward = HistorySyncLibraryProtocol.canonicalEntries(listOf(first, second))
        assertEquals(forward, HistorySyncLibraryProtocol.canonicalEntries(listOf(second, first)))
        assertEquals(1, forward.size)
        assertEquals("A", forward.single().metadata.mangaTitle)
        assertEquals(100L, forward.single().dateAdded)
    }

    @Test
    fun `chapter merging never forwards any installation library envelope`() {
        val ownDocument =
            HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, emptyList(), snapshot(listOf(entry("own"))))
        val peerDocument = ownDocument.copy(
            deviceId = peer,
            librarySnapshot = snapshot(listOf(entry("peer"))).copy(deviceId = peer),
        )
        val merged = HistorySyncProtocol.merge(own, ownDocument, peerDocument)
        assertNull(merged.librarySnapshot)
        assertTrue(merged.records.isEmpty())
    }

    @Test
    fun `entry and total serialized limits reject oversized complete snapshots`() {
        val tooMany = snapshot(List(HistorySyncLibraryProtocol.MAX_ENTRIES + 1) { entry(it.toString()) })
        assertThrows(IllegalArgumentException::class.java) { HistorySyncLibraryProtocol.validate(tooMany, own) }
        val largeTitle = "x".repeat(15_000)
        val huge =
            snapshot(List(600) { entry(it.toString()).copy(metadata = HistorySyncMetadata(mangaTitle = largeTitle)) })
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncProtocol.encode(HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, own, emptyList(), huge))
        }
    }

    private fun entry(slug: String): HistorySyncLibraryEntry {
        val source = "mangabuff"
        val manga = "/manga/$slug"
        return HistorySyncLibraryEntry(HistorySyncLibraryProtocol.titleId(source, manga), source, manga)
    }

    private fun snapshot(entries: List<HistorySyncLibraryEntry>) = HistorySyncLibrarySnapshot(
        HistorySyncLibraryProtocol.FORMAT,
        1,
        own,
        1,
        entries,
    )
}
