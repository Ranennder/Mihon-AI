package eu.kanade.tachiyomi.ui.reader.setting

import android.content.Context
import android.view.WindowManager

@Suppress("DEPRECATION")
internal fun physicalPortraitScreenWidth(context: Context): Int {
    val physicalWidth = runCatching {
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.mode?.let { mode ->
            minOf(mode.physicalWidth, mode.physicalHeight).takeIf { it > 0 }
        }
    }.getOrNull()
    if (physicalWidth != null) return physicalWidth
    val metrics = context.resources.displayMetrics
    return minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(ReaderPreferences.AI_TARGET_WIDTH_MIN)
}
