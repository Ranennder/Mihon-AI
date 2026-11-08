package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderProgressTrackerTest {
    @Test
    fun `opening at a cached page does not stamp stale progress`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 12, pageCount = 30))
    }

    @Test
    fun `restoring a cached or clamped page does not stamp stale progress`() {
        val tracker = ReaderProgressTracker(restoredFromState = true)
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 29, pageCount = 30))
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 29, pageCount = 30))
    }

    @Test
    fun `moving forward and backward are both real reading actions`() {
        val tracker = ReaderProgressTracker(restoredFromState = true)
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 12, pageCount = 30))
        assertTrue(tracker.shouldPersist(chapterId = 1, pageIndex = 13, pageCount = 30))
        assertTrue(tracker.shouldPersist(chapterId = 1, pageIndex = 12, pageCount = 30))
    }

    @Test
    fun `layout callbacks at the same page do not recreate a reading action`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        tracker.shouldPersist(chapterId = 1, pageIndex = 12, pageCount = 30)
        assertTrue(tracker.shouldPersist(chapterId = 1, pageIndex = 13, pageCount = 30))
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 13, pageCount = 30))
    }

    @Test
    fun `switching chapters persists the first selected page even at the same index`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        tracker.shouldPersist(chapterId = 1, pageIndex = 0, pageCount = 30)
        assertTrue(tracker.shouldPersist(chapterId = 2, pageIndex = 0, pageCount = 30))
        assertFalse(tracker.shouldPersist(chapterId = 2, pageIndex = 0, pageCount = 30))
        assertTrue(tracker.shouldPersist(chapterId = 1, pageIndex = 0, pageCount = 30))
    }

    @Test
    fun `fresh one page chapter still completes exactly once`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        assertTrue(tracker.shouldPersist(chapterId = 1, pageIndex = 0, pageCount = 1))
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 0, pageCount = 1))
    }

    @Test
    fun `process restored one page chapter does not repeat completion`() {
        val tracker = ReaderProgressTracker(restoredFromState = true)
        assertFalse(tracker.shouldPersist(chapterId = 1, pageIndex = 0, pageCount = 1))
        assertTrue(tracker.shouldPersist(chapterId = 2, pageIndex = 0, pageCount = 1))
    }

    @Test
    fun `live history is immediate on first real move and limited to once per second`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_000))
        assertFalse(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_000))
        assertFalse(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_999))
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 6_000))
    }

    @Test
    fun `live history interval is isolated per chapter`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_000))
        assertTrue(tracker.shouldPersistHistory(chapterId = 2, nowMillis = 5_500))
        assertFalse(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_999))
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 6_000))
        assertFalse(tracker.shouldPersistHistory(chapterId = 2, nowMillis = 6_499))
        assertTrue(tracker.shouldPersistHistory(chapterId = 2, nowMillis = 6_500))
    }

    @Test
    fun `live history gate recovers from an elapsed clock reset`() {
        val tracker = ReaderProgressTracker(restoredFromState = false)
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 5_000))
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 0))
        assertFalse(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 999))
        assertTrue(tracker.shouldPersistHistory(chapterId = 1, nowMillis = 1_000))
    }
}
