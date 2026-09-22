package eu.kanade.tachiyomi.ui.reader.upscale

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterUploadBatchesTest {

    @Test
    fun `uploads continue while earlier AI results are pending with at most two jobs in flight`() = runBlocking {
        val firstResult = CompletableDeferred<Unit>()
        val firstReceiveStarted = CompletableDeferred<Unit>()
        val secondUploaded = CompletableDeferred<Unit>()
        val submitted = mutableListOf<List<Int>>()
        val received = mutableListOf<List<Int>>()
        val processing = async {
            processChapterUploadBatches(
                pages = (0..40).toList(),
                isReady = { true },
                startBatch = { batch ->
                    submitted += batch.toList()
                    if (submitted.size == 2) secondUploaded.complete(Unit)
                    batch.toList()
                },
                awaitBatch = { batch ->
                    if (batch.first() == 0) {
                        firstReceiveStarted.complete(Unit)
                        firstResult.await()
                    }
                    received += batch
                },
            )
        }
        try {
            withTimeout(5_000) {
                firstReceiveStarted.await()
                secondUploaded.await()
            }
            yield()
            assertEquals(listOf(listOf(0), (1..16).toList()), submitted)
            assertTrue(received.isEmpty())
            assertFalse(processing.isCompleted)
            firstResult.complete(Unit)
            withTimeout(5_000) { processing.await() }
            assertEquals((0..40).toList(), received.flatten())
        } finally {
            processing.cancelAndJoin()
        }
    }

    @Test
    fun `batches retain original indexes and do not wait for an unready page to fill a batch`() = runBlocking {
        val pageIndexes = listOf(3, 7, 8, 12, 20, 22, 31, 33, 42, 51)
        val uploaded = mutableListOf<List<Int>>()

        processChapterUploadBatches(
            pages = pageIndexes,
            isReady = { it < 12 },
            startBatch = { it.toList().also(uploaded::add) },
            awaitBatch = {},
        )

        assertEquals(listOf(3), uploaded.first())
        assertEquals(listOf(7, 8), uploaded[1])
        assertTrue(uploaded.drop(2).all { it.size == 1 })
        assertEquals(pageIndexes, uploaded.flatten())
    }

    @Test
    fun `canceling during an upload never prepares or submits another batch`() = runBlocking {
        val uploadStarted = CompletableDeferred<Unit>()
        val pendingUpload = CompletableDeferred<Unit>()
        val submitted = mutableListOf<List<Int>>()
        val processing = launch {
            processChapterUploadBatches(
                pages = (0..9).toList(),
                isReady = { true },
                startBatch = { batch ->
                    submitted += batch.toList()
                    uploadStarted.complete(Unit)
                    pendingUpload.await()
                    batch
                },
                awaitBatch = { error("Canceled upload must not be received") },
            )
        }

        withTimeout(5_000) { uploadStarted.await() }
        processing.cancelAndJoin()
        pendingUpload.complete(Unit)

        assertEquals(listOf(listOf(0)), submitted)
    }

    @Test
    fun `canceling while results are pending cancels both stages and no third job is submitted`() = runBlocking {
        val secondUploaded = CompletableDeferred<Unit>()
        val pendingResult = CompletableDeferred<Unit>()
        val submitted = mutableListOf<List<Int>>()
        var receiverStopped = false
        val processing = launch {
            processChapterUploadBatches(
                pages = (0..40).toList(),
                isReady = { true },
                startBatch = { batch ->
                    submitted += batch.toList()
                    if (submitted.size == 2) secondUploaded.complete(Unit)
                    batch
                },
                awaitBatch = {
                    try {
                        pendingResult.await()
                    } finally {
                        receiverStopped = true
                    }
                },
            )
        }

        withTimeout(5_000) { secondUploaded.await() }
        yield()
        processing.cancelAndJoin()

        assertEquals(2, submitted.size)
        assertTrue(receiverStopped)
    }

    @Test
    fun `skipped batches release their slot so later uploads can continue`() = runBlocking {
        val submitted = mutableListOf<Int>()
        val received = mutableListOf<Int>()
        withTimeout(5_000) {
            processChapterUploadBatches(
                pages = (0..4).toList(),
                isReady = { false },
                startBatch = { batch ->
                    submitted += batch.first()
                    batch.first().takeIf { it >= 3 }
                },
                awaitBatch = { received += it },
            )
        }
        assertEquals((0..4).toList(), submitted)
        assertEquals(listOf(3, 4), received)
    }

    @Test
    fun `an empty chapter does not open a remote job`() = runBlocking {
        processChapterUploadBatches<Int, Int>(
            pages = emptyList(),
            isReady = { true },
            startBatch = { error("Unexpected upload") },
            awaitBatch = { error("Unexpected receive") },
        )
    }
}
