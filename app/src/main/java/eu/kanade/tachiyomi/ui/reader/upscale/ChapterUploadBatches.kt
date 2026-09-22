package eu.kanade.tachiyomi.ui.reader.upscale

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.coroutineContext

/** Upload ahead while earlier jobs process and return pages, with at most two jobs in flight. */
internal suspend fun <T, J : Any> processChapterUploadBatches(
    pages: List<T>,
    isReady: (T) -> Boolean,
    startBatch: suspend (List<T>) -> J?,
    awaitBatch: suspend (J) -> Unit,
) = coroutineScope {
    val slots = Semaphore(2)
    val submitted = Channel<J>(capacity = 1)
    launch {
        try {
            var nextPage = 0
            while (nextPage < pages.size) {
                slots.acquire()
                coroutineContext.ensureActive()
                // Never wait to fill a batch. Amortize model startup over pages already downloaded.
                var endPage = nextPage + 1
                if (nextPage > 0) {
                    while (endPage < pages.size && endPage - nextPage < 16 && isReady(pages[endPage])) {
                        endPage++
                    }
                }
                val job = startBatch(pages.subList(nextPage, endPage))
                coroutineContext.ensureActive()
                if (job == null) {
                    slots.release()
                } else {
                    submitted.send(job)
                }
                nextPage = endPage
            }
        } finally {
            submitted.close()
        }
    }
    for (job in submitted) {
        try {
            coroutineContext.ensureActive()
            awaitBatch(job)
        } finally {
            slots.release()
        }
    }
}
