package eu.kanade.tachiyomi.ui.reader.upscale

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Start the first page immediately, then keep preparation and in-flight uploads bounded. */
internal suspend fun <T> processChapterUploadBatches(
    pages: List<T>,
    processBatch: suspend (List<T>) -> Unit,
) {
    var nextPage = 0
    while (nextPage < pages.size) {
        coroutineContext.ensureActive()
        val count = if (nextPage == 0) 1 else 4
        val endPage = (nextPage + count).coerceAtMost(pages.size)
        processBatch(pages.subList(nextPage, endPage))
        coroutineContext.ensureActive()
        nextPage = endPage
    }
}
