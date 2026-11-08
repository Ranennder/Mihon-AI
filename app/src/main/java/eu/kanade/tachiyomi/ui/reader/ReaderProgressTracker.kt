package eu.kanade.tachiyomi.ui.reader

/** Viewer restoration and layout callbacks are not new reading actions. In
 * particular, a cached page must not receive a newer sync stamp merely because
 * the app reopened before its first cloud pull completed. */
internal class ReaderProgressTracker(private val restoredFromState: Boolean) {
    private data class Selection(val chapterId: Long, val pageIndex: Int)

    private var previous: Selection? = null
    private val historySavedAt = mutableMapOf<Long, Long>()

    fun shouldPersist(chapterId: Long, pageIndex: Int, pageCount: Int): Boolean {
        val selection = Selection(chapterId, pageIndex)
        val old = previous
        previous = selection
        if (old == selection) return false
        // Opening a fresh one-page chapter is also its completion action.
        // Restoring that chapter from process state must not repeat completion.
        if (old == null && (pageCount > 1 || restoredFromState)) return false
        return true
    }

    /** Called only after a genuine page move was committed. Use elapsed time
     * so a wall-clock correction cannot postpone live history updates. */
    fun shouldPersistHistory(chapterId: Long, nowMillis: Long): Boolean {
        val previous = historySavedAt[chapterId]
        if (previous != null && nowMillis >= previous && nowMillis - previous < 1_000) return false
        historySavedAt[chapterId] = nowMillis
        return true
    }
}
