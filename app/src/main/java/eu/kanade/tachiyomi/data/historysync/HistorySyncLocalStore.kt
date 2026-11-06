package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tachiyomi.data.Database
import tachiyomi.data.History_sync_outbox
import tachiyomi.domain.source.service.SourceManager
import java.util.Date
import java.util.UUID

/** Local actions and their stamps enter the SQL outbox in the native write
 * transaction. Network work never runs while a database transaction is held. */
class HistorySyncLocalStore(private val database: Database, private val sourceManager: SourceManager) {
    data class Result(val document: HistorySyncDocument, val applied: Int, val unmatched: Int)

    private data class LocalChapter(
        val id: Long,
        val mangaId: Long,
        val source: Long,
        val mangaUrl: String,
        val chapterUrl: String,
        val mangaTitle: String,
        val chapterTitle: String,
        val read: Boolean,
        val lastPage: Long,
        val lastRead: Long?,
    )

    private data class Snapshot(
        val device: String,
        val clock: Long,
        val account: String,
        val chapters: List<LocalChapter>,
        val pending: List<History_sync_outbox>,
        val records: List<HistorySyncRecord>,
    )

    private data class Resolved(
        val chapter: LocalChapter,
        val identity: HistorySyncIdentity,
        val metadata: HistorySyncMetadata,
    )

    suspend fun initialize() {
        database.historySyncQueries.initialize(UUID.randomUUID().toString())
    }

    /** Account journals are isolated; old unmatched records and pending actions
     * stay available if the user returns to their previous WebDAV account. */
    suspend fun selectAccount(account: String) {
        initialize()
        database.historySyncQueries.selectAccount(account)
    }

    /** Capture again after network I/O, so actions made during a pull cannot be
     * overwritten by the snapshot that initiated that pull. */
    suspend fun reconcile(remote: List<HistorySyncDocument> = emptyList(), apply: Boolean = false): Result {
        remote.forEach(HistorySyncProtocol::validate)
        // Resolve identities only after installed sources exist. Waiting before
        // opening a transaction lets source initialization finish its DB work.
        sourceManager.isInitialized.first { it }
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val snapshot = readSnapshot()
            val resolved = resolve(snapshot.chapters, snapshot.pending)
            val records = snapshot.records.associateByTo(sortedMapOf()) { it.id }
            for (item in resolved.values) {
                val chapter = item.chapter
                if (!chapter.read && chapter.lastPage <= 0 && (chapter.lastRead ?: 0) <= 0) continue
                records.putIfAbsent(item.identity.recordId(), seed(item, snapshot.device))
            }
            for (event in snapshot.pending) {
                require(event.event_clock in 0..HistorySyncProtocol.MAX_TIME) {
                    "History sync clock is exhausted; pending local changes are preserved"
                }
                val item = resolved[event.chapter_id] ?: continue
                val id = item.identity.recordId()
                val current = records[id] ?: seed(item, snapshot.device)
                val changed = current.copy(
                    progress = event.progress_page?.let {
                        val total = event.progress_total ?: current.progress.totalPages?.takeIf { total -> total >= it }
                        HistorySyncProgress(it, total, event.event_clock, snapshot.device)
                    } ?: current.progress,
                    completion = event.completion_read?.let {
                        HistorySyncCompletion(it, event.event_clock, snapshot.device)
                    } ?: current.completion,
                    history = if (event.history_changed) {
                        HistorySyncHistory(event.history_last_read, event.event_clock, snapshot.device)
                    } else {
                        current.history
                    },
                )
                records[id] = HistorySyncProtocol.mergeRecord(current, changed)
            }
            // A URL/login alias may address the same physical cloud folder.
            // Distinct account journals must never overwrite each other's
            // replica, even if the provider temporarily omits a listing entry.
            val replicaDevice = UUID.nameUUIDFromBytes(
                "${snapshot.device}\n${snapshot.account}".toByteArray(Charsets.UTF_8),
            ).toString()
            val local = HistorySyncDocument(HistorySyncProtocol.FORMAT, 1, replicaDevice, records.values.toList())
            val merged = HistorySyncProtocol.merge(replicaDevice, local, *remote.toTypedArray())
            // Check the serialized limit before committing or applying anything.
            HistorySyncProtocol.encode(merged)
            val nativeIds = snapshot.chapters.mapTo(HashSet()) { it.id }
            val index = resolved.values.filter { it.chapter.id in nativeIds }
                .groupBy { it.identity.recordId() }
            var applied = 0
            var unmatched = 0
            currentCoroutineContext().ensureActive()
            val committed = database.transactionWithResult {
                val current = readSnapshot()
                if (current != snapshot) return@transactionWithResult false
                database.historySyncQueries.observeClock(HistorySyncProtocol.maxClock(merged.records))
                for (record in merged.records) {
                    currentCoroutineContext().ensureActive()
                    database.historySyncQueries.putRecord(record.id, HistorySyncProtocol.json.encodeToString(record))
                    val matches = index[record.id].orEmpty()
                    if (matches.size != 1 || record.progress.page > Int.MAX_VALUE.toLong()) {
                        unmatched += 1
                        continue
                    }
                    if (!apply) continue
                    val chapter = matches.single().chapter
                    val lastPage = (record.progress.page - 1).coerceAtLeast(0)
                    if (chapter.read != record.completion.read || chapter.lastPage != lastPage) {
                        database.historySyncQueries.applyChapter(record.completion.read, lastPage, chapter.id)
                        database.historySyncQueries.finishApplyingChapter(chapter.id)
                    }
                    val lastRead = record.history.lastReadAt?.takeIf { it > 0 }
                    if (chapter.lastRead?.takeIf { it > 0 } != lastRead) {
                        // Replacing the date preserves existing time_read; do
                        // not call native upsert, which adds session duration.
                        database.historySyncQueries.applyHistory(chapter.id, Date(lastRead ?: 0))
                    }
                    applied += 1
                }
                snapshot.pending.lastOrNull()?.let { database.historySyncQueries.removePendingThrough(it._id) }
                true
            }
            if (committed) return Result(merged, applied, unmatched)
        }
        error("History changed during synchronization; pending changes are preserved")
    }

    private suspend fun readSnapshot(): Snapshot = database.transactionWithResult {
        val meta = database.historySyncQueries.getMeta().awaitAsOne()
        val chapters = database.historySyncQueries.getLocalChapters {
                id,
                manga,
                source,
                mangaUrl,
                chapterUrl,
                title,
                name,
                read,
                page,
                date,
            ->
            LocalChapter(id, manga, source, mangaUrl, chapterUrl, title, name, read, page, date?.time)
        }.awaitAsList()
        val pending = database.historySyncQueries.getPending().awaitAsList()
        val records = database.historySyncQueries.getRecords().awaitAsList().map {
            HistorySyncProtocol.json.decodeFromString<HistorySyncRecord>(it.json)
        }
        Snapshot(meta.device_id, meta.logical_clock, meta.active_account, chapters, pending, records)
    }

    private fun resolve(chapters: List<LocalChapter>, pending: List<History_sync_outbox>): Map<Long, Resolved> {
        val currentIds = chapters.mapTo(HashSet()) { it.id }
        val all = chapters + pending.filter { it.chapter_id !in currentIds }.distinctBy { it.chapter_id }.map {
            LocalChapter(
                it.chapter_id, -1, it.source_id, it.manga_url, it.chapter_url,
                it.manga_title, it.chapter_title, false, 0, null,
            )
        }
        data class Website(val chapter: LocalChapter, val base: String?, val manga: String?, val url: String?)
        val websites = all.map { chapter ->
            val source = sourceManager.get(chapter.source) as? HttpSource
            val base = runCatching { source?.baseUrl }.getOrNull()
            val manga = runCatching {
                source?.getMangaUrl(
                    SManga.create().apply {
                        url = chapter.mangaUrl
                        title = chapter.mangaTitle
                    },
                )
            }.getOrNull()
            val url = runCatching {
                source?.getChapterUrl(
                    SChapter.create().apply {
                        this.url = chapter.chapterUrl
                        name =
                            chapter.chapterTitle
                    },
                )
            }.getOrNull()
            Website(chapter, base, manga, url)
        }
        val counts = websites.groupingBy {
            Triple(
                it.chapter.source to it.chapter.mangaUrl,
                it.base,
                it.url?.let { url ->
                    HistorySyncIdentityResolver.website(url, it.base, false)
                },
            )
        }.eachCount()
        return websites.associate { website ->
            val chapter = website.chapter
            val key = Triple(
                chapter.source to chapter.mangaUrl,
                website.base,
                website.url?.let { HistorySyncIdentityResolver.website(it, website.base, false) },
            )
            val identity = HistorySyncIdentityResolver.resolve(
                chapter.source,
                chapter.mangaUrl,
                chapter.chapterUrl,
                website.manga,
                website.url,
                website.base,
                counts[key] == 1,
            )
            chapter.id to Resolved(
                chapter,
                identity,
                HistorySyncMetadata(chapter.mangaTitle, chapter.chapterTitle, website.manga, website.url),
            )
        }
    }

    private fun seed(item: Resolved, device: String): HistorySyncRecord {
        val chapter = item.chapter
        val time = chapter.lastRead?.takeIf { it in 1..HistorySyncProtocol.MAX_TIME } ?: 1
        val visited = chapter.lastPage > 0 || (chapter.lastRead ?: 0) > 0
        val page = if (visited) chapter.lastPage.coerceAtLeast(0) + 1 else 0
        return HistorySyncRecord(
            item.identity.recordId(),
            item.identity,
            item.metadata,
            HistorySyncProgress(page, null, if (visited) time else 0, device),
            HistorySyncCompletion(chapter.read, if (chapter.read) time else 0, device),
            HistorySyncHistory(
                chapter.lastRead?.takeIf { it > 0 },
                if ((chapter.lastRead ?: 0) >
                    0
                ) {
                    time
                } else {
                    0
                },
                device,
            ),
        )
    }
}
