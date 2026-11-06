package eu.kanade.tachiyomi.data.historysync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class HistorySyncIdentityTest {
    @Test
    fun `shared Swift and Kotlin identity fixtures have identical components and SHA256`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/history-sync/canonical-identities-v1.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = HistorySyncProtocol.json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertEquals(11, cases.size)
        for (fixture in cases) {
            val input = fixture.jsonObject.getValue("input").jsonObject
            fun string(key: String) = input[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val actual = HistorySyncIdentityResolver.canonical(
                string("platform")!!, string("sourceId")!!, string("mangaId")!!, string("chapterId")!!,
                string("sourceURL"), string("mangaURL"), string("chapterURL"),
                input.getValue("uniqueChapterURL").jsonPrimitive.boolean, string("verifiedProviderChapterKey"),
            )
            val expected = fixture.jsonObject.getValue("expected").jsonObject
            assertEquals(expected.getValue("source").jsonPrimitive.content, actual.source)
            assertEquals(expected.getValue("manga").jsonPrimitive.content, actual.manga)
            assertEquals(expected.getValue("chapter").jsonPrimitive.content, actual.chapter)
            assertEquals(expected.getValue("id").jsonPrimitive.content, actual.recordId())
        }
    }

    @Test
    fun `foreign origins and duplicate chapter URLs stay platform scoped`() {
        val foreign = HistorySyncIdentityResolver.canonical(
            "android",
            "12",
            "m",
            "c",
            "https://mangabuff.ru",
            "/manga/1",
            "https://evil.example/chapter/1",
            true,
        )
        val duplicate = HistorySyncIdentityResolver.canonical(
            "android",
            "12",
            "m",
            "c",
            "https://senkuro.me",
            "/manga/1",
            "/manga/1",
            false,
        )
        assertEquals("local:android:12", foreign.source)
        assertEquals("local:android:12", duplicate.source)
    }

    @Test
    fun `known family with explicit port does not alias normal website`() {
        val normal = HistorySyncIdentityResolver.website("https://mangalib.me/m", null, true)
        val port = HistorySyncIdentityResolver.website("https://mangalib.me:443/m", null, true)
        assertEquals("mangalib.me:443", port?.source)
        assertNotEquals(normal, port)
    }

    @Test
    fun `unique website URL wins over fallback key and absolute manga supplies optional origin`() {
        val identity = HistorySyncIdentityResolver.canonical(
            "android", "12", "m", "c", null,
            "https://mangabuff.ru/manga/1", "/manga/1/chapter/2", true, "other-key",
        )
        assertEquals("mangabuff", identity.source)
        assertEquals("/manga/1/chapter/2", identity.chapter)
    }
}
