package eu.kanade.tachiyomi.data.historysync

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.source.service.SourceManager
import java.net.URI

/** Exact website identities only. Titles never participate in lookup. */
internal class HistorySyncLibraryMaterializer(private val sources: SourceManager) {
    data class Fetched(val source: HttpSource, val manga: SManga, val chapters: List<SChapter>)

    suspend fun fetch(record: HistorySyncLibraryRecord): Fetched? = withTimeout(30_000) {
        if (record.source.startsWith("local:")) return@withTimeout null
        val website = record.metadata.mangaUrl ?: return@withTimeout null
        val providers = sources.getAll().filterIsInstance<HttpSource>().filter { provider ->
            runCatching {
                HistorySyncIdentityResolver.website(provider.baseUrl, null, true)?.source == record.source
            }.getOrDefault(false)
        }
        // Multiple language/source variants can share a website. Materializing
        // into an arbitrary native source would split subsequent read history.
        if (providers.size != 1) return@withTimeout null
        val provider = providers.single()
        val candidates = mutableListOf<SManga>()
        if (provider is ResolvableSource && provider.getUriType(website) == UriType.Manga) {
            provider.getManga(website)?.let(candidates::add)
        }
        if (record.source == "senkuro" || record.source == "senkognito.com") {
            senkuro(provider, website)?.let(candidates::add)
        }
        routes(record, website).forEach { route ->
            candidates += SManga.create().apply {
                url = route
                title = record.metadata.mangaTitle.orEmpty()
            }
        }
        for (candidate in candidates.distinctBy { it.url }) {
            if (!matches(provider, candidate, record)) continue
            try {
                val update = provider.getMangaUpdate(candidate, emptyList(), fetchDetails = true, fetchChapters = true)
                val fetched = update.manga
                // Some older providers return details without setting their raw
                // route. The verified input remains the only allowed fallback.
                if (runCatching { fetched.url }.getOrNull().isNullOrEmpty()) fetched.url = candidate.url
                if (!matches(provider, fetched, record) || fetched.title.isBlank()) continue
                require(
                    update.chapters.size <= 50_000 && update.chapters.all {
                        it.url.isNotEmpty() && it.name.isNotEmpty() && it.chapter_number.isFinite()
                    },
                ) { "Invalid source chapter list" }
                return@withTimeout Fetched(provider, fetched, update.chapters.distinctBy { it.url })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is HttpException && error.code in setOf(401, 403, 429, 503)) throw error
                // Continue only through the small, exact set of native routes.
            }
        }
        null
    }

    internal fun routes(record: HistorySyncLibraryRecord, website: String): List<String> {
        val uri = URI(website)
        val path = uri.rawPath.orEmpty() + uri.rawQuery?.let { "?$it" }.orEmpty()
        val routes = linkedSetOf(record.manga, path, website)
        if (record.source in setOf("mangalib", "slashlib")) {
            routes += "/manga${record.manga}"
            routes += "/ru/manga${record.manga}"
        }
        return routes.toList()
    }

    private fun matches(provider: HttpSource, manga: SManga, record: HistorySyncLibraryRecord): Boolean = runCatching {
        val url = provider.getMangaUrl(manga)
        val canonical = HistorySyncIdentityResolver.website(url, provider.baseUrl, true)
        canonical?.source == record.source && canonical.path == record.manga
    }.getOrDefault(false)

    /** The source's public slug query resolves its required id,,slug raw key.
     * This is the same exact query used by our native Senkuro iOS port. */
    private suspend fun senkuro(provider: HttpSource, website: String): SManga? {
        val parts = URI(website).rawPath.trim('/').split('/')
        if (parts.size != 2 || parts[0] != "manga") return null
        val slug = parts[1]
        val base = URI(provider.baseUrl)
        val api = URI(base.scheme, null, "api.${base.host}", base.port, "/graphql", null, null)
        val body = buildJsonObject {
            put("query", "query Manga(${'$'}slug: String!) { manga(slug: ${'$'}slug) { id slug } }")
            putJsonObject("variables") { put("slug", slug) }
        }
        val request = Request.Builder().url(api.toASCIIString()).headers(provider.headers)
            .header("App-Id", "4026531840100")
            .header("App-Version", "060626")
            .header("Origin", provider.baseUrl)
            .header("Referer", provider.baseUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return provider.client.newCall(request).awaitSuccess().use { response ->
            val result = HistorySyncProtocol.json.parseToJsonElement(response.body.string()).jsonObject
                .get("data")?.jsonObject?.get("manga")?.jsonObject ?: return@use null
            val id = result["id"]?.jsonPrimitive?.contentOrNull ?: return@use null
            val returnedSlug = result["slug"]?.jsonPrimitive?.contentOrNull ?: return@use null
            if (returnedSlug != slug || id.isEmpty() || id.contains(",,")) return@use null
            SManga.create().apply {
                url = "$id,,$slug"
                title = slug
            }
        }
    }
}
