package eu.kanade.tachiyomi.data.historysync

import java.net.URI
import java.net.URLDecoder

/** Verified GroupLe reader credentials never belong in portable identities or cloud metadata. */
object HistorySyncSourcePolicy {
    private val groupLeHosts = setOf("a.zazaza.me", "20.allhen.online")
    private val userHash = Regex("[A-Za-z0-9_-]{1,256}")
    private val routePart = Regex("[A-Za-z0-9._~%-]+")

    fun canonicalPath(source: String, value: String, manga: Boolean): String {
        if (source !in groupLeHosts) return value
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null) { "Unsupported GroupLe history route" }
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null) {
            "Unsupported GroupLe history route"
        }
        val query = uri.rawQuery ?: return value
        val clean = cleanQuery(query) ?: return value
        require(validPath(uri.rawPath, manga)) { "Unsupported GroupLe history route" }
        return uri.rawPath + clean.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
    }

    fun metadataURL(value: String?, manga: Boolean, sourceHint: String? = null): String? {
        if (value == null) return null
        val host = metadataHost(value)
        val source = host ?: sourceHint
        if (source !in groupLeHosts) return value
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null) { "Unsupported GroupLe history metadata" }
        if (uri.rawQuery == null) return value
        val clean = cleanQuery(uri.rawQuery) ?: return value
        require(
            uri.rawUserInfo == null && uri.port == -1 &&
                (
                    (uri.scheme == null && uri.rawAuthority == null) ||
                        (uri.scheme?.lowercase() in setOf("https", "http") && host != null)
                    ) &&
                validPath(uri.rawPath, manga),
        ) { "Unsupported GroupLe history metadata" }
        return value.substringBefore('?') + clean.takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty() +
            uri.rawFragment?.let { "#$it" }.orEmpty()
    }

    fun normalizeRecord(record: HistorySyncRecord): HistorySyncRecord {
        val (identity, metadata) = normalize(record.identity, record.metadata)
        return record.copy(id = identity.recordId(), identity = identity, metadata = metadata)
    }

    fun normalize(
        identity: HistorySyncIdentity,
        metadata: HistorySyncMetadata,
    ): Pair<HistorySyncIdentity, HistorySyncMetadata> {
        val hint = sequenceOf(metadata.mangaUrl, metadata.chapterUrl).mapNotNull(::metadataHost)
            .firstOrNull { it in groupLeHosts }
        val source = identity.source.takeIf { it in groupLeHosts }
            ?: hint?.takeIf { identity.source.startsWith("local:") }
        val normalized = if (source != null) {
            val manga = canonicalPath(source, identity.manga, manga = true)
            val chapter = canonicalPath(source, identity.chapter, manga = false)
            if (manga != identity.manga || chapter != identity.chapter) {
                val mangaPath = URI(manga).rawPath
                val chapterPath = URI(chapter).rawPath
                require(
                    validPath(mangaPath, manga = true) && validPath(chapterPath, manga = false) &&
                        chapterPath.startsWith(mangaPath + "/vol"),
                ) {
                    "Unsupported GroupLe history identity"
                }
            }
            identity.copy(manga = manga, chapter = chapter)
        } else {
            identity
        }
        return normalized to metadata.copy(
            mangaUrl = metadataURL(metadata.mangaUrl, manga = true, sourceHint = source),
            chapterUrl = metadataURL(metadata.chapterUrl, manga = false, sourceHint = source),
        )
    }

    /** Inspect every original stamp before aliases collapse, including obsolete conflicts across replicas. */
    fun normalizeDocuments(documents: List<HistorySyncDocument>): List<HistorySyncDocument> {
        documents.forEach(HistorySyncProtocol::validate)
        val normalized = documents.map { document -> document.copy(records = document.records.map(::normalizeRecord)) }
        data class StampKey(val id: String, val field: String, val time: Long, val device: String)
        val evidence = HashMap<StampKey, Any>()
        fun check(id: String, field: String, time: Long, device: String, value: Any) {
            require(evidence.putIfAbsent(StampKey(id, field, time, device), value)?.let { it == value } != false) {
                "Conflicting values at the same history stamp"
            }
        }
        normalized.forEach { document ->
            document.records.forEach { record ->
                with(record.progress) { check(record.id, "progress", modifiedAt, deviceId, this) }
                with(record.completion) { check(record.id, "completion", modifiedAt, deviceId, this) }
                with(record.history) { check(record.id, "history", modifiedAt, deviceId, this) }
            }
        }
        return normalized.map { document ->
            val records = sortedMapOf<String, HistorySyncRecord>()
            document.records.forEach { record ->
                records[record.id] = records[record.id]?.let { HistorySyncProtocol.mergeRecord(it, record) } ?: record
            }
            document.copy(records = records.values.toList()).also(HistorySyncProtocol::validate)
        }
    }

    /** Null means there are no verified reader parameters; all other query bytes retain their order. */
    private fun cleanQuery(query: String): String? {
        val parts = query.split('&')
        val keys = parts.map(::key)
        if (keys.none { it == "d" || it == "mtr" }) return null
        require(keys.count { it == "d" } <= 1 && keys.count { it == "mtr" } <= 1) {
            "Ambiguous GroupLe reader parameters"
        }
        return parts.filterIndexed { index, part ->
            when (keys[index]) {
                "d" -> {
                    require(part.startsWith("d=") && userHash.matches(part.removePrefix("d="))) {
                        "Invalid GroupLe reader parameters"
                    }
                    false
                }
                "mtr" -> {
                    require(part == "mtr=true") { "Invalid GroupLe reader parameters" }
                    false
                }
                else -> true
            }
        }.joinToString("&")
    }

    private fun key(part: String): String = runCatching {
        URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
    }.getOrElse { throw IllegalArgumentException("Invalid GroupLe reader parameters") }

    private fun metadataHost(value: String?): String? = runCatching {
        value?.substringBefore('?')?.substringBefore('#')?.let(::URI)?.host?.lowercase()?.removePrefix("www.")
    }.getOrNull()

    private fun validPath(path: String?, manga: Boolean): Boolean {
        val parts = path?.split('/') ?: return false
        if (parts.size != (if (manga) 2 else 4) || parts.first().isNotEmpty()) return false
        if (parts.drop(1).any { part ->
                !routePart.matches(part) || part in setOf(".", "..") ||
                    runCatching {
                        val decoded = decodePathPart(part)
                        decoded.any {
                            it == '/' || it == '\\' || it.isWhitespace() || it.code < 32 ||
                                it.code in 127..159
                        } ||
                            decoded in setOf(".", "..")
                    }.getOrDefault(true)
            }
        ) {
            return false
        }
        return manga || (parts[2].startsWith("vol") && parts[2].length > 3)
    }

    private fun decodePathPart(value: String): String {
        val bytes = ByteArray(value.length)
        var input = 0
        var output = 0
        while (input < value.length) {
            bytes[output++] = if (value[input] == '%') {
                require(input + 2 < value.length) { "Unsupported GroupLe history route" }
                val encoded = value.substring(input + 1, input + 3).toInt(16)
                input += 3
                encoded.toByte()
            } else {
                value[input++].code.toByte()
            }
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, output)).toString()
    }
}
