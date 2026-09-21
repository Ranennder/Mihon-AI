package eu.kanade.tachiyomi.ui.reader.upscale

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterUploadBatchesTest {

    @Test
    fun `the first page reaches AI before a later source page becomes available`() = runBlocking {
        val laterPageRequested = CompletableDeferred<Unit>()
        val laterPageAvailable = CompletableDeferred<Unit>()
        val completedUploads = mutableListOf<List<Int>>()
        val processing = async {
            processChapterUploadBatches(listOf(0, 1, 2)) { batch ->
                for (page in batch) {
                    if (page == 1) {
                        laterPageRequested.complete(Unit)
                        laterPageAvailable.await()
                    }
                }
                completedUploads += batch.toList()
            }
        }
        try {
            withTimeout(5_000) { laterPageRequested.await() }
            assertEquals(listOf(listOf(0)), completedUploads)
            assertFalse(processing.isCompleted)
            laterPageAvailable.complete(Unit)
            withTimeout(5_000) { processing.await() }
            assertEquals(listOf(listOf(0), listOf(1, 2)), completedUploads)
        } finally {
            processing.cancelAndJoin()
        }
    }

    @Test
    fun `batches retain nonconsecutive original page indexes and never exceed four pages`() = runBlocking {
        val pageIndexes = listOf(3, 7, 8, 12, 20, 22, 31, 33, 42, 51)
        val uploaded = mutableListOf<List<Int>>()

        processChapterUploadBatches(pageIndexes) { uploaded += it.toList() }

        assertEquals(listOf(3), uploaded.first())
        assertTrue(uploaded.drop(1).all { it.size in 1..4 })
        assertEquals(pageIndexes, uploaded.flatten())
    }

    @Test
    fun `canceling during an upload never prepares or submits another batch`() = runBlocking {
        val uploadStarted = CompletableDeferred<Unit>()
        val pendingUpload = CompletableDeferred<Unit>()
        val submitted = mutableListOf<List<Int>>()
        val processing = launch {
            processChapterUploadBatches((0..9).toList()) { batch ->
                submitted += batch.toList()
                uploadStarted.complete(Unit)
                pendingUpload.await()
            }
        }

        withTimeout(5_000) { uploadStarted.await() }
        processing.cancelAndJoin()
        pendingUpload.complete(Unit)

        assertEquals(listOf(listOf(0)), submitted)
    }

    @Test
    fun `an empty chapter does not open a remote job`() = runBlocking {
        processChapterUploadBatches(emptyList<Int>()) { error("Unexpected upload") }
    }
}
