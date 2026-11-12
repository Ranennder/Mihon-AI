package eu.kanade.tachiyomi.data.historysync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tachiyomi.data.Database
import tachiyomi.data.History_sync_library_outbox
import tachiyomi.domain.source.service.SourceManager
import java.net.URI
import java.util.UUID

/** The SQL outbox captures native intent in the same transaction as favorite.
 * Resolution and network requests happen outside the optimistic commit. */
internal class HistorySyncLibraryStateStore(
    private val database: Database,
    private val sources: SourceManager,
    private val now: () -> Long,
) {
    data class Issue(val title: String, val source: String, val retryAt: Long)

    private data class Retry(val attempt: Int, val after: Long)
    private val retries = HashMap<String, Retry>()
    private val sourceRetries = HashMap<String, Retry>()
    private val materializerMutex = Mutex()

    @Volatile var issues: List<Issue> = emptyList()
        private set
    data class Native(
        val id: Long,
        val source: Long,
        val url: String,
        val title: String,
        val dateAdded: Long,
        val favorite: Boolean,
    )

    data class Binding(val source: Long, val url: String, val entry: HistorySyncLibraryEntry)

    data class Snapshot(
        val account: String,
        val writer: String,
        val clock: Long,
        val seeded: Boolean,
        val native: List<Native>,
        val pending: List<History_sync_library_outbox>,
        val records: List<HistorySyncLibraryRecord>,
        val bindings: Map<Long, Binding>,
    )

    data class Plan(
        val state: HistorySyncLibraryState,
        val bindings: Map<Long, Binding>,
        val pending: List<Long>,
        val blockedNativeIds: Set<Long>,
    )

    suspend fun readSnapshot(): Snapshot {
        val meta = database.historySyncLibraryQueries.getMeta().awaitAsOneOrNull()
        val global = database.historySyncQueries.getMeta().awaitAsOne()
        val account = global.active_account
        val writer = UUID.nameUUIDFromBytes("${global.device_id}\n$account".toByteArray(Charsets.UTF_8)).toString()
        val native = database.historySyncLibraryQueries.getLocalMangas { id, source, url, title, added, favorite ->
            Native(id, source, url, title, added, favorite)
        }.awaitAsList()
        return Snapshot(
            account,
            writer,
            meta?.logical_clock ?: 0,
            meta?.seeded ?: false,
            native,
            database.historySyncLibraryQueries.getPending().awaitAsList(),
            database.historySyncLibraryQueries.getRecords().awaitAsList().map {
                HistorySyncProtocol.json.decodeFromString<HistorySyncLibraryRecord>(it.json)
            },
            database.historySyncLibraryQueries.getBindings().awaitAsList().associate {
                it.manga_id to Binding(
                    it.source_id,
                    it.manga_url,
                    HistorySyncProtocol.json.decodeFromString<HistorySyncLibraryEntry>(it.json),
                )
            },
        )
    }

    fun plan(snapshot: Snapshot, writer: String, remote: List<HistorySyncDocument>): Plan {
        val bindings = snapshot.bindings.toMutableMap()
        val entries = snapshot.native.associate { native ->
            native.id to entry(native, bindings[native.id], writer).also {
                bindings[native.id] = Binding(native.source, native.url, it)
            }
        }
        val records = snapshot.records.associateByTo(sortedMapOf()) { it.id }
        val evidence = ArrayList<HistorySyncLibraryRecord>()
        val consumed = ArrayList<Long>()
        val blocked = HashSet<Long>()
        fun add(entry: HistorySyncLibraryEntry, included: Boolean, stamp: Long) {
            val incoming = HistorySyncLibraryRecord(
                entry.id,
                entry.source,
                entry.manga,
                entry.metadata,
                entry.dateAdded,
                HistorySyncLibraryMembership(included, stamp, writer),
            )
            evidence += incoming
            records[incoming.id] = records[incoming.id]?.let {
                HistorySyncLibraryStateProtocol.mergeRecord(it, incoming)
            } ?: incoming
        }
        // Existing data is older than every actual post-upgrade action. A read
        // nonfavorite does not imply either membership or a removal tombstone.
        if (!snapshot.seeded) {
            snapshot.native.filter { it.favorite }.forEach { add(entries.getValue(it.id), true, 0) }
        }
        // Metadata refresh does not manufacture a new membership timestamp.
        entries.values.forEach { current ->
            records[current.id]?.let { existing ->
                records[current.id] = HistorySyncLibraryStateProtocol.mergeRecord(
                    existing,
                    existing.copy(metadata = current.metadata, dateAdded = current.dateAdded),
                )
            }
        }
        for (event in snapshot.pending) {
            if (event.event_clock !in 1..HistorySyncProtocol.MAX_TIME) {
                // Preserve an exhausted local membership intent without letting
                // its older remote value overwrite the visible native choice.
                blocked += event.manga_id
                continue
            }
            val current = entry(
                Native(
                    event.manga_id,
                    event.source_id,
                    event.manga_url,
                    event.manga_title,
                    event.date_added,
                    event.included,
                ),
                snapshot.bindings[event.manga_id],
                writer,
            ).also {
                if (event.manga_id !in entries) bindings[event.manga_id] = Binding(event.source_id, event.manga_url, it)
            }
            val duplicateStillFavorite = snapshot.native.any {
                it.id != event.manga_id && it.favorite && entries[it.id]?.id == current.id
            }
            add(current, event.included || duplicateStillFavorite, event.event_clock)
            consumed += event._id
        }
        var maintenanceClock = maxOf(
            snapshot.clock,
            records.values.maxOfOrNull { it.membership.modifiedAt } ?: 0,
            remote.flatMap { it.libraryState?.records.orEmpty() }.maxOfOrNull { it.membership.modifiedAt } ?: 0,
        )
        for (native in snapshot.native) {
            val previous = snapshot.bindings[native.id] ?: continue
            val current = entries.getValue(native.id)
            if (previous.source != native.source || previous.url != native.url ||
                !previous.entry.source.startsWith("local:android:$writer:") || current.source.startsWith("local:") ||
                previous.entry.id == current.id
            ) {
                continue
            }
            val original = records[previous.entry.id] ?: continue
            // Promotion preserves the last real decision. A newer portable
            // removal must win even when the source was just installed here.
            val portable = HistorySyncLibraryRecord(
                current.id,
                current.source,
                current.manga,
                current.metadata,
                original.dateAdded,
                original.membership,
            )
            if (original.membership.included && maintenanceClock >= HistorySyncProtocol.MAX_TIME) {
                bindings[native.id] = previous
                blocked += native.id
                continue
            }
            evidence += portable
            records[current.id] = records[current.id]?.let {
                HistorySyncLibraryStateProtocol.mergeRecord(it, portable)
            } ?: portable
            if (original.membership.included) {
                maintenanceClock = HistorySyncProtocol.nextClock(now(), maintenanceClock)
                val retired = original.copy(membership = HistorySyncLibraryMembership(false, maintenanceClock, writer))
                evidence += retired
                records[original.id] = retired
            }
        }
        val local = HistorySyncLibraryStateProtocol.state(records.values.toList())
        val merged = requireNotNull(
            HistorySyncLibraryStateProtocol.merge(
                listOf(HistorySyncLibraryStateProtocol.state(snapshot.records), local) +
                    remote.mapNotNull { it.libraryState },
                evidence,
            ),
        )
        return Plan(merged, bindings, consumed, blocked)
    }

    /** Called inside the surrounding chapter transaction after its full native
     * snapshot and encoded 8 MiB limit have both been checked. */
    suspend fun commit(snapshot: Snapshot, plan: Plan, apply: Boolean): Pair<Int, Int> {
        database.historySyncLibraryQueries.initializeAccount()
        database.historySyncLibraryQueries.observeClock(
            plan.state.records.maxOfOrNull { it.membership.modifiedAt } ?: 0,
        )
        val previous = snapshot.records.associateBy { it.id }
        plan.state.records.forEach {
            if (previous[it.id] != it) {
                database.historySyncLibraryQueries.putRecord(it.id, HistorySyncProtocol.json.encodeToString(it))
            }
        }
        val recordIds = plan.state.records.mapTo(HashSet()) { it.id }
        plan.bindings.forEach { (id, binding) ->
            if (binding.entry.id in recordIds && snapshot.bindings[id] != binding) {
                database.historySyncLibraryQueries.putBinding(
                    id,
                    binding.source,
                    binding.url,
                    HistorySyncProtocol.json.encodeToString(binding.entry),
                )
            }
        }
        var applied = 0
        var pending = plan.blockedNativeIds.size
        val blockedRecords = plan.blockedNativeIds.mapNotNull { plan.bindings[it]?.entry?.id }.toSet()
        if (apply) {
            val nativeByIdentity = snapshot.native.groupBy { plan.bindings[it.id]?.entry?.id }
            for (record in plan.state.records) {
                if (record.id in blockedRecords) continue
                val matches = nativeByIdentity[record.id].orEmpty()
                if (matches.any { it.id in plan.blockedNativeIds }) continue
                if (matches.isEmpty()) {
                    if (record.membership.included) pending++
                    continue
                }
                if (record.membership.included) {
                    if (matches.none { it.favorite }) {
                        applyNative(matches.minBy { it.id }, true, record.dateAdded)
                        applied++
                    }
                } else {
                    matches.filter { it.favorite }.forEach {
                        applyNative(it, false, null)
                        applied++
                    }
                }
            }
        }
        plan.pending.forEach { database.historySyncLibraryQueries.removePending(it) }
        return applied to pending
    }

    private suspend fun applyNative(native: Native, included: Boolean, dateAdded: Long?) {
        database.historySyncLibraryQueries.applyMembership(
            included,
            if (included) dateAdded?.takeIf { it > 0 } ?: native.dateAdded.takeIf { it > 0 } ?: now() else 0,
            native.id,
            native.favorite,
        )
        database.historySyncLibraryQueries.finishApplying(native.id)
    }

    private fun entry(native: Native, previous: Binding?, writer: String): HistorySyncLibraryEntry {
        val provider = runCatching { sources.get(native.source) as? HttpSource }.getOrNull()
        val supplied = runCatching {
            provider?.getMangaUrl(
                SManga.create().apply {
                    url = native.url
                    title = native.title
                },
            )
        }.getOrNull()
        val url = runCatching {
            supplied?.let { value ->
                val uri = URI(value)
                val absolute = if (uri.isAbsolute) uri else URI(requireNotNull(provider).baseUrl).resolve(uri)
                HistorySyncSourcePolicy.metadataURL(absolute.toASCIIString(), manga = true)
                    ?.takeIf(HistorySyncLibraryProtocol::safeWebsiteURL)
            }
        }.getOrNull()
        val identity = url?.let { HistorySyncIdentityResolver.website(it, provider?.baseUrl, manga = true) }
        if (identity == null && previous != null && previous.source == native.source && previous.url == native.url) {
            return previous.entry
        }
        val source = identity?.source ?: "local:android:$writer:${native.source}"
        val manga = identity?.path ?: "id:${native.id}"
        return HistorySyncLibraryEntry(
            HistorySyncLibraryProtocol.titleId(source, manga),
            source,
            manga,
            HistorySyncMetadata(
                mangaTitle = native.title.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 16_384 },
                mangaUrl = url,
            ),
            native.dateAdded.takeIf { it in 1..HistorySyncProtocol.MAX_TIME },
        )
    }

    /** One bounded pass; failed additions stay in the durable register journal.
     * Account, winning record and native intent are fenced after every fetch. */
    suspend fun materialize(limit: Int = 3): Int = materializerMutex.withLock { materializeLocked(limit) }

    private suspend fun materializeLocked(limit: Int): Int {
        val snapshot = database.transactionWithResult { readSnapshot() }
        val mapped = snapshot.native.map { native ->
            entry(native, snapshot.bindings[native.id], snapshot.writer).id
        }.toSet()
        val unpublished = snapshot.pending.map { event ->
            entry(
                Native(
                    event.manga_id,
                    event.source_id,
                    event.manga_url,
                    event.manga_title,
                    event.date_added,
                    event.included,
                ),
                snapshot.bindings[event.manga_id],
                snapshot.writer,
            ).id
        }.toSet()
        val pending = snapshot.records.filter {
            it.membership.included && it.id !in mapped && it.id !in unpublished &&
                (retries["${snapshot.account}:${it.id}"]?.after ?: 0) <= now() &&
                (sourceRetries["${snapshot.account}:${it.source}"]?.after ?: 0) <= now()
        }.take(limit)
        var applied = 0
        for (record in pending) {
            currentCoroutineContext().ensureActive()
            val key = "${snapshot.account}:${record.id}"
            val sourceKey = "${snapshot.account}:${record.source}"
            if ((sourceRetries[sourceKey]?.after ?: 0) > now()) continue
            var sourceFailed = false
            val fetched = try {
                HistorySyncLibraryMaterializer(sources).fetch(record)
            } catch (_: TimeoutCancellationException) {
                sourceFailed = true
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                sourceFailed = error is HttpException && error.code in setOf(401, 403, 429, 503)
                null
            }
            if (fetched == null) {
                val attempt = ((retries[key]?.attempt ?: 0) + 1).coerceAtMost(8)
                val delay = (15_000L * (1L shl attempt)).coerceAtMost(15 * 60_000L)
                retries[key] = Retry(attempt, now() + delay + kotlin.random.Random.nextLong(5_000))
                if (sourceFailed) {
                    val sourceAttempt = ((sourceRetries[sourceKey]?.attempt ?: 0) + 1).coerceAtMost(8)
                    val sourceDelay = (30_000L * (1L shl sourceAttempt)).coerceAtMost(15 * 60_000L)
                    sourceRetries[sourceKey] =
                        Retry(sourceAttempt, now() + sourceDelay + kotlin.random.Random.nextLong(10_000))
                }
                continue
            }
            currentCoroutineContext().ensureActive()
            val committed = database.transactionWithResult {
                val current = readSnapshot()
                if (current.account != snapshot.account || current.records.find { it.id == record.id } != record ||
                    current.pending != snapshot.pending || current.native != snapshot.native
                ) {
                    return@transactionWithResult false
                }
                // Verify again inside the commit; a duplicate native title must
                // never be inserted because another import finished meanwhile.
                if (current.native.any { entry(it, current.bindings[it.id], current.writer).id == record.id }) {
                    return@transactionWithResult false
                }
                val manga = fetched.manga
                val id = database.historySyncLibraryQueries.insertMaterialized(
                    fetched.source.id, manga.url, manga.title,
                    manga.artist, manga.author, manga.description, manga.getGenres(),
                    manga.status.toLong(), manga.thumbnail_url,
                    manga.update_strategy, manga.memo,
                ).awaitAsOne()
                val native = Native(id, fetched.source.id, manga.url, manga.title, 0, false)
                fetched.chapters.forEachIndexed { order, chapter ->
                    database.chaptersQueries.insert(
                        id, chapter.url, chapter.name, chapter.scanlator, false, false, 0,
                        chapter.chapter_number.toDouble(), order.toLong(), now(), chapter.date_upload,
                        0, chapter.memo,
                    )
                }
                database.historySyncLibraryQueries.putBinding(
                    id,
                    fetched.source.id,
                    manga.url,
                    HistorySyncProtocol.json.encodeToString(record.entry()),
                )
                applyNative(native, true, record.dateAdded)
                true
            }
            if (committed) {
                applied++
                retries.remove(key)
            }
            // Native snapshot changed after one insertion: return to the next
            // sync cycle rather than reusing an obsolete optimistic fence.
            if (committed) break
        }
        issues = snapshot.records.filter { it.membership.included && it.id !in mapped }.map {
            Issue(it.metadata.mangaTitle ?: it.manga, it.source, retries["${snapshot.account}:${it.id}"]?.after ?: 0)
        }
        return applied
    }
}
