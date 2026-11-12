package eu.kanade.tachiyomi.data.historysync

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class HistorySyncLibraryMaterializerTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val source = mockk<HttpSource>()
    private val sources = mockk<SourceManager>().also { every { it.getAll() } returns listOf(source) }
    private val materializer = HistorySyncLibraryMaterializer(sources)

    @AfterEach
    fun close() = clients.forEach {
        it.dispatcher.executorService.shutdown()
        it.connectionPool.evictAll()
    }

    private fun record(source: String, path: String, url: String) = HistorySyncLibraryRecord(
        HistorySyncLibraryProtocol.titleId(source, path),
        source,
        path,
        HistorySyncMetadata(mangaTitle = "Exact provider title", mangaUrl = url),
        membership = HistorySyncLibraryMembership(true, 100, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
    )

    @Test
    fun `LibGroup website route resolves to exact native slug instead of using title search`() = runBlocking {
        every { source.baseUrl } returns "https://mangalib.me"
        every { source.getMangaUrl(any()) } answers { "https://mangalib.me/ru/manga" + firstArg<SManga>().url }
        coEvery { source.getMangaUpdate(any(), any(), true, true) } answers {
            SMangaUpdate(firstArg<SManga>().copy(), emptyList())
        }
        val result = materializer.fetch(record("mangalib", "/123--hero", "https://mangalib.me/ru/manga/123--hero"))
        assertEquals("/123--hero", requireNotNull(result).manga.url)
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), true, true) }
    }

    @Test
    fun `a changed identity returned by native fetch is rejected before database insertion`() = runBlocking {
        every { source.baseUrl } returns "https://mangabuff.ru"
        every { source.getMangaUrl(any()) } answers { "https://mangabuff.ru" + firstArg<SManga>().url }
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } answers {
            SMangaUpdate(firstArg<SManga>().copy().apply { url = "/different-title" }, emptyList())
        }
        assertNull(materializer.fetch(record("mangabuff", "/hero", "https://mangabuff.ru/hero")))
    }

    @Test
    fun `multiple native sources sharing an origin remain pending instead of choosing arbitrarily`() = runBlocking {
        every { source.baseUrl } returns "https://mangabuff.ru"
        val other = mockk<HttpSource>().also { every { it.baseUrl } returns "https://mangabuff.ru" }
        every { sources.getAll() } returns listOf(source, other)
        assertNull(materializer.fetch(record("mangabuff", "/hero", "https://mangabuff.ru/hero")))
        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
    }

    @Test
    fun `Senkuro exact public slug query resolves required native id comma comma slug route`() = runBlocking {
        every { source.baseUrl } returns "https://senkuro.me"
        every { source.headers } returns Headers.Builder().build()
        every { source.getMangaUrl(any()) } answers {
            firstArg<SManga>().url.split(",,").getOrNull(1)?.let { "https://senkuro.me/manga/$it" }.orEmpty()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.senkuro.me/graphql", request.url.toString())
            assertEquals("4026531840100", request.header("App-Id"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"manga":{"id":"native-id","slug":"hero"}}}""".toResponseBody()).build()
        }.build().also(clients::add)
        every { source.client } returns client
        coEvery { source.getMangaUpdate(any(), any(), true, true) } answers {
            assertEquals("native-id,,hero", firstArg<SManga>().url)
            SMangaUpdate(firstArg<SManga>().copy().apply { title = "Fetched title" }, emptyList())
        }
        val result = materializer.fetch(record("senkuro", "/manga/hero", "https://senkuro.me/manga/hero"))
        assertNotNull(result)
        assertEquals("native-id,,hero", requireNotNull(result).manga.url)
    }
}
