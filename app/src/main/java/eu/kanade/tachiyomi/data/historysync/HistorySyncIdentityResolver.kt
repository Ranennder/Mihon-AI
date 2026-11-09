package eu.kanade.tachiyomi.data.historysync

import java.net.URI
import java.net.URLDecoder

object HistorySyncIdentityResolver {
    private val families = mapOf(
        "mangalib.me" to "mangalib",
        "slashlib.me" to "slashlib",
        "mangabuff.ru" to "mangabuff",
        "senkuro.me" to "senkuro",
    )

    data class WebsiteIdentity(val source: String, val path: String)

    fun website(raw: String, baseUrl: String?, manga: Boolean): WebsiteIdentity? = runCatching {
        val supplied = URI(raw)
        val base = baseUrl?.let(::URI)
        val resolved = if (supplied.isAbsolute) {
            supplied
        } else {
            require(base != null && !raw.startsWith("//"))
            base.resolve(supplied)
        }
        val url = URI(resolved.toASCIIString())
        require(url.scheme?.lowercase() in setOf("https", "http") && url.rawUserInfo == null)
        val host = url.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
        require(host.isNotEmpty() && host.all { it.code < 128 })
        if (!supplied.isAbsolute && base != null) {
            require(url.host.equals(base.host, ignoreCase = true) && url.port == base.port)
        }
        val source = if (url.port == -1) families[host] ?: host else "$host:${url.port}"
        var path = url.rawPath.orEmpty().ifEmpty { "/" }
        if (path.endsWith('/')) path = path.dropLast(1)
        if (path.isEmpty()) path = "/"
        var query = url.rawQuery
        if (source == "mangalib" || source == "slashlib") {
            if (manga && path.startsWith("/ru/manga/") && '/' !in path.removePrefix("/ru/manga/")) {
                path = "/" + path.removePrefix("/ru/manga/")
            } else if (!manga && Regex("^/ru/[^/]+/read/").containsMatchIn(path)) {
                path = path.removePrefix("/ru")
            }
            var parts = query?.split('&')?.filter { it.isNotEmpty() }?.filterNot {
                val key = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name())
                key == "ui" || key == "page"
            }.orEmpty()
            if (!manga && Regex("^/[^/]+/chapter$").matches(path)) {
                fun values(key: String) = parts.filter {
                    URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) == key
                }.map { it.substringAfter('=', "") }
                val volumes = values("volume")
                val numbers = values("number")
                val branches = values("branch_id")
                val routePart = Regex("[A-Za-z0-9._~-]+")
                if (volumes.size == 1 && numbers.size == 1 && routePart.matches(volumes.single()) &&
                    routePart.matches(numbers.single()) && branches.size <= 1 &&
                    branches.all { Regex("[0-9]+").matches(it) } && values("bid").isEmpty()
                ) {
                    path = path.removeSuffix("/chapter") + "/read/v${volumes.single()}/c${numbers.single()}"
                    parts = branches.map { "bid=$it" } + parts.filterNot {
                        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) in
                            setOf("volume", "number", "branch_id")
                    }
                }
            }
            query = parts.joinToString("&").takeIf(String::isNotEmpty)
        }
        val route = path + query?.let { "?$it" }.orEmpty()
        WebsiteIdentity(source, HistorySyncSourcePolicy.canonicalPath(source, route, manga))
    }.getOrNull()

    fun resolve(
        sourceId: Long,
        rawManga: String,
        rawChapter: String,
        mangaWebsiteUrl: String?,
        chapterWebsiteUrl: String?,
        baseUrl: String?,
        uniqueChapterWebsite: Boolean,
    ): HistorySyncIdentity {
        val websiteOrigin =
            mangaWebsiteUrl?.takeIf { runCatching { URI(it).isAbsolute }.getOrDefault(false) } ?: baseUrl
        return canonical(
            "android",
            sourceId.toString(),
            rawManga,
            rawChapter,
            websiteOrigin,
            mangaWebsiteUrl,
            chapterWebsiteUrl,
            uniqueChapterWebsite,
        )
    }

    fun canonical(
        platform: String,
        sourceId: String,
        mangaId: String,
        chapterId: String,
        sourceURL: String?,
        mangaURL: String?,
        chapterURL: String?,
        uniqueChapterURL: Boolean,
        verifiedProviderChapterKey: String? = null,
    ): HistorySyncIdentity {
        val base = sourceURL ?: mangaURL?.takeIf { runCatching { URI(it).isAbsolute }.getOrDefault(false) }
        val source = base?.let { website(it, null, manga = true)?.source }
        val manga = mangaURL?.let { website(it, base, manga = true) }
        val chapter = chapterURL?.let { website(it, base, manga = false) }
        if (source != null && manga != null && manga.source == source) {
            if (uniqueChapterURL && chapter != null && chapter.source == source) {
                return HistorySyncIdentity(source, manga.path, chapter.path)
            }
            if (!verifiedProviderChapterKey.isNullOrEmpty()) {
                return HistorySyncIdentity(source, manga.path, "key:$verifiedProviderChapterKey")
            }
        }
        // Source keys are only portable when explicitly verified. An overview
        // URL shared by several Senkuro chapters must never stand for them all.
        return HistorySyncIdentity("local:$platform:$sourceId", mangaId, chapterId)
    }
}
