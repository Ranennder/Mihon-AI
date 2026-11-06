package eu.kanade.tachiyomi.data.historysync

/** The document protocol is independent of WebDAV; another cloud adapter can
 * implement this interface without changing identity or conflict semantics. */
interface HistorySyncTransport {
    suspend fun pull(): List<HistorySyncDocument>
    suspend fun push(document: HistorySyncDocument)
}
