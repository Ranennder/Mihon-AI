package eu.kanade.tachiyomi.data.historysync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HistorySyncSourcePolicyTest {
    private val writer = "00000000-0000-4000-8000-000000000001"
    private val otherWriter = "00000000-0000-4000-8000-000000000002"

    @Test
    fun `GroupLe canonical identity removes credentials and preserves exact translation query bytes`() {
        val identity = HistorySyncIdentityResolver.canonical(
            "android",
            "5",
            "/title",
            "/title/vol1/2?d=fixture_token&mtr=true&bid=42&translation=red%2Fblue",
            "https://a.zazaza.me",
            "https://a.zazaza.me/title",
            "https://a.zazaza.me/title/vol1/2?d=fixture_token&mtr=true&bid=42&translation=red%2Fblue",
            true,
        )
        assertEquals("a.zazaza.me", identity.source)
        assertEquals("/title/vol1/2?bid=42&translation=red%2Fblue", identity.chapter)
        assertEquals(
            "/title/vol1/2?translation=красный",
            HistorySyncSourcePolicy.canonicalPath(
                "a.zazaza.me",
                "/title/vol1/2?d=fixture&mtr=true&translation=красный",
                manga = false,
            ),
        )
    }

    @Test
    fun `both verified GroupLe hosts normalize without guessing mirrors`() {
        for (host in listOf("a.zazaza.me", "20.allhen.online")) {
            assertEquals(
                "/title/vol1/2",
                HistorySyncSourcePolicy.canonicalPath(host, "/title/vol1/2?mtr=true&d=fixture", manga = false),
            )
        }
        for (host in listOf("unrelated.example", "a.zazaza.me:443", "mirror.zazaza.me")) {
            val value = "/title/vol1/2?d=chapter-variant&mtr=true&bid=42"
            assertEquals(value, HistorySyncSourcePolicy.canonicalPath(host, value, manga = false))
        }
    }

    @Test
    fun `opaque local identities from unrelated providers retain d and mtr semantics`() {
        val identity = HistorySyncIdentity("local:android:123", "opaque-key", "/c?d=branch&mtr=translation")
        val metadata = HistorySyncMetadata(chapterUrl = "https://unrelated.example/c?d=branch&mtr=translation")
        assertEquals(identity to metadata, HistorySyncSourcePolicy.normalize(identity, metadata))
    }

    @Test
    fun `credential-bearing metadata and IDs are removed without restamping state`() {
        val old = record("d=fixture_token&mtr=true&bid=42")
        val result = HistorySyncSourcePolicy.normalizeDocuments(listOf(document(old))).single().records.single()
        assertEquals("/title/vol1/2?bid=42", result.identity.chapter)
        assertEquals("https://a.zazaza.me/title/vol1/2?bid=42", result.metadata.chapterUrl)
        assertNotEquals(old.id, result.id)
        assertEquals(old.progress, result.progress)
        assertEquals(old.completion, result.completion)
        assertEquals(old.history, result.history)
    }

    @Test
    fun `credential aliases merge registers independently and preserve deletion tombstones`() {
        val first = record("d=fixture_old&mtr=true", page = 10, progressTime = 300, read = true, completionTime = 100)
        val second = record("mtr=true&d=fixture_new", progressTime = 200, completionTime = 400, historyTime = 500)
        val result = HistorySyncSourcePolicy.normalizeDocuments(listOf(document(first, second))).single()
        val merged = result.records.single()
        assertEquals("/title/vol1/2", merged.identity.chapter)
        assertEquals(first.progress, merged.progress)
        assertEquals(second.completion, merged.completion)
        assertFalse(merged.completion.read)
        assertEquals(second.history, merged.history)
        assertEquals(null, merged.history.lastReadAt)
        assertEquals(writer, result.deviceId)
        assertEquals(listOf(result), HistorySyncSourcePolicy.normalizeDocuments(listOf(result)))
        val encoded = HistorySyncProtocol.encode(result).toString(Charsets.UTF_8)
        assertFalse(encoded.contains("fixture_old") || encoded.contains("fixture_new") || encoded.contains("mtr="))
    }

    @Test
    fun `obsolete alias conflicts across replicas cannot hide behind a newer value`() {
        val first = record("d=fixture_one", page = 2, progressTime = 10)
        val newer = record("d=fixture_two", page = 9, progressTime = 20)
        val conflict = record("d=fixture_three", page = 3, progressTime = 10)
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncSourcePolicy.normalizeDocuments(
                listOf(document(first, newer), document(conflict).copy(deviceId = otherWriter)),
            )
        }
    }

    @Test
    fun `malformed reader parameters abort before token-bearing data can be uploaded`() {
        for (query in listOf(
            "d=fixture&d=other",
            "%64=fixture",
            "d=fixture%2Ftoken",
            "d=",
            "mtr=false",
            "mtr=true&mtr=true",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                HistorySyncSourcePolicy.normalizeDocuments(listOf(document(record(query))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncSourcePolicy.canonicalPath("a.zazaza.me", "/title/unknown/2?d=fixture", manga = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncSourcePolicy.canonicalPath("a.zazaza.me", "/title/vol1/%FF?d=fixture", manga = false)
        }
    }

    @Test
    fun `migration validates original IDs before rebuilding them`() {
        val invalid = record("d=fixture").copy(id = "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncSourcePolicy.normalizeDocuments(listOf(document(invalid)))
        }
    }

    @Test
    fun `relative metadata and persisted bindings use the same narrow cleanup`() {
        val identity = record("d=fixture&mtr=true").identity
        val metadata = HistorySyncMetadata(
            "Title",
            "Chapter",
            "/title?d=fixture",
            "/title/vol1/2?mtr=true&d=fixture&bid=42",
        )
        val (clean, labels) = HistorySyncSourcePolicy.normalize(identity, metadata)
        assertEquals("/title/vol1/2", clean.chapter)
        assertEquals(HistorySyncMetadata("Title", "Chapter", "/title", "/title/vol1/2?bid=42"), labels)
    }

    @Test
    fun `known source metadata cannot claim another chapter route or credential-bearing origin`() {
        for (url in listOf(
            "https://user:secret@a.zazaza.me/title/vol1/2?d=fixture",
            "https://a.zazaza.me:443/title/vol1/2?d=fixture",
            "https://a.zazaza.me/title/unknown/2?d=fixture",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                HistorySyncSourcePolicy.normalizeRecord(
                    record("d=fixture").copy(metadata = HistorySyncMetadata(chapterUrl = url)),
                )
            }
        }
    }

    private fun record(
        query: String,
        page: Long = 2,
        progressTime: Long = 10,
        read: Boolean = false,
        completionTime: Long = 10,
        historyTime: Long = 10,
    ): HistorySyncRecord {
        val identity = HistorySyncIdentity("a.zazaza.me", "/title", "/title/vol1/2?$query")
        return HistorySyncRecord(
            identity.recordId(),
            identity,
            HistorySyncMetadata(
                "Title",
                "Chapter",
                "https://a.zazaza.me/title",
                "https://a.zazaza.me${identity.chapter}",
            ),
            HistorySyncProgress(page, null, progressTime, writer),
            HistorySyncCompletion(read, completionTime, writer),
            HistorySyncHistory(null, historyTime, writer),
        )
    }

    private fun document(vararg records: HistorySyncRecord): HistorySyncDocument =
        HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, writer, records.toList())
}
