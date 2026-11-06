package eu.kanade.tachiyomi.data.historysync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistorySyncProtocolTest {
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val identity = HistorySyncIdentity("mangabuff", "/manga/1", "/manga/1/chapter/2")

    private fun record(time: Long = 10, device: String = a) = HistorySyncRecord(
        identity.recordId(),
        identity,
        HistorySyncMetadata(mangaTitle = "Example"),
        HistorySyncProgress(3, 10, time, device),
        HistorySyncCompletion(false, time, device),
        HistorySyncHistory(10, time, device),
    )

    private fun document(record: HistorySyncRecord, device: String = a) =
        HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, device, listOf(record))

    @Test
    fun `independent registers retain remote completion during newer local page change`() {
        val local = record().copy(progress = HistorySyncProgress(7, 10, 30, a))
        val remote = record(20, b).copy(completion = HistorySyncCompletion(true, 20, b))
        val result = HistorySyncProtocol.merge(a, document(local), document(remote, b)).records.single()
        assertEquals(7, result.progress.page)
        assertTrue(result.completion.read)
        assertEquals(20, result.history.modifiedAt)
    }

    @Test
    fun `explicit unread and history deletion can lower state independently`() {
        val old = record().copy(completion = HistorySyncCompletion(true, 10, a))
        val unread = old.copy(
            progress = HistorySyncProgress(0, null, 20, b),
            completion = HistorySyncCompletion(false, 20, b),
        )
        val removed = old.copy(history = HistorySyncHistory(null, 30, a))
        val result = HistorySyncProtocol.merge(
            a,
            document(old),
            document(unread, b),
            document(removed),
        ).records.single()
        assertEquals(0, result.progress.page)
        assertFalse(result.completion.read)
        assertEquals(null, result.history.lastReadAt)
    }

    @Test
    fun `all permutations converge and merge is idempotent`() {
        val first = document(record())
        val second = document(record(20, b).copy(progress = HistorySyncProgress(2, 10, 20, b)), b)
        val third = document(record().copy(history = HistorySyncHistory(null, 30, a)))
        val inputs = listOf(first, second, third)
        val expected = HistorySyncProtocol.merge(a, *inputs.toTypedArray())
        for (x in inputs.indices) {
            for (y in inputs.indices.filter { it != x }) {
                val z = inputs.indices.single { it != x && it != y }
                assertEquals(expected, HistorySyncProtocol.merge(a, inputs[x], inputs[y], inputs[z]))
            }
        }
        assertEquals(expected, HistorySyncProtocol.merge(a, expected, expected))
        assertEquals(expected, HistorySyncProtocol.merge(a, HistorySyncProtocol.merge(a, first, second), third))
    }

    @Test
    fun `obsolete equal-stamp conflicts reject every fold order`() {
        val first = document(record())
        val newer = document(record(20, b), b)
        val corrupt = document(record().copy(progress = HistorySyncProgress(9, 10, 10, a)))
        val inputs = listOf(first, newer, corrupt)
        for (x in inputs.indices) {
            for (y in inputs.indices.filter { it != x }) {
                val z = inputs.indices.single { it != x && it != y }
                assertThrows(IllegalArgumentException::class.java) {
                    HistorySyncProtocol.merge(a, inputs[x], inputs[y], inputs[z])
                }
            }
        }
    }

    @Test
    fun `unknown enrollment cannot override known state seeded at one`() {
        val known = record(1, a).copy(completion = HistorySyncCompletion(true, 1, a))
        val untouched = record(
            0,
            b,
        ).copy(progress = HistorySyncProgress(0, null, 0, b), history = HistorySyncHistory(null, 0, b))
        assertTrue(
            HistorySyncProtocol.merge(a, document(known), document(untouched, b)).records.single().completion.read,
        )
    }

    @Test
    fun `metadata uses UTF8 byte order rather than UTF16 string order`() {
        val bmp = record().copy(metadata = HistorySyncMetadata(mangaTitle = "\uE000"))
        val astral = record().copy(metadata = HistorySyncMetadata(mangaTitle = "\uD800\uDC00"))
        assertEquals(
            "\uE000",
            HistorySyncProtocol.merge(a, document(astral), document(bmp)).records.single().metadata.mangaTitle,
        )
    }

    @Test
    fun `malformed replica rejects before any valid sibling is returned`() {
        val valid = document(record())
        val invalid = valid.copy(records = valid.records + record().copy(id = "f".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.merge(a, valid, invalid) }
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncProtocol.validate(
                valid.copy(
                    records =
                    valid.records + valid.records,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.validate(valid.copy(version = 2)) }
    }

    @Test
    fun `invalid bounds controls and malformed Unicode are rejected`() {
        val invalid = listOf(
            record().copy(progress = HistorySyncProgress(-1, null, 10, a)),
            record().copy(progress = HistorySyncProgress(12, 10, 10, a)),
            record().copy(history = HistorySyncHistory(HistorySyncProtocol.MAX_TIME + 1, 10, a)),
            record().copy(identity = identity.copy(chapter = "bad\nchapter")),
            record().copy(metadata = HistorySyncMetadata(mangaTitle = "\uD800")),
        )
        invalid.forEach { item ->
            assertThrows(IllegalArgumentException::class.java) { HistorySyncProtocol.validate(document(item)) }
        }
        assertThrows(Exception::class.java) { HistorySyncProtocol.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test
    fun `persisted clock recovers from a future device and JSON round trips`() {
        assertEquals(2001, HistorySyncProtocol.nextClock(1000, 2000))
        assertThrows(IllegalArgumentException::class.java) {
            HistorySyncProtocol.nextClock(1, HistorySyncProtocol.MAX_TIME)
        }
        val value = document(record())
        assertEquals(value, HistorySyncProtocol.decode(HistorySyncProtocol.encode(value)))
    }
}
