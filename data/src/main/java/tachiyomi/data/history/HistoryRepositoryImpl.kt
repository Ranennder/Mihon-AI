package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import java.util.UUID

class HistoryRepositoryImpl(
    private val database: Database,
) : HistoryRepository {

    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> {
        return database.historyViewQueries
            .history(query, HistoryMapper::mapHistoryWithRelations)
            .subscribeToList()
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        return database.historyViewQueries
            .getLatestHistory(HistoryMapper::mapHistoryWithRelations)
            .awaitAsOneOrNull()
    }

    override suspend fun getTotalReadDuration(): Long {
        return database.historyQueries
            .getReadDuration()
            .awaitAsOne()
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return database.historyQueries
            .getHistoryByMangaId(mangaId, HistoryMapper::mapHistory)
            .awaitAsList()
    }

    override suspend fun resetHistory(historyId: Long) {
        try {
            database.transaction {
                val chapter = database.historySyncQueries.getHistoryChapterById(historyId).awaitAsOneOrNull()
                database.historyQueries.resetHistoryById(historyId)
                if (chapter != null) recordSyncHistory(listOf(chapter), null)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        try {
            database.transaction {
                val chapters = database.historySyncQueries.getMangaHistoryChapterIds(mangaId).awaitAsList()
                database.historyQueries.resetHistoryByMangaId(mangaId)
                recordSyncHistory(chapters, null)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            database.transaction {
                val chapters = database.historySyncQueries.getHistoryChapterIds().awaitAsList()
                database.historyQueries.removeAllHistory()
                recordSyncHistory(chapters, null)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        try {
            database.transaction {
                database.historyQueries.upsert(
                    historyUpdate.chapterId,
                    historyUpdate.readAt,
                    historyUpdate.sessionReadDuration,
                )
                recordSyncHistory(listOf(historyUpdate.chapterId), historyUpdate.readAt.time)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }

    private suspend fun recordSyncHistory(chapters: List<Long>, readAt: Long?) {
        if (chapters.isEmpty()) return
        database.historySyncQueries.initialize(UUID.randomUUID().toString())
        database.historySyncQueries.bumpClock(System.currentTimeMillis())
        chapters.forEach { chapterId ->
            database.historySyncQueries.recordChange(
                progressPage = null,
                progressTotal = null,
                completionRead = null,
                historyChanged = true,
                historyLastRead = readAt,
                chapterId = chapterId,
            )
        }
    }
}
